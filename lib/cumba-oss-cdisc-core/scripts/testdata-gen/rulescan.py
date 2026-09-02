"""Scan the CORE rule corpus (``rules-src/checks/CORE/*.yaml``).

Produces two things the generator needs:

* ``domain_targets`` — ``{domain: [coreId, ...]}`` from each rule's
  ``Scope.Domains.Include`` (``--`` wildcards kept verbatim; ``ALL`` /
  ``SUPP--`` / ``AP--`` / ``NONE`` are recorded but separated from concrete
  domain codes).
* ``referenced_vars`` — the set of variable names any rule mentions, used to
  decide which otherwise-optional variables a generated dataset must carry.
  ``$``-prefixed operation ids are excluded; ``--`` patterns are kept.

Every rule also carries the set of standard names it applies to
(``rule_standards``), so later phases can restrict the scan to one lane
(e.g. only SDTMIG rules when generating the SDTMIG study).

This module only *reads* the rule YAML; it has no dependency on the engine.
"""

from __future__ import annotations

import glob
import os
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field

import yaml

# Dual-form Check loading (Fix #257): the shared expression reader
# (corej-cdisc-rules/scripts/lib/check_expr.py — the module that owns the
# corpus) lowers an ``{expression: "..."}`` Check to the legacy dict this
# module walks; a legacy Check passes through unchanged. The cross-tree path
# mirrors ``_default_checks_root()``'s existing data dependency on that module.
_SHARED_LIB = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "..",
    "corej-cdisc-rules", "scripts", "lib"))
if _SHARED_LIB not in sys.path:
    sys.path.insert(0, _SHARED_LIB)
from check_expr import dual_form_check  # noqa: E402  (needs the sys.path bootstrap)

# Concrete domain codes are 2 uppercase letters optionally followed by more
# uppercase letters/digits; the wildcard families are handled separately.
_WILDCARD_DOMAINS = {"ALL", "SUPP--", "AP--", "NONE"}
# Tokens that appear in Domains.Include in some rules but are not domains
# (they are variable names mistakenly broad, or pool markers). Kept out of the
# concrete-domain set.
_NON_DOMAIN_TOKENS = {"POOLID", "IDVAR", "IDVARVAL"}

# A plausible SDTM/SEND variable name: 2+ uppercase alphanumerics, or a
# ``--``/``**`` wildcard form. Excludes pure numbers and $-operation ids.
_VAR_RE = re.compile(r"^(?:--|\*\*)?[A-Z][A-Z0-9]*(?:--)?[A-Z0-9]*$")


# Organisation subdirectories of ``rules-src/checks`` that the scan skips.
# ``DRAFT`` holds unshipped drafts, so counting it would overstate the corpus.
_EXCLUDED_ORGS = {"DRAFT"}


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


def _default_checks_root() -> str:
    """``lib/corej-cdisc-rules/rules-src/checks`` — the *multi-org* corpus root.

    Its immediate subdirectories are the authoring organisations (CDISC, CORE,
    FDA, PMDA, DRAFT). :func:`scan` walks all of them except
    :data:`_EXCLUDED_ORGS`.
    """
    here = os.path.dirname(os.path.abspath(__file__))
    # scripts/testdata-gen/ -> lib/corej-cdisc-rules/rules-src/checks (sibling module)
    return os.path.normpath(
        os.path.join(here, "..", "..", "..", "corej-cdisc-rules", "rules-src", "checks")
    )


def _default_core_dir() -> str:
    """The flat ``checks/CORE`` directory only.

    ⚠ Deliberately *narrower* than :func:`_default_checks_root`. Callers that
    resolve a bare ``CORE-xxxxxx.yaml`` filename against a single flat directory
    (``tests/test_violations.py``) depend on this staying CORE-only; the corpus
    scan does **not** use it.
    """
    return os.path.join(_default_checks_root(), "CORE")


def _org_dirs(checks_root: str) -> list[str]:
    """Scannable organisation directories under ``checks_root``, sorted.

    Falls back to ``[checks_root]`` when the path has no organisation
    subdirectories, so an explicitly-passed flat directory still scans.
    """
    orgs = sorted(
        entry.path
        for entry in os.scandir(checks_root)
        if entry.is_dir() and entry.name not in _EXCLUDED_ORGS
    )
    return orgs or [checks_root]


@dataclass
class RuleScan:
    """Aggregated view over the rule corpus."""

    domain_targets: dict[str, list[str]] = field(default_factory=lambda: defaultdict(list))
    referenced_vars: set[str] = field(default_factory=set)
    rule_standards: dict[str, set[str]] = field(default_factory=dict)
    rule_domains: dict[str, list[str]] = field(default_factory=dict)
    n_rules: int = 0

    def concrete_domains(self) -> set[str]:
        """Domain codes that are real datasets (not ALL/SUPP--/AP--/NONE/markers)."""
        return {
            d
            for d in self.domain_targets
            if d not in _WILDCARD_DOMAINS and d not in _NON_DOMAIN_TOKENS
        }

    def targets_for_standard(self, standard: str) -> dict[str, list[str]]:
        """``domain -> [coreId]`` restricted to rules applying to ``standard``.

        ``standard`` is matched case-insensitively against the rule's standard
        names (e.g. ``"SDTMIG"`` or ``"SENDIG"``).
        """
        want = standard.upper()
        out: dict[str, list[str]] = defaultdict(list)
        for dom, ids in self.domain_targets.items():
            for cid in ids:
                names = {n.upper() for n in self.rule_standards.get(cid, set())}
                if want in names:
                    out[dom].append(cid)
        return out


def _walk_check(node, names: set[str]) -> None:
    """Collect leaf ``name`` (and column-ref ``value``) from a Check tree."""
    if isinstance(node, dict):
        if "all" in node or "any" in node:
            for child in node.get("all") or node.get("any") or []:
                _walk_check(child, names)
            return
        if "not" in node:
            _walk_check(node["not"], names)
            return
        # leaf
        nm = node.get("name")
        if isinstance(nm, str):
            names.add(nm)
        # value may reference another column unless flagged literal
        val = node.get("value")
        if isinstance(val, str) and not node.get("value_is_literal"):
            names.add(val)
    elif isinstance(node, list):
        for child in node:
            _walk_check(child, names)


def _clean_var(name: str) -> str | None:
    """Return a normalized variable token, or None if not a variable name."""
    if not isinstance(name, str):
        return None
    name = name.strip()
    # An ``!X`` Output_Variables exclusion token only withholds X from the FINDING; the
    # rule still reads X, so the generator must still provide the column — strip the marker.
    if name.startswith("!"):
        name = name[1:]
    if not name or name.startswith("$"):
        return None
    # strip a dataset qualifier like ``RELREC.--TERM`` -> ``--TERM``
    if "." in name:
        name = name.split(".", 1)[1]
    if not _VAR_RE.match(name):
        return None
    return name


def scan(checks_root: str | None = None) -> RuleScan:
    """Scan every organisation under ``checks_root`` except :data:`_EXCLUDED_ORGS`.

    ``checks_root`` defaults to :func:`_default_checks_root`. A flat directory
    of ``*.yaml`` is also accepted (see :func:`_org_dirs`).
    """
    checks_root = checks_root or _default_checks_root()
    paths = [p for org in _org_dirs(checks_root) for p in glob.glob(os.path.join(org, "*.yaml"))]
    result = RuleScan()
    for path in sorted(paths):
        with open(path, encoding="utf-8") as fh:
            doc = yaml.safe_load(fh) or {}
        result.n_rules += 1
        cid = (doc.get("Core") or {}).get("Id") or os.path.basename(path)

        scope = doc.get("Scope") or {}
        domains = scope.get("Domains") or {}
        include = domains.get("Include") or []
        result.rule_domains[cid] = list(include)
        for dom in include:
            result.domain_targets[dom].append(cid)

        # standards this rule applies to
        std_names: set[str] = set()
        for auth in doc.get("Standards") or []:
            for std in auth.get("Standards") or []:
                if std.get("Name"):
                    std_names.add(std["Name"])
        result.rule_standards[cid] = std_names

        # referenced variables: Check leaves, Operations, Output_Variables,
        # Requirements.Variables
        raw: set[str] = set()
        _walk_check(dual_form_check(doc.get("Check")), raw)
        for op in doc.get("Operations") or []:
            name = _formb_name(op)
            if isinstance(name, str):
                raw.add(name)
            raw.update(_formb_group(op))
        for v in (doc.get("Outcome") or {}).get("Output_Variables") or []:
            raw.add(v)
        # ALL THREE facets. This set is "every column the rule mentions", not a
        # presence guarantee, so `Any` and `None` belong in it exactly as the old
        # `Exclude` did — a column the rule requires to be ABSENT is still a column
        # the generated test data has to know about.
        variables = (doc.get("Requirements") or {}).get("Variables") or {}
        for key in ("All", "Any", "None"):
            raw.update(variables.get(key) or [])

        for token in raw:
            cleaned = _clean_var(token)
            if cleaned:
                result.referenced_vars.add(cleaned)

    return result


if __name__ == "__main__":  # pragma: no cover - manual inspection helper
    sc = scan()
    print(f"rules scanned: {sc.n_rules}")
    print(f"concrete domains: {len(sc.concrete_domains())}")
    print(f"referenced vars: {len(sc.referenced_vars)}")
    top = sorted(
        ((d, len(ids)) for d, ids in sc.domain_targets.items()),
        key=lambda x: -x[1],
    )[:12]
    print("top domain targets:", top)
