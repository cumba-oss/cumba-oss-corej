"""Unit tests for the co-presence derivation (``copresence.py``).

The pure helpers are exercised against hand-built ``Check`` trees so a failure
points at the analysis, not at the corpus; a handful of tests then assert the
derivation still finds the real pairs in the *shipped* rule packages, which is
what stops the module quietly deriving nothing when the corpus moves.
"""

import pytest

import copresence as cp


def leaf(name, operator, **kw):
    return {"name": name, "operator": operator, **kw}


# --------------------------------------------------------------------------- #
# missing_verdict — the engine's ScalarSemantics, not a guess
# --------------------------------------------------------------------------- #
def test_missing_verdict_folds_a_missing_cell_to_the_empty_string():
    # ScalarSemantics folds a missing side of a comparison to "". That is why
    # `any: [not_exists(X), not_equal_to(X, "Y")]` is neutral under the drop:
    # the second branch is already true while the column is present-and-empty.
    assert cp.missing_verdict("empty", None) is True
    assert cp.missing_verdict("is_missing", None) is True
    assert cp.missing_verdict("non_empty", None) is False
    assert cp.missing_verdict("not_equal_to", "Y") is True
    assert cp.missing_verdict("equal_to", "Y") is False
    assert cp.missing_verdict("equal_to", "") is True


def test_missing_verdict_numeric_comparison_is_no_violation():
    # comparisonLhsAsDouble returns null for a missing cell => no violation.
    assert cp.missing_verdict("less_than", 5) is False
    assert cp.missing_verdict("date_greater_than", "2020-01-01") is False


def test_missing_verdict_returns_none_when_it_cannot_decide():
    # An unknown verdict is *not* an assumed one: the caller then treats the
    # leaf as unknown-but-identical before and after the drop.
    assert cp.missing_verdict("is_complete_date", None) is None
    assert cp.missing_verdict("valid_external_dictionary_value", "MEDDRA") is None
    assert cp.missing_verdict("longer_than", "not-a-number") is None


# --------------------------------------------------------------------------- #
# can_newly_fire
# --------------------------------------------------------------------------- #
_PAIR = {"all": [leaf("--DTC", "exists"), leaf("--DY", "not_exists")]}


def test_dropping_one_half_of_a_co_presence_pair_fires_the_rule():
    assert cp.can_newly_fire(
        _PAIR, "BS", frozenset({"BSDTC", "BSDY"}), frozenset({"BSDTC", "BSDY"}), "BSDY")


def test_dropping_the_pair_together_fires_nothing():
    # "populate it, or drop the pair" — with the trigger already gone the rule
    # cannot fire, so the partner is free to go too.
    assert not cp.can_newly_fire(
        _PAIR, "BS", frozenset({"BSDY"}), frozenset({"BSDY"}), "BSDY")


def test_dropping_the_trigger_itself_is_harmless():
    assert not cp.can_newly_fire(
        _PAIR, "BS", frozenset({"BSDTC", "BSDY"}), frozenset({"BSDTC", "BSDY"}), "BSDTC")


def test_absent_or_empty_idiom_is_neutral():
    # `any: [not_exists(X), empty(X)]` is the corpus's way of saying "X carries
    # no value". An absent column IS an all-missing column, so the drop cannot
    # change this rule's verdict and the column must stay droppable.
    check = {"all": [{"any": [leaf("--DUR", "not_exists"), leaf("--DUR", "empty")]},
                     leaf("--TERM", "non_empty")]}
    assert not cp.can_newly_fire(
        check, "AE", frozenset({"AEDUR", "AETERM"}), frozenset({"AEDUR"}), "AEDUR")


def test_absent_or_not_equal_idiom_is_neutral():
    # The same idiom written with a value comparison instead of `empty`
    # (CDISC-CG0348's shape). Mistaking this for a hazard kept 50 columns that
    # measurably did not need keeping.
    check = {"any": [
        {"all": [leaf("--STAT", "empty"),
                 leaf("--DRVFL", "not_equal_to", value="Y", value_is_literal=True)]},
        {"all": [leaf("--STAT", "empty"), leaf("--DRVFL", "not_exists")]},
    ]}
    assert not cp.can_newly_fire(
        check, "LB", frozenset({"LBSTAT", "LBDRVFL"}), frozenset({"LBSTAT", "LBDRVFL"}),
        "LBDRVFL")


def test_unknown_leaves_are_the_same_before_and_after():
    # A data leaf the analysis cannot decide must be quantified over *once*, not
    # independently per state — otherwise every rule looks like a hazard.
    check = {"all": [leaf("--ORRES", "is_complete_date"), leaf("--DY", "not_exists")]}
    assert cp.can_newly_fire(
        check, "LB", frozenset({"LBORRES", "LBDY"}), frozenset({"LBDY"}), "LBDY")
    # ... and with the only absence conjunct removed there is nothing to flip.
    check_no_absence = {"all": [leaf("--ORRES", "is_complete_date")]}
    assert not cp.can_newly_fire(
        check_no_absence, "LB", frozenset({"LBORRES", "LBDY"}), frozenset({"LBDY"}), "LBDY")


def test_too_many_unknown_leaves_is_resolved_conservatively():
    check = {"all": [leaf(f"--V{i:02d}", "is_complete_date")
                     for i in range(cp._MAX_FREE_LEAVES + 1)]}
    assert cp.can_newly_fire(check, "LB", frozenset({"LBDY"}), frozenset({"LBDY"}), "LBDY")


# --------------------------------------------------------------------------- #
# forced_keep
# --------------------------------------------------------------------------- #
def _rule(core_id, check):
    absent = frozenset(
        lf["name"] for lf in cp.iter_leaves(check) if lf["operator"] in cp.ABSENT_OPS
    )
    return cp.HazardRule(core_id, check, absent)


def test_forced_keep_drops_both_halves_when_both_are_droppable():
    rules = [_rule("R-PAIR", _PAIR)]
    kept = cp.forced_keep("BS", {"BSDTC", "BSDY"}, {"BSDTC", "BSDY"},
                          ["BSDTC", "BSDY"], rules)
    assert kept == {}


def test_forced_keep_keeps_the_orphan_when_the_trigger_cannot_go():
    # BSDTC is Expected, so it is not a candidate and stays; BSDY must stay too.
    rules = [_rule("R-PAIR", _PAIR)]
    kept = cp.forced_keep("BS", {"BSDTC", "BSDY"}, {"BSDTC", "BSDY"}, ["BSDY"], rules)
    assert kept == {"BSDY": "R-PAIR"}


def test_forced_keep_runs_to_a_fixpoint():
    # X survives => Y must be kept => Y is now present => Z must be kept too.
    # A single pass in the wrong order drops Z.
    r1 = _rule("R1", {"all": [leaf("X", "exists"), leaf("Y", "not_exists")]})
    r2 = _rule("R2", {"all": [leaf("Y", "exists"), leaf("Z", "not_exists")]})
    kept = cp.forced_keep("ZZ", {"X", "Y", "Z"}, {"X", "Y", "Z"}, ["Z", "Y"], [r1, r2])
    assert kept == {"Y": "R1", "Z": "R2"}


# --------------------------------------------------------------------------- #
# Cross-dataset presence counts
# --------------------------------------------------------------------------- #
def test_resolve_count_coupling_zero_retainers_is_safe():
    # Nothing keeps the column, so the rule's `exists` conjunct fails everywhere.
    coupling = cp.CountCoupling("--LNKID", 2, ("CDISC-CG0024",))
    assert cp.resolve_count_coupling(coupling, {"AG": False, "TR": False}) == []


def test_resolve_count_coupling_undrops_up_to_the_minimum():
    coupling = cp.CountCoupling("--LNKID", 2, ("CDISC-CG0024",))
    assert cp.resolve_count_coupling(coupling, {"TR": True, "GF": False, "AG": False}) \
        == ["AG"]


def test_resolve_count_coupling_leaves_a_satisfied_set_alone():
    coupling = cp.CountCoupling("--LNKID", 2, ("CDISC-CG0024",))
    assert cp.resolve_count_coupling(coupling, {"TR": True, "AG": True, "GF": False}) == []


# --------------------------------------------------------------------------- #
# Column-metadata surface rules
# --------------------------------------------------------------------------- #
def test_metadata_leaf_verdict_reads_the_right_subject():
    lbl = leaf("variable_label", "longer_than", value=5)
    assert cp.metadata_leaf_verdict(lbl, "AB", "Six ch") is True
    assert cp.metadata_leaf_verdict(lbl, "A very long name", "Tiny") is False
    nm = leaf("variable_name", "longer_than", value=8)
    assert cp.metadata_leaf_verdict(nm, "NINECHARS", "x") is True


def test_metadata_leaf_verdict_refuses_operation_backed_values():
    # `$x` is an Operation result, not a literal. Comparing against the *string*
    # "$model_order" would answer confidently and wrongly — and `matches_regex`
    # is the sharp case, because "$..." is a valid regex that simply never
    # matches, so the leaf would silently decide False instead of "unknown".
    for operator in ("equal_to", "not_equal_to", "matches_regex", "not_matches_regex"):
        lf = leaf("variable_name", operator, value="$model_order")
        assert cp.metadata_leaf_verdict(lf, "ABC", "Label") is None, operator
    # The shape the corpus actually ships (FDA-SD0058, CDISC-SEND-0265, …).
    lf = leaf("variable_name", "is_not_contained_by", value="$allowed_variables")
    assert cp.metadata_leaf_verdict(lf, "ABC", "Label") is None


def test_metadata_hazard_respects_domain_scope():
    rule = cp.MetadataRule(
        "R", {"all": [leaf("variable_label", "longer_than", value=3)]}, frozenset({"IS"}))
    assert cp.metadata_hazard(rule, "IS", "ISX", "Long label")
    assert not cp.metadata_hazard(rule, "LB", "LBX", "Long label")


# --------------------------------------------------------------------------- #
# Against the shipped corpus — the derivation must not silently find nothing
# --------------------------------------------------------------------------- #
def test_shipped_rule_ids_raises_rather_than_passing_everything():
    with pytest.raises(RuntimeError):
        cp.shipped_rule_ids("nosuch", "0-0")


def test_hazard_rules_find_the_dtc_dy_pair_in_the_shipped_sdtmig_corpus():
    rules = {r.core_id: r for r in cp.hazard_rules("sdtmig", "3-4")}
    assert len(rules) > 100
    # The --DY / --DTC family: derived, not enumerated.
    for core_id in ("CORE-000321", "FDA-SD1083", "PMDA-SD1083"):
        assert "--DY" in rules[core_id].absent_names
    # ... and the --TPTREF / --RFTDTC family.
    for core_id in ("CDISC-CG0090", "CORE-000165", "FDA-SD1245", "PMDA-SD1245"):
        assert "--TPTREF" in rules[core_id].absent_names


def test_count_coupled_finds_the_link_id_family():
    found = {c.name: c for c in cp.count_coupled("sdtmig", "3-4")}
    assert set(found) == {"--LNKID", "--LNKGRP"}
    assert found["--LNKID"].minimum == 2
    assert "CDISC-CG0024" in found["--LNKID"].core_ids
    assert "CORE-000571" in found["--LNKID"].core_ids


def test_metadata_surface_rules_include_the_title_case_check():
    rules = {r.core_id for r in cp.metadata_surface_rules("sdtmig", "3-4")}
    assert {"CORE-000594", "CDISC-CG0359"} <= rules
    # Operation-backed metadata rules are not decidable here and must be absent.
    assert "FDA-SD0058" not in rules
    assert "FDA-SD1078" not in rules
