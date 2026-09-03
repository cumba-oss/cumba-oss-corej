"""CORE-000044 (SDTMIG): SJ dataset must not be present.

Presence-negative domain (DART), subject-level (carries USUBJID + SJSEQ).
"""

from violations import lib as vlib

META = {
    "coreId": "CORE-000044",
    "standard": "sdtmig",
    "domain": "SJ",
    "summary": "add forbidden SJ (DART) dataset (domain-presence-negative)",
}


def inject(study):
    study.add_dataset("SJ", vlib.build_presence_dataset("SJ"))
    return {"domain": "SJ", "variable": None, "expect_status": "ISSUE_REPORTED"}
