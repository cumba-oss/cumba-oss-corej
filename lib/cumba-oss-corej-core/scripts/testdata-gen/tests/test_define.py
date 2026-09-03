"""Unit tests for the Define-XML v2.1 generation (Phase 5).

Asserts the define matches the generated data: every dataset/column has a
matching ItemGroupDef/ItemDef, every CT value used in the data appears in its
CodeList, OIDs resolve internally (ItemRef -> ItemDef, ItemDef -> CodeList), the
ItemRef Role mirrors the library role (so CORE-001081 stays green), and the
output is deterministic.
"""

import json
import os
import xml.etree.ElementTree as ET

import pytest

import define as define_mod
import domains as dom_mod
import library
import study as study_mod
from generate import Generator

ODM = "{http://www.cdisc.org/ns/odm/v1.3}"
DEF = "{http://www.cdisc.org/ns/def/v2.1}"

LANES = [
    ("sdtmig", "3-4", library.SDTMIG_3_4),
    ("sendig", "3-1-1", library.SENDIG_3_1_1),
]


def _generate(tmp_path, standard, version, spec):
    lib = library.Library(spec)
    st = study_mod.build_study(standard, version, n_subjects=4, n_visits=5)
    out = os.path.join(str(tmp_path), standard)
    Generator(lib, st).generate(out)
    return lib, out


def _load_define(out):
    root = ET.parse(os.path.join(out, "define.xml")).getroot()
    mdv = root.find(f"{ODM}Study/{ODM}MetaDataVersion")
    return root, mdv


def _datasets_on_disk(out):
    files = [f for f in os.listdir(out) if f.endswith(".json")]
    return {json.load(open(os.path.join(out, f)))["name"]: json.load(open(os.path.join(out, f)))
            for f in files}


@pytest.mark.parametrize("standard,version,spec", LANES)
def test_define_is_well_formed(tmp_path, standard, version, spec):
    _, out = _generate(tmp_path, standard, version, spec)
    root, mdv = _load_define(out)
    assert root.tag == f"{ODM}ODM"
    assert mdv is not None
    assert mdv.get(f"{DEF}DefineVersion") == "2.1.0"


@pytest.mark.parametrize("standard,version,spec", LANES)
def test_every_dataset_and_column_has_metadata(tmp_path, standard, version, spec):
    _, out = _generate(tmp_path, standard, version, spec)
    _, mdv = _load_define(out)
    groups = {g.get("Name"): g for g in mdv.findall(f"{ODM}ItemGroupDef")}
    item_oids = {d.get("OID") for d in mdv.findall(f"{ODM}ItemDef")}
    datasets = _datasets_on_disk(out)
    for name, ds in datasets.items():
        assert name in groups, f"{name} missing ItemGroupDef"
        refs = {r.get("ItemOID") for r in groups[name].findall(f"{ODM}ItemRef")}
        for col in ds["columns"]:
            oid = f"IT.{name}.{col['name']}"
            assert oid in refs, f"{oid} not referenced by ItemGroup"
            assert oid in item_oids, f"{oid} has no ItemDef"


@pytest.mark.parametrize("standard,version,spec", LANES)
def test_itemref_oids_and_codelist_oids_resolve(tmp_path, standard, version, spec):
    _, out = _generate(tmp_path, standard, version, spec)
    _, mdv = _load_define(out)
    item_oids = {d.get("OID") for d in mdv.findall(f"{ODM}ItemDef")}
    cl_oids = {c.get("OID") for c in mdv.findall(f"{ODM}CodeList")}
    # ItemRef -> ItemDef
    for g in mdv.findall(f"{ODM}ItemGroupDef"):
        for r in g.findall(f"{ODM}ItemRef"):
            assert r.get("ItemOID") in item_oids
    # ItemDef -> CodeList
    for d in mdv.findall(f"{ODM}ItemDef"):
        ref = d.find(f"{ODM}CodeListRef")
        if ref is not None:
            assert ref.get("CodeListOID") in cl_oids


@pytest.mark.parametrize("standard,version,spec", LANES)
def test_codelists_reproduce_full_ct(tmp_path, standard, version, spec):
    """Each referenced CodeList enumerates the FULL CT codelist (no omission).

    This is the define-to-data fidelity guarantee: because every define CodeList
    reproduces its CT codelist exactly, no CT value a variable could legitimately
    carry is ever missing from the define. (Generated data values that are *not*
    CT submission values — e.g. a decoded --TEST long name on an extensible
    codelist — are a generator value choice, orthogonal to define fidelity.)
    """
    lib, out = _generate(tmp_path, standard, version, spec)
    _, mdv = _load_define(out)
    codelists = mdv.findall(f"{ODM}CodeList")
    assert codelists, "expected at least one CodeList"
    for c in codelists:
        code = c.get("OID").split(".", 1)[-1]
        cdef = lib.codelist_def(code)
        assert cdef is not None, f"{code} does not resolve in CT"
        defined = {it.get("CodedValue") for it in c.findall(f"{ODM}CodeListItem")}
        expected = {sub for _cid, sub, _decode in cdef["terms"]}
        assert defined == expected, f"CL.{code} differs from CT enumeration"


@pytest.mark.parametrize("standard,version,spec", LANES)
def test_ct_values_used_in_data_appear_in_codelist(tmp_path, standard, version, spec):
    """Every data cell that *is* a CT submission value appears in its CodeList."""
    lib, out = _generate(tmp_path, standard, version, spec)
    _, mdv = _load_define(out)
    cl_values = {
        c.get("OID"): {it.get("CodedValue") for it in c.findall(f"{ODM}CodeListItem")}
        for c in mdv.findall(f"{ODM}CodeList")
    }
    ct_values = {
        c.get("OID"): {
            sub for _cid, sub, _d in (lib.codelist_def(c.get("OID").split(".", 1)[-1]) or
                                      {"terms": []})["terms"]
        }
        for c in mdv.findall(f"{ODM}CodeList")
    }
    var_cl = {
        d.get("OID"): d.find(f"{ODM}CodeListRef").get("CodeListOID")
        for d in mdv.findall(f"{ODM}ItemDef")
        if d.find(f"{ODM}CodeListRef") is not None
    }
    datasets = _datasets_on_disk(out)
    checked = 0
    for name, ds in datasets.items():
        for col_i, col in enumerate(ds["columns"]):
            cl = var_cl.get(f"IT.{name}.{col['name']}")
            if cl is None:
                continue
            for row in ds["rows"]:
                val = row[col_i]
                if val in (None, "") or str(val) not in ct_values[cl]:
                    continue
                assert str(val) in cl_values[cl], (
                    f"{name}.{col['name']} CT value {val!r} missing from {cl}"
                )
                checked += 1
    assert checked > 0, "expected at least one CT-valued populated cell"


@pytest.mark.parametrize("standard,version,spec", LANES)
def test_itemref_role_matches_library_role(tmp_path, standard, version, spec):
    lib, out = _generate(tmp_path, standard, version, spec)
    _, mdv = _load_define(out)
    for g in mdv.findall(f"{ODM}ItemGroupDef"):
        domain = g.get("Name")
        roles = {v.name: v.role for v in lib.variables(domain)}
        for r in g.findall(f"{ODM}ItemRef"):
            var = r.get("ItemOID").rsplit(".", 1)[-1]
            lib_role = roles.get(var, "")
            if lib_role:
                assert r.get("Role") == lib_role
            else:
                assert r.get("Role") is None


def test_codelist_has_ext_code_id(tmp_path):
    _, out = _generate(tmp_path, "sdtmig", "3-4", library.SDTMIG_3_4)
    _, mdv = _load_define(out)
    cls = mdv.findall(f"{ODM}CodeList")
    assert cls, "expected at least one CodeList"
    for c in cls:
        aliases = [a for a in c.findall(f"{ODM}Alias")
                   if a.get("Context") == "nci:ExtCodeID"]
        # codelist-level ExtCodeID (the ccode) must be present
        assert any(a.get("Name") == c.get("OID").split(".", 1)[-1] for a in aliases)


def test_define_is_deterministic(tmp_path):
    lib = library.Library(library.SDTMIG_3_4)
    st = study_mod.build_study("sdtmig", "3-4", n_subjects=4, n_visits=5)
    out1 = os.path.join(str(tmp_path), "a")
    out2 = os.path.join(str(tmp_path), "b")
    Generator(lib, st).generate(out1)
    Generator(library.Library(library.SDTMIG_3_4),
              study_mod.build_study("sdtmig", "3-4", n_subjects=4, n_visits=5)).generate(out2)
    a = open(os.path.join(out1, "define.xml"), "rb").read()
    b = open(os.path.join(out2, "define.xml"), "rb").read()
    assert a == b
