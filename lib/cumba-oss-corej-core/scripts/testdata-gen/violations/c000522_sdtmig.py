"""CORE-000522 (SDTMIG): DSCAT must not be empty.

Empty-violation on a standalone categorical variable (no timing companion).
"""

META = {
    "coreId": "CORE-000522",
    "standard": "sdtmig",
    "domain": "DS",
    "summary": "blank DS.DSCAT on one record (DSCAT empty)",
    # FDA-SD1035 is the exact twin ("DSCAT not populated").
    "allowedCollateral": ["FDA-SD1035"],
}


def inject(study):
    idx = study.find_row("DS", lambda r: str(r.get("DSCAT", "")) != "")
    study.set_cell("DS", idx, "DSCAT", "")
    return {"domain": "DS", "variable": "DSCAT", "expect_status": "ISSUE_REPORTED"}
