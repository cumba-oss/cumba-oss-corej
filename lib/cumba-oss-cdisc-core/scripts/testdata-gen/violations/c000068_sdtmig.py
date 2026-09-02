"""CORE-000068 (SDTMIG): AGETXT must not be present in DM.

Presence-negative *variable*: ``AGETXT`` is a valid SENDIG/DART DM variable but
is forbidden in an SDTMIG DM. The clean SDTMIG study omits it by construction;
this injector adds the forbidden column with one populated cell.
"""

META = {
    "coreId": "CORE-000068",
    "standard": "sdtmig",
    "domain": "DM",
    "summary": "add forbidden AGETXT column to DM (variable-presence-negative)",
    # Adding any non-IG variable inherently trips the engine's disallowed-variable
    # check and the observation-class column-order rules; FDA-SD2020 fires because
    # the same subject already carries AGE (only one of AGE/AGETXT is allowed).
    # The value "85-89" satisfies the AGETXT number-number format (FDA-SD2019), so
    # the only collateral is this irreducible "forbidden variable present" family.
    "allowedCollateral": ["GEN-DISALLOW-DM", "CORE-000852", "FDA-SD1079", "FDA-SD2020"],
}


def inject(study):
    idx = study.find_row("DM", lambda r: True)
    study.add_column("DM", "AGETXT", "Age Text", "string", {idx: "85-89"})
    return {"domain": "DM", "variable": "AGETXT", "expect_status": "ISSUE_REPORTED"}
