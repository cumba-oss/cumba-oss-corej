"""CORE-000048 (SDTMIG): --METHOD must not be present.

Variable-presence-negative: ``EXMETHOD`` (Method of Administration) is not used in
human clinical trials and is absent from the SDTMIG EX definition. Adding the
column trips the generic ``--METHOD must not be present`` rule (CORE-000048).

(The EX-specific CORE-000326 covers the same condition but is not part of the
active rules corpus, so CORE-000048 is the rule actually exercised here.)
"""

META = {
    "coreId": "CORE-000048",
    "standard": "sdtmig",
    "domain": "EX",
    "summary": "add forbidden EXMETHOD column to EX (variable-presence-negative)",
    "allowedCollateral": ["GEN-DISALLOW-EX", "CORE-000852", "FDA-SD1079"],
}


def inject(study):
    idx = study.find_row("EX", lambda r: True)
    study.add_column("EX", "EXMETHOD", "Method of Administration", "string", {idx: "INJECTION"})
    return {"domain": "EX", "variable": "EXMETHOD", "expect_status": "ISSUE_REPORTED"}
