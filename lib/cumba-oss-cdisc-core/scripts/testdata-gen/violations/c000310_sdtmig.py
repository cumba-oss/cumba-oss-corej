"""CORE-000310 (SDTMIG): AGE must not be less than 0.

Numeric-range violation: set one subject's DM ``AGE`` to a negative value. AGE is
not cross-referenced by any other DM rule, so the mutation is surgical.
"""

META = {
    "coreId": "CORE-000310",
    "standard": "sdtmig",
    "domain": "DM",
    "summary": "set DM.AGE to a negative value (AGE less_than 0)",
    # FDA-SD0084 ("AGE <= 0") is an exact twin: any value < 0 is also <= 0.
    "allowedCollateral": ["FDA-SD0084"],
}


def inject(study):
    idx = study.find_row("DM", lambda r: str(r.get("AGE", "")) != "")
    study.set_cell("DM", idx, "AGE", -1)
    return {"domain": "DM", "variable": "AGE", "expect_status": "ISSUE_REPORTED"}
