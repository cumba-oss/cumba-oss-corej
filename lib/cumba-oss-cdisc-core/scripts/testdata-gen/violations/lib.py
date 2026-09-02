"""Shared helpers for the per-rule violation injectors.

An *injector* takes a clean study (parsed Dataset-JSON per domain), applies the
**minimal single mutation** that trips exactly one CORE rule, and records what it
changed. The driver (``apply_violations.py``) copies the clean study into a
sub-study folder, runs the injector, and writes the mutated Dataset-JSON files
plus an ``expectation.json``.

Design rules:

* The clean study is **never** mutated in place — the driver loads a fresh copy
  per injector.
* Every mutation a ``Study`` performs is recorded in ``Study.changes`` so the
  expectation (and the unit tests) can assert that exactly the claimed cells /
  columns / datasets changed and nothing else.
* Mutations are deterministic (no RNG / ``hash()``).
"""

from __future__ import annotations

import json
import os
import shutil
from dataclasses import dataclass, field
from typing import Callable

# The generator package modules live one directory up; importing them lets the
# presence injectors build structurally-valid DART datasets from the same
# library cache the engine validates against.
import emit  # noqa: E402  (sibling module, added to sys.path by the driver/tests)
import library  # noqa: E402


_CREATION_DT = "2026-06-29T00:00:00"


# ---------------------------------------------------------------------------
# Study: a mutable, change-tracking view over a clean study.
# ---------------------------------------------------------------------------
@dataclass
class Study:
    """A parsed clean study: ``{DOMAIN: dataset-json dict}`` + a change log."""

    datasets: dict[str, dict]
    changes: list[dict] = field(default_factory=list)

    # ---- column / row access ------------------------------------------------
    def has_domain(self, domain: str) -> bool:
        return domain.upper() in self.datasets

    def _ds(self, domain: str) -> dict:
        return self.datasets[domain.upper()]

    def col_names(self, domain: str) -> list[str]:
        return [c["name"] for c in self._ds(domain)["columns"]]

    def col_index(self, domain: str, col: str) -> int:
        names = self.col_names(domain)
        try:
            return names.index(col)
        except ValueError as exc:  # pragma: no cover - guards injector typos
            raise KeyError(f"{col} not in {domain}: {names}") from exc

    def n_rows(self, domain: str) -> int:
        return len(self._ds(domain)["rows"])

    def row_view(self, domain: str, idx: int) -> dict:
        """Read-only ``{column: value}`` dict for one row."""
        return dict(zip(self.col_names(domain), self._ds(domain)["rows"][idx]))

    def key_columns(self, domain: str) -> list[str]:
        cols = self._ds(domain)["columns"]
        keyed = [c for c in cols if c.get("keySequence")]
        keyed.sort(key=lambda c: c["keySequence"])
        return [c["name"] for c in keyed]

    def row_key(self, domain: str, idx: int) -> dict:
        view = self.row_view(domain, idx)
        return {k: view.get(k, "") for k in self.key_columns(domain)}

    # ---- predicates ---------------------------------------------------------
    def find_row(self, domain: str, predicate: Callable[[dict], bool]) -> int:
        """Index of the first row whose ``row_view`` satisfies ``predicate``.

        Raises ``LookupError`` when no row matches (an injector that cannot find
        a clean anchor row is a bug, not a silent no-op).
        """
        ds = self._ds(domain)
        names = self.col_names(domain)
        for i, row in enumerate(ds["rows"]):
            if predicate(dict(zip(names, row))):
                return i
        raise LookupError(f"no row in {domain} matches predicate")

    # ---- mutations (each logged in self.changes) ----------------------------
    def set_cell(self, domain: str, idx: int, col: str, value) -> None:
        ci = self.col_index(domain, col)
        row = self._ds(domain)["rows"][idx]
        old = row[ci]
        row[ci] = value
        self.changes.append(
            {
                "type": "cell",
                "domain": domain.upper(),
                "variable": col,
                "row": idx,
                "row_key": self.row_key(domain, idx),
                "old": old,
                "new": value,
            }
        )

    def add_column(
        self,
        domain: str,
        name: str,
        label: str,
        data_type: str,
        values_by_idx: dict[int, object],
        default: object = "",
        after: str | None = None,
    ) -> None:
        """Add a new column; populate only ``values_by_idx``.

        ``after`` names an **existing** column the new one is inserted directly
        behind; without it the column is appended last.

        **Pass ``after`` whenever the column belongs to the standard.** The three
        column-order rules that ship on both lanes — ``CORE-000852`` and
        ``FDA-SD1079`` (``is_ordered_subset_of($column_order_from_dataset,
        $model_column_order)``) plus ``CDISC-SEND-0048`` on the SEND lane
        (``$column_order_from_library``) — pass on the clean study only because
        the generator emits columns in library ordinal order. Appending a column
        that the standard places in the middle breaks the ordered-subset property
        and makes all three fire as collateral on an injector that meant to trip
        exactly one rule. Measured on ``CORE-100099`` (SENDIG CL, re-introducing
        the dropped Permissible ``CLSEV``): appended → not an ordered subset,
        3 collateral ids; inserted after ``CLLOC`` → ordered subset restored, 0.

        Leave ``after`` unset only when the column is *meant* to be foreign to the
        domain — the ``GEN-DISALLOW-*`` injectors, which already declare the
        order rules in their ``allowedCollateral`` because a variable outside the
        model has no correct position.
        """
        ds = self._ds(domain)
        if name in self.col_names(domain):  # pragma: no cover - injector typo guard
            raise ValueError(f"{name} already present in {domain}")
        # col_index raises KeyError naming the domain's columns when `after` is
        # absent — a loud failure, so an anchor that the generator later stops
        # shipping cannot silently degrade back to an append.
        pos = len(ds["columns"]) if after is None else self.col_index(domain, after) + 1
        ds["columns"].insert(
            pos,
            {
                "itemOID": f"IT.{domain.upper()}.{name}",
                "name": name,
                "label": label,
                "dataType": data_type,
            },
        )
        for row in ds["rows"]:
            row.insert(pos, default)
        for idx, val in values_by_idx.items():
            ds["rows"][idx][pos] = val
        self.changes.append(
            {
                "type": "add_column",
                "domain": domain.upper(),
                "variable": name,
                "values_by_idx": {str(k): v for k, v in values_by_idx.items()},
            }
        )

    def add_dataset(self, domain: str, dataset: dict) -> None:
        self.datasets[domain.upper()] = dataset
        self.changes.append(
            {"type": "add_dataset", "domain": domain.upper(), "records": dataset["records"]}
        )


# ---------------------------------------------------------------------------
# Loading / writing studies.
# ---------------------------------------------------------------------------
def load_clean(clean_dir: str) -> Study:
    """Load every ``*.json`` dataset under ``clean_dir`` into a fresh Study."""
    datasets: dict[str, dict] = {}
    for fname in sorted(os.listdir(clean_dir)):
        if not fname.endswith(".json") or fname == "expectation.json":
            continue
        path = os.path.join(clean_dir, fname)
        with open(path, encoding="utf-8") as fh:
            ds = json.load(fh)
        name = ds.get("name")
        if name:
            datasets[name.upper()] = ds
    if not datasets:
        raise FileNotFoundError(f"no Dataset-JSON files found in {clean_dir}")
    return Study(datasets=datasets)


def write_substudy(out_dir: str, study: Study, expectation: dict, clean_dir: str) -> None:
    """Write all datasets + ``expectation.json`` into ``out_dir``.

    ``define.xml`` is copied from ``clean_dir`` when present so the sub-study is
    self-contained (Phase 5 define generation is independent of this phase).
    """
    if os.path.isdir(out_dir):
        shutil.rmtree(out_dir)
    os.makedirs(out_dir, exist_ok=True)
    for name, ds in sorted(study.datasets.items()):
        path = os.path.join(out_dir, f"{name.lower()}.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(ds, fh, indent=1)
    define = os.path.join(clean_dir, "define.xml")
    if os.path.isfile(define):
        shutil.copy2(define, os.path.join(out_dir, "define.xml"))
    with open(os.path.join(out_dir, "expectation.json"), "w", encoding="utf-8") as fh:
        json.dump(expectation, fh, indent=1)


# ---------------------------------------------------------------------------
# Presence-dataset builder (for Domain Presence Check injectors).
# ---------------------------------------------------------------------------
# DART (SENDIG reproductive) is the home standard of the SDTMIG presence-negative
# domains TP / TT / SJ; build them structurally-valid from that lane's metadata.
_DART_SPEC = library.StandardSpec("sendig", "dart-1-1", "sendct-2024-09-27")


def _presence_value(var: library.Variable, lib: library.Library, domain: str) -> object:
    """A minimal valid value for a Req/Exp variable in a presence dataset."""
    name = var.name
    if name == "STUDYID":
        return "SYNTH01"
    if name == "DOMAIN":
        return domain
    if name == "USUBJID":
        return "SYNTH01-001"
    if name.endswith("SEQ"):
        return 1
    if name.endswith("DTC"):
        return "2026-01-01"
    terms, _ext = lib.codelist_terms(var.codelist_codes)
    if terms:
        return terms[0]
    if var.datatype == "Num":
        return 1
    # Short readable token derived from the label's initials; deterministic and
    # human-readable (these domains carry no other rules, so any valid value is
    # acceptable — but keep it non-empty for Req/Exp).
    initials = "".join(w[0] for w in (var.label or name).split() if w[0].isalpha())
    return (initials[:8] or name[:8]).upper()


def build_presence_dataset(domain: str, cache_dir: str | None = None) -> dict:
    """A minimal structurally-valid 1-row Dataset-JSON for a DART domain.

    A ``Domain Presence Check`` fires on the *existence* of the dataset, not its
    contents, so this carries only the universally-valid identifier columns
    (``STUDYID``, ``DOMAIN``, and ``USUBJID``/``--SEQ`` for subject-level
    domains). The DART-specific variables (e.g. ``RPHASE``, ``RPATHCD``,
    ``RSTGCD``) are deliberately **omitted** — several are themselves
    presence-negative under ``ALL``-scoped SDTMIG rules (e.g. CORE-000058
    "RPHASE must not be present"), and including them would add collateral
    findings beyond the one presence rule under test.
    """
    lib = library.Library(_DART_SPEC, cache_dir=cache_dir)
    by_name = {v.name: v for v in lib.variables(domain)}
    seq = next((n for n in by_name if n.endswith("SEQ")), None)
    wanted = ["STUDYID", "DOMAIN"]
    if "USUBJID" in by_name:
        wanted.append("USUBJID")
        if seq:
            wanted.append(seq)
    variables = [by_name[n] for n in wanted if n in by_name]
    key_names = [n for n in ("STUDYID", "USUBJID", seq) if n and n in wanted]
    row = [_presence_value(by_name[n], lib, domain) for n in wanted if n in by_name]
    label = lib.dataset_label(domain) or domain
    return emit.build_dataset(domain, label, variables, [row], key_names)
