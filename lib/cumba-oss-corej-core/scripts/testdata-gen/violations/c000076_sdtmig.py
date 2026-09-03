"""CORE-000076 (SDTMIG): TRPORTOT must not be present in TR."""

META = {
    "coreId": "CORE-000076",
    "standard": "sdtmig",
    "domain": "TR",
    "summary": "add forbidden TRPORTOT column to TR (variable-presence-negative)",
    # Presence family, plus the --PORTOT-without---LOC rules (CORE-000096 /
    # FDA-SD1284): TRPORTOT exists while TRLOC does not, which is itself flagged.
    "allowedCollateral": [
        "GEN-DISALLOW-TR",
        "CORE-000852",
        "FDA-SD1079",
        "CORE-000096",
        "FDA-SD1284",
    ],
}


def inject(study):
    idx = study.find_row("TR", lambda r: True)
    study.add_column("TR", "TRPORTOT", "Portion or Totality", "string", {idx: "PORTION"})
    return {"domain": "TR", "variable": "TRPORTOT", "expect_status": "ISSUE_REPORTED"}
