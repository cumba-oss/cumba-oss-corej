"""Unit tests for the Phase-6 verification harness (``verify.py``).

These are deliberately **engine-free** — they exercise only the pure
report-parsing / floor-comparison / coverage-rendering helpers (the Java CLI is
exercised by running ``verify.py`` itself). The committed floor baseline
``expected_residuals.json`` is also sanity-checked here so corpus drift that
changes the floor is caught by the test suite.
"""

import json
import os

import verify


def test_load_expected_residuals_has_both_lanes():
    data = verify.load_expected_residuals()
    assert set(data) >= {"sdtmig", "sendig", "_meta"}


def test_expected_for_lane_strips_meta_keys():
    data = {
        "sdtmig": {"CORE-1": {"domain": "PP"}, "_note": "x"},
    }
    lane = verify.expected_for_lane(data, "sdtmig")
    assert lane == {"CORE-1": {"domain": "PP"}}


def test_expected_residuals_baseline_counts():
    """The committed floor matches the documented re-baselined counts.

    9 / 6 were the 2026-06-29 counts. 2026-08-06 they became 17 / 7: the owner
    accepted 8 sdtmig entries (CDISC-CG0359, PMDA-SD2244, PMDA-SD0062 and the
    five PMDA family twins) and 1 sendig entry (FDA-SD0062) onto the floor -
    see ``_meta.added_2026_08_06``. This is a ratchet, not a target: raising it
    again needs the same explicit ruling.
    """
    data = verify.load_expected_residuals()
    assert len(verify.expected_for_lane(data, "sdtmig")) == 17
    assert len(verify.expected_for_lane(data, "sendig")) == 7
    # every entry carries a domain + why rationale
    for lane in ("sdtmig", "sendig"):
        for cid, meta in verify.expected_for_lane(data, lane).items():
            assert cid and meta.get("domain") and meta.get("why")


def test_expected_residuals_file_is_valid_json():
    path = os.path.join(os.path.dirname(verify.__file__), "expected_residuals.json")
    with open(path, encoding="utf-8") as fh:
        json.load(fh)  # raises on malformed


def test_summarize_report_extracts_metrics():
    report = {
        "Issue_Summary": [
            {"core_id": "CORE-1"}, {"core_id": "CORE-1"}, {"core_id": "FDA-2"},
            {"message": "no core_id here"},
        ],
        "Rules_Report": [{"core_id": "CORE-1"}, {"core_id": "CORE-9"}],
        "Skipped_Rules": [
            {"core_id": "CORE-5", "dataset": "PE"},
            {"core_id": "CORE-5", "dataset": "VS"},
            {"core_id": "CORE-7", "dataset": "LB"},
        ],
    }
    s = verify.summarize_report(report)
    assert s["issue_ids"] == {"CORE-1", "FDA-2"}
    assert s["executable_ids"] == {"CORE-1", "CORE-9"}
    assert s["skipped_entries"] == 3            # per (rule, dataset)
    assert s["skipped_ids"] == {"CORE-5", "CORE-7"}


def test_summarize_report_empty():
    s = verify.summarize_report({})
    assert s["issue_ids"] == set()
    assert s["skipped_entries"] == 0


def test_compare_floor_clean_at_floor():
    expected = {"CORE-1": {}, "CORE-2": {}}
    cmp = verify.compare_floor({"CORE-1", "CORE-2"}, expected)
    assert cmp["undocumented"] == []
    assert cmp["disappeared"] == []
    assert cmp["matched"] == ["CORE-1", "CORE-2"]


def test_compare_floor_undocumented_is_failure_set():
    expected = {"CORE-1": {}}
    cmp = verify.compare_floor({"CORE-1", "CORE-NEW"}, expected)
    assert cmp["undocumented"] == ["CORE-NEW"]   # a NEW finding -> failure
    assert cmp["disappeared"] == []


def test_compare_floor_disappeared_is_notable_only():
    expected = {"CORE-1": {}, "CORE-2": {}}
    cmp = verify.compare_floor({"CORE-1"}, expected)
    assert cmp["undocumented"] == []             # not a failure
    assert cmp["disappeared"] == ["CORE-2"]


def test_dormant_rules_unlocked_separates_new_domains():
    # PC is absent from the existing studies; DM/LB/AE are present.
    gen = ["DM", "PC", "LB", "AE", "PP"]
    new_domains, rule_ids, per = verify.dormant_rules_unlocked("sdtmig", gen)
    assert "PC" in new_domains and "PP" in new_domains
    assert "DM" not in new_domains and "LB" not in new_domains and "AE" not in new_domains
    # the newly-covered domains carry real rules
    assert rule_ids
    assert per.get("PC", 0) > 0
    # rule_ids is exactly the union over per-domain counts' domains
    assert set(per) <= set(new_domains)


def test_dormant_rules_unlocked_sendig_only_new():
    new_domains, rule_ids, _ = verify.dormant_rules_unlocked("sendig", ["DM", "BW", "TF"])
    assert "BW" in new_domains and "TF" in new_domains
    assert "DM" not in new_domains
    assert rule_ids


def test_render_coverage_smoke():
    results = [
        {
            "lane": "sdtmig", "ok": True, "n_domains": 39, "n_rows": 2112,
            "executable_rules": 879, "skipped_entries_def": 41,
            "skipped_entries_nodef": 80, "define_unlocked": ["CORE-001081"],
            "counts": {"PC": 60, "DM": 20},
            "new_domains": ["PC"], "dormant_ids": ["CORE-1", "CORE-2"],
            "dormant_per": {"PC": 2},
            "inj_passed": 14, "inj_total": 14,
            "floor": {
                "matched": ["CORE-000080"], "undocumented": [], "disappeared": [],
            },
            "expected": {"CORE-000080": {"domain": "PP", "why": "companions absent"}},
        }
    ]
    md = verify.render_coverage(results, {"_meta": {"rebaselined": "2026-06-29"}})
    assert "# Synthetic SDTM/SEND test-data coverage" in md
    assert "| domains generated | 39 |" in md
    assert "CORE-000080" in md
    assert "RESULT" in md and "PASS" in md


def test_render_coverage_flags_undocumented():
    results = [
        {
            "lane": "sendig", "ok": False, "n_domains": 16, "n_rows": 691,
            "executable_rules": 615, "skipped_entries_def": 25,
            "skipped_entries_nodef": 25, "define_unlocked": [],
            "counts": {"BW": 60}, "new_domains": ["BW"], "dormant_ids": [],
            "dormant_per": {}, "inj_passed": 11, "inj_total": 12,
            "floor": {
                "matched": ["CORE-000328"], "undocumented": ["CORE-NEW"],
                "disappeared": ["CORE-OLD"],
            },
            "expected": {"CORE-000328": {"domain": "SE", "why": "no study-day cols"}},
        }
    ]
    md = verify.render_coverage(results, {})
    assert "FAILURE" in md and "CORE-NEW" in md
    assert "Notable change" in md and "CORE-OLD" in md
