"""Generate a clean, standard-bounded synthetic study for one lane.

Usage:
    python generate.py --standard sdtmig --version 3-4 --out <dir>
    python generate.py --standard sendig --version 3-1-1 --out <dir>

The variable list for every domain comes from the engine cache (``library``);
the subject/trial skeleton comes from ``study``; cell values from ``values``.
Output is Dataset-JSON, one file per domain, plus (Phase 5) a define.xml.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import os

import copresence
import define
import library
import domains as dom_mod
import emit
import study as study_mod
from library import Library, Variable, SDTMIG_3_4, SENDIG_3_1_1
from study import Study, iso
from values import Ctx, ValueFactory

SPECS = {("sdtmig", "3-4"): SDTMIG_3_4, ("sendig", "3-1-1"): SENDIG_3_1_1}

# Trial elements aligned to epochs (etcd, element, epoch).
ELEMENTS = [
    ("SCRN", "Screening", "SCREENING"),
    ("TRT", "Treatment", "TREATMENT"),
    ("FUP", "Follow-up", "FOLLOW-UP"),
]

# Required Trial Summary parameters (TSPARMCD -> TSVAL). Covers the FDA-mandated
# set (FDA-SD22xx) and CORE-000741 required parameters for an interventional
# study. Values are plausible and CT-valid where a codelist applies.
TS_PARAMS = [
    ("TITLE", "A Synthetic Study to Exercise CDISC CORE Conformance Rules"),
    ("STYPE", "INTERVENTIONAL"),
    ("TTYPE", "EFFICACY"),
    ("TPHASE", "PHASE II TRIAL"),
    ("SSTDTC", "2020-01-06"),
    ("SENDTC", "2020-12-31"),
    ("ADDON", "N"),
    ("AGEMIN", "P18Y"),
    ("AGEMAX", "P85Y"),
    ("LENGTH", "P12W"),
    ("PLANSUB", "20"),
    ("ACTSUB", "20"),
    ("RANDOM", "Y"),
    ("SEXPOP", "BOTH"),
    ("STOPRULE", "None"),
    ("TBLIND", "DOUBLE BLIND"),
    ("TCNTRL", "PLACEBO"),
    ("TDIGRP", "Hypertension"),
    ("TINDTP", "TREATMENT"),
    ("CURTRT", "Standard of Care"),
    ("OBJPRIM", "Assess efficacy versus placebo"),
    ("OBJSEC", "Assess safety and tolerability"),
    ("SPONSOR", "Synthetic Pharma Inc"),
    ("TRT", "Drug A"),
    ("REGID", "NCT00000000"),
    ("OUTMSPRI", "Change in primary endpoint at Week 12"),
    ("PCLAS", "Muscarinic Agonist"),
    ("PCLASS", "Muscarinic Agonist"),  # CORE-000741 spells it PCLASS
    ("FCNTRY", "USA"),
    ("ADAPT", "N"),
    ("DCUTDTC", "2020-12-31"),
    ("DCUTDESC", "Final database lock"),
    ("INTMODEL", "PARALLEL"),
    ("INTTYPE", "DRUG"),
    ("NARMS", "3"),
    ("HLTSUBJI", "N"),
    ("EXTTIND", "N"),
    ("NCOHORT", "1"),
    ("PDPSTIND", "N"),
    ("PDSTIND", "N"),
    ("PIPIND", "N"),
    ("RDIND", "N"),
    ("SDTIGVER", "3.4"),
    ("SDTMVER", "2.0"),
    ("THERAREA", "Cardiovascular"),
]


# Domains whose events predate the study reference start (medical history etc.).
HISTORY_DOMAINS = {"MH"}

# SEND necropsy / post-mortem Findings domains: their records are collected at
# terminal sacrifice, so the collection date must align with the subject's
# disposition (DS.DSSTDTC / DSSTDY). FDA-SE2265 (--DTC not before DSSTDTC, OM),
# FDA-SE2270 (--DY == DSSTDY, MA/TF), FDA-SE2276 (--DTC == DSSTDTC, MA/TF).
POSTMORTEM_SEND = {"DD", "MA", "MI", "OM", "TF"}

# SEND (nonclinical) Trial Summary parameters expected by CORE-100179.
SEND_TS_PARAMS = [
    ("STITLE", "A Synthetic Nonclinical Study"), ("SSTYP", "PARALLEL"),
    ("SDESIGN", "PARALLEL"), ("STCAT", "GENERAL TOXICOLOGY"),
    ("STDIR", "Repeat-dose toxicity"), ("STSTDTC", "2020-01-06"),
    ("EXPSTDTC", "2020-01-06"), ("EXPENDTC", "2020-12-31"),
    ("DOSDUR", "P28D"), ("GLPFL", "Y"), ("ROUTE", "ORAL"),
    ("SPECIES", "RAT"), ("STRAIN", "WISTAR"), ("AGE", "12"), ("AGEU", "WEEKS"),
    ("SEXPOP", "BOTH"), ("STRPSTAT", "RT-ENG"),
    ("SNDIGVER", "SEND Implementation Guide Version 3.1.1"),
    ("SNDCTVER", "2024-09-27"), ("TRMSAC", "P1D"),
    ("SPLRNAM", "Synthetic Labs"), ("SPREFID", "SUP-001"),
    ("SSPONSOR", "Synthetic Pharma Inc"), ("TRT", "Drug A"),
    ("TRTV", "Drug A vehicle"), ("TRTCAS", "50-00-0"), ("TRTUNII", "ABCDEF1234"),
    ("TFCNTRY", "USA"),
    ("TSTFLOC", "Synthetic Test Facility"), ("TSTFNAM", "Site 1"),
    # FDA-SE23xx required SEND Trial Summary parameters.
    ("SLENGTH", "P28D"), ("SPLANSUB", "20"),
    ("STENDTC", "2020-12-31"),
    ("DOSSTDTC", "2020-01-06"), ("DOSENDTC", "2020-02-03"),
    ("PCLASS", "Muscarinic Agonist"), ("PDOSFRQ", "QD"),
]

# Per-domain permissible variables to omit from the clean study because their
# presence triggers an unsatisfiable rule. MH start/end dates require --STDY/
# --ENDY columns the IG omits (FDA-SD1087); a dateless medical history is valid.
_DOMAIN_DROP_VARS = {"MH": ("MHSTDTC", "MHENDTC")}


def _is_blank(value) -> bool:
    """True when a generated cell carries no value at all."""
    return value is None or str(value) == ""


def has_anomalous_label(var: Variable) -> bool:
    """True when the variable's *library* label is itself malformed.

    ``GEN-VMCALM-LBL`` ("variable label must match the CDISC Library definition")
    has no variable-name predicate: it evaluates over whatever columns the
    dataset emits, and it can only fire on a column whose published label does
    not survive the round trip through the dataset and Define-XML writers. Drop
    that column and the rule reports nothing at all — not fired, not executed —
    which is exactly the state ``verify.verify_lane``'s vacuity guard fails the
    lane for, and the library defect the study exists to surface goes unreported.

    A label is anomalous when it is not equal to its own stripped form, carries a
    control character, or embeds a backslash escape. Measured 2026-08-07 over
    both lanes' full column sets (55 datasets), exactly **one** column matches:
    ``GF.GFSEQID``, whose published label is ``"Sequence Identifier \\n"``.
    """
    label = var.label or ""
    return (label != label.strip()
            or "\\" in label
            or any(ord(ch) < 32 for ch in label))


def unpopulated_permissible(
    cols: list[Variable], rows: list[list], protected: set[str],
) -> list[str]:
    """``Perm`` columns that no record populates and that nothing protects."""
    if not rows:
        return []
    return [
        var.name
        for i, var in enumerate(cols)
        if var.core == "Perm"
        and var.name not in protected
        and all(_is_blank(row[i]) for row in rows)
    ]


def all_missing_columns(cols: list[Variable], rows: list[list]) -> set[str]:
    """Every column — of any core class — that no record populates."""
    if not rows:
        return {var.name for var in cols}
    return {
        var.name
        for i, var in enumerate(cols)
        if all(_is_blank(row[i]) for row in rows)
    }


def drop_unpopulated_permissible(
    cols: list[Variable], rows: list[list], protected: set[str],
    keep: set[str] | frozenset[str] = frozenset(),
) -> tuple[list[Variable], list[list], list[str]]:
    """Remove Permissible columns that are empty in **every** record.

    ``FDA-SD1078`` / ``PMDA-SD1078`` raise an error for each *Permissible*
    variable that is present in a dataset but empty for all of its records — a
    sponsor must not ship a column it never populates, and the rule is right to
    say so. Plan decision 5 emits the standard's *whole* variable list, so every
    unused ``Perm`` variable was a finding by construction (578 findings across
    33 domains, measured 2026-08-05). Emitting a ``Perm`` column only when it is
    populated makes the synthetic study submittable and keeps SD1078 a live
    check rather than a baselined residual.

    What deliberately stays:

    * ``Req`` / ``Exp`` columns, even when empty. For those, *presence is the
      contract* — an absent Expected variable is its own finding — and their
      emptiness is the separate, still-open ``FDA-SD1149`` residual.
    * every name in ``protected`` (the dataset's key variables). A key
      identifies a record; dropping one would strip the ``keySequence`` that
      ``emit.build_dataset`` writes and the Define-XML ``KeySequence`` derived
      from it.
    * a ``Perm`` column populated on even one row.
    * every name in ``keep``. **This is the co-presence contract.** Removing a
      column is not the same as leaving it empty: a rule that tests a variable's
      *absence* sees a different dataset afterwards. ``Generator.generate``
      computes ``keep`` from the shipped rule corpus via
      :mod:`copresence` — never leave one half of a co-presence pair present and
      the other absent. Measured 2026-08-07, dropping every unpopulated ``Perm``
      column without this guard made nine rules fire that had been silent and
      left ``GEN-VMCALM-LBL`` unloadable.

    A dataset with **no rows** returns everything unchanged: "empty in every
    row" is vacuously true for every column there, so emptiness carries no
    information — and ``var_is_null`` has no record to be null on either, so
    SD1078 cannot fire on it.

    **The SEND lane runs this drop for no SD1078 benefit** (measured 2026-08-08).
    No SENDIG rule package ships SD1078 or anything with its semantics: scanning
    all 25 ``rules/rules-*sendig*.json`` for an operation
    ``get_dataset_filtered_variables(key_name="core", key_value="Perm")`` finds
    zero (positive control: the same scan finds ``FDA-SD1078`` / ``PMDA-SD1078``
    in exactly the 10 SDTMIG packages), and ``var_is_null`` appears in no SEND
    package at all. The only SEND rule that mentions ``Perm`` is
    ``CDISC-SEND-0055``, which requires ``variable_value`` **non_empty** and so
    cannot see an all-empty column either way. The drop still removes **98**
    columns across 14 SEND domains, with 2 held back by the co-presence guard.

    It is nevertheless **left enabled**, deliberately. Turning it off is not a
    no-op that merely stops removing columns: it re-introduces 98 present-but-
    empty Permissible columns into the SEND study, and *no rule fires on an
    empty Perm column* has not been established — only *no rule with SD1078's
    semantics ships*. Establishing the stronger claim needs a full engine run
    and a re-baselined SEND lane, and ``verify.py``'s SEND baseline is currently
    red and un-rebaselined (Fix #153). Changing generator behaviour underneath a
    red verifier would make the change unverifiable, which is a worse trade than
    keeping a step that costs nothing but the co-presence guard already in place.

    Returns ``(cols, rows, dropped_names)``; ``rows`` is rebuilt so it stays
    index-aligned with ``cols``.
    """
    dropped = [
        name for name in unpopulated_permissible(cols, rows, protected)
        if name not in keep
    ]
    if not dropped:
        return cols, rows, []
    survivors = [i for i, var in enumerate(cols) if var.name not in set(dropped)]
    return (
        [cols[i] for i in survivors],
        [[row[i] for i in survivors] for row in rows],
        dropped,
    )


class Generator:
    def __init__(self, lib: Library, study: Study) -> None:
        self.lib = lib
        self.study = study
        self.vf = ValueFactory(lib)
        self.subj_by_id = {s.usubjid: s for s in study.subjects}
        # {domain: [Perm variables dropped because no record populated them]},
        # filled by generate(); see drop_unpopulated_permissible.
        self.dropped_permissible: dict[str, list[str]] = {}
        # {domain: {variable: why}} for unpopulated Perm columns the drop had to
        # spare — the co-presence contract. Each of these is one surviving
        # SD1078 finding, and the reason it survives is recorded here.
        self.kept_permissible: dict[str, dict[str, str]] = {}

    # ---- column selection ----
    def columns(self, domain: str) -> list[Variable]:
        """Full definition for the target standard, ordered by ordinal.

        The variable set is exactly the standard's definition, minus the few
        permissible variables whose mere presence trips an otherwise-unsatisfiable
        rule (see ``_DOMAIN_DROP_VARS`` and the SEND ``EXMETHOD`` case below).
        """
        # The variable set is exactly the standard's definition; the engine
        # rejects any variable not in a domain's allowed list, so variables the
        # IG omits cannot be injected (see KNOWN-RESIDUALS.md). The only
        # subtractions are permissible variables whose mere presence triggers an
        # otherwise-unsatisfiable rule (currently MH start/end dates).
        cols = self.lib.variables(domain)
        drop = set(_DOMAIN_DROP_VARS.get(domain, ()))
        # EXMETHOD is in SEND EX's variable metadata but not its allowed-variable
        # list (FDA-SD0058/1076) — drop it on the SEND lane.
        if self.study.standard == "sendig" and domain == "EX":
            drop.add("EXMETHOD")
        if not drop:
            return cols
        return [v for v in cols if v.name not in drop]

    # ---- value helpers ----
    def _code_name_pairs(self, domain: str, code_var: Variable, name_var: Variable | None):
        """Bijective (code, name) pairs for a --TESTCD/--TEST or --PARMCD/--PARM.

        Names are kept <= 40 chars and distinct, so the 40-char truncation never
        maps two codes to the same name (which would break the 1:1 rule).
        """
        cache = getattr(self, "_pair_cache", None)
        if cache is None:
            cache = self._pair_cache = {}
        if code_var.name in cache:
            return cache[code_var.name]
        pairs: list[tuple[str, str]] = []
        seen_names: set[str] = set()
        terms, _ = self.lib.codelist_terms(code_var.codelist_codes)
        for code in terms:
            if len(code) > 8:
                continue
            name = (self.lib.decode(code_var.codelist_codes, code) or code.title())[:40]
            if name in seen_names:
                continue
            seen_names.add(name)
            pairs.append((code, name))
            if len(pairs) >= 5:
                break
        if not pairs:  # uncoded code list: synthesize short, unique pairs
            base = (name_var.label if name_var else domain)[:30]
            pairs = [(f"{domain}T{i + 1:02d}", f"{base} {i + 1}"[:40]) for i in range(3)]
        cache[code_var.name] = pairs
        return pairs

    # ---- generic subject-level builder (findings/events/interventions) ----
    def build_subject_level(self, domain: str, n_per_subject: int = 3) -> list[list]:
        cols = self.columns(domain)
        by_name = {v.name: v for v in cols}
        names = set(by_name)
        p = domain  # variable prefix for standard domains
        testcd_name = f"{p}TESTCD"
        topic = dom_mod.topic_var(self.lib, domain)
        has_results = f"{p}ORRES" in names
        has_stresn = f"{p}STRESN" in names
        history = domain in HISTORY_DOMAINS
        post_mortem = self.study.standard == "sendig" and domain in POSTMORTEM_SEND
        rows: list[list] = []
        trt_visits = self.study.treatment_visits() or self.study.visits
        for subj in self.study.subjects:
            for r in range(n_per_subject):
                visit = trt_visits[r % len(trt_visits)]
                ctx = Ctx(domain=domain, subject_index=subj.index, record_index=r)
                vals: dict[str, object] = {"STUDYID": self.study.studyid, "DOMAIN": domain}
                for ident, attr in (("USUBJID", "usubjid"), ("SUBJID", "subjid"),
                                    ("SITEID", "siteid")):
                    if ident in names:
                        vals[ident] = getattr(subj, attr)
                if f"{p}SEQ" in names:
                    vals[f"{p}SEQ"] = r + 1

                # topic: --TESTCD/--TEST or --PARMCD/--PARM bijective pairs;
                # otherwise a readable topic value.
                code_name = None
                if testcd_name in names:
                    code_name = (testcd_name, f"{p}TEST")
                elif f"{p}PARMCD" in names:
                    code_name = (f"{p}PARMCD", f"{p}PARM")
                if code_name:
                    cvar, nname = code_name
                    if domain in self._FIXED_TESTCD:
                        code, name = self._FIXED_TESTCD[domain]
                    else:
                        pairs = self._code_name_pairs(
                            domain, by_name[cvar], by_name.get(nname)
                        )
                        code, name = pairs[r % len(pairs)]
                    ctx.testcd = code
                    vals[cvar] = code
                    if nname in names:
                        vals[nname] = name
                elif topic and topic in names:
                    vals[topic] = self.vf.text(by_name[topic], ctx)[:40]

                # dates: every Req/Exp date var gets a valid in-window date.
                # History domains (MH) leave dates empty — partial/absent dates
                # are conformant and avoid study-day/ordering rules that assume
                # in-study collection.
                if not history:
                    # Necropsy/post-mortem SEND findings are collected at terminal
                    # sacrifice: align the date with the subject's disposition
                    # (DS.DSSTDTC == rfendtc) so --DTC/--DY match DSSTDTC/DSSTDY
                    # (FDA-SE2265/2270/2276).
                    base = subj.rfendtc if post_mortem else subj.visit_date(visit.day)
                    # Populate timing dates even when Perm so the time-point rules
                    # (FDA-SD0021/0022: a record needs a start and end time point;
                    # FDA-SE0009: a SEND record needs --DTC or --DY) are satisfied.
                    # Gate on EPOCH presence (or SEND): a date on an EPOCH-less
                    # domain (BS/GF/CO/SM) would trip the EPOCH rule (FDA-SD1339),
                    # which is already an irreducible residual there.
                    ok_date = "EPOCH" in names or self.study.standard == "sendig"
                    if f"{p}STDTC" in names and ok_date:
                        vals[f"{p}STDTC"] = iso(base)
                        if f"{p}ENDTC" in names:
                            vals[f"{p}ENDTC"] = iso(base)
                    if f"{p}DTC" in names and ok_date:
                        vals[f"{p}DTC"] = iso(base)

                # visit / epoch / planned study day
                if not history:
                    if "VISITNUM" in names:
                        vals["VISITNUM"] = visit.num
                    if "VISIT" in names:
                        vals["VISIT"] = visit.name
                    if "VISITDY" in names:
                        vals["VISITDY"] = visit.day
                    if "EPOCH" in names:
                        vals["EPOCH"] = visit.epoch
                elif "EPOCH" in names:
                    vals["EPOCH"] = "SCREENING"

                # categories: non-empty, distinct from --DECOD/--BODSYS, and
                # varying across records (a single value for all records is itself
                # flagged, e.g. CORE-000365).
                cat_n = (r % 2) + 1
                if f"{p}CAT" in names:
                    vals[f"{p}CAT"] = f"{p} CATEGORY {cat_n}"
                if f"{p}SCAT" in names:
                    vals[f"{p}SCAT"] = f"{p} SUBCATEGORY {cat_n}"
                # result category (e.g. tumor findings): a safe, non-metastatic
                # value (CORE-100144 flags METASTATIC without a linked primary).
                # Only populate when required (Req/Exp): a populated --RESCAT on a
                # record with an empty --STRESC trips FDA-SD0045, so a Perm
                # --RESCAT (e.g. CL) is left empty.
                if f"{p}RESCAT" in names and by_name[f"{p}RESCAT"].core in ("Req", "Exp"):
                    vals[f"{p}RESCAT"] = "BENIGN"

                # result group: a real numeric result needs --STRESN. Without it,
                # record the test as NOT DONE (clean and valid) rather than a
                # unit-less character result that trips unit-consistency rules.
                has_stat = f"{p}STAT" in names
                produced_result = False
                if has_results and has_stresn:
                    self._set_results(domain, by_name, names, vals, ctx)
                    produced_result = True
                elif has_results and not has_stat and self.study.standard == "sendig":
                    # SEND categorical-result finding (e.g. TF tumor findings):
                    # no --STRESN and no --STAT, so NOT DONE cannot be expressed
                    # and a Req --RESCAT forces a result. Populate --ORRES/--STRESC
                    # with a categorical value; --STRESU is absent from the model
                    # so FDA-SD0029 is the documented floor (see KNOWN-RESIDUALS).
                    for rv in (f"{p}ORRES", f"{p}STRESC"):
                        if rv in names:
                            vals[rv] = "ADENOMA"
                    produced_result = True
                elif has_results:
                    # NOT DONE: results explicitly empty (so they don't fall
                    # through to a codelist pick like "DEAD"); --STAT set if the
                    # domain has it. (Domains with --RESCAT but no --STAT — e.g.
                    # TF — cannot represent NOT DONE and remain a documented
                    # residual; see KNOWN-RESIDUALS.md.)
                    for empt in (f"{p}ORRES", f"{p}STRESC", f"{p}STRESN"):
                        if empt in names:
                            vals[empt] = ""
                    if f"{p}STAT" in names:
                        vals[f"{p}STAT"] = "NOT DONE"
                    if f"{p}REASND" in names:
                        vals[f"{p}REASND"] = "NOT ASSESSED"

                # baseline / last-before-exposure flags only when a result exists
                # (a flagged record with no result is itself an error). Skipped
                # for SEND: all generated records are post-dose (--DY > 0), and a
                # baseline flag on a post-dose record is flagged (CORE-100089).
                if produced_result and self.study.standard != "sendig":
                    if f"{p}BLFL" in names:
                        vals[f"{p}BLFL"] = "Y" if r == 0 else ""
                    if f"{p}LOBXFL" in names:
                        vals[f"{p}LOBXFL"] = "Y" if r == 0 else ""

                # assemble: managed -> vals; else Req/Exp -> value; Perm -> empty
                row = []
                for v in cols:
                    if v.name in vals:
                        row.append(vals[v.name])
                    elif v.core in ("Req", "Exp"):
                        row.append(self.vf.value(v, ctx))
                    else:
                        row.append("")
                rows.append(row)
        return rows

    def _set_results(self, domain, by_name, names, vals, ctx) -> None:
        """Populate a coherent numeric result group.

        ``--ORRES``/``--STRESC`` = the numeric as text, ``--STRESN`` = the number,
        ``--ORRESU``/``--STRESU`` = a CT-valid unit, and any normal-range bounds
        set low < high.
        """
        p = domain
        num = self.vf.numeric(by_name.get(f"{p}STRESN"), ctx)
        s = str(num)
        unit = ""
        for uvar in (f"{p}ORRESU", f"{p}STRESU"):
            if uvar in names:
                terms, _ = self.lib.codelist_terms(by_name[uvar].codelist_codes)
                readable = [t for t in terms if t[:1].isalnum()]
                if readable:
                    # one unit per test (stable, not per record), so units stay
                    # consistent within a test (CORE-000699 / FDA-SD0007).
                    unit = readable[0]
                    break
        for rv in (f"{p}ORRES", f"{p}STRESC"):
            if rv in names:
                vals[rv] = s
        vals[f"{p}STRESN"] = num
        if unit:
            for uvar in (f"{p}ORRESU", f"{p}STRESU"):
                if uvar in names:
                    vals[uvar] = unit
        # normal ranges: low < high (avoid CORE-000672 hi <= lo)
        for lo, hi in ((f"{p}STNRLO", f"{p}STNRHI"), (f"{p}ORNRLO", f"{p}ORNRHI")):
            if lo in names and hi in names:
                vals[lo] = str(num - 1)
                vals[hi] = str(num + 1)

    # ---- DM ----
    def build_dm(self) -> list[list]:
        cols = self.columns("DM")
        names = {v.name for v in cols}
        rows = []
        for s in self.study.subjects:
            ctx = Ctx("DM", s.index, 0)
            fixed = {
                "STUDYID": self.study.studyid, "DOMAIN": "DM", "USUBJID": s.usubjid,
                "SUBJID": s.subjid, "SITEID": s.siteid, "AGE": s.age, "AGEU": "YEARS",
                "SEX": s.sex, "RACE": s.race, "COUNTRY": s.country,
                "ARMCD": s.armcd, "ARM": s.arm, "ACTARMCD": s.armcd, "ACTARM": s.arm,
                "RFSTDTC": iso(s.rfstdtc), "RFENDTC": iso(s.rfendtc),
                "RFXSTDTC": iso(s.rfxstdtc), "RFXENDTC": iso(s.rfxendtc),
                "RFICDTC": iso(s.rficdtc), "RFPENDTC": iso(s.rfpendtc),
                "DTHDTC": "", "DTHFL": "", "ARMNRS": "", "ACTARMUD": "",
                "INVID": "", "INVNAM": "", "BRTHDTC": "",
                # SEND set/species (SETCD must be <= 8 chars)
                "SETCD": s.armcd, "SET": s.arm, "SPECIES": "RAT",
                "STRAIN": "WISTAR", "SBSTRAIN": "WISTAR", "AGETXT": "",
            }
            row = []
            for v in cols:
                if v.name in fixed:
                    row.append(fixed[v.name])
                elif v.core in ("Req", "Exp"):
                    row.append(self.vf.value(v, ctx))
                else:
                    row.append("")
            rows.append(row)
        return rows

    # ---- trial design + anchors ----
    # TSVCDREF (controlled-terminology reference) required for certain coded
    # parameters (FDA-SD2241..2266).
    _TS_VCDREF = {
        "CURTRT": "UNII", "TRT": "UNII", "PCLAS": "MED-RT",
        "FCNTRY": "ISO 3166", "TDIGRP": "SNOMED",
    }

    def build_ts(self) -> list[list]:
        cols = self.columns("TS")
        params = SEND_TS_PARAMS if self.study.standard == "sendig" else TS_PARAMS
        rows = []
        seq = 0
        for parmcd, val in params:
            ref = self._TS_VCDREF.get(parmcd, "")
            seq += 1
            vals = {
                "STUDYID": self.study.studyid, "DOMAIN": "TS", "TSSEQ": seq,
                "TSPARMCD": parmcd, "TSPARM": parmcd.title(), "TSVAL": val,
                "TSVCDREF": ref, "TSVCDVER": "2024-09-27" if ref else "",
                # coded value reference requires TSVALCD (CORE-DRAFT-900006);
                # unique per parameter (by seq) so TSVAL<->TSVALCD stays 1:1.
                "TSVALCD": f"C{1_000_000 + seq}" if ref else "",
            }
            rows.append([vals.get(c.name, "") for c in cols])
        return rows

    def build_ta(self) -> list[list]:
        cols = self.columns("TA")
        rows = []
        for ai, (armcd, arm, _trt, _dose) in enumerate(study_mod.ARMS):
            for order, (etcd, elem, epoch) in enumerate(ELEMENTS, start=1):
                vals = {
                    "STUDYID": self.study.studyid, "DOMAIN": "TA", "ARMCD": armcd,
                    "ARM": arm, "TAETORD": order, "ETCD": etcd, "ELEMENT": elem,
                    "EPOCH": epoch,
                }
                rows.append([vals.get(c.name, "") for c in cols])
        return rows

    def build_te(self) -> list[list]:
        cols = self.columns("TE")
        rows = []
        # distinct (TESTRL, TEENRL, TEDUR) per element (CORE-100163)
        rules = {
            "SCRN": ("Start of screening", "End of screening", "P14D"),
            "TRT": ("First dose", "Last dose", "P28D"),
            "FUP": ("Start of follow-up", "End of follow-up", "P7D"),
        }
        for etcd, elem, _epoch in ELEMENTS:
            srl, enrl, dur = rules.get(etcd, ("Start", "End", "P1D"))
            vals = {
                "STUDYID": self.study.studyid, "DOMAIN": "TE", "ETCD": etcd,
                "ELEMENT": elem, "TESTRL": srl, "TEENRL": enrl, "TEDUR": dur,
            }
            rows.append([vals.get(c.name, "") for c in cols])
        return rows

    def build_ti(self) -> list[list]:
        cols = self.columns("TI")
        rows = []
        crit = [("INCL01", "INCLUSION", "Age >= 18"), ("EXCL01", "EXCLUSION", "Pregnant")]
        for code, cat, txt in crit:
            vals = {
                "STUDYID": self.study.studyid, "DOMAIN": "TI", "IETESTCD": code,
                "IETEST": txt, "IECAT": cat,
            }
            rows.append([vals.get(c.name, "") for c in cols])
        return rows

    def build_tv(self) -> list[list]:
        cols = self.columns("TV")
        rows = []
        for vis in self.study.visits:
            vals = {
                "STUDYID": self.study.studyid, "DOMAIN": "TV", "VISITNUM": vis.num,
                "VISIT": vis.name, "VISITDY": vis.day, "ARMCD": "", "ARM": "",
                "TVSTRL": f"Start of visit {vis.name}",
            }
            rows.append([vals.get(c.name, "") for c in cols])
        return rows

    def build_se(self) -> list[list]:
        cols = self.columns("SE")
        rows = []
        for s in self.study.subjects:
            # Chain element boundaries: each element's end == next element's start
            # (CORE-000352) and start dates ascend with SESEQ (CORE-000535).
            bounds = [s.rficdtc, s.rfstdtc, s.rfxendtc, s.rfendtc]
            for order, (etcd, elem, epoch) in enumerate(ELEMENTS, start=1):
                start, end = bounds[order - 1], bounds[order]
                vals = {
                    "STUDYID": self.study.studyid, "DOMAIN": "SE", "USUBJID": s.usubjid,
                    "SESEQ": order, "ETCD": etcd, "ELEMENT": elem, "EPOCH": epoch,
                    "SESTDTC": iso(start), "SEENDTC": iso(end),
                    "TAETORD": order,
                }
                rows.append([vals.get(c.name, "") for c in cols])
        return rows

    def build_sv(self) -> list[list]:
        cols = self.columns("SV")
        rows = []
        for s in self.study.subjects:
            for vis in self.study.visits:
                d = s.visit_date(vis.day)
                vals = {
                    "STUDYID": self.study.studyid, "DOMAIN": "SV", "USUBJID": s.usubjid,
                    "VISITNUM": vis.num, "VISIT": vis.name, "VISITDY": vis.day,
                    "EPOCH": vis.epoch, "SVSTDTC": iso(d), "SVENDTC": iso(d),
                    "SVPRESP": "Y",  # pre-specified (planned) visit
                }
                rows.append([vals.get(c.name, "") for c in cols])
        return rows

    def build_ex(self) -> list[list]:
        cols = self.columns("EX")
        rows = []
        is_send = self.study.standard == "sendig"
        for s in self.study.subjects:
            for seq, vis in enumerate(self.study.treatment_visits(), start=1):
                d = s.visit_date(vis.day)
                vals = {
                    "STUDYID": self.study.studyid, "DOMAIN": "EX", "USUBJID": s.usubjid,
                    "EXSEQ": seq, "EXTRT": s.trt, "EXDOSE": s.dose, "EXDOSU": "mg",
                    "EXDOSFRM": "TABLET", "EXDOSFRQ": "QD", "EXROUTE": "ORAL",
                    "EXSTDTC": iso(d), "EXENDTC": iso(d),
                }
                if not is_send:  # SEND (nonclinical) has no visit/epoch structure
                    vals.update({"EPOCH": "TREATMENT", "VISIT": vis.name,
                                 "VISITNUM": vis.num})
                elif s.dose > 0:  # SEND: EXLOT required when a dose was given
                    vals["EXLOT"] = "LOT-001"  # FDA-SE2353
                rows.append([vals.get(c.name, "") for c in cols])
        return rows

    def build_tx(self) -> list[list]:
        """Trial Sets — one record per parameter per set (SEND).

        Defines a set per arm so DM.SETCD resolves in TX.SETCD, with the
        expected TX parameters (ARMCD/SPGRPCD/GRPLBL/TRTDOS/TRTDOSU).
        """
        cols = self.columns("TX")
        rows = []
        seq = 0
        for ai, (armcd, arm, _trt, dose) in enumerate(study_mod.ARMS):
            params = [
                ("ARMCD", armcd), ("SPGRPCD", f"SG{ai + 1}"), ("GRPLBL", arm),
                ("TRTDOS", str(dose)), ("TRTDOSU", "mg/kg"),
                ("PLANMSUB", "10"), ("PLANFSUB", "10"),
            ]
            if dose == 0:  # the placebo set is the control
                params.append(("TCNTRL", "PLACEBO"))
            for parmcd, val in params:
                seq += 1
                vals = {
                    "STUDYID": self.study.studyid, "DOMAIN": "TX", "SETCD": armcd,
                    "SET": arm, "TXSEQ": seq, "TXPARMCD": parmcd,
                    "TXPARM": parmcd.title(), "TXVAL": val,
                }
                rows.append([vals.get(c.name, "") for c in cols])
        return rows

    def build_td(self) -> list[list]:
        """Trial Disease Assessments — one row per planned assessment period."""
        cols = self.columns("TD")
        rows = []
        for i in range(2):
            vals = {
                "STUDYID": self.study.studyid, "DOMAIN": "TD", "TDORDER": i + 1,
                "TDANCVAR": "RFSTDTC", "TDSTOFF": "P0D", "TDTGTPAI": "P2W",
                "TDMINPAI": "P10D", "TDMAXPAI": "P18D", "TDNUMRPT": 6,
            }
            rows.append([vals.get(c.name, "") for c in cols])
        return rows

    # A single disease milestone type, referenced by SM (so MIDS has a home).
    _MIDSTYPE = "DIAGNOSIS"

    def build_tm(self) -> list[list]:
        """Trial Disease Milestones — one row per milestone type."""
        cols = self.columns("TM")
        vals = {
            "STUDYID": self.study.studyid, "DOMAIN": "TM", "MIDSTYPE": self._MIDSTYPE,
            "TMDEF": "Date of initial diagnosis", "TMRPT": "N",
        }
        return [[vals.get(c.name, "") for c in cols]]

    def build_sm(self) -> list[list]:
        """Subject Disease Milestones — one milestone per subject (links to TM)."""
        cols = self.columns("SM")
        rows = []
        for s in self.study.subjects:
            vals = {
                "STUDYID": self.study.studyid, "DOMAIN": "SM", "USUBJID": s.usubjid,
                "SMSEQ": 1, "MIDS": f"{self._MIDSTYPE} 1", "MIDSTYPE": self._MIDSTYPE,
                "SMSTDTC": iso(s.rficdtc), "SMENDTC": iso(s.rficdtc),
            }
            rows.append([vals.get(c.name, "") for c in cols])
        return rows

    def build_ds(self) -> list[list]:
        cols = self.columns("DS")
        rows = []
        for s in self.study.subjects:
            vals = {
                "STUDYID": self.study.studyid, "DOMAIN": "DS", "USUBJID": s.usubjid,
                "DSSEQ": 1, "DSTERM": "COMPLETED", "DSDECOD": "COMPLETED",
                "DSCAT": "DISPOSITION EVENT", "DSSTDTC": iso(s.rfendtc),
                "EPOCH": "FOLLOW-UP",
            }
            rows.append([vals.get(c.name, "") for c in cols])
        return rows

    # ---- dispatch ----
    _SPECIAL = {
        "DM": "build_dm", "TS": "build_ts", "TA": "build_ta", "TE": "build_te",
        "TI": "build_ti", "TV": "build_tv", "TD": "build_td", "TM": "build_tm",
        "TX": "build_tx", "SM": "build_sm", "SE": "build_se", "SV": "build_sv",
        "EX": "build_ex", "DS": "build_ds",
    }

    # Domains whose topic --TESTCD is constrained to a fixed value (SEND).
    _FIXED_TESTCD = {
        "BW": ("BW", "Body Weight"),
        "MA": ("GROSPATH", "Gross Pathology"),
    }

    def build_domain(self, domain: str) -> list[list]:
        special = self._SPECIAL.get(domain)
        if special:
            return getattr(self, special)()
        # Necropsy domains get one record per subject (all collected on the same
        # disposition date, so multiple rows would only differ by --SEQ).
        n = 1 if (self.study.standard == "sendig" and domain in POSTMORTEM_SEND) else 3
        return self.build_subject_level(domain, n)

    def _apply_study_days(self, domain: str, cols: list[Variable], rows: list[list]) -> None:
        """Fill --DY/--STDY/--ENDY from their dates for any domain (incl. specials)."""
        idx = {v.name: i for i, v in enumerate(cols)}
        if "USUBJID" not in idx:
            return
        pairs = [
            (f"{domain}DY", f"{domain}DTC"),
            (f"{domain}STDY", f"{domain}STDTC"),
            (f"{domain}ENDY", f"{domain}ENDTC"),
        ]
        pairs = [(dy, dt) for dy, dt in pairs if dy in idx and dt in idx]
        if not pairs:
            return
        uidx = idx["USUBJID"]
        for row in rows:
            subj = self.subj_by_id.get(row[uidx])
            if subj is None:
                continue
            for dy, dt in pairs:
                dval = row[idx[dt]]
                if dval and not row[idx[dy]]:
                    row[idx[dy]] = subj.study_day(_dt.date.fromisoformat(str(dval)[:10]))

    def _plan_permissible_drops(self, built: dict) -> dict[str, dict[str, str]]:
        """Per domain, the unpopulated ``Perm`` columns the drop must spare.

        Three sources, all derived rather than enumerated:

        1. :func:`copresence.forced_keep` — a rule in the lane's *shipped*
           packages whose verdict flips from false to true when the column goes.
           Both halves of a pair being droppable is the good case: the fixpoint
           drops them together and nothing fires.
        2. :func:`copresence.metadata_surface_rules` — a rule that judges a
           column's own name or label and names no variable, so the column is
           the rule's only occasion to fire (``CORE-000594``'s title-case check
           on ``IS.ISMSCBCE``). Plus :func:`has_anomalous_label`, which is the
           generator's model of ``GEN-VMCALM-LBL`` — a rule it cannot evaluate
           directly, because it compares the emitted label against the library
           label the generator copied it from, and the two differ only where the
           label fails to survive the round trip.
        3. :func:`copresence.count_coupled` — a variable a rule requires in
           several datasets once it appears in one (``--LNKID``, ``--LNKGRP``).
           Dropping it everywhere is safe; dropping it in all but one is not.
        """
        rules = copresence.hazard_rules(self.study.standard, self.study.version)
        meta_rules = copresence.metadata_surface_rules(
            self.study.standard, self.study.version)
        candidates: dict[str, list[str]] = {}
        keep: dict[str, dict[str, str]] = {}
        for domain, (cols, rows, keys) in built.items():
            cand = unpopulated_permissible(cols, rows, set(keys))
            candidates[domain] = cand
            if not cand:
                keep[domain] = {}
                continue
            forced = copresence.forced_keep(
                domain, {v.name for v in cols}, all_missing_columns(cols, rows),
                cand, rules,
            )
            for var in cols:
                if var.name not in cand:
                    continue
                for meta in meta_rules:
                    if copresence.metadata_hazard(meta, domain, var.name, var.label or ""):
                        forced.setdefault(var.name, meta.core_id)
                if has_anomalous_label(var):
                    forced.setdefault(var.name, "GEN-VMCALM-LBL")
            keep[domain] = forced

        for coupling in copresence.count_coupled(self.study.standard, self.study.version):
            carriers: dict[str, str] = {}
            retained: dict[str, bool] = {}
            for domain, (cols, _rows, _keys) in built.items():
                resolved = copresence.resolve_name(coupling.name, domain)
                if resolved is None or resolved not in {v.name for v in cols}:
                    continue
                carriers[domain] = resolved
                retained[domain] = (resolved not in candidates[domain]
                                    or resolved in keep[domain])
            for domain in copresence.resolve_count_coupling(coupling, retained):
                keep[domain][carriers[domain]] = ", ".join(coupling.core_ids)
        return keep

    def generate(self, out_dir: str) -> dict[str, int]:
        counts: dict[str, int] = {}
        datasets: dict[str, dict] = {}
        # Every domain is built first: the Permissible drop is decided across the
        # whole study, because a rule may couple one dataset's columns to another's.
        built: dict[str, tuple[list[Variable], list[list], list[str]]] = {}
        for domain in dom_mod.lane_domains(self.study.standard):
            if not self.lib.has_domain(domain):
                continue
            cols = self.columns(domain)
            rows = self.build_domain(domain)
            # Study days are derived first: a --DY/--STDY/--ENDY that gets a
            # value here must survive the Permissible drop below.
            self._apply_study_days(domain, cols, rows)
            keys = dom_mod.key_vars(self.lib, domain, {v.name for v in cols})
            built[domain] = (cols, rows, keys)

        keep_plan = self._plan_permissible_drops(built)
        for domain, (cols, rows, keys) in built.items():
            # FDA-SD1078: ship a Permissible variable only when the study
            # populates it (keys and co-presence partners excepted).
            # See drop_unpopulated_permissible and copresence.
            keep = keep_plan.get(domain, {})
            cols, rows, dropped = drop_unpopulated_permissible(
                cols, rows, set(keys), keep=set(keep),
            )
            if dropped:
                self.dropped_permissible[domain] = dropped
            if keep:
                self.kept_permissible[domain] = dict(keep)
            label = self._label(domain)
            ds = emit.build_dataset(domain, label, cols, rows, keys, self.study.studyid)
            emit.write_dataset(out_dir, domain, ds)
            datasets[domain] = ds
            counts[domain] = len(rows)
        # Phase 5: a full Define-XML 2.1 matching the generated data, so the engine's
        # define-metadata rules execute (instead of being SKIPPED) via -dxp.
        define.write_define(
            out_dir, self.lib, self.study.studyid, self.study.standard,
            self.study.version, datasets,
        )
        return counts

    def _label(self, domain: str) -> str:
        return self.lib.dataset_label(domain) or domain


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--standard", required=True, choices=["sdtmig", "sendig"])
    ap.add_argument("--version", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--subjects", type=int, default=20)
    ap.add_argument("--visits", type=int, default=10)
    ap.add_argument("--seed", type=int, default=0)
    library.add_cache_dir_argument(ap)
    args = ap.parse_args()

    library.set_cache_dir(args.cache_dir)

    spec = SPECS.get((args.standard, args.version))
    if spec is None:
        raise SystemExit(f"no library spec for {args.standard}/{args.version}")
    # The pickle cache has no default; without one this stops here, naming the
    # flag and the environment variable, rather than dying on the first open().
    try:
        lib = Library(spec)
    except library.CacheDirNotConfigured as exc:
        raise SystemExit(str(exc)) from exc
    study = study_mod.build_study(
        args.standard, args.version, n_subjects=args.subjects,
        n_visits=args.visits, seed=args.seed,
    )
    gen = Generator(lib, study)
    counts = gen.generate(args.out)
    print(f"generated {len(counts)} domains into {args.out}")
    for d, n in counts.items():
        print(f"  {d:8s} {n} rows")


if __name__ == "__main__":
    main()
