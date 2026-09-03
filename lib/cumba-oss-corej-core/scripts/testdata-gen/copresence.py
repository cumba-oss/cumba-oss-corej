"""Which unpopulated Permissible columns are safe to drop?

``generate.drop_unpopulated_permissible`` removes a ``Perm`` column that no
record populates, so ``FDA-SD1078`` / ``PMDA-SD1078`` ("a Permissible variable
is present but empty on every record") stop firing by construction. Dropping a
column is **not** free, though: a rule that keys on a variable's *absence*
rather than on its emptiness sees a different dataset afterwards, and a study
that was conformant can stop being conformant.

The corpus is full of such rules. The canonical shape is a **co-presence
pair** — *"if ``--DTC`` is present then ``--DY`` must be present too"*:

.. code-block:: yaml

    Check:
      all:
      - {name: "--DTC", operator: "exists"}
      - {name: "--DY",  operator: "not_exists"}

Drop an unpopulated ``--DY`` while its populated-or-not ``--DTC`` partner stays,
and the rule fires. Measured on 2026-08-07, dropping every unpopulated ``Perm``
column made nine such rules fire that had been silent.

**This module derives the pairs from the rule corpus rather than from the rules
that happened to fire.** Those nine are one run's symptom set; the next
unpopulated ``Perm`` variable in an unmeasured pair would break identically.

Derivation
----------

1. **Which rules count.** The id set comes from the *shipped* packages the
   engine actually loads for the lane (``rules/rules-*-<std>-<ver>.json``,
   the same selection ``verify_violations.families_for`` makes). The shipped
   packages store ``Check`` in expression form, so the structured tree is read
   from the authoring source ``rules-src/checks/<org>/<id>.yaml``. The two
   agree: for ``sdtmig-3-4`` the id sets are identical (1841 = 1841); for
   ``sendig-3-1-1`` ``rules-src`` carries four ids the packages no longer ship
   (``CDISC-SEND-0202/0203/0204/0205``), and restricting to the shipped ids
   drops them.

2. **What makes a drop unsafe.** Per the project's settled semantics, *an
   absent column behaves exactly as a present all-missing column* for every
   data-level operator. So removing an already-all-empty column can only change
   a rule's verdict through the operators that distinguish presence from
   emptiness: ``exists`` / ``not_exists`` / ``var_exists`` / ``var_not_exists``
   / ``variable_exists``. Everything else must evaluate identically before and
   after.

   :func:`can_newly_fire` makes that precise. It evaluates the ``Check`` tree
   twice — once with the column present, once with it removed — over the *same*
   assignment of the leaves whose truth it cannot know, and asks whether any
   assignment exists that is false before and true after. Leaves it *can* know
   are pinned: existence leaves from the column set, and data leaves on an
   all-missing column from :func:`missing_verdict` (the engine folds a missing
   cell to ``""``, so e.g. ``not_equal_to(X, "Y")`` is **true** on a missing
   cell — which is exactly why the common ``any: [not_exists(X), empty(X)]``
   and ``any: [not_exists(X), not_equal_to(X, lit)]`` idioms are *neutral*
   under the drop and must not be mistaken for hazards).

3. **Populate it, or drop the pair.** :func:`forced_keep` runs the analysis to
   a fixpoint over the whole candidate set, so a hazard whose *trigger* is
   itself a droppable candidate resolves by dropping both halves — the pair
   goes together and no rule fires. Only when the trigger cannot be dropped
   (it is ``Req``/``Exp``, a key, or populated) is the candidate kept.

4. **Cross-dataset presence counts.** A second family couples datasets rather
   than columns: ``CDISC-CG0024`` / ``CORE-000571`` (``--LNKID``) and
   ``CDISC-CG0022`` / ``CORE-000358`` (``--LNKGRP``) fire when a variable is
   present in *this* dataset but in fewer than two datasets overall. Dropping
   it everywhere is safe (the ``exists`` conjunct then fails everywhere);
   dropping it in all but one dataset is not. :func:`count_coupled` derives the
   names and their minimum from the rules' ``variable_count`` Operations, and
   :func:`resolve_count_coupling` decides how many datasets must keep it.

Nothing here is specific to a domain, a variable or a rule id: change the
corpus and the answers change with it.
"""

from __future__ import annotations

import functools
import glob
import itertools
import json
import os
import re
import sys
from dataclasses import dataclass

import yaml

try:  # libyaml is ~5x faster and the corpus is ~3700 files
    from yaml import CSafeLoader as _Loader
except ImportError:  # pragma: no cover - depends on the local libyaml build
    from yaml import SafeLoader as _Loader  # type: ignore[assignment]

# Operators that assert a column is *present* / *absent* in the dataset. These
# are the only ones whose verdict differs between "present but all missing" and
# "not there at all"; every other operator sees an absent column as all-missing.
PRESENT_OPS = frozenset({"exists", "var_exists", "variable_exists"})
ABSENT_OPS = frozenset({"not_exists", "var_not_exists"})

# Beyond this many unknown leaves the exhaustive search is abandoned and the
# column is kept. Conservative in the safe direction (a kept column costs one
# SD1078 finding; a wrongly dropped one costs a conformance regression).
_MAX_FREE_LEAVES = 18

# Text prefilter for rules that test a variable's absence. The legacy corpus
# spells absence with the ``*_not_exists`` operators (``"not_exists"`` is a
# substring of ``"var_not_exists"`` / ``"ds_not_exists"``, so one marker covers
# all three); the expression form spells it ``not var_exists(...)`` /
# ``not ds_exists(...)`` / ``not exists(...)``. A prefilter miss is silent
# vacuity (the rule drops out of the hazard set), so over-inclusion is the
# safe direction.
_ABSENCE_MARKERS = ("not_exists", "not exists(", "not var_exists(",
                    "not ds_exists(")


_FORMB_HEAD = re.compile(r"\s*([A-Za-z_][A-Za-z0-9_]*)\s*\((.*)\)\s*$", re.S)
_FORMB_FIRST_ARG = re.compile(
    r"^\s*(?:\"([^\"]*)\"|`([^`]*)`|([A-Za-z0-9_.$-]+))\s*(?:[,)]|$)")
_FORMB_GROUP = re.compile(r"group=\[([^\]]*)\]")


def _formb_operator(op):
    """Field-form `operator:` or the Form-B expression's leading function name
    (PLAN-retire-corpus-transforms phase 8; T2 review H4)."""
    operator = op.get("operator")
    if operator:
        return operator
    expression = op.get("expression")
    if isinstance(expression, str):
        match = _FORMB_HEAD.match(expression)
        if match:
            return match.group(1)
    return None


def _formb_name(op):
    """Field-form `name:` or the Form-B expression's first positional argument
    (a bareword, quoted or backticked column; None when the call starts with a
    kwarg)."""
    name = op.get("name")
    if isinstance(name, str):
        return name
    expression = op.get("expression")
    if isinstance(expression, str):
        head = _FORMB_HEAD.match(expression)
        if head:
            body = head.group(2)
            if not re.match(r"\s*[A-Za-z_][A-Za-z0-9_]*\s*=", body):
                arg = _FORMB_FIRST_ARG.match(body)
                if arg:
                    return next(g for g in arg.groups() if g is not None)
    return None


def _formb_group(op):
    """Field-form `group:` list or the Form-B `group=[...]` members."""
    group = op.get("group")
    if isinstance(group, list):
        return [g for g in group if isinstance(g, str)]
    expression = op.get("expression")
    if isinstance(expression, str):
        match = _FORMB_GROUP.search(expression)
        if match:
            return [t.strip().strip('\"').strip("`")
                    for t in match.group(1).split(",") if t.strip()]
    return []


def _here() -> str:
    return os.path.dirname(os.path.abspath(__file__))


def _rules_dir() -> str:
    # scripts/testdata-gen/ -> lib/corej-cdisc-rules/rules
    return os.path.normpath(os.path.join(_here(), "..", "..", "..",
                                         "corej-cdisc-rules", "rules"))


def _rules_src_dir() -> str:
    return os.path.normpath(os.path.join(_here(), "..", "..", "..",
                                         "corej-cdisc-rules", "rules-src", "checks"))


# Dual-form Check loading (Fix #257): the shared expression reader
# (corej-cdisc-rules/scripts/lib/check_expr.py — the module that owns the
# corpus) lowers an ``{expression: "..."}`` Check to the legacy dict this
# module walks; a legacy Check passes through unchanged. The cross-tree path
# mirrors ``_rules_dir()``'s existing data dependency on that module.
_SHARED_LIB = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "..",
    "corej-cdisc-rules", "scripts", "lib"))
if _SHARED_LIB not in sys.path:
    sys.path.insert(0, _SHARED_LIB)
from check_expr import dual_form_check  # noqa: E402  (needs the sys.path bootstrap)


# --------------------------------------------------------------------------- #
# Missing-value semantics (mirrors net.cumba.corej.core.exec.ScalarSemantics)
# --------------------------------------------------------------------------- #
def _as_text(value) -> str:
    if isinstance(value, str):
        return value
    return "" if value is None else str(value)


def missing_verdict(operator: str, value):  # noqa: C901 - a lookup table
    """Verdict of ``operator`` on a cell that is missing, or ``None`` if unknown.

    ``ScalarSemantics`` treats ``null`` and ``""`` alike and folds a missing side
    of a comparison to ``""``; a missing left-hand side of a numeric or date
    comparison yields *no violation*. Returning ``None`` means "cannot decide" —
    the caller then treats the leaf as unknown-but-identical before and after the
    drop, which is sound because an absent column is an all-missing column.
    """
    if operator in ("empty", "is_missing"):
        return True
    if operator == "non_empty":
        return False
    if operator in ("equal_to", "equal_to_case_insensitive"):
        return _as_text(value) == "" if not isinstance(value, (list, dict)) else None
    if operator in ("not_equal_to", "not_equal_to_case_insensitive"):
        return _as_text(value) != "" if not isinstance(value, (list, dict)) else None
    if operator in ("is_contained_by", "is_contained_by_case_insensitive"):
        return isinstance(value, list) and "" in [_as_text(v) for v in value]
    if operator in ("is_not_contained_by", "is_not_contained_by_case_insensitive"):
        return not (isinstance(value, list) and "" in [_as_text(v) for v in value])
    if operator in ("matches_regex", "not_matches_regex"):
        if not isinstance(value, str):
            return None
        try:
            hit = re.search(value, "") is not None
        except re.error:
            return None
        return hit if operator == "matches_regex" else not hit
    if operator in ("contains", "does_not_contain", "does_not_contain_case_insensitive"):
        if not isinstance(value, str):
            return None
        return (value == "") if operator == "contains" else (value != "")
    if operator in ("starts_with", "ends_with"):
        return _as_text(value) == "" if not isinstance(value, (list, dict)) else None
    if operator in ("longer_than", "longer_than_or_equal_to",
                    "shorter_than", "shorter_than_or_equal_to"):
        try:
            bound = float(value)  # type: ignore[arg-type]
        except (TypeError, ValueError):
            return None
        return {
            "longer_than": 0 > bound,
            "longer_than_or_equal_to": 0 >= bound,
            "shorter_than": 0 < bound,
            "shorter_than_or_equal_to": 0 <= bound,
        }[operator]
    # Numeric / date comparisons: a missing LHS coerces to null => no violation.
    if operator in ("greater_than", "greater_than_or_equal_to", "less_than",
                    "less_than_or_equal_to", "date_greater_than", "date_less_than",
                    "date_greater_than_or_equal_to", "date_less_than_or_equal_to",
                    "date_equal_to", "date_not_equal_to"):
        return False
    return None


# --------------------------------------------------------------------------- #
# Check-tree walking
# --------------------------------------------------------------------------- #
def iter_leaves(node):
    """Yield every leaf of a ``Check`` tree (``all`` / ``any`` are the only nodes)."""
    if not isinstance(node, dict):
        return
    if "all" in node:
        for child in node["all"] or ():
            yield from iter_leaves(child)
    elif "any" in node:
        for child in node["any"] or ():
            yield from iter_leaves(child)
    else:
        yield node


def resolve_name(name, domain: str):
    """Resolve a rule's variable name against ``domain``; ``None`` if not a column.

    ``--X`` is the domain-prefix wildcard. ``$id`` is an Operation result and
    ``**`` / ``name_pattern`` forms name no single column, so both are unknown.
    """
    if not isinstance(name, str) or not name:
        return None
    if name.startswith("--"):
        return domain[:2] + name[2:]
    if name.startswith("$") or name.startswith("*"):
        return None
    return name


def _leaf_verdict(leaf: dict, domain: str, present: frozenset, all_missing: frozenset):
    """``True``/``False``, or ``("free", key)`` / ``("not", key)`` for an unknown."""
    operator = leaf.get("operator")
    name = resolve_name(leaf.get("name"), domain)
    negated = bool(leaf.get("negative"))

    def finish(verdict):
        if isinstance(verdict, bool):
            return (not verdict) if negated else verdict
        return ("not", verdict[1]) if negated else verdict

    if name is None:
        return finish(("free", ("leaf", id(leaf))))
    if operator in PRESENT_OPS:
        return finish(name in present)
    if operator in ABSENT_OPS:
        return finish(name not in present)
    # A leaf whose comparison target is another column / a sub-string window is
    # not decidable from the column set alone.
    decidable = not any(leaf.get(k) for k in
                        ("value_is_reference", "within", "prefix", "suffix", "regex"))
    if decidable and (name not in present or name in all_missing):
        verdict = missing_verdict(operator, leaf.get("value"))
        if verdict is not None:
            return finish(verdict)
    key = (name, operator, json.dumps(leaf.get("value"), sort_keys=True, default=str))
    return finish(("free", key))


def _evaluate(node: dict, domain: str, present: frozenset, all_missing: frozenset,
              assignment: dict) -> bool:
    if "all" in node:
        return all(_evaluate(c, domain, present, all_missing, assignment)
                   for c in node["all"] or ())
    if "any" in node:
        return any(_evaluate(c, domain, present, all_missing, assignment)
                   for c in node["any"] or ())
    verdict = _leaf_verdict(node, domain, present, all_missing)
    if isinstance(verdict, bool):
        return verdict
    kind, key = verdict
    return assignment[key] if kind == "free" else (not assignment[key])


def _free_keys(check: dict, domain: str, present: frozenset, all_missing: frozenset):
    keys = set()
    for leaf in iter_leaves(check):
        verdict = _leaf_verdict(leaf, domain, present, all_missing)
        if not isinstance(verdict, bool):
            keys.add(verdict[1])
    return keys


def can_newly_fire(check: dict, domain: str, present: frozenset,
                   all_missing: frozenset, dropped: str) -> bool:
    """Can removing ``dropped`` from ``present`` flip ``check`` from false to true?

    ``present`` is the column set **with** ``dropped`` still in it; ``all_missing``
    names the columns no record populates. Leaves whose value cannot be derived
    are quantified over exhaustively, and their value is the *same* in both
    states — an absent column is an all-missing column, so nothing but the
    existence operators may differ.
    """
    after = present - {dropped}
    keys = sorted(_free_keys(check, domain, present, all_missing)
                  | _free_keys(check, domain, after, all_missing), key=str)
    if len(keys) > _MAX_FREE_LEAVES:
        return True
    for bits in itertools.product((False, True), repeat=len(keys)):
        assignment = dict(zip(keys, bits))
        if (_evaluate(check, domain, after, all_missing, assignment)
                and not _evaluate(check, domain, present, all_missing, assignment)):
            return True
    return False


# --------------------------------------------------------------------------- #
# Corpus loading
# --------------------------------------------------------------------------- #
@dataclass(frozen=True)
class HazardRule:
    """A shipped rule whose verdict can change when a column is dropped."""

    core_id: str
    check: dict
    absent_names: frozenset  # the names it tests for absence (``--`` kept verbatim)


def shipped_rule_ids(standard: str, version: str, rules_dir: str | None = None) -> set[str]:
    """Ids of every rule the engine loads for ``(standard, version)``.

    Mirrors the package selection in ``verify_violations.families_for``: every
    ``rules-<family>-<standard>-<version>.json`` in the shipped rules directory.
    """
    rules_dir = rules_dir or _rules_dir()
    suffix = f"-{standard}-{version}.json"
    ids: set[str] = set()
    for path in sorted(glob.glob(os.path.join(rules_dir, "*.json"))):
        if not path.endswith(suffix):
            continue
        with open(path, encoding="utf-8") as fh:
            ids |= set(json.load(fh).get("rules", {}))
    if not ids:
        raise RuntimeError(
            f"no shipped rule package matches *{suffix} under {rules_dir} — "
            "the co-presence derivation would silently pass everything"
        )
    return ids


@functools.lru_cache(maxsize=4)
def _authored_rules(src_dir: str) -> dict[str, dict]:
    """``{coreId: rule}`` for every authored rule that tests a variable's absence.

    Only files that mention an absence operator are parsed — the corpus is ~3700
    YAML files and roughly one in ten is relevant.
    """
    out: dict[str, dict] = {}
    for path in sorted(glob.glob(os.path.join(src_dir, "*", "*.yaml"))):
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        if not any(marker in text for marker in _ABSENCE_MARKERS):
            continue
        rule = yaml.load(text, Loader=_Loader)
        if "Check" in rule:
            rule["Check"] = dual_form_check(rule["Check"])
        core_id = (rule.get("Core") or {}).get("Id")
        if core_id:
            out[core_id] = rule
    return out


def hazard_rules(standard: str, version: str, rules_dir: str | None = None,
                 src_dir: str | None = None) -> list[HazardRule]:
    """Every shipped rule for the lane that tests some variable's absence."""
    ids = shipped_rule_ids(standard, version, rules_dir)
    authored = _authored_rules(src_dir or _rules_src_dir())
    out: list[HazardRule] = []
    for core_id in sorted(ids & set(authored)):
        check = authored[core_id].get("Check")
        if not isinstance(check, dict) or not ({"all", "any"} & set(check)):
            continue
        absent = {
            leaf.get("name") for leaf in iter_leaves(check)
            if isinstance(leaf.get("name"), str)
            and (leaf.get("operator") in ABSENT_OPS
                 or (leaf.get("operator") in PRESENT_OPS and leaf.get("negative")))
        }
        if absent:
            out.append(HazardRule(core_id, check, frozenset(absent)))
    return out


# --------------------------------------------------------------------------- #
# The per-domain decision
# --------------------------------------------------------------------------- #
def forced_keep(domain: str, all_names, all_missing, candidates,
                rules: list[HazardRule]) -> dict[str, str]:
    """Which of ``candidates`` must stay, and the rule id that forces each.

    Runs to a fixpoint: dropping a column can only *remove* triggers, so once a
    candidate is un-dropped it may in turn force another. Both halves of a pair
    being droppable is the good case — they go together and nothing fires.
    """
    all_names = set(all_names)
    all_missing = frozenset(all_missing)
    remaining = set(candidates)
    kept: dict[str, str] = {}
    changed = True
    while changed:
        changed = False
        surviving = all_names - remaining
        for name in sorted(remaining):
            present = frozenset(surviving | {name})
            for rule in rules:
                if not any(resolve_name(n, domain) == name for n in rule.absent_names):
                    continue
                if can_newly_fire(rule.check, domain, present, all_missing, name):
                    remaining.discard(name)
                    kept[name] = rule.core_id
                    changed = True
                    break
    return kept


# --------------------------------------------------------------------------- #
# Column-metadata surface rules
# --------------------------------------------------------------------------- #
_METADATA_SUBJECTS = frozenset({"variable_label", "variable_name"})


@dataclass(frozen=True)
class MetadataRule:
    """A rule that judges a column's own *metadata*, with no name predicate.

    ``CORE-000594`` ("variable label must be title case") and ``CDISC-CG0311``
    ("variable label must not exceed 40 characters") do not name a variable:
    they evaluate over whatever columns the dataset emits. Dropping the one
    column whose published label is defective silences the rule completely —
    the study stops reporting a real CDISC Library defect, and a floor entry
    resting on it goes dormant.
    """

    core_id: str
    check: dict
    domains: frozenset  # empty => every domain


def _scope_domains(rule: dict) -> frozenset:
    include = (((rule.get("Scope") or {}).get("Domains") or {}).get("Include") or ())
    names = {d for d in include if isinstance(d, str)}
    return frozenset() if not names or "ALL" in names else frozenset(names)


def metadata_leaf_verdict(leaf: dict, variable_name: str, variable_label: str):
    """Verdict of a metadata leaf for one column, or ``None`` when undecidable."""
    subject_name = leaf.get("name")
    if subject_name not in _METADATA_SUBJECTS:
        return None
    subject = variable_label if subject_name == "variable_label" else variable_name
    operator = leaf.get("operator")
    value = leaf.get("value")
    if isinstance(value, str) and value.startswith("$"):
        return None  # an Operation result the generator cannot compute

    def sized(bound):
        try:
            return float(bound)
        except (TypeError, ValueError):
            return None

    verdict = None
    if operator in ("matches_regex", "not_matches_regex"):
        if isinstance(value, str):
            try:
                hit = re.search(value, subject) is not None
            except re.error:
                return None
            verdict = hit if operator == "matches_regex" else not hit
    elif operator in ("equal_to", "not_equal_to"):
        if not isinstance(value, (list, dict)):
            same = subject == _as_text(value)
            verdict = same if operator == "equal_to" else not same
    elif operator in ("is_contained_by", "is_not_contained_by"):
        if isinstance(value, list):
            inside = subject in [_as_text(v) for v in value]
            verdict = inside if operator == "is_contained_by" else not inside
    elif operator in ("longer_than", "longer_than_or_equal_to",
                      "shorter_than", "shorter_than_or_equal_to"):
        bound = sized(value)
        if bound is not None:
            verdict = {
                "longer_than": len(subject) > bound,
                "longer_than_or_equal_to": len(subject) >= bound,
                "shorter_than": len(subject) < bound,
                "shorter_than_or_equal_to": len(subject) <= bound,
            }[operator]
    if verdict is None:
        return None
    return (not verdict) if leaf.get("negative") else verdict


def _metadata_decidable(check: dict) -> bool:
    probe = ("PROBE", "Probe Label")
    return all(metadata_leaf_verdict(leaf, *probe) is not None
               for leaf in iter_leaves(check))


def metadata_surface_rules(standard: str, version: str, rules_dir: str | None = None,
                           src_dir: str | None = None) -> list[MetadataRule]:
    """Shipped rules that judge column metadata and that the generator can evaluate.

    A rule qualifies when every leaf of its ``Check`` tests ``variable_label`` or
    ``variable_name`` against a **literal** — rules that route through an
    Operation (``$allowed_variables``, ``$model_order``, ``$len_mismatch`` …)
    cannot be decided here and are skipped.
    """
    ids = shipped_rule_ids(standard, version, rules_dir)
    src_dir = src_dir or _rules_src_dir()
    out: list[MetadataRule] = []
    for path in sorted(glob.glob(os.path.join(src_dir, "*", "*.yaml"))):
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        # The expression form spells the two subjects `varname()` / `var_label(…)`.
        if (not any(subject in text for subject in _METADATA_SUBJECTS)
                and "varname(" not in text and "var_label(" not in text):
            continue
        rule = yaml.load(text, Loader=_Loader)
        core_id = (rule.get("Core") or {}).get("Id")
        check = dual_form_check(rule.get("Check"))
        if core_id not in ids or not isinstance(check, dict):
            continue
        if not ({"all", "any"} & set(check)):
            continue
        leaves = list(iter_leaves(check))
        if not leaves or not {leaf.get("name") for leaf in leaves} <= _METADATA_SUBJECTS:
            continue
        if not _metadata_decidable(check):
            continue
        out.append(MetadataRule(core_id, check, _scope_domains(rule)))
    return out


def metadata_hazard(rule: MetadataRule, domain: str, variable_name: str,
                    variable_label: str) -> bool:
    """Does ``rule`` fire on this one column?"""
    if rule.domains and domain not in rule.domains:
        return False

    def walk(node):
        if "all" in node:
            return all(walk(c) for c in node["all"] or ())
        if "any" in node:
            return any(walk(c) for c in node["any"] or ())
        return bool(metadata_leaf_verdict(node, variable_name, variable_label))

    return walk(rule.check)


# --------------------------------------------------------------------------- #
# Cross-dataset presence counts
# --------------------------------------------------------------------------- #
@dataclass(frozen=True)
class CountCoupling:
    """``name`` must be present in ``minimum`` datasets, or in none at all."""

    name: str
    minimum: int
    core_ids: tuple[str, ...]


_COUNT_BOUND_OPS = {"less_than": 0, "less_than_or_equal_to": 1}


def count_coupled(standard: str, version: str, rules_dir: str | None = None,
                  src_dir: str | None = None) -> list[CountCoupling]:
    """Names a rule requires in *several* datasets once they appear in one.

    Derived from rules whose ``Operations`` count a named variable's datasets
    (``operator: variable_count``) and whose ``Check`` conjoins ``exists(name)``
    with an upper bound on that count.
    """
    ids = shipped_rule_ids(standard, version, rules_dir)
    src_dir = src_dir or _rules_src_dir()
    found: dict[str, tuple[int, set[str]]] = {}
    for path in sorted(glob.glob(os.path.join(src_dir, "*", "*.yaml"))):
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        if "variable_count" not in text:
            continue
        rule = yaml.load(text, Loader=_Loader)
        core_id = (rule.get("Core") or {}).get("Id")
        if core_id not in ids:
            continue
        counted = {op.get("id"): _formb_name(op)
                   for op in (rule.get("Operations") or ())
                   if _formb_operator(op) == "variable_count" and _formb_name(op)}
        if not counted:
            continue
        check = dual_form_check(rule.get("Check"))
        if not isinstance(check, dict):
            continue
        leaves = list(iter_leaves(check))
        present_names = {leaf.get("name") for leaf in leaves
                         if leaf.get("operator") in PRESENT_OPS}
        for leaf in leaves:
            bump = _COUNT_BOUND_OPS.get(leaf.get("operator") or "")
            name = counted.get(leaf.get("name"))
            if bump is None or name is None or name not in present_names:
                continue
            try:
                minimum = int(leaf.get("value")) + bump
            except (TypeError, ValueError):
                continue
            prev = found.get(name)
            merged = max(minimum, prev[0]) if prev else minimum
            ids_so_far = (prev[1] if prev else set()) | {core_id}
            found[name] = (merged, ids_so_far)
    return [CountCoupling(name, minimum, tuple(sorted(core_ids)))
            for name, (minimum, core_ids) in sorted(found.items())]


def resolve_count_coupling(coupling: CountCoupling, retained: dict[str, bool]) -> list[str]:
    """Datasets that must un-drop the coupled column.

    ``retained`` maps *the datasets that carry the column* to whether the current
    plan keeps it. Zero retained datasets is safe (nothing can satisfy the
    ``exists`` conjunct); otherwise at least ``minimum`` must retain it. Datasets
    are un-dropped in name order so the choice is deterministic.
    """
    keeping = [d for d, keep in retained.items() if keep]
    if not keeping or len(keeping) >= coupling.minimum:
        return []
    droppable = sorted(d for d, keep in retained.items() if not keep)
    return droppable[: coupling.minimum - len(keeping)]
