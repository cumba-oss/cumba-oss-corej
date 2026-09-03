"""Unit tests for library — the engine-cache metadata reader."""

import pytest

import library


@pytest.fixture(scope="module")
def sdtmig():
    return library.Library(library.SDTMIG_3_4)


@pytest.fixture(scope="module")
def sendig():
    return library.Library(library.SENDIG_3_1_1)


def test_domains_present(sdtmig, sendig):
    assert sdtmig.has_domain("PC") and sdtmig.has_domain("DM")
    # SEND-only domains live in the SENDIG lane, not SDTMIG.
    assert sendig.has_domain("BW") and sendig.has_domain("TX")
    assert not sdtmig.has_domain("BW")


def test_variables_ordered_and_typed(sdtmig):
    vs = sdtmig.variables("PC")
    assert vs[0].name == "STUDYID" and vs[0].core == "Req"
    assert all(v.datatype in ("Char", "Num") for v in vs)
    # ordinals are non-decreasing after the sort
    ordinals = [v.ordinal for v in vs]
    assert ordinals == sorted(ordinals)


def test_cross_standard_dm_variable_split(sdtmig, sendig):
    sdtmig_dm = {v.name for v in sdtmig.variables("DM")}
    sendig_dm = {v.name for v in sendig.variables("DM")}
    # The AGETXT pitfall: SENDIG-only variable must not appear in SDTMIG DM.
    assert "AGETXT" in sendig_dm and "AGETXT" not in sdtmig_dm
    # And SDTM-only human variables must not appear in SENDIG DM.
    assert "RACE" in sdtmig_dm and "RACE" not in sendig_dm


def test_codelist_terms_resolve(sdtmig):
    sex = next(v for v in sdtmig.variables("DM") if v.name == "SEX")
    terms, _extensible = sdtmig.codelist_terms(sex.codelist_codes)
    assert "M" in terms and "F" in terms


def test_codelist_terms_empty_when_uncoded(sdtmig):
    studyid = next(v for v in sdtmig.variables("DM") if v.name == "STUDYID")
    terms, extensible = sdtmig.codelist_terms(studyid.codelist_codes)
    assert terms == [] and extensible is False


def test_decode_pairs_code_to_term(sdtmig):
    # PCTESTCD <-> PCTEST style decode via the test codelist.
    pctestcd = next(
        (v for v in sdtmig.variables("PC") if v.name == "PCTESTCD" and v.codelist_codes),
        None,
    )
    if pctestcd is None:
        pytest.skip("PCTESTCD has no codelist in this cache")
    terms, _ = sdtmig.codelist_terms(pctestcd.codelist_codes)
    decoded = sdtmig.decode(pctestcd.codelist_codes, terms[0])
    assert decoded  # a non-empty preferred term


def test_cache_dir_env_override(monkeypatch, tmp_path):
    # On a host that keeps the pickle cache somewhere other than the default
    # path, that path does not exist and every entry point dies on the first
    # open(). The env var is the supported escape hatch — and verify.py reads the
    # same one for the engine's `-pc` flag, so the generator and the engine
    # cannot disagree.
    monkeypatch.setenv(library.CACHE_DIR_ENV, str(tmp_path))
    assert library._default_cache_dir() == str(tmp_path)
    assert library.Library(library.SDTMIG_3_4).cache_dir == str(tmp_path)
    # An explicit argument still wins over the environment.
    assert library.Library(library.SDTMIG_3_4, cache_dir="/x").cache_dir == "/x"
    monkeypatch.delenv(library.CACHE_DIR_ENV)
    assert library._default_cache_dir() == library.DEFAULT_CACHE_DIR
    assert library.DEFAULT_CACHE_DIR == "/data/cdisc.metadata.library-cache-pkl"
