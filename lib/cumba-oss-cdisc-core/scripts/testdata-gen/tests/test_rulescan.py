"""Unit tests for rulescan — the rule-corpus scanner."""

import pytest

import rulescan


@pytest.fixture(scope="module")
def sc():
    return rulescan.scan()


def test_scans_full_corpus(sc):
    # The scan covers every org under rules-src/checks except DRAFT:
    # CDISC + CORE + FDA + PMDA == 3707 rules (measured 2026-08-08).
    # The >700 floor predates the multi-org widening and is retained on purpose:
    # a regression to the CORE org alone yields 495, which it catches.
    assert sc.n_rules > 700
    assert sc.n_rules > 3000, "scan must cover all orgs, not just CORE (495)"


def test_draft_org_is_excluded(sc):
    # DRAFT/ holds unshipped drafts (20 files); including them would overstate
    # the corpus at 3727. No DRAFT rule may reach the scan.
    assert not [cid for cid in sc.rule_standards if cid.startswith("DRAFT-")]
    assert not [cid for cid in sc.rule_domains if cid.startswith("DRAFT-")]


def test_domain_targets_known_counts(sc):
    # Spot-check a few well-known Domains.Include totals (across all standards).
    # Re-pinned 2026-08-08 when the scan was widened from the CORE org alone to
    # all four shipped orgs. Old CORE-only-era values: CO 16, PC 15, PP 14.
    assert len(sc.domain_targets["CO"]) == 41
    assert len(sc.domain_targets["PC"]) == 31
    assert len(sc.domain_targets["PP"]) == 26
    # ALL is a wildcard, recorded but not a concrete domain.
    assert "ALL" in sc.domain_targets
    assert "ALL" not in sc.concrete_domains()


def test_concrete_domains_exclude_wildcards_and_markers(sc):
    concrete = sc.concrete_domains()
    for token in ("ALL", "SUPP--", "AP--", "NONE", "POOLID", "IDVAR"):
        assert token not in concrete
    assert "PC" in concrete and "BW" in concrete


def test_referenced_vars(sc):
    rv = sc.referenced_vars
    # concrete names, -- patterns, and SENDIG-only vars are captured
    for name in ("PCSTRESN", "EXDOSE", "--STDTC", "AGETXT"):
        assert name in rv
    # operation ids ($-prefixed) are excluded
    assert not any(v.startswith("$") for v in rv)


def test_clean_var_strips_the_output_variable_exclusion_marker():
    # Fix #354 / R-9.12: an ``!X`` Output_Variables entry only withholds X from the
    # FINDING; the rule still reads X, so the generator must still provide the column.
    assert rulescan._clean_var("!AEDECOD") == "AEDECOD"
    assert rulescan._clean_var("!--DTC") == "--DTC"
    assert rulescan._clean_var("!DM.ARM") == "ARM"
    assert rulescan._clean_var("!$op") is None
    assert rulescan._clean_var("!") is None
    # and the include forms are untouched
    assert rulescan._clean_var("--DTC") == "--DTC"
    assert rulescan._clean_var("AEDECOD") == "AEDECOD"


def test_standard_filtering(sc):
    sdtmig = sc.targets_for_standard("SDTMIG")
    sendig = sc.targets_for_standard("SENDIG")
    # SEND-only domains appear under SENDIG, and BW carries rules there.
    assert "BW" in sendig and len(sendig["BW"]) >= 1
    # CO is split across standards; SDTMIG slice is a strict subset of the total.
    assert 0 < len(sdtmig["CO"]) <= len(sc.domain_targets["CO"])


def test_presence_negative_domains_targeted(sc):
    # Re-pinned 2026-08-08 with the widening to all four shipped orgs.
    # Old CORE-only-era values: TP/TT/SJ each == 1 (the single CORE presence
    # check). The CDISC and FDA orgs carry the rest.
    assert len(sc.domain_targets["TP"]) == 17
    assert len(sc.domain_targets["TT"]) == 12
    assert len(sc.domain_targets["SJ"]) == 15
