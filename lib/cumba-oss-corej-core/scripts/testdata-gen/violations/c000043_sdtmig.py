"""CORE-000043 (SDTMIG): TP dataset must not be present.

Presence-negative *domain*: ``TP`` is a valid SENDIG-DART domain, forbidden in
an SDTMIG submission. The clean SDTMIG study omits it; this injector adds a
structurally-valid 1-row TP dataset built from the DART library metadata.
"""

from violations import lib as vlib

META = {
    "coreId": "CORE-000043",
    "standard": "sdtmig",
    "domain": "TP",
    "summary": "add forbidden TP (DART) dataset (domain-presence-negative)",
}


def inject(study):
    study.add_dataset("TP", vlib.build_presence_dataset("TP"))
    return {"domain": "TP", "variable": None, "expect_status": "ISSUE_REPORTED"}
