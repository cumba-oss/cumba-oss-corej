"""CORE-000086 (SDTMIG): DVSTDTC earlier than the subject's DM.RFICDTC.

Cross-dataset date violation: set one DV record's start date to a date before the
informed-consent date (RFICDTC) of the same subject in DM.
"""

import datetime as _dt

_EARLY = "2000-01-01"

META = {
    "coreId": "CORE-000086",
    "standard": "sdtmig",
    "domain": "DV",
    "summary": "set DV.DVSTDTC to a date earlier than the subject's DM.RFICDTC",
    # FDA-SD1319 is the exact twin (DS/DV --STDTC earlier than DM.RFICDTC).
    "allowedCollateral": ["FDA-SD1319"],
}


def _study_day(ref: str, date: str) -> int:
    """SDTM study day for ``date`` relative to reference start ``ref`` (no day 0)."""
    diff = (_dt.date.fromisoformat(date) - _dt.date.fromisoformat(ref)).days
    return diff + 1 if diff >= 0 else diff


def inject(study):
    # Subject -> (RFICDTC, RFSTDTC) from DM (need RFSTDTC to keep --STDY coherent).
    dm = {}
    for i in range(study.n_rows("DM")):
        r = study.row_view("DM", i)
        if str(r.get("RFICDTC", "")) != "" and str(r.get("RFSTDTC", "")) != "":
            dm[r["USUBJID"]] = (r["RFICDTC"], r["RFSTDTC"])
    idx = study.find_row(
        "DV", lambda r: r.get("USUBJID") in dm and str(r.get("DVSTDTC", "")) != ""
    )
    usubjid = study.row_view("DV", idx)["USUBJID"]
    _ric, rfst = dm[usubjid]
    # Set the start date years before consent, and recompute DVSTDY for the new
    # date so the only new violation is the date-before-consent rule (leaving a
    # stale or empty --STDY would trip the study-day consistency/missing rules).
    study.set_cell("DV", idx, "DVSTDTC", _EARLY)
    if "DVSTDY" in study.col_names("DV"):
        study.set_cell("DV", idx, "DVSTDY", _study_day(rfst, _EARLY))
    return {"domain": "DV", "variable": "DVSTDTC", "expect_status": "ISSUE_REPORTED"}
