"""Unit tests for the generator (Phase 1, SDTMIG lane)."""

import re

import pytest

import domains as dom_mod
import library
import study as study_mod
from generate import (
    Generator,
    all_missing_columns,
    drop_unpopulated_permissible,
    has_anomalous_label,
    unpopulated_permissible,
)


@pytest.fixture(scope="module")
def gen():
    lib = library.Library(library.SDTMIG_3_4)
    st = study_mod.build_study("sdtmig", "3-4", n_subjects=4, n_visits=5)
    return Generator(lib, st)


def _row_dicts(gen, domain):
    cols = [v.name for v in gen.columns(domain)]
    rows = gen.build_domain(domain)
    return cols, [dict(zip(cols, r)) for r in rows]


def test_columns_are_standard_bounded(gen):
    # AGETXT is SENDIG-only; it must never appear in the SDTMIG DM.
    dm_cols = {v.name for v in gen.columns("DM")}
    assert "AGETXT" not in dm_cols
    assert {"STUDYID", "USUBJID", "ARMCD"} <= dm_cols


def test_seq_unique_per_subject(gen):
    cols, rows = _row_dicts(gen, "PC")
    by_subj: dict[str, list] = {}
    for r in rows:
        by_subj.setdefault(r["USUBJID"], []).append(r["PCSEQ"])
    for seqs in by_subj.values():
        assert len(seqs) == len(set(seqs))


def test_required_variables_populated(gen):
    cols, rows = _row_dicts(gen, "AE")
    for v in gen.columns("AE"):
        if v.core == "Req":
            assert all(str(r[v.name]) != "" for r in rows), f"{v.name} empty"


def test_testcd_test_one_to_one_and_short(gen):
    cols, rows = _row_dicts(gen, "MB")
    mapping = {}
    for r in rows:
        code, name = r["MBTESTCD"], r["MBTEST"]
        assert len(name) <= 40
        assert mapping.setdefault(code, name) == name  # bijective


def test_values_are_human_readable(gen):
    _cols, rows = _row_dicts(gen, "LB")
    junk = re.compile(r"^[A-Za-z]\d+$")  # e.g. "A1" placeholder style
    for r in rows[:10]:
        assert not junk.match(str(r["LBTEST"]))
        assert " " in r["LBTEST"]  # a real multi-word test name


def test_dm_arms_match_ta(gen):
    _c, dm = _row_dicts(gen, "DM")
    _c2, ta = _row_dicts(gen, "TA")
    ta_arms = {r["ARMCD"] for r in ta}
    assert {r["ARMCD"] for r in dm} <= ta_arms


def test_dates_are_iso(gen):
    _c, rows = _row_dicts(gen, "VS")
    for r in rows:
        if r.get("VSDTC"):
            # YYYY-MM-DD
            assert re.match(r"^\d{4}-\d{2}-\d{2}$", str(r["VSDTC"]))


def test_study_day_consistent_with_date(gen):
    _c, rows = _row_dicts(gen, "VS")
    subj = {s.usubjid: s for s in gen.study.subjects}
    import datetime as dt
    for r in rows:
        if r.get("VSDTC") and r.get("VSDY") != "":
            s = subj[r["USUBJID"]]
            expected = s.study_day(dt.date.fromisoformat(r["VSDTC"]))
            assert r["VSDY"] == expected


def test_mh_dates_dropped(gen):
    # MH start/end dates are omitted (the STDY contradiction); confirms the
    # per-domain drop is applied.
    mh_cols = {v.name for v in gen.columns("MH")}
    assert "MHSTDTC" not in mh_cols and "MHENDTC" not in mh_cols


def test_ex_not_after_disposition(gen):
    # No EX exposure record may end after the subject's disposition date
    # (DS.DSSTDTC). EX dates live inside the treatment window (<= rfxendtc),
    # which precedes DS (= rfendtc).
    _c, ex = _row_dicts(gen, "EX")
    _c2, ds = _row_dicts(gen, "DS")
    ds_date = {r["USUBJID"]: r["DSSTDTC"] for r in ds}
    for r in ex:
        if r.get("EXENDTC"):
            assert r["EXENDTC"] <= ds_date[r["USUBJID"]]  # ISO strings sort by date


def test_visit_numbers_unique_for_small_configs():
    # Regression: with n_visits < 3 the follow-up VISITNUM used to collide with
    # the single treatment visit (both 2.0). VISITNUM must stay unique for every
    # supported --visits value so TV/SV stay coherent.
    for nv in (2, 3, 4, 10):
        st = study_mod.build_study("sdtmig", "3-4", n_subjects=2, n_visits=nv)
        nums = [v.num for v in st.visits]
        assert len(nums) == len(set(nums)), f"duplicate VISITNUM at n_visits={nv}"


def test_generation_deterministic_for_nondefault_config(tmp_path):
    # Two independent generations with the same non-default shape must be
    # byte-identical (no hash()/set-ordering/time leakage into output).
    import os

    lib = library.Library(library.SDTMIG_3_4)

    def gen_to(dirpath):
        st = study_mod.build_study("sdtmig", "3-4", n_subjects=7, n_visits=6)
        Generator(lib, st).generate(str(dirpath))

    a, b = tmp_path / "a", tmp_path / "b"
    gen_to(a)
    gen_to(b)
    files = sorted(os.listdir(a))
    assert files == sorted(os.listdir(b))
    for f in files:
        assert (a / f).read_bytes() == (b / f).read_bytes(), f


# --- SENDIG lane (Phase 2) ---------------------------------------------------


@pytest.fixture(scope="module")
def send_gen():
    lib = library.Library(library.SENDIG_3_1_1)
    st = study_mod.build_study("sendig", "3-1-1", n_subjects=4, n_visits=5)
    return Generator(lib, st)


def _send_rows(send_gen, domain):
    cols = [v.name for v in send_gen.columns(domain)]
    rows = send_gen.build_domain(domain)
    return [dict(zip(cols, r)) for r in rows]


def test_send_ts_has_required_fda_params(send_gen):
    rows = _send_rows(send_gen, "TS")
    parmcds = {r["TSPARMCD"] for r in rows}
    # FDA-SE23xx required SEND Trial Summary parameters.
    assert {"SLENGTH", "SPLANSUB", "STENDTC", "DOSSTDTC", "DOSENDTC",
            "PCLASS", "PDOSFRQ"} <= parmcds


def test_send_exlot_populated_when_dosed(send_gen):
    rows = _send_rows(send_gen, "EX")
    # FDA-SE2353: EXLOT must be non-empty whenever EXDOSE > 0.
    for r in rows:
        if float(r["EXDOSE"] or 0) > 0:
            assert str(r["EXLOT"]) != ""


def test_send_necropsy_date_matches_disposition(send_gen):
    # FDA-SE2270/2276: post-mortem findings (TF) are dated at the subject's
    # disposition (DS.DSSTDTC == rfendtc), and one record per subject.
    ds = {r["USUBJID"]: r for r in _send_rows(send_gen, "DS")}
    tf = _send_rows(send_gen, "TF")
    seen: dict[str, int] = {}
    for r in tf:
        seen[r["USUBJID"]] = seen.get(r["USUBJID"], 0) + 1
        assert r["TFDTC"] == ds[r["USUBJID"]]["DSSTDTC"]
        assert str(r["TFDY"]) == str(ds[r["USUBJID"]]["DSSTDY"])
    assert all(n == 1 for n in seen.values())


def test_send_tf_has_categorical_result(send_gen):
    # TF has a Req --RESCAT but no --STRESN/--STAT, so a categorical --STRESC is
    # populated (FDA-SD0045/0047/1320 cleared; FDA-SD0029 is the documented floor).
    tf = _send_rows(send_gen, "TF")
    for r in tf:
        assert str(r["TFSTRESC"]) != ""
        assert str(r["TFORRES"]) != ""
        assert str(r["TFRESCAT"]) != ""


def test_send_cl_rescat_left_empty(send_gen):
    # CLRESCAT is Perm: leaving it empty avoids FDA-SD0045 (RESCAT without STRESC).
    cl = _send_rows(send_gen, "CL")
    assert all(str(r["CLRESCAT"]) == "" for r in cl)


def test_send_dm_excludes_sdtm_only_vars(send_gen):
    # No cross-standard leak: the SENDIG DM carries the SEND variables and must
    # never carry the SDTM-only human-demographics variables.
    cols = {v.name for v in send_gen.columns("DM")}
    assert "RACE" not in cols and "ETHNIC" not in cols
    assert {"SPECIES", "STRAIN", "SETCD"} <= cols


# --- FDA-SD1078: no Permissible variable is shipped unpopulated --------------
# `FDA-SD1078` / `PMDA-SD1078` raise an error for every Permissible variable
# present in a dataset but empty for all records. The generator therefore emits a
# Perm column only when some record populates it (generate.drop_unpopulated_
# permissible). These tests pin that behaviour and its three carve-outs:
# Req/Exp columns, key variables, and zero-row datasets.


def _var(name, core):
    return library.Variable(
        name=name, label=name, datatype="Char", core=core, role="",
        ordinal=0, description="", codelist_codes=(),
    )


def test_drop_unpopulated_permissible_drops_only_empty_perm():
    cols = [_var("REQE", "Req"), _var("EXPE", "Exp"),
            _var("PERMEMPTY", "Perm"), _var("PERMUSED", "Perm")]
    rows = [["a", "", "", ""], ["b", "", "", "x"]]
    kept, new_rows, dropped = drop_unpopulated_permissible(cols, rows, set())
    # Only the all-empty Permissible column goes.
    assert dropped == ["PERMEMPTY"]
    assert [v.name for v in kept] == ["REQE", "EXPE", "PERMUSED"]
    # Rows stay index-aligned with the surviving columns.
    assert new_rows == [["a", "", ""], ["b", "", "x"]]
    # The originals are not mutated (the caller may still need them).
    assert len(cols) == 4 and rows[0] == ["a", "", "", ""]


def test_drop_unpopulated_permissible_keeps_expected_and_required_empty():
    # Req/Exp are the contract: an Expected variable that is empty for every
    # record is FDA-SD1149's business, not SD1078's, and it must stay.
    cols = [_var("REQE", "Req"), _var("EXPE", "Exp")]
    rows = [["", ""], ["", ""]]
    kept, new_rows, dropped = drop_unpopulated_permissible(cols, rows, set())
    assert dropped == []
    assert [v.name for v in kept] == ["REQE", "EXPE"]
    assert new_rows is rows


def test_drop_unpopulated_permissible_keeps_protected_keys():
    # A key variable identifies a record; dropping it would strip the
    # keySequence emit.build_dataset writes and the Define-XML KeySequence.
    cols = [_var("KEYVAR", "Perm"), _var("OTHER", "Perm")]
    rows = [["", ""], ["", ""]]
    kept, _new_rows, dropped = drop_unpopulated_permissible(cols, rows, {"KEYVAR"})
    assert dropped == ["OTHER"]
    assert [v.name for v in kept] == ["KEYVAR"]


def test_drop_unpopulated_permissible_zero_rows_drops_nothing():
    # "empty in every row" is vacuously true with no rows, so it carries no
    # information — and var_is_null has no record to be null on either.
    cols = [_var("PERMA", "Perm"), _var("PERMB", "Perm")]
    kept, new_rows, dropped = drop_unpopulated_permissible(cols, [], set())
    assert dropped == []
    assert [v.name for v in kept] == ["PERMA", "PERMB"]
    assert new_rows == []


def test_drop_unpopulated_permissible_treats_none_as_empty():
    cols = [_var("PERMA", "Perm")]
    kept, _rows, dropped = drop_unpopulated_permissible(cols, [[None], [""]], set())
    assert dropped == ["PERMA"] and kept == []


def test_drop_unpopulated_permissible_honours_the_co_presence_keep_set():
    # The co-presence contract: a column named in ``keep`` stays even though it
    # is an unpopulated Permissible variable, because dropping it would let some
    # rule that tests its *absence* fire.
    cols = [_var("PERMA", "Perm"), _var("PERMB", "Perm")]
    rows = [["", ""], ["", ""]]
    kept, new_rows, dropped = drop_unpopulated_permissible(
        cols, rows, set(), keep={"PERMA"})
    assert dropped == ["PERMB"]
    assert [v.name for v in kept] == ["PERMA"]
    assert new_rows == [[""], [""]]


def test_unpopulated_permissible_lists_the_candidates():
    cols = [_var("REQE", "Req"), _var("PERMEMPTY", "Perm"),
            _var("PERMUSED", "Perm"), _var("PERMKEY", "Perm")]
    rows = [["a", "", "x", ""]]
    assert unpopulated_permissible(cols, rows, {"PERMKEY"}) == ["PERMEMPTY"]
    assert unpopulated_permissible(cols, [], set()) == []


def test_all_missing_columns_covers_every_core_class():
    cols = [_var("REQE", "Req"), _var("EXPE", "Exp"), _var("PERMUSED", "Perm")]
    rows = [["a", "", "x"], ["b", "", ""]]
    assert all_missing_columns(cols, rows) == {"EXPE"}
    # With no rows every column is (vacuously) unpopulated.
    assert all_missing_columns(cols, []) == {"REQE", "EXPE", "PERMUSED"}


def test_has_anomalous_label_flags_only_malformed_library_labels():
    # GF.GFSEQID's published label is literally "Sequence Identifier \n". It
    # anchors GEN-VMCALM-LBL, which reports nothing at all once the column goes.
    assert has_anomalous_label(_var_labelled("GFSEQID", "Sequence Identifier \\n"))
    assert has_anomalous_label(_var_labelled("X", "Trailing "))
    assert has_anomalous_label(_var_labelled("X", "New\nline"))
    assert not has_anomalous_label(_var_labelled("VISITNUM", "Visit Number"))
    assert not has_anomalous_label(_var_labelled("X", ""))


def _var_labelled(name, label):
    return library.Variable(
        name=name, label=label, datatype="Char", core="Perm", role="",
        ordinal=0, description="", codelist_codes=(),
    )


def _emitted(gen, tmp_path):
    """Generate into ``tmp_path``; return ``{DOMAIN: dataset-json dict}``."""
    import json
    import os

    gen.generate(str(tmp_path))
    out = {}
    for fname in sorted(os.listdir(tmp_path)):
        if fname.endswith(".json"):
            with open(tmp_path / fname, encoding="utf-8") as fh:
                ds = json.load(fh)
            out[ds["name"]] = ds
    return out


def _all_empty_perm(lib, ds):
    """Names of Perm columns in ``ds`` that are empty on every record."""
    core = {v.name: v.core for v in lib.variables(ds["name"])}
    names = [c["name"] for c in ds["columns"]]
    rows = ds["rows"]
    if not rows:
        return []
    return [
        n for i, n in enumerate(names)
        if core.get(n) == "Perm"
        and all(r[i] is None or str(r[i]) == "" for r in rows)
    ]


def _surviving_empty_perm(g, datasets):
    """``{domain: [all-empty Perm columns still emitted]}``, empty entries pruned."""
    out = {}
    for domain, ds in datasets.items():
        offenders = _all_empty_perm(g.lib, ds)
        if offenders:
            out[domain] = sorted(offenders)
    return out


def test_only_co_presence_partners_survive_as_empty_permissible_sdtmig(gen, tmp_path):
    # Every all-empty Perm column that is still emitted must be one the drop was
    # *forced* to spare, and the reason must be recorded. An unexplained survivor
    # is an SD1078 finding nobody signed off on.
    datasets = _emitted(gen, tmp_path)
    survivors = _surviving_empty_perm(gen, datasets)
    explained = {d: sorted(m) for d, m in gen.kept_permissible.items()}
    assert survivors == explained
    assert all(reason for m in gen.kept_permissible.values() for reason in m.values())
    # Positive control: the drop actually removed something, so the assertion
    # above is not green for lack of Permissible variables.
    assert sum(len(v) for v in gen.dropped_permissible.values()) > 100
    # ... and it spared far fewer than it dropped.
    assert sum(len(v) for v in explained.values()) < 50


def test_only_co_presence_partners_survive_as_empty_permissible_sendig(send_gen, tmp_path):
    datasets = _emitted(send_gen, tmp_path)
    survivors = _surviving_empty_perm(send_gen, datasets)
    assert survivors == {d: sorted(m) for d, m in send_gen.kept_permissible.items()}
    assert sum(len(v) for v in send_gen.dropped_permissible.values()) > 20


def test_the_dtc_dy_co_presence_pair_is_never_half_emitted(gen, tmp_path):
    # CORE-000321 / FDA-SD1083 / PMDA-SD1083: "--DTC present => --DY present".
    # BS.BSDTC is Expected (so it cannot be dropped) and empty, which is exactly
    # the shape that made the naive drop fire nine rules on 2026-08-07.
    datasets = _emitted(gen, tmp_path)
    for domain in ("BS", "GF"):
        names = {c["name"] for c in datasets[domain]["columns"]}
        assert (f"{domain}DTC" in names) == (f"{domain}DY" in names), domain
        assert f"{domain}DY" in gen.kept_permissible.get(domain, {})


def test_link_id_is_present_in_at_least_two_datasets_or_none(gen, tmp_path):
    # CDISC-CG0024 / CORE-000571: --LNKID present here but in no other domain.
    datasets = _emitted(gen, tmp_path)
    carriers = [d for d, ds in datasets.items()
                if any(c["name"].endswith("LNKID") for c in ds["columns"])]
    assert len(carriers) != 1, carriers
    assert len(carriers) >= 2


def test_populated_permissible_column_survives(gen, tmp_path):
    # EX.EXROUTE and EX.EPOCH are Permissible *and* populated on every record —
    # a Perm column that carries data must not be dropped, or the study would
    # lose real content in the name of SD1078.
    datasets = _emitted(gen, tmp_path)
    core = {v.name: v.core for v in gen.lib.variables("EX")}
    assert core["EXROUTE"] == "Perm" and core["EPOCH"] == "Perm"
    names = [c["name"] for c in datasets["EX"]["columns"]]
    assert {"EXROUTE", "EPOCH"} <= set(names)
    for col in ("EXROUTE", "EPOCH"):
        i = names.index(col)
        assert any(str(r[i]) != "" for r in datasets["EX"]["rows"])


def test_expected_empty_column_is_not_dropped(gen, tmp_path):
    # DM.DTHDTC/DTHFL are Expected and legitimately empty (no deaths). They stay:
    # an absent Expected variable is its own finding.
    datasets = _emitted(gen, tmp_path)
    dm_names = [c["name"] for c in datasets["DM"]["columns"]]
    assert {"DTHDTC", "DTHFL"} <= set(dm_names)


# NOTE: there is deliberately no *integration* test that key columns survive the
# drop. Measured on both lanes, no key variable derived by ``domains.key_vars``
# is ``Perm`` in any generated domain, so such a test stays green even with the
# ``protected`` guard removed — it could not fail, and so would prove nothing.
# The guard is pinned by ``test_drop_unpopulated_permissible_keeps_protected_keys``
# instead, which does go red when the guard is deleted.


def test_define_xml_declares_exactly_the_emitted_columns(gen, tmp_path):
    # define.py consumes the emitted dataset dicts, so a dropped column must
    # disappear from define.xml too — otherwise the define would describe a
    # variable the data does not ship.
    import xml.etree.ElementTree as ET

    datasets = _emitted(gen, tmp_path)
    assert gen.dropped_permissible, "nothing was dropped; the test proves nothing"
    odm = "{http://www.cdisc.org/ns/odm/v1.3}"
    root = ET.parse(tmp_path / "define.xml").getroot()
    item_oids = {el.get("OID") for el in root.iter(f"{odm}ItemDef")}
    for igd in root.iter(f"{odm}ItemGroupDef"):
        domain = igd.get("Name")
        refs = [r.get("ItemOID") for r in igd.findall(f"{odm}ItemRef")]
        expected = [f"IT.{domain}.{c['name']}" for c in datasets[domain]["columns"]]
        assert refs == expected, domain
    # Every dropped variable is absent from the define, not merely unreferenced.
    for domain, dropped in gen.dropped_permissible.items():
        for name in dropped:
            assert f"IT.{domain}.{name}" not in item_oids, f"{domain}.{name}"
