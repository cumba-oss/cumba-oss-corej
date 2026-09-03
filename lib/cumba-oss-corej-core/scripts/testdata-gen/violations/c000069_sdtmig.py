"""CORE-000069 (SDTMIG): SPECIES must not be present in DM.

Variable-presence-negative: SPECIES is a SEND DM variable, forbidden in SDTMIG.
"""

META = {
    "coreId": "CORE-000069",
    "standard": "sdtmig",
    "domain": "DM",
    "summary": "add forbidden SPECIES column to DM (variable-presence-negative)",
    # CORE-000174 is a duplicate rule ("SPECIES present in DM"); it co-fires.
    "allowedCollateral": ["GEN-DISALLOW-DM", "CORE-000852", "FDA-SD1079", "CORE-000174"],
}


def inject(study):
    idx = study.find_row("DM", lambda r: True)
    study.add_column("DM", "SPECIES", "Species", "string", {idx: "RAT"})
    return {"domain": "DM", "variable": "SPECIES", "expect_status": "ISSUE_REPORTED"}
