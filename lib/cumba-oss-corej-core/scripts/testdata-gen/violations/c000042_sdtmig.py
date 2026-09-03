"""CORE-000042 (SDTMIG): TT dataset must not be present.

Presence-negative domain (DART), same shape as CORE-000043/TP.
"""

from violations import lib as vlib

META = {
    "coreId": "CORE-000042",
    "standard": "sdtmig",
    "domain": "TT",
    "summary": "add forbidden TT (DART) dataset (domain-presence-negative)",
}


def inject(study):
    study.add_dataset("TT", vlib.build_presence_dataset("TT"))
    return {"domain": "TT", "variable": None, "expect_status": "ISSUE_REPORTED"}
