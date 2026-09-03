"""Human-readable value library.

Resolves each non-identifier, non-date cell to a realistic value, in priority
order:

1. a curated pick for well-known codelists / variable names,
2. a controlled-terminology submission value (already a real word),
3. a paired decode (``--TEST`` from ``--TESTCD``),
4. a per-variable heuristic driven by the variable label / description,
5. a datatype default.

Identifiers (STUDYID/DOMAIN/USUBJID/--SEQ) and dates (``--DTC``) are supplied by
the generator/timeline, not here.
"""

from __future__ import annotations

from dataclasses import dataclass

from library import Library, Variable


@dataclass
class Ctx:
    """Per-cell context."""

    domain: str
    subject_index: int  # 0-based subject ordinal
    record_index: int  # 0-based record ordinal within the subject/dataset
    testcd: str | None = None  # the row's --TESTCD value, when known


# Curated picks for common codelists / variable names, keyed by exact name or
# by a name suffix. A value here must be a member of the variable's codelist
# (asserted indirectly by the clean-study verification).
_NAME_PREFERRED: dict[str, str] = {
    "AGEU": "YEARS",
    "COUNTRY": "USA",
    "SEX": "M",  # alternated per subject in pick_codelist
}

# Plausible numeric result ranges + unit by test short name (extend as needed).
# (low, high, unit). Used for --STRESN / --ORRES of findings.
_TEST_RANGE: dict[str, tuple[float, float, str]] = {
    "ALT": (10, 55, "U/L"),
    "AST": (10, 45, "U/L"),
    "BILI": (3, 20, "umol/L"),
    "CREAT": (50, 110, "umol/L"),
    "GLUC": (4, 7, "mmol/L"),
    "HGB": (120, 170, "g/L"),
    "WBC": (4, 11, "10^9/L"),
    "PLAT": (150, 400, "10^9/L"),
    "SYSBP": (100, 140, "mmHg"),
    "DIABP": (60, 90, "mmHg"),
    "PULSE": (55, 90, "beats/min"),
    "TEMP": (36, 38, "C"),
    "RESP": (12, 20, "breaths/min"),
    "WEIGHT": (50, 95, "kg"),
    "HEIGHT": (150, 190, "cm"),
    "BW": (200, 450, "g"),  # SEND body weight (animal)
}

_DEFAULT_NUM_RANGE = (1, 10, "")


class ValueFactory:
    def __init__(self, lib: Library) -> None:
        self.lib = lib

    # ---- codelist-bound ----
    def pick_codelist(self, var: Variable, ctx: Ctx) -> str | None:
        terms, _extensible = self.lib.codelist_terms(var.codelist_codes)
        if not terms:
            return None
        # name-based preference (e.g. AGEU -> YEARS)
        pref = _NAME_PREFERRED.get(var.name)
        if pref and pref in terms:
            return pref
        # SEX-style two-value codelists: alternate by subject for variety
        if var.name == "SEX" and {"M", "F"} <= set(terms):
            return "M" if ctx.subject_index % 2 == 0 else "F"
        # No/Yes-style codelists (NY, or NY+NA/U): default to "N", a safe
        # non-triggering value (e.g. AESER='N' => not a serious event).
        if {"N", "Y"} <= set(terms) and len(terms) <= 5:
            return "N"
        # otherwise a deterministic, stable pick that avoids odd leading symbols
        readable = [t for t in terms if t[:1].isalnum()] or terms
        return readable[ctx.record_index % len(readable)]

    # ---- numerics ----
    def numeric(self, var: Variable, ctx: Ctx) -> int | float:
        low, high, _unit = self._range_for(ctx.testcd)
        span = max(1, int(high - low))
        val = low + (ctx.record_index * 7 + ctx.subject_index * 3) % span
        return int(val)

    @staticmethod
    def _range_for(testcd: str | None):
        if testcd and testcd in _TEST_RANGE:
            return _TEST_RANGE[testcd]
        return _DEFAULT_NUM_RANGE

    # ---- free text ----
    def text(self, var: Variable, ctx: Ctx) -> str:
        """A short readable phrase derived from the variable label."""
        # Use the label as the basis so values self-describe (e.g.
        # "Sponsor-Defined Identifier" -> "SPONSOR-DEFINED IDENTIFIER 1").
        base = (var.label or var.name).strip()
        return f"{base.upper()} {ctx.record_index + 1}"

    # Dictionary-coded MedDRA hierarchy: filled with a *constant* per variable so
    # every 1:1 relationship (single value <-> single value) holds trivially and
    # paired code/term vars are both populated (e.g. AEDECOD + AEPTCD).
    _CONST_SUFFIXES = (
        "DECOD", "BODSYS", "BDSYCD", "PTCD", "LLTCD", "LLT", "SOCCD", "SOC",
        "HLGTCD", "HLGT", "HLTCD", "HLT",
    )
    # Record link ids and time-point references: left empty — populating them
    # pulls in cross-record / cross-dataset references with no coverage value.
    _EMPTY_SUFFIXES = ("LNKID", "LNKGRP", "RFTDTC", "TPTREF", "TPTNUM", "TPT", "ELTM")

    # ---- dispatch ----
    def value(self, var: Variable, ctx: Ctx) -> object:
        if var.name.endswith(self._EMPTY_SUFFIXES):
            return ""
        if var.name.endswith(self._CONST_SUFFIXES):
            # numeric-style code vars (--PTCD/--SOCCD/…) get a numeric constant;
            # text dictionary vars get a word constant.
            return "1" if var.name.endswith("CD") else "CODED"
        # Flags (--FL): leave empty by default — a populated flag must usually be
        # "Y", and the generator sets the few that need a value (e.g. baseline).
        if var.name.endswith("FL"):
            return ""
        # Date variables must never get a text value (would be invalid ISO 8601);
        # the generator owns all date placement, so default these to empty.
        if var.name.endswith("DTC"):
            return ""
        if var.codelist_codes:
            picked = self.pick_codelist(var, ctx)
            if picked is not None:
                return picked
        if var.datatype == "Num":
            return self.numeric(var, ctx)
        return self.text(var, ctx)
