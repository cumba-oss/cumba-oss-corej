"""CORE-000310 (SENDIG): AGE must not be less than 0.

Same rule as the SDTMIG lane, applied to the SENDIG DM. Only injected when AGE is
populated in the clean study.
"""

META = {
    "coreId": "CORE-000310",
    "standard": "sendig",
    "domain": "DM",
    "summary": "set DM.AGE to a negative value (AGE less_than 0)",
    "allowedCollateral": ["FDA-SD0084"],
}


def inject(study):
    idx = study.find_row("DM", lambda r: str(r.get("AGE", "")) != "")
    study.set_cell("DM", idx, "AGE", -1)
    return {"domain": "DM", "variable": "AGE", "expect_status": "ISSUE_REPORTED"}
