"""Read the engine's own pickle cache as the metadata source.

This is the same ``-pc`` cache the engine validates against
(``/data/cdisc.metadata.library-cache-pkl``), so generating from it guarantees
the synthetic data matches what the engine expects.

Files used:

* ``variables_metadata.pkl`` — ``{"library_variables_metadata/<std>/<ver>":
  {DOMAIN: {VAR: record}}}`` where each record carries ``name, label,
  simpleDatatype, core, role, ordinal, codelistSubmissionValues, description,
  _links.codelist``.
* ``variable_codelist_maps.pkl`` — ``{"<std>-<ver>-codelists": {VAR: [Ccode,…]}}``.
* ``standards_details.pkl`` — ``{"standards/<std>/<ver>": {... dataset_names,
  classes ...}}``.
* ``<ct>-<date>.pkl`` — ``{"package": str, "codelists": [ {conceptId,
  extensible, submissionValue, terms:[{submissionValue, preferredTerm}]} ]}``.

Everything loads with a plain ``pickle.load`` (no engine classes required).
"""

from __future__ import annotations

import os
import pickle
from dataclasses import dataclass
from functools import cached_property


#: Overrides the pickle-cache location. The default below is this project's
#: standard host path; on a machine that keeps the cache elsewhere the default
#: resolves to a directory that does not exist and every generator and harness
#: entry point dies on the first ``open()``. Setting this env var to a
#: materialised cache is the supported way to run in such a tree.
CACHE_DIR_ENV = "CDISC_PICKLE_CACHE_DIR"


#: The standalone pickle cache, which survives the fork submodule's removal
#: (PLAN-fork-removal-stage4 phase 3). It is a host path, not a repo path, so it
#: is the same value whether the caller runs from a clone or a worktree.
DEFAULT_CACHE_DIR = "/data/cdisc.metadata.library-cache-pkl"


def _default_cache_dir() -> str:
    return os.environ.get(CACHE_DIR_ENV) or DEFAULT_CACHE_DIR


# Which standard maps to which CT product + version-key spelling.
# version: the ``variables_metadata`` / codelist-map spelling (e.g. "3-4").
@dataclass(frozen=True)
class StandardSpec:
    standard: str  # "sdtmig" | "sendig"
    version: str  # "3-4" | "3-1-1"
    ct_package: str  # CT pickle filename stem, e.g. "sdtmct-2024-09-27"


SDTMIG_3_4 = StandardSpec("sdtmig", "3-4", "sdtmct-2024-09-27")
SENDIG_3_1_1 = StandardSpec("sendig", "3-1-1", "sendct-2024-09-27")


@dataclass
class Variable:
    name: str
    label: str
    datatype: str  # "Char" | "Num"
    core: str  # "Req" | "Exp" | "Perm"
    role: str
    ordinal: int
    description: str
    codelist_codes: tuple[str, ...]  # C-codes assigned to this variable


class Library:
    """Metadata access for one standard/version lane."""

    def __init__(self, spec: StandardSpec, cache_dir: str | None = None) -> None:
        self.spec = spec
        self.cache_dir = cache_dir or _default_cache_dir()
        # Memoized per-domain variable lists. Variables are read-only dataclasses
        # (no caller mutates them), so sharing the cached list is safe and avoids
        # re-resolving codelists on every call (define.py looks a domain up once
        # per column).
        self._var_cache: dict[str, list[Variable]] = {}

    # ---- raw pickle loaders (cached) ----
    @cached_property
    def _vars_meta(self) -> dict:
        with open(os.path.join(self.cache_dir, "variables_metadata.pkl"), "rb") as fh:
            return pickle.load(fh)

    @cached_property
    def _codelist_map(self) -> dict:
        with open(os.path.join(self.cache_dir, "variable_codelist_maps.pkl"), "rb") as fh:
            return pickle.load(fh)

    @cached_property
    def _standards_details(self) -> dict:
        with open(os.path.join(self.cache_dir, "standards_details.pkl"), "rb") as fh:
            return pickle.load(fh)

    @cached_property
    def _ct_by_code(self) -> dict[str, dict]:
        path = os.path.join(self.cache_dir, self.spec.ct_package + ".pkl")
        with open(path, "rb") as fh:
            ct = pickle.load(fh)
        return {c["conceptId"]: c for c in ct.get("codelists", []) if c.get("conceptId")}

    # ---- keys ----
    @property
    def _vm_key(self) -> str:
        return f"library_variables_metadata/{self.spec.standard}/{self.spec.version}"

    @property
    def _cm_key(self) -> str:
        return f"{self.spec.standard}-{self.spec.version}-codelists"

    @property
    def _sd_key(self) -> str:
        return f"standards/{self.spec.standard}/{self.spec.version}"

    # ---- public API ----
    def domains(self) -> list[str]:
        """All dataset/domain codes defined for this standard."""
        return sorted(self._vars_meta[self._vm_key].keys())

    def has_domain(self, domain: str) -> bool:
        return domain in self._vars_meta[self._vm_key]

    def dataset_class(self, domain: str) -> str | None:
        """Best-effort class label from standards_details, else None."""
        for cls, ds in self._iter_datasets():
            if ds.get("name") == domain:
                return cls.get("label") or cls.get("name")
        return None

    def dataset_label(self, domain: str) -> str | None:
        """The dataset's published label (e.g. 'Demographics'), else None."""
        for _cls, ds in self._iter_datasets():
            if ds.get("name") == domain:
                return ds.get("label")
        return None

    def dataset_structure(self, domain: str) -> str | None:
        for _cls, ds in self._iter_datasets():
            if ds.get("name") == domain:
                return ds.get("datasetStructure")
        return None

    def _iter_datasets(self):
        detail = self._standards_details.get(self._sd_key, {})
        for cls in detail.get("classes", []) or []:
            for ds in cls.get("datasets", []) or []:
                yield cls, ds

    def variables(self, domain: str) -> list[Variable]:
        """Ordered variables for ``domain`` (by ``ordinal``)."""
        cached = self._var_cache.get(domain)
        if cached is not None:
            return cached
        dom = self._vars_meta[self._vm_key].get(domain)
        if dom is None:
            raise KeyError(f"{domain} not in {self.spec.standard}/{self.spec.version}")
        cmap = self._codelist_map.get(self._cm_key, {})
        out: list[Variable] = []
        for name, rec in dom.items():
            codes = self._codes_for(name, rec, cmap)
            try:
                ordinal = int(rec.get("ordinal") or 0)
            except (TypeError, ValueError):
                ordinal = 0
            out.append(
                Variable(
                    name=name,
                    label=rec.get("label") or name,
                    datatype=rec.get("simpleDatatype") or "Char",
                    core=rec.get("core") or "Perm",
                    role=rec.get("role") or "",
                    ordinal=ordinal,
                    description=rec.get("description") or "",
                    codelist_codes=codes,
                )
            )
        out.sort(key=lambda v: (v.ordinal, v.name))
        self._var_cache[domain] = out
        return out

    @staticmethod
    def _codes_for(name: str, rec: dict, cmap: dict) -> tuple[str, ...]:
        codes = list(cmap.get(name, []))
        if not codes:
            # fall back to the variable record's _links.codelist hrefs
            for link in (rec.get("_links", {}) or {}).get("codelist", []) or []:
                href = link.get("href", "")
                code = href.rsplit("/", 1)[-1]
                if code.startswith("C"):
                    codes.append(code)
        return tuple(dict.fromkeys(codes))  # de-dup, keep order

    def codelist_terms(self, codes: tuple[str, ...]) -> tuple[list[str], bool]:
        """Return (submission values, extensible) for the first resolvable code.

        Returns ([], False) when no code resolves (so callers can synthesize).
        """
        for code in codes:
            cl = self._ct_by_code.get(code)
            if cl:
                vals = [
                    t["submissionValue"]
                    for t in cl.get("terms", [])
                    if t.get("submissionValue")
                ]
                return vals, bool(cl.get("extensible"))
        return [], False

    def first_codelist_code(self, codes: tuple[str, ...]) -> str | None:
        """The first C-code that resolves in the CT package (the one used for data)."""
        for code in codes:
            if code in self._ct_by_code:
                return code
        return None

    def codelist_def(self, code: str) -> dict | None:
        """Full enumerated codelist for ``code`` (for Define-XML CodeList output).

        Returns ``{code, name, submissionValue, extensible, terms}`` where each
        term is ``(conceptId, submissionValue, preferredTerm)`` in CT order, or
        ``None`` when the code does not resolve in this lane's CT package.
        """
        cl = self._ct_by_code.get(code)
        if cl is None:
            return None
        terms = [
            (t.get("conceptId") or "", t.get("submissionValue") or "",
             t.get("preferredTerm") or t.get("submissionValue") or "")
            for t in cl.get("terms", [])
            if t.get("submissionValue")
        ]
        return {
            "code": code,
            "name": cl.get("name") or cl.get("submissionValue") or code,
            "submissionValue": cl.get("submissionValue") or "",
            "extensible": bool(cl.get("extensible")),
            "terms": terms,
        }

    def decode(self, codes: tuple[str, ...], submission: str) -> str | None:
        """Preferred term for a submission value within the given codelist(s)."""
        for code in codes:
            cl = self._ct_by_code.get(code)
            if not cl:
                continue
            for t in cl.get("terms", []):
                if t.get("submissionValue") == submission:
                    return t.get("preferredTerm")
        return None


if __name__ == "__main__":  # pragma: no cover - manual inspection helper
    for spec in (SDTMIG_3_4, SENDIG_3_1_1):
        lib = Library(spec)
        doms = lib.domains()
        print(f"{spec.standard}/{spec.version}: {len(doms)} domains")
        sample = "PC" if "PC" in doms else doms[0]
        vs = lib.variables(sample)
        print(f"  {sample}: {len(vs)} vars; first 3:",
              [(v.name, v.core, v.datatype) for v in vs[:3]])
        ct_var = next((v for v in vs if v.codelist_codes), None)
        if ct_var:
            terms, ext = lib.codelist_terms(ct_var.codelist_codes)
            print(f"  {ct_var.name} codelist {ct_var.codelist_codes} -> "
                  f"{len(terms)} terms (extensible={ext}); e.g. {terms[:3]}")
