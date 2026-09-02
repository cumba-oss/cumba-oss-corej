"""CORE-000109 (SDTMIG): SMSTDTC must not be empty.

Empty-violation: blank one SM record's ``SMSTDTC`` (which the clean study
populates). A single isolated row is blanked.
"""

META = {
    "coreId": "CORE-000109",
    "standard": "sdtmig",
    "domain": "SM",
    "summary": "blank SM.SMSTDTC (and its timing companions) on one record",
    # FDA-SD1368 is the exact twin ("SMSTDTC is empty").
    "allowedCollateral": ["FDA-SD1368"],
}


def inject(study):
    # Clear the whole timing group on one row so the only violation is the empty
    # start date: leaving SMENDTC populated would additionally trip FDA-SD0031
    # (end timepoint without start), and a populated SMSTDY/SMENDY would trip the
    # study-day-without-date rules.
    idx = study.find_row("SM", lambda r: str(r.get("SMSTDTC", "")) != "")
    for col in ("SMSTDTC", "SMENDTC", "SMSTDY", "SMENDY"):
        if col in study.col_names("SM"):
            study.set_cell("SM", idx, col, "")
    return {"domain": "SM", "variable": "SMSTDTC", "expect_status": "ISSUE_REPORTED"}
