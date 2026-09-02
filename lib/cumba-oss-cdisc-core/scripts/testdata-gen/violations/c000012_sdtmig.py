"""CORE-000012 (SDTMIG): AEOCCUR must not be present in AE."""

META = {
    "coreId": "CORE-000012",
    "standard": "sdtmig",
    "domain": "AE",
    "summary": "add forbidden AEOCCUR column to AE (variable-presence-negative)",
    # Presence family (disallowed variable + column order) plus FDA-SD0041:
    # AEOCCUR populated on a non-solicited record (AEPRESP != 'Y') is itself a
    # finding — a direct consequence of injecting the forbidden value.
    "allowedCollateral": ["GEN-DISALLOW-AE", "CORE-000852", "FDA-SD1079", "FDA-SD0041"],
}


def inject(study):
    idx = study.find_row("AE", lambda r: True)
    study.add_column("AE", "AEOCCUR", "AE Occurrence", "string", {idx: "Y"})
    return {"domain": "AE", "variable": "AEOCCUR", "expect_status": "ISSUE_REPORTED"}
