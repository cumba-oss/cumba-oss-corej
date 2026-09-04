"""Unit tests for the per-rule violation injectors (Phase 4).

These tests are deliberately **engine-free** (the Java CLI is exercised by
``verify_violations.py`` instead). They check that:

* every injector module imports and exposes a well-formed ``META`` + ``inject``;
* each ``(standard, coreId)`` is unique and the target ``coreId`` is a real CORE
  rule yaml;
* applying an injector changes only the cells / columns / datasets it records in
  ``study.changes`` (diff vs a fresh clean study) and is deterministic;
* the driver assembles the expected file set with a faithful ``expectation.json``.
"""

import json
import os

import pytest

import apply_violations
import library
import rulescan
import study as study_mod
from generate import Generator
from violations import lib as vlib

# Reuse the scanner's own resolution rather than re-deriving the relative path
# here: ``rules-src`` lives in the sibling ``corej-rules`` module, and a
# second hand-rolled copy of that walk silently rotted when the modules split.
_CORE_DIR = rulescan._default_core_dir()

_SPECS = {
    "sdtmig": ("sdtmig", "3-4", library.SDTMIG_3_4),
    "sendig": ("sendig", "3-1-1", library.SENDIG_3_1_1),
}


@pytest.fixture(scope="session")
def clean_dirs(tmp_path_factory):
    """Generate a fresh clean study per lane into a temp dir (no external data)."""
    dirs = {}
    for std, (standard, version, spec) in _SPECS.items():
        out = tmp_path_factory.mktemp(f"clean-{std}")
        lib = library.Library(spec)
        st = study_mod.build_study(standard, version, n_subjects=20, n_visits=10)
        Generator(lib, st).generate(str(out))
        dirs[std] = str(out)
    return dirs


def _injectors():
    return apply_violations.discover()


def _diff(clean: vlib.Study, mutated: vlib.Study):
    """(new_datasets, new_columns, changed_cells) between two studies."""
    new_datasets = set(mutated.datasets) - set(clean.datasets)
    new_columns: set[tuple[str, str]] = set()
    changed_cells: set[tuple[str, int, str]] = set()
    for dom in clean.datasets:
        clean_cols = clean.col_names(dom)
        mut_cols = mutated.col_names(dom)
        for c in mut_cols:
            if c not in clean_cols:
                new_columns.add((dom, c))
        shared = [c for c in mut_cols if c in clean_cols]
        for i in range(clean.n_rows(dom)):
            cv = clean.row_view(dom, i)
            mv = mutated.row_view(dom, i)
            for c in shared:
                if cv.get(c) != mv.get(c):
                    changed_cells.add((dom, i, c))
    return new_datasets, new_columns, changed_cells


def _claimed(changes):
    """What the change-log claims it touched, in _diff's vocabulary."""
    new_datasets, new_columns, changed_cells = set(), set(), set()
    for ch in changes:
        if ch["type"] == "add_dataset":
            new_datasets.add(ch["domain"])
        elif ch["type"] == "add_column":
            new_columns.add((ch["domain"], ch["variable"]))
        elif ch["type"] == "cell":
            changed_cells.add((ch["domain"], ch["row"], ch["variable"]))
    return new_datasets, new_columns, changed_cells


def test_injectors_discovered():
    inj = _injectors()
    # Re-pinned 2026-08-08. Old floor: >= 20, when there were 26 injectors
    # (14 sdtmig + 12 sendig). Commit 95ef971f7 deleted the CORE-1xxxxx rules
    # ("these have never been authored in CORE"), which orphaned 11 of the 12
    # sendig injectors; they were removed, leaving 15 (14 sdtmig + 1 sendig).
    assert len(inj) >= 15
    # ⚠ The SENDIG lane is down to the single CORE-000310 injector. Keep both
    # lanes asserted so a lane cannot silently disappear the way this one nearly
    # did — a bare total would have absorbed the loss without a word.
    by_std: dict[str, int] = {}
    for i in inj:
        by_std[i.standard] = by_std.get(i.standard, 0) + 1
    assert by_std.get("sdtmig", 0) >= 14
    assert by_std.get("sendig", 0) >= 1


def test_meta_well_formed_and_unique():
    seen = set()
    for inj in _injectors():
        meta = inj.meta
        assert set(("coreId", "standard", "domain")) <= set(meta)
        assert meta["standard"] in ("sdtmig", "sendig")
        assert meta["coreId"].startswith(("CORE-", "FDA-"))
        key = (meta["standard"], meta["coreId"])
        assert key not in seen, f"duplicate injector for {key}"
        seen.add(key)


def test_target_coreid_is_a_real_rule():
    for inj in _injectors():
        path = os.path.join(_CORE_DIR, inj.core_id + ".yaml")
        assert os.path.isfile(path), f"{inj.core_id} has no CORE rule yaml"


@pytest.mark.parametrize("inj", _injectors(), ids=lambda i: f"{i.standard}:{i.core_id}")
def test_injector_changes_only_claimed_and_is_deterministic(inj, clean_dirs):
    clean_dir = clean_dirs[inj.standard]

    baseline = vlib.load_clean(clean_dir)
    study = vlib.load_clean(clean_dir)
    inj.inject(study)

    # The injector must record at least one change.
    assert study.changes, f"{inj.core_id} recorded no changes"

    diff = _diff(baseline, study)
    claimed = _claimed(study.changes)
    assert diff == claimed, (
        f"{inj.core_id}: actual diff {diff} != claimed {claimed}"
    )

    # Every cell change references a real row in the clean study.
    for ch in study.changes:
        if ch["type"] == "cell":
            assert 0 <= ch["row"] < baseline.n_rows(ch["domain"])

    # Determinism: a second independent application yields the same change log.
    study2 = vlib.load_clean(clean_dir)
    inj.inject(study2)
    assert study2.changes == study.changes


def _is_ordered_subset(sub, sup):
    """``sub`` appears inside ``sup`` in order — the engine's is_ordered_subset_of."""
    it = iter(sup)
    return all(x in it for x in sub)


# The three column-order rules that ship on these lanes. CORE-000852 / FDA-SD1079
# compare against the SDTM model order, CDISC-SEND-0048 against the library order
# (SEND lane only); all three are `is_ordered_subset_of(dataset_order, ...)`.
_ORDER_RULES = {"CORE-000852", "FDA-SD1079", "CDISC-SEND-0048"}


@pytest.mark.parametrize("inj", _injectors(), ids=lambda i: f"{i.standard}:{i.core_id}")
def test_added_standard_column_keeps_library_order(inj, clean_dirs):
    """A re-introduced *standard* variable must land in its library position.

    The generator emits columns in library ordinal order, which is why the clean
    study satisfies the order rules. ``Study.add_column`` appends by default, so
    an injector re-introducing a variable the standard places mid-list (e.g.
    ``CORE-100099`` putting back the dropped Permissible ``CL.CLSEV``) would push
    it last, break the ordered-subset property and gain all three order rules as
    collateral. Pass ``after=<nearest surviving predecessor>`` instead.

    A column that is *not* in the standard has no correct position — those
    injectors (``GEN-DISALLOW-*``) declare the order rules in ``allowedCollateral``
    and are exempt here.
    """
    _, _, spec = _SPECS[inj.standard]
    lib = library.Library(spec)
    allowed = set(inj.meta.get("allowedCollateral", []))

    study = vlib.load_clean(clean_dirs[inj.standard])
    inj.inject(study)

    for ch in study.changes:
        if ch["type"] != "add_column":
            continue
        domain = ch["domain"]
        if not lib.has_domain(domain):
            continue
        model = [v.name for v in lib.variables(domain)]
        if ch["variable"] not in model:
            continue  # foreign column: no correct position, see docstring
        assert not (_ORDER_RULES & allowed), (
            f"{inj.core_id} re-introduces the standard variable {domain}.{ch['variable']} "
            f"yet declares an order rule as allowed collateral — fix the position, "
            f"do not baseline it"
        )
        assert _is_ordered_subset(study.col_names(domain), model), (
            f"{inj.core_id}: {domain} column order is no longer an ordered subset of the "
            f"{inj.standard} library order after adding {ch['variable']}; "
            f"{sorted(_ORDER_RULES)} would fire as collateral. "
            f"got={study.col_names(domain)}"
        )


def test_driver_writes_expected_fileset(tmp_path, clean_dirs):
    inj = next(i for i in _injectors() if i.standard == "sdtmig")
    out_root = str(tmp_path / "vio")
    out_dir = apply_violations.apply_one(inj, clean_dirs["sdtmig"], out_root)

    files = set(os.listdir(out_dir))
    assert "expectation.json" in files
    # One Dataset-JSON per clean domain (presence injectors add one more).
    clean = vlib.load_clean(clean_dirs["sdtmig"])
    json_files = {f for f in files if f.endswith(".json") and f != "expectation.json"}
    assert len(json_files) >= len(clean.datasets)

    with open(os.path.join(out_dir, "expectation.json"), encoding="utf-8") as fh:
        exp = json.load(fh)
    assert exp["coreId"] == inj.core_id
    assert exp["standard"] == "sdtmig"
    assert exp["changes"]
    assert "allowedCollateral" in exp


def test_driver_is_idempotent(tmp_path, clean_dirs):
    inj = next(i for i in _injectors() if i.standard == "sendig")
    out_root = str(tmp_path / "vio")

    d1 = apply_violations.apply_one(inj, clean_dirs["sendig"], out_root)
    snap1 = {f: open(os.path.join(d1, f), "rb").read() for f in os.listdir(d1)}
    d2 = apply_violations.apply_one(inj, clean_dirs["sendig"], out_root)
    snap2 = {f: open(os.path.join(d2, f), "rb").read() for f in os.listdir(d2)}
    assert snap1 == snap2
