"""CORE-000779 (SDTMIG): TDSTOFF not equal to '0' and not a valid ISO-8601 duration.

Format-violation: set one TD record's TDSTOFF to a non-duration string.
"""

META = {
    "coreId": "CORE-000779",
    "standard": "sdtmig",
    "domain": "TD",
    "summary": "set TD.TDSTOFF to an invalid (non ISO-8601 duration) value",
    # FDA-SD1011 (generic duration-format) and FDA-SD1301 (TDSTOFF-specific) are
    # exact twins checking the same ISO-8601 duration constraint.
    "allowedCollateral": ["FDA-SD1011", "FDA-SD1301"],
}


def inject(study):
    idx = study.find_row("TD", lambda r: True)
    study.set_cell("TD", idx, "TDSTOFF", "5 days")
    return {"domain": "TD", "variable": "TDSTOFF", "expect_status": "ISSUE_REPORTED"}
