"""Write a complete Define-XML v2.1 for a generated study.

The Define-XML is driven by the SAME ``library`` metadata and the SAME generated
datasets as the data emission, so it matches the data exactly:

* one ``ItemGroupDef`` per emitted dataset (OID, Name, Domain, Class, Repeating,
  IsReferenceData, Structure, archive location ``<domain>.json``);
* one ``ItemDef`` per column (OID, Name, Label, DataType, Length, the variable's
  CodeListRef, Origin), with the variable's library ``Role`` and ``KeySequence``
  carried on the ItemGroup's ``ItemRef`` (so the engine's ``define_variable_role``
  operand — CORE-001081 — matches the library role and the rule passes);
* one shared ``CodeList`` per CT-bound codelist actually referenced, fully
  enumerated from the CT package (``CodedValue`` + ``Decode`` + the term's NCI
  ``ExtCodeID`` Alias and the codelist's own ExtCodeID Alias / extensibility);
* a minimal ``def:ValueListDef`` / ``def:WhereClauseDef`` block (one findings
  domain) so value-level-metadata rules have something to execute against.

The output is well-formed ODM 1.3.2 + Define-XML 2.1, deterministic, and accepted
by the engine via ``-dxp <file> -dv 2-1``. See the plan's Phase 5 section.

Engine contract (see ``OdmDefineXMLProvider`` / ``DefineXmlMetadataProvider`` in
``lib/corej-cdisc-core``): the engine matches an ``ItemGroupDef`` by ``Name`` or
``Domain``, resolves each ``ItemRef`` to its ``ItemDef`` by ``ItemOID``, and reads
``role`` from the *ItemRef*, ``ccode`` from the referenced ``CodeList``'s
``nci:ExtCodeID`` Alias, and the codelist coded codes from each CodeListItem's
``nci:ExtCodeID`` Alias. We mirror that layout.
"""

from __future__ import annotations

import os
import xml.etree.ElementTree as ET

from library import Library

ODM_NS = "http://www.cdisc.org/ns/odm/v1.3"
DEF_NS = "http://www.cdisc.org/ns/def/v2.1"
XLINK_NS = "http://www.w3.org/1999/xlink"

ET.register_namespace("", ODM_NS)
ET.register_namespace("def", DEF_NS)
ET.register_namespace("xlink", XLINK_NS)


def _odm(tag: str) -> str:
    return f"{{{ODM_NS}}}{tag}"


def _def(tag: str) -> str:
    return f"{{{DEF_NS}}}{tag}"


def _xlink(tag: str) -> str:
    return f"{{{XLINK_NS}}}{tag}"


def _data_type(name: str, datatype: str) -> str:
    """Define-XML DataType for a variable (mirrors emit._data_type's intent)."""
    if name.endswith("DTC"):
        return "date"
    if datatype == "Num":
        return "integer"
    return "text"


def _translated(parent: ET.Element, text: str) -> None:
    tt = ET.SubElement(parent, _odm("TranslatedText"))
    tt.set("{http://www.w3.org/XML/1998/namespace}lang", "en")
    tt.text = text


def _description(parent: ET.Element, text: str) -> None:
    desc = ET.SubElement(parent, _odm("Description"))
    _translated(desc, text)


def _column_length(rows: list[list], col_index: int) -> int:
    """Length = longest non-empty value in the column (>=1)."""
    longest = 0
    for row in rows:
        val = row[col_index]
        if val is None:
            continue
        n = len(str(val))
        if n > longest:
            longest = n
    return max(longest, 1)


class DefineBuilder:
    """Builds the Define-XML for one lane from its library + generated datasets."""

    def __init__(self, lib: Library, studyid: str, standard: str, version: str) -> None:
        self.lib = lib
        self.studyid = studyid
        self.standard = standard
        self.version = version
        # conceptId -> codelist def, collected as ItemDefs reference codelists.
        self._codelists: dict[str, dict] = {}

    # ---- public entry ----
    def build(self, datasets: dict[str, dict]) -> ET.Element:
        """``datasets``: ordered ``{DOMAIN: emit.build_dataset(...) dict}``."""
        odm = ET.Element(_odm("ODM"))
        odm.set("ODMVersion", "1.3.2")
        odm.set("FileType", "Snapshot")
        odm.set("FileOID", f"{self.studyid}.Define-XML.2.1.0")
        odm.set("CreationDateTime", "2026-06-29T00:00:00")
        odm.set("Originator", "corej synthetic test-data generator")
        odm.set(_def("Context"), "Submission")

        study = ET.SubElement(odm, _odm("Study"))
        study.set("OID", self.studyid)
        gv = ET.SubElement(study, _odm("GlobalVariables"))
        ET.SubElement(gv, _odm("StudyName")).text = self.studyid
        ET.SubElement(gv, _odm("StudyDescription")).text = (
            "Synthetic study generated to exercise CDISC CORE conformance rules"
        )
        ET.SubElement(gv, _odm("ProtocolName")).text = self.studyid

        mdv = ET.SubElement(study, _odm("MetaDataVersion"))
        std_upper = self.standard.upper()
        mdv.set("OID", f"MDV.{self.studyid}")
        mdv.set("Name", f"{std_upper} {self.version} metadata for {self.studyid}")
        mdv.set("Description", f"Define-XML 2.1 metadata for the synthetic {std_upper} study")
        mdv.set(_def("DefineVersion"), "2.1.0")

        self._standards(mdv, std_upper)

        # ItemGroupDefs (datasets) — also collects referenced codelists.
        item_defs: list[ET.Element] = []
        for domain, ds in datasets.items():
            self._item_group(mdv, domain, ds, item_defs)

        # A minimal value-level-metadata block on the first findings domain that
        # has a --TESTCD topic, so VLM/where-clause rules have data to execute on.
        self._value_level(mdv, datasets, item_defs)

        # ItemDefs follow the ItemGroupDefs (ODM order: groups, then items, then
        # codelists). Append the collected ItemDefs and CodeLists.
        for el in item_defs:
            mdv.append(el)
        self._code_lists(mdv)
        return odm

    # ---- standards block ----
    def _standards(self, mdv: ET.Element, std_upper: str) -> None:
        standards = ET.SubElement(mdv, _def("Standards"))
        ig = ET.SubElement(standards, _def("Standard"))
        ig.set("OID", "STD.IG")
        ig.set("Name", std_upper)
        ig.set("Type", "IG")
        ig.set("Version", self.version.replace("-", "."))
        ig.set("Status", "Final")
        ct = ET.SubElement(standards, _def("Standard"))
        ct.set("OID", "STD.CT")
        ct.set("Name", "CDISC/NCI")
        ct.set("Type", "CT")
        ct.set("PublishingSet", std_upper.replace("IG", ""))
        ct.set("Version", "2024-09-27")
        ct.set("Status", "Final")

    # ---- one dataset ----
    def _item_group(
        self, mdv: ET.Element, domain: str, ds: dict, item_defs: list[ET.Element]
    ) -> None:
        cols = ds["columns"]
        rows = ds["rows"]
        names = [c["name"] for c in cols]
        is_reference = "USUBJID" not in names
        igd = ET.SubElement(mdv, _odm("ItemGroupDef"))
        igd.set("OID", f"IG.{domain}")
        igd.set("Name", domain)
        igd.set("Domain", domain)
        igd.set("Repeating", "No" if domain == "DM" else "Yes")
        igd.set("IsReferenceData", "Yes" if is_reference else "No")
        igd.set("SASDatasetName", domain)
        igd.set("Purpose", "Tabulation")
        structure = self.lib.dataset_structure(domain) or (
            "One record per trial design element" if is_reference
            else "One record per subject"
        )
        igd.set(_def("Structure"), structure)
        igd.set(_def("ArchiveLocationID"), f"LF.{domain}")
        _description(igd, ds.get("label") or domain)

        by_name = {v.name: v for v in self.lib.variables(domain)}
        for i, col in enumerate(cols):
            v = by_name.get(col["name"])
            ref = ET.SubElement(igd, _odm("ItemRef"))
            ref.set("ItemOID", f"IT.{domain}.{col['name']}")
            ref.set("OrderNumber", str(i + 1))
            mandatory = "Yes" if (v is not None and v.core == "Req") else "No"
            ref.set("Mandatory", mandatory)
            if "keySequence" in col:
                ref.set("KeySequence", str(col["keySequence"]))
            if v is not None and v.role:
                # The engine reads role from the ItemRef; matching the library role
                # keeps CORE-001081 (define_variable_role != library_variable_role)
                # green.
                ref.set("Role", v.role)
            item_defs.append(self._item_def(domain, col, rows, i, v))

        cls = self.lib.dataset_class(domain)
        if cls:
            ET.SubElement(igd, _def("Class")).set("Name", cls.upper())
        leaf = ET.SubElement(igd, _def("leaf"))
        leaf.set("ID", f"LF.{domain}")
        leaf.set(_xlink("href"), f"{domain.lower()}.json")
        ET.SubElement(leaf, _def("title")).text = f"{domain.lower()}.json"

    # ---- one variable ----
    def _item_def(
        self, domain: str, col: dict, rows: list[list], col_index: int, var=None
    ) -> ET.Element:
        datatype = var.datatype if var is not None else "Char"
        define_dt = _data_type(col["name"], datatype)
        item = ET.Element(_odm("ItemDef"))
        item.set("OID", f"IT.{domain}.{col['name']}")
        item.set("Name", col["name"])
        item.set("DataType", define_dt)
        item.set("Length", str(_column_length(rows, col_index)))
        item.set("SASFieldName", col["name"])
        _description(item, col.get("label") or col["name"])

        if var is not None and var.codelist_codes:
            code = self.lib.first_codelist_code(var.codelist_codes)
            if code is not None:
                cldef = self.lib.codelist_def(code)
                if cldef is not None:
                    self._codelists.setdefault(code, cldef)
                    clref = ET.SubElement(item, _odm("CodeListRef"))
                    clref.set("CodeListOID", f"CL.{code}")

        origin = ET.SubElement(item, _def("Origin"))
        origin.set("Type", "Assigned")
        return item

    # ---- value-level metadata (minimal, present) ----
    def _value_level(
        self, mdv: ET.Element, datasets: dict[str, dict], item_defs: list[ET.Element]
    ) -> None:
        """Emit one ValueListDef + WhereClauseDef keyed on a findings --TESTCD.

        Picks the first emitted findings dataset that has a populated ``--TESTCD``
        and an ``--ORRES`` result, and defines value-level metadata for the result
        variable subset where ``--TESTCD == <first code>`` — enough for the engine's
        value-list / where-clause readers to have a non-empty structure.
        """
        for domain, ds in datasets.items():
            names = [c["name"] for c in ds["columns"]]
            testcd, orres = f"{domain}TESTCD", f"{domain}ORRES"
            if testcd not in names or orres not in names:
                continue
            tc_idx = names.index(testcd)
            code = next((str(r[tc_idx]) for r in ds["rows"] if r[tc_idx]), "")
            if not code:
                continue
            wc_oid = f"WC.{domain}.{code}"
            vl_oid = f"VL.{domain}.{orres}"
            wcd = ET.SubElement(mdv, _def("WhereClauseDef"))
            wcd.set("OID", wc_oid)
            rc = ET.SubElement(wcd, _odm("RangeCheck"))
            rc.set("Comparator", "EQ")
            rc.set("SoftHard", "Soft")
            rc.set(_def("ItemOID"), f"IT.{domain}.{testcd}")
            ET.SubElement(rc, _odm("CheckValue")).text = code
            vld = ET.SubElement(mdv, _def("ValueListDef"))
            vld.set("OID", vl_oid)
            iref = ET.SubElement(vld, _odm("ItemRef"))
            iref.set("ItemOID", f"IT.{domain}.{orres}")
            iref.set("OrderNumber", "1")
            iref.set("Mandatory", "No")
            wcr = ET.SubElement(iref, _def("WhereClauseRef"))
            wcr.set("WhereClauseOID", wc_oid)
            # Link the result ItemDef to its value list.
            for el in item_defs:
                if el.get("OID") == f"IT.{domain}.{orres}":
                    vlr = ET.Element(_def("ValueListRef"))
                    vlr.set("ValueListOID", vl_oid)
                    el.insert(0, vlr)
                    break
            return

    # ---- codelists ----
    def _code_lists(self, mdv: ET.Element) -> None:
        for code in sorted(self._codelists):
            cldef = self._codelists[code]
            cl = ET.SubElement(mdv, _odm("CodeList"))
            cl.set("OID", f"CL.{code}")
            cl.set("Name", cldef["name"])
            cl.set("DataType", "text")
            for order, (term_code, sub, decode) in enumerate(cldef["terms"], start=1):
                item = ET.SubElement(cl, _odm("CodeListItem"))
                item.set("CodedValue", sub)
                item.set("OrderNumber", str(order))
                dec = ET.SubElement(item, _odm("Decode"))
                _translated(dec, decode)
                if term_code:
                    alias = ET.SubElement(item, _odm("Alias"))
                    alias.set("Context", "nci:ExtCodeID")
                    alias.set("Name", term_code)
            # The codelist's own NCI ExtCodeID — the engine reads this as the
            # variable ccode (define_variable_ccode, CORE-000929).
            cl_alias = ET.SubElement(cl, _odm("Alias"))
            cl_alias.set("Context", "nci:ExtCodeID")
            cl_alias.set("Name", code)


def build_define(
    lib: Library, studyid: str, standard: str, version: str, datasets: dict[str, dict]
) -> ET.ElementTree:
    """Build the Define-XML tree for ``datasets`` (ordered DOMAIN -> dataset dict)."""
    builder = DefineBuilder(lib, studyid, standard, version)
    root = builder.build(datasets)
    tree = ET.ElementTree(root)
    ET.indent(tree, space="  ")
    return tree


def write_define(
    out_dir: str, lib: Library, studyid: str, standard: str, version: str,
    datasets: dict[str, dict], filename: str = "define.xml",
) -> str:
    """Write ``define.xml`` into ``out_dir``; return the path."""
    os.makedirs(out_dir, exist_ok=True)
    tree = build_define(lib, studyid, standard, version, datasets)
    path = os.path.join(out_dir, filename)
    tree.write(path, encoding="utf-8", xml_declaration=True)
    return path
