"""CORE-000003 (SDTMIG): TRLOC must not be present in TR.

Variable-presence-negative: TRLOC is not in the SDTMIG TR definition.
"""

META = {
    "coreId": "CORE-000003",
    "standard": "sdtmig",
    "domain": "TR",
    "summary": "add forbidden TRLOC column to TR (variable-presence-negative)",
    "allowedCollateral": ["GEN-DISALLOW-TR", "CORE-000852", "FDA-SD1079"],
}


def inject(study):
    idx = study.find_row("TR", lambda r: True)
    study.add_column("TR", "TRLOC", "Location Used for the Measurement", "string", {idx: "LIVER"})
    return {"domain": "TR", "variable": "TRLOC", "expect_status": "ISSUE_REPORTED"}
