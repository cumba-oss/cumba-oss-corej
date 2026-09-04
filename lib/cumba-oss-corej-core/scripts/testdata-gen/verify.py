"""Phase 6 — one-command verification harness & coverage report.

Per lane this:

1. **Regenerates** the clean study + ``define.xml`` (unless ``--skip-regen``)
   and re-applies every violation injector into ``<lane>/violations/``.
2. Runs the Java CORE CLI on ``clean/`` **with** ``-dxp`` and asserts the
   ``Issue_Summary`` equals the documented conformance floor in
   ``expected_residuals.json`` — a fired rule **not** in the floor is a FAILURE;
   a floor rule that stops firing is a *notable change* (reported, not failed).
3. Verifies every violation sub-study fires its ``expectation.json`` target and
   only its declared ``allowedCollateral`` twins — by reusing
   :func:`verify_violations.verify_lane` (no logic is duplicated here).
4. Emits a markdown **coverage report** to
   ``documentation/synthetic-testdata-coverage.md``.
5. Prints a PASS/FAIL summary and exits non-zero on any failure (CI gate).

Usage::

    PY=../../.venv-py-parity/bin/python
    $PY verify.py                       # both lanes, full regen
    $PY verify.py --lane sdtmig
    $PY verify.py --skip-regen          # reuse existing clean/ + violations/

The report-parsing / floor-comparison helpers (:func:`summarize_report`,
:func:`compare_floor`, :func:`load_expected_residuals`,
:func:`dormant_rules_unlocked`) are pure and unit-tested without the Java CLI
(see ``tests/test_verify.py``).
"""

from __future__ import annotations

import argparse
import datetime as _dt
import json
import os
import subprocess
import sys
import tempfile

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import apply_violations  # noqa: E402
import library  # noqa: E402
import paths  # noqa: E402
import rulescan  # noqa: E402
import study as study_mod  # noqa: E402
import verify_violations  # noqa: E402
from generate import Generator  # noqa: E402

# --------------------------------------------------------------------------- #
# Engine invocation parameters (single source of truth).
# --------------------------------------------------------------------------- #
# Derived from the script's own location, not hard-coded — see verify_violations._REPO.
_REPO = os.path.normpath(os.path.join(_HERE, "..", "..", "..", ".."))
_JAR = f"{_REPO}/clients/corej-cdisc-cli/target/corej-cdisc-cli-0.1.0-SNAPSHOT.jar"
_RULES_DIR = f"{_REPO}/lib/corej-cdisc-rules/rules"
# The engine's ``-pc`` pickle cache has no default — see ``library.resolve_cache_dir``.
# It is resolved at the point of use, not at import, so the pure helpers below (and
# ``tests/test_verify.py``) stay importable in a tree that has no cache configured.
_EXPECTED = os.path.join(_HERE, "expected_residuals.json")
_COVERAGE_DOC = os.path.normpath(
    os.path.join(_HERE, "..", "..", "..", "corej-cdisc-rules", "documentation",
                 "synthetic-testdata-coverage.md")
)

# The lanes and their library spec / engine std-version.
_LANES = {
    "sdtmig": {
        "flag": "sdtmig",
        "version": "3-4",
        "spec": library.SDTMIG_3_4,
        "dir": "sdtmig-3-4",
    },
    "sendig": {
        "flag": "sendig",
        "version": "3-1-1",
        "spec": library.SENDIG_3_1_1,
        "dir": "sendig-3-1-1",
    },
}

# Domains already present in the three existing whole-study test datasets
# (DataExchange / PhUSE / Pilot). A generated domain outside this set is one the
# real studies never carried — so its rules were dormant for lack of data.
_EXISTING_DOMAINS = {
    "AE", "CM", "DD", "DI", "DM", "DS", "EC", "EX", "FA", "FT", "IE", "LB",
    "MH", "OE", "QS", "RELREC", "RS", "SC", "SE", "SV", "TA", "TE", "TI",
    "TS", "TV", "VS",
}


# --------------------------------------------------------------------------- #
# Pure helpers (unit-tested without the Java CLI).
# --------------------------------------------------------------------------- #
def load_expected_residuals(path: str = _EXPECTED) -> dict:
    """Load the committed floor baseline (``{lane: {coreId: {...}}}``)."""
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def expected_for_lane(data: dict, lane: str) -> dict[str, dict]:
    """The ``{coreId: meta}`` floor for one lane (``_meta`` keys stripped)."""
    return {k: v for k, v in data.get(lane, {}).items() if not k.startswith("_")}


def summarize_report(report: dict) -> dict:
    """Distil an engine ``…v2.json`` report into the metrics the harness needs."""
    issue_ids = {
        e["core_id"] for e in report.get("Issue_Summary", []) if e.get("core_id")
    }
    executable_ids = {
        r["core_id"] for r in report.get("Rules_Report", []) if r.get("core_id")
    }
    skipped = report.get("Skipped_Rules", [])
    skipped_ids = {s["core_id"] for s in skipped if s.get("core_id")}
    return {
        "issue_ids": issue_ids,
        "executable_ids": executable_ids,
        "skipped_entries": len(skipped),
        "skipped_ids": skipped_ids,
    }


def compare_floor(issue_ids: set[str], expected: dict[str, dict]) -> dict:
    """Diff a fired-rule set against the documented floor.

    ``undocumented`` (fired but not in the floor) is the FAILURE set;
    ``disappeared`` (in the floor but no longer firing) is merely notable.
    """
    exp = set(expected)
    return {
        "undocumented": sorted(issue_ids - exp),
        "disappeared": sorted(exp - issue_ids),
        "matched": sorted(issue_ids & exp),
    }


def dormant_rules_unlocked(standard: str, generated_domains) -> tuple[list[str], set[str], dict]:
    """Rules that were dormant for lack of data, now exercised by this lane.

    Uses :func:`rulescan.RuleScan.targets_for_standard` to count, per
    newly-added domain (generated but absent from the existing studies), the
    distinct rules targeting it. Returns ``(new_domains, rule_ids, per_domain)``.
    """
    scan = rulescan.scan()
    targets = scan.targets_for_standard(standard)
    new_domains = sorted(set(generated_domains) - _EXISTING_DOMAINS)
    rule_ids: set[str] = set()
    per_domain: dict[str, int] = {}
    for dom in new_domains:
        ids = set(targets.get(dom, []))
        if ids:
            per_domain[dom] = len(ids)
            rule_ids |= ids
    return new_domains, rule_ids, per_domain


# --------------------------------------------------------------------------- #
# Engine + generator orchestration (needs the Java CLI / filesystem).
# --------------------------------------------------------------------------- #
def regenerate(lane: str) -> dict[str, int]:
    """Regenerate ``<lane>/clean/`` (+ define.xml); return ``{domain: rows}``."""
    cfg = _LANES[lane]
    clean_dir = os.path.join(paths.lane_root(cfg["dir"]), "clean")
    lib = library.Library(cfg["spec"])
    st = study_mod.build_study(cfg["flag"], cfg["version"])
    counts = Generator(lib, st).generate(clean_dir)
    return counts


def reapply_violations(lane: str) -> int:
    """Re-run every injector for the lane into ``<lane>/violations/``."""
    cfg = _LANES[lane]
    clean_dir = os.path.join(paths.lane_root(cfg["dir"]), "clean")
    out_root = os.path.join(paths.lane_root(cfg["dir"]), "violations")
    injectors = apply_violations.select(apply_violations.discover(), lane, None)
    for inj in injectors:
        apply_violations.apply_one(inj, clean_dir, out_root)
    return len(injectors)


def run_engine(lane: str, data_dir: str, with_define: bool) -> dict:
    """Run the CLI on ``data_dir``; return the parsed ``…v2.json`` report."""
    cfg = _LANES[lane]
    with tempfile.TemporaryDirectory() as tmp:
        stem = os.path.join(tmp, "r.json")
        cmd = [
            "java", "-Dcorej.maxErrorsPerRule=0", "-jar", _JAR,
            "-d", data_dir,
        ]
        if with_define:
            define = os.path.join(data_dir, "define.xml")
            cmd += ["-dxp", define, "-dv", "2-1"]
        # Naming EVERY package for this (standard, version) is not optional: selecting only
        # the CDISC one means no CORE / FDA floor rule can be read, which made this whole
        # comparison vacuous between 2026-07-03 and the fix that introduced the family list.
        # Plan 2 turned that family list into a package list; the hazard is unchanged.
        cmd += [
            "--rules-dir", _RULES_DIR,
            "-rp", ",".join(verify_violations.packages_for(cfg["flag"], cfg["version"])),
            "-pc", library.resolve_cache_dir(),
            "-of", "json2", "-o", stem,
        ]
        proc = subprocess.run(cmd, cwd=_REPO, capture_output=True, text=True)
        report = stem[: -len(".json")] + ".v2.json"
        if not os.path.isfile(report):
            raise RuntimeError(
                f"engine produced no report for {data_dir}\n"
                f"stdout:\n{proc.stdout[-2000:]}\nstderr:\n{proc.stderr[-2000:]}"
            )
        with open(report, encoding="utf-8") as fh:
            return json.load(fh)


# --------------------------------------------------------------------------- #
# Per-lane verification.
# --------------------------------------------------------------------------- #
def verify_lane(lane: str, expected_all: dict, skip_regen: bool) -> dict:
    """Run the full Phase-6 verification for one lane; return a result dict."""
    cfg = _LANES[lane]
    clean_dir = os.path.join(paths.lane_root(cfg["dir"]), "clean")
    expected = expected_for_lane(expected_all, lane)

    print(f"\n=== lane {lane} ===")
    if skip_regen:
        # Domain/row counts come from the already-generated clean study.
        counts = _counts_from_disk(clean_dir)
        n_injectors = _count_injectors(lane)
        print(f"  [skip-regen] using existing {clean_dir}")
    else:
        print("  regenerating clean study + define.xml ...")
        counts = regenerate(lane)
        n_injectors = reapply_violations(lane)
        print(f"  regenerated {len(counts)} domains; re-applied {n_injectors} injectors")

    print("  engine run on clean (with define) ...")
    rep_def = summarize_report(run_engine(lane, clean_dir, with_define=True))
    print("  engine run on clean (no define) ...")
    rep_nodef = summarize_report(run_engine(lane, clean_dir, with_define=False))

    floor = compare_floor(rep_def["issue_ids"], expected)

    # Vacuity guard (the positive control this harness lacked). `undocumented == 0` is only
    # evidence when the floor rules were actually in the run: between 2026-07-03 and the
    # `-f` fix above, the CDISC-only default meant not one CORE / FDA floor rule was loaded,
    # so the set was empty for the wrong reason and every recorded PASS was worthless. A
    # floor rule that appears nowhere in the report — not fired, not executed, not skipped —
    # means the comparison is measuring nothing, so it fails the lane outright.
    seen_ids = rep_def["issue_ids"] | rep_def["executable_ids"] | rep_def["skipped_ids"]
    not_loaded = sorted(set(expected) - seen_ids)
    if not_loaded:
        print(f"  !! floor rules absent from the engine report: {', '.join(not_loaded)}")

    print("  verifying violation injectors ...")
    inj_passed, inj_total, inj_lines = verify_violations.verify_lane(lane, None)

    new_domains, dormant_ids, dormant_per = dormant_rules_unlocked(lane, counts.keys())
    define_unlocked = sorted(rep_nodef["skipped_ids"] - rep_def["skipped_ids"])

    clean_ok = not floor["undocumented"] and not not_loaded
    inj_ok = inj_passed == inj_total
    lane_ok = clean_ok and inj_ok

    return {
        "lane": lane,
        "ok": lane_ok,
        "clean_ok": clean_ok,
        "inj_ok": inj_ok,
        "not_loaded": not_loaded,
        "counts": counts,
        "n_domains": len(counts),
        "n_rows": sum(counts.values()),
        "executable_rules": len(rep_def["executable_ids"]),
        "skipped_entries_def": rep_def["skipped_entries"],
        "skipped_entries_nodef": rep_nodef["skipped_entries"],
        "define_unlocked": define_unlocked,
        "floor": floor,
        "expected": expected,
        "new_domains": new_domains,
        "dormant_ids": sorted(dormant_ids),
        "dormant_per": dormant_per,
        "inj_passed": inj_passed,
        "inj_total": inj_total,
        "inj_lines": inj_lines,
    }


def _counts_from_disk(clean_dir: str) -> dict[str, int]:
    counts: dict[str, int] = {}
    for fname in sorted(os.listdir(clean_dir)):
        if not fname.endswith(".json"):
            continue
        with open(os.path.join(clean_dir, fname), encoding="utf-8") as fh:
            ds = json.load(fh)
        name = ds.get("name")
        if name:
            counts[name] = int(ds.get("records", len(ds.get("rows", []))))
    return counts


def _count_injectors(lane: str) -> int:
    return len(apply_violations.select(apply_violations.discover(), lane, None))


# --------------------------------------------------------------------------- #
# Coverage report (markdown).
# --------------------------------------------------------------------------- #
def render_coverage(results: list[dict], expected_all: dict) -> str:
    now = _dt.datetime.now().strftime("%Y-%m-%d")
    rebaselined = expected_all.get("_meta", {}).get("rebaselined", "")
    lines: list[str] = []
    lines.append("# Synthetic SDTM/SEND test-data coverage")
    lines.append("")
    lines.append(
        "Generated by `scripts/testdata-gen/verify.py` (Phase 6 harness). It "
        "regenerates each clean study + `define.xml`, runs the Java CORE CLI, "
        "checks the clean study against its documented conformance floor "
        "(`expected_residuals.json` / `KNOWN-RESIDUALS.md`), and verifies every "
        "violation injector fires its target rule."
    )
    lines.append("")
    lines.append(f"_Last run: {now}. Floor baseline: {rebaselined}_")
    lines.append("")

    # Per-lane summary table.
    lines.append("## Per-lane summary")
    lines.append("")
    lines.append(
        "| metric | "
        + " | ".join(r["lane"] for r in results)
        + " |"
    )
    lines.append("|" + "---|" * (len(results) + 1))

    def row(label, fn):
        return "| " + label + " | " + " | ".join(str(fn(r)) for r in results) + " |"

    lines.append(row("domains generated", lambda r: r["n_domains"]))
    lines.append(row("rows generated", lambda r: r["n_rows"]))
    lines.append(row("rules executable on clean study (non-SKIPPED)", lambda r: r["executable_rules"]))
    lines.append(
        row(
            "rules dormant before (domains absent from existing studies)",
            lambda r: len(r["dormant_ids"]),
        )
    )
    lines.append(
        row(
            "Skipped_Rules entries (no define -> with define)",
            lambda r: f"{r['skipped_entries_nodef']} -> {r['skipped_entries_def']}",
        )
    )
    lines.append(
        row(
            "define-metadata rules un-skipped by define",
            lambda r: ", ".join(r["define_unlocked"]) or "(none)",
        )
    )
    lines.append(
        row("violation injectors passing", lambda r: f"{r['inj_passed']}/{r['inj_total']}")
    )
    lines.append(
        row("clean-study residual findings (== floor)", lambda r: len(r["floor"]["matched"]))
    )
    lines.append(row("undocumented clean findings (must be 0)", lambda r: len(r["floor"]["undocumented"])))
    lines.append(row("RESULT", lambda r: "PASS" if r["ok"] else "FAIL"))
    lines.append("")

    # Per-lane detail.
    for r in results:
        lines.append(f"## Lane `{r['lane']}` — detail")
        lines.append("")
        lines.append(
            f"- **Domains generated ({r['n_domains']}, {r['n_rows']} rows):** "
            + ", ".join(f"{d}({n})" for d, n in sorted(r["counts"].items()))
        )
        lines.append(
            f"- **Newly-covered domains ({len(r['new_domains'])})** (absent from the existing "
            f"DataExchange/PhUSE/Pilot studies): " + ", ".join(r["new_domains"])
        )
        lines.append(
            f"- **Dormant rules now exercised ({len(r['dormant_ids'])})** — rules targeting "
            "the newly-covered domains that had no data before. Per domain: "
            + ", ".join(f"{d}:{n}" for d, n in sorted(r["dormant_per"].items()))
        )
        lines.append(
            f"- **define-metadata rules now running:** Skipped_Rules "
            f"{r['skipped_entries_nodef']} -> {r['skipped_entries_def']} entries with "
            f"`-dxp`; un-skipped rule(s): "
            + (", ".join(r["define_unlocked"]) or "(none — no define-gated rule in this lane's corpus)")
        )
        lines.append(
            f"- **Violation injectors:** {r['inj_passed']}/{r['inj_total']} fire their "
            "target rule with only documented collateral twins."
        )
        lines.append("")

        # Floor table.
        lines.append(f"### Clean-study residual findings ({len(r['floor']['matched'])}) — the floor")
        lines.append("")
        lines.append("| rule | domain | why it is irreducible |")
        lines.append("|------|--------|------------------------|")
        for cid in r["floor"]["matched"]:
            meta = r["expected"].get(cid, {})
            lines.append(f"| {cid} | {meta.get('domain', '?')} | {meta.get('why', '')} |")
        lines.append("")
        if r["floor"]["disappeared"]:
            lines.append(
                "> **Notable change:** floor rules no longer firing (corpus drift, "
                "not a regression): " + ", ".join(r["floor"]["disappeared"])
            )
            lines.append("")
        if r["floor"]["undocumented"]:
            lines.append(
                "> **FAILURE:** undocumented findings not in the floor: "
                + ", ".join(r["floor"]["undocumented"])
            )
            lines.append("")
        if r.get("not_loaded"):
            lines.append(
                "> **FAILURE (vacuous run):** floor rules absent from the engine report "
                "entirely — the comparison measured nothing: "
                + ", ".join(r["not_loaded"])
            )
            lines.append("")

    return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------- #
# Entry point.
# --------------------------------------------------------------------------- #
def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--lane", choices=("sdtmig", "sendig", "both"), default="both")
    ap.add_argument("--skip-regen", action="store_true",
                    help="reuse the existing clean/ + violations/ (no regen)")
    ap.add_argument("--no-coverage-doc", action="store_true",
                    help="do not write documentation/synthetic-testdata-coverage.md")
    library.add_cache_dir_argument(ap)
    paths.add_synth_root_argument(ap)
    args = ap.parse_args(argv)

    library.set_cache_dir(args.cache_dir)
    paths.set_synth_root(args.synth_root)
    # Both host-local inputs are resolved up front: an unconfigured run must fail
    # here, naming what to set, rather than part-way through a lane.
    try:
        library.resolve_cache_dir()
        paths.synth_root()
    except (library.CacheDirNotConfigured, paths.SynthRootNotConfigured) as exc:
        raise SystemExit(str(exc)) from exc

    lanes = ["sdtmig", "sendig"] if args.lane == "both" else [args.lane]
    expected_all = load_expected_residuals()

    results = [verify_lane(lane, expected_all, args.skip_regen) for lane in lanes]

    if not args.no_coverage_doc:
        os.makedirs(os.path.dirname(_COVERAGE_DOC), exist_ok=True)
        with open(_COVERAGE_DOC, "w", encoding="utf-8") as fh:
            fh.write(render_coverage(results, expected_all))
        print(f"\ncoverage report -> {_COVERAGE_DOC}")

    # Summary.
    print("\n" + "=" * 60)
    print("VERIFICATION SUMMARY")
    print("=" * 60)
    all_ok = True
    for r in results:
        all_ok &= r["ok"]
        status = "PASS" if r["ok"] else "FAIL"
        print(
            f"  [{status}] {r['lane']:7s} clean={len(r['floor']['matched'])} floor "
            f"(undocumented={len(r['floor']['undocumented'])}), "
            f"injectors {r['inj_passed']}/{r['inj_total']}"
        )
        if r["floor"]["undocumented"]:
            print(f"           UNDOCUMENTED clean findings: {r['floor']['undocumented']}")
        if r["floor"]["disappeared"]:
            print(f"           note: floor rules no longer firing: {r['floor']['disappeared']}")
        if not r["inj_ok"]:
            for ln in r["inj_lines"]:
                if "FAIL" in ln:
                    print("          " + ln.strip())
    print("=" * 60)
    print("OVERALL:", "PASS" if all_ok else "FAIL")
    return 0 if all_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
