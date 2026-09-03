"""Write a domain table as a Dataset-JSON 1.1 file.

Mirrors the shape of the existing test studies (DataExchange). Critically,
every file includes ``datasetJSONCreationDateTime`` and ``datasetJSONVersion``
— without them the engine reports a ``CUMBA-DATASET-LOAD`` finding and the
dataset never loads (confirmed Phase 0).
"""

from __future__ import annotations

import json
import os
import re

from library import Variable

# Some library labels carry literal escape junk (e.g. GF.GFSEQID =
# "Sequence Identifier \\n"); strip such sequences and collapse whitespace.
_ESCAPE_JUNK = re.compile(r"\\[a-zA-Z]")


def _clean_label(text: str) -> str:
    return " ".join(_ESCAPE_JUNK.sub(" ", text).split())

# A fixed timestamp keeps regenerated studies byte-stable (the generator is
# deterministic; see also the ``--seed`` flag).
_CREATION_DT = "2026-06-29T00:00:00"


def _data_type(var: Variable) -> str:
    """Dataset-JSON dataType for a variable."""
    if var.name.endswith("DTC"):
        return "date"
    if var.datatype == "Num":
        return "integer"
    return "string"


def build_dataset(
    domain: str,
    label: str,
    variables: list[Variable],
    rows: list[list],
    key_names: list[str],
    study_oid: str = "SYNTH01",
) -> dict:
    """Assemble the Dataset-JSON object for one domain."""
    key_seq = {name: i + 1 for i, name in enumerate(key_names)}
    columns = []
    for var in variables:
        # Normalize whitespace in labels: some library labels carry stray
        # newlines (e.g. GF.GFSEQID = "Sequence Identifier \n"), which fail the
        # title-case check. Collapsing to single spaces yields the clean label.
        col_label = _clean_label(var.label or var.name)
        col = {
            "itemOID": f"IT.{domain}.{var.name}",
            "name": var.name,
            "label": col_label,
            "dataType": _data_type(var),
        }
        if var.name in key_seq:
            col["keySequence"] = key_seq[var.name]
        columns.append(col)
    return {
        "datasetJSONCreationDateTime": _CREATION_DT,
        "datasetJSONVersion": "1.1.0",
        "studyOID": study_oid,
        "metaDataRef": "define.xml",
        "itemGroupOID": f"IG.{domain}",
        "records": len(rows),
        "name": domain,
        "label": label,
        "columns": columns,
        "rows": rows,
    }


def write_dataset(out_dir: str, domain: str, dataset: dict) -> str:
    """Write ``<domain>.json`` (lowercase) into ``out_dir``; return the path."""
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, f"{domain.lower()}.json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(dataset, fh, indent=1)
    return path
