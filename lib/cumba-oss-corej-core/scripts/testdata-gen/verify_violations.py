"""Engine verification for the violation sub-studies.

For each generated ``<lane>/violations/<coreId>/`` sub-study this runs the Java
CORE CLI with the lane's ``-s/-v`` and checks two things against the clean
baseline:

1. the sub-study's target rule (``coreId`` from ``expectation.json``) **fires**
   (its ``core_id`` appears in ``Issue_Summary``), and
2. **no other** rule newly fires — the sub-study's fired-rule set equals
   ``clean_set | {coreId}`` (no collateral findings).

The clean baseline rule-set is computed once per lane.

This script depends on the Java CLI, so it is intentionally **not** part of the
pytest suite. Run it directly::

    PY=../../.venv-py-parity/bin/python
    $PY verify_violations.py --standard sdtmig          # all sub-studies in the lane
    $PY verify_violations.py --standard sendig --rules CORE-000310
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import library  # noqa: E402
import paths  # noqa: E402

# The repository this script belongs to. Derived from the script's own location
# (``<repo>/lib/<engine module>/scripts/testdata-gen/``) rather than hard-coded, so a
# checkout or git worktree verifies the engine and corpus *it* ships instead of silently
# measuring a different tree.
_REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                      "..", "..", "..", ".."))
_JAR = f"{_REPO}/clients/corej-cli/target/corej-cli-0.1.0-SNAPSHOT.jar"
_RULES_DIR = f"{_REPO}/lib/corej-rules/rules"
# The engine's ``-pc`` pickle cache has no default — see ``library.resolve_cache_dir``.
# It is resolved at the point of use so importing this module (``verify.py`` does)
# never depends on a configured host.

#: ``{lane: (standard flag, version, directory under the synthetic-testdata root)}``.
#: The lane's absolute root is resolved through :func:`paths.lane_root`, which has no
#: default: the study tree is generated output, not repository content.
_LANE = {
    "sdtmig": ("sdtmig", "3-4", "sdtmig-3-4"),
    "sendig": ("sendig", "3-1-1", "sendig-3-1-1"),
}


def families_for(standard_flag: str, version: str, rules_dir: str = _RULES_DIR) -> list[str]:
    """Every rule family that ships a package for ``(standard, version)``.

    **Why this exists.** Until family sharding landed (``03b058bf5``, 2026-07-03) the
    engine loaded one package per ``(standard, version)`` holding *every* family, and the
    conformance floor in ``expected_residuals.json`` was baselined against that whole-corpus
    view — its entries are CORE and FDA rules. After sharding, a run that names no
    ``-f/--family`` falls back to ``StudyValidationParams.DEFAULT_FAMILY = "CDISC"``
    (``StudyValidationService.pickConventionalRulesFiles``), so **not one floor rule could
    load**: the clean-study assertion "a fired rule not in the floor is a FAILURE" reported
    OVERALL PASS while checking nothing. Both harness scripts were broken this way from
    2026-07-03 until this fix.

    Resolving the list from the shipped ``packages.json`` manifest — rather than hard-coding
    ``CORE,FDA`` — restores the pre-sharding "all rules for this standard" semantics and keeps
    the harness honest when a family is added or dropped.

    Raises rather than returning an empty list: a family list that silently came back empty is
    exactly the failure mode this function was written to end.
    """
    manifest_path = os.path.join(rules_dir, "packages.json")
    with open(manifest_path, encoding="utf-8") as fh:
        manifest = json.load(fh)
    suffix = f"-{standard_flag}-{version}.json"
    families = sorted({
        p["family"] for p in manifest.get("packages", [])
        if p.get("file", "").endswith(suffix)
        and os.path.isfile(os.path.join(rules_dir, p["file"]))
    })
    if not families:
        raise RuntimeError(
            f"no rule package for {standard_flag} {version} in {manifest_path} — "
            "the engine would run with the default CDISC family alone and the floor "
            "comparison would be vacuous"
        )
    return families


def packages_for(standard_flag: str, version: str, rules_dir: str = _RULES_DIR) -> list[str]:
    """Every rule PACKAGE short name shipping for ``(standard, version)``.

    Plan 2 (``PLAN-rules-package-selection.md``) removed ``-s`` / ``-v`` / ``-f``: a run now
    names packages directly with ``-rp / --rules-package``. A short name is the package file
    name minus the invariant ``rules-`` prefix and ``.json`` suffix, so it is READ from the
    manifest's ``file`` field rather than reassembled from family/standard/version — the three
    axes are separately encoded and reassembling them is exactly the guesswork the plan removed.

    Keeps :func:`families_for`'s guarantee: raises rather than returning an empty list, because
    a silently empty selection is the failure mode both functions exist to end. (Under Plan 2
    the engine would also refuse to run at all — R3 — but failing here names the reason.)
    """
    manifest_path = os.path.join(rules_dir, "packages.json")
    with open(manifest_path, encoding="utf-8") as fh:
        manifest = json.load(fh)
    suffix = f"-{standard_flag}-{version}.json"
    names = sorted({
        p["file"][len("rules-"):-len(".json")]
        for p in manifest.get("packages", [])
        if p.get("file", "").startswith("rules-")
        and p.get("file", "").endswith(suffix)
        and os.path.isfile(os.path.join(rules_dir, p["file"]))
    })
    if not names:
        raise RuntimeError(
            f"no rule package for {standard_flag} {version} in {manifest_path} — "
            "the run would select no rules and the floor comparison would be vacuous"
        )
    return names


def run_engine(standard_flag: str, version: str, data_dir: str) -> set[str]:
    """Run the CLI on ``data_dir``; return the set of fired ``core_id``s."""
    with tempfile.TemporaryDirectory() as tmp:
        stem = os.path.join(tmp, "r.json")
        cmd = [
            "java",
            "-Dcorej.maxErrorsPerRule=0",
            "-jar",
            _JAR,
            "-d",
            data_dir,
            "--rules-dir",
            _RULES_DIR,
            "-rp",
            ",".join(packages_for(standard_flag, version)),
            "-pc",
            library.resolve_cache_dir(),
            "-of",
            "json2",
            "-o",
            stem,
        ]
        proc = subprocess.run(cmd, cwd=_REPO, capture_output=True, text=True)
        report = stem[: -len(".json")] + ".v2.json"
        if not os.path.isfile(report):
            raise RuntimeError(
                f"engine produced no report for {data_dir}\n"
                f"stdout:\n{proc.stdout[-2000:]}\nstderr:\n{proc.stderr[-2000:]}"
            )
        with open(report, encoding="utf-8") as fh:
            data = json.load(fh)
    return {e["core_id"] for e in data.get("Issue_Summary", []) if e.get("core_id")}


def verify_lane(standard: str, rules: set[str] | None) -> tuple[int, int, list[str]]:
    flag, version, lane_dir = _LANE[standard]
    root = paths.lane_root(lane_dir)
    clean_set = run_engine(flag, version, os.path.join(root, "clean"))
    vio_root = os.path.join(root, "violations")
    if not os.path.isdir(vio_root):
        raise FileNotFoundError(f"no violations dir: {vio_root} (run apply_violations.py first)")

    passed = 0
    total = 0
    report_lines: list[str] = [f"clean baseline ({standard}): {len(clean_set)} fired rules"]
    for core_id in sorted(os.listdir(vio_root)):
        sub = os.path.join(vio_root, core_id)
        exp_path = os.path.join(sub, "expectation.json")
        if not os.path.isfile(exp_path):
            continue
        with open(exp_path, encoding="utf-8") as fh:
            exp = json.load(fh)
        target = exp["coreId"]
        if rules is not None and target not in rules:
            continue
        total += 1
        allowed = set(exp.get("allowedCollateral", []))
        fired = run_engine(flag, version, sub)
        fires = target in fired
        new = fired - clean_set                  # everything newly firing
        collateral = new - {target}              # newly firing besides the target
        undocumented = collateral - allowed      # collateral NOT declared as a twin
        missing = (clean_set - fired)            # clean rules that stopped firing
        strict = fires and not collateral        # the ideal: exactly one new finding
        ok = fires and not undocumented          # target + only declared twins
        passed += ok
        if strict:
            status = "PASS"
        elif ok:
            status = "PASS*"                     # passes thanks to declared twins
        else:
            status = "FAIL"
        detail = f"fires={fires}"
        if undocumented:
            detail += f" UNDOCUMENTED={sorted(undocumented)}"
        if allowed & collateral:
            detail += f" twins={sorted(allowed & collateral)}"
        if not fires:
            detail += f" new_fired={sorted(new)}"
        if missing:
            detail += f" no_longer_firing={sorted(missing)}"
        report_lines.append(f"  [{status}] {target:14s} {detail}")
    report_lines.append(
        f"\n{standard}: {passed}/{total} sub-studies fire their target with only "
        f"documented collateral (PASS* = relies on declared twins)"
    )
    return passed, total, report_lines


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--standard", required=True, choices=("sdtmig", "sendig"))
    ap.add_argument("--rules", help="comma-separated CORE ids (default: all in lane)")
    library.add_cache_dir_argument(ap)
    paths.add_synth_root_argument(ap)
    args = ap.parse_args(argv)

    library.set_cache_dir(args.cache_dir)
    paths.set_synth_root(args.synth_root)
    # Resolved up front so an unconfigured run fails naming what to set, rather
    # than after the first engine invocation has already been spawned.
    try:
        library.resolve_cache_dir()
        paths.synth_root()
    except (library.CacheDirNotConfigured, paths.SynthRootNotConfigured) as exc:
        raise SystemExit(str(exc)) from exc

    rules = {r.strip() for r in args.rules.split(",")} if args.rules else None
    _passed, _total, lines = verify_lane(args.standard, rules)
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
