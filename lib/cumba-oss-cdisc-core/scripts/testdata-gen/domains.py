"""Domain registry and key/topic derivation for a standard lane.

The variable *list* for every domain comes from ``library.py`` (the engine
cache); this module only supplies what the metadata does not give directly:

* which domains a lane's clean study should contain (the missing target domains
  plus the trial-design / partner / anchor domains needed for cross-dataset
  coherence), and
* per-domain **key** and **topic** variables, derived from variable roles and
  SDTM naming conventions.
"""

from __future__ import annotations

from library import Library

# ---------------------------------------------------------------------------
# Which domains each lane's clean study generates.
# ---------------------------------------------------------------------------

# The SDTM clinical domains missing from the existing test studies (Phase 1
# target). Class is resolved from the library by name.
SDTMIG_MISSING = [
    "CO", "PC", "PP", "MI", "EG", "TR", "SS", "CV", "RE", "SM", "MB", "MS",
    "DV", "OI", "CE", "PE", "AG", "SU", "BS", "CP", "GF", "IS", "TD",
]

# Anchor / partner / trial-design domains needed so the missing domains'
# cross-dataset rules resolve (subject backbone, arms, epochs, visits,
# disposition, exposure, subject elements). DM first — it is the subject source.
# AE/LB/VS/CM/MH are common domains that FDA presence rules expect.
SDTMIG_ANCHORS = [
    "DM", "TS", "TA", "TE", "TI", "TV", "TM", "SE", "SV", "EX", "DS",
    "AE", "LB", "VS", "CM", "MH",
]

# SEND-specific domains for the SENDIG lane (Phase 2). Shared domains (CO PC PP
# EG …) are added from the SENDIG registry too, with SEND variable sets.
SENDIG_MISSING = ["BW", "BG", "CL", "FW", "MA", "OM", "PM", "TF", "TX"]
# POOLDEF is omitted: nothing in the generated SEND study pools subjects (EX uses
# USUBJID), and a generic POOLDEF would duplicate (USUBJID, POOLID) rows.
SENDIG_ANCHORS = ["DM", "TS", "TA", "TE", "TX", "SE", "EX", "DS"]

# Domains that must never appear in an SDTMIG clean study (presence-negative):
# valid SENDIG-DART domains flagged on presence by SDTM rules. Injector-only.
SDTMIG_FORBIDDEN_DOMAINS = {"TP", "TT", "SJ"}


def lane_domains(standard: str) -> list[str]:
    """Ordered, de-duplicated domain list for a lane's clean study."""
    if standard == "sdtmig":
        seq = SDTMIG_ANCHORS + SDTMIG_MISSING
    elif standard == "sendig":
        seq = SENDIG_ANCHORS + SENDIG_MISSING
    else:
        raise ValueError(f"unknown standard {standard!r}")
    seen: dict[str, None] = {}
    for d in seq:
        seen.setdefault(d, None)
    return list(seen)


# ---------------------------------------------------------------------------
# Key / topic derivation.
# ---------------------------------------------------------------------------


def topic_var(lib: Library, domain: str) -> str | None:
    """The domain's Topic variable (role == 'Topic'), if any."""
    for v in lib.variables(domain):
        if v.role == "Topic":
            return v.name
    return None


def seq_var(domain: str, names: set[str]) -> str | None:
    """The ``--SEQ`` sequence variable for the domain, if present."""
    cand = f"{domain}SEQ"
    if cand in names:
        return cand
    return next((n for n in names if n.endswith("SEQ")), None)


def key_vars(lib: Library, domain: str, names: set[str]) -> list[str]:
    """Best-effort ordered key for a domain, from the variables present.

    Subject-level: STUDYID, USUBJID, --SEQ. Trial-design (no USUBJID):
    STUDYID, topic/--SEQ. Falls back to STUDYID + topic.
    """
    keys: list[str] = []
    if "STUDYID" in names:
        keys.append("STUDYID")
    if "USUBJID" in names:
        keys.append("USUBJID")
    seq = seq_var(domain, names)
    topic = topic_var(lib, domain)
    if "USUBJID" in names:
        if seq:
            keys.append(seq)
    else:
        # trial design / reference: key by topic then seq
        if topic and topic not in keys:
            keys.append(topic)
        if seq and seq not in keys:
            keys.append(seq)
    if len(keys) <= 1 and topic and topic not in keys:
        keys.append(topic)
    return keys
