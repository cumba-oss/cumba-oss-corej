"""Unit tests for library — the engine-cache metadata reader."""

import pytest

import library


def _library_or_skip(spec):
    """Build a :class:`library.Library`, skipping when no pickle cache is configured.

    The cache is host-local and has no default (see ``library.CACHE_DIR_ENV``), so an
    unconfigured checkout is a legitimate state — these tests skip there rather than
    fail. The *contract* that resolution refuses to guess is asserted below, and that
    assertion needs no cache.
    """
    try:
        return library.Library(spec)
    except library.CacheDirNotConfigured as exc:
        pytest.skip(str(exc))


@pytest.fixture(scope="module")
def sdtmig():
    return _library_or_skip(library.SDTMIG_3_4)


@pytest.fixture(scope="module")
def sendig():
    return _library_or_skip(library.SENDIG_3_1_1)


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


def test_cache_dir_resolution_order(monkeypatch, tmp_path):
    # The cache is not in this repository and its location differs per host, so it
    # is always configured — never guessed. verify.py reads the same value for the
    # engine's `-pc` flag, so the generator and the engine cannot disagree.
    monkeypatch.setattr(library, "_cache_dir_override", None)
    monkeypatch.setenv(library.CACHE_DIR_ENV, str(tmp_path))
    assert library.resolve_cache_dir() == str(tmp_path)
    assert library.Library(library.SDTMIG_3_4).cache_dir == str(tmp_path)
    # An explicit constructor argument still wins over the environment.
    assert library.Library(library.SDTMIG_3_4, cache_dir="/x").cache_dir == "/x"
    # …and --cache-dir (recorded by set_cache_dir) wins over the environment.
    library.set_cache_dir(str(tmp_path / "flag"))
    assert library.resolve_cache_dir() == str(tmp_path / "flag")


def test_unconfigured_cache_dir_fails_immediately_and_says_how(monkeypatch):
    """No configuration => a loud failure naming both routes, not a hidden default.

    Replaces the old assertion that an absolute host path was the built-in default.
    That default resolved on exactly one machine; everywhere else it turned a
    configuration mistake into a FileNotFoundError from deep inside a pickle load.
    """
    monkeypatch.setattr(library, "_cache_dir_override", None)
    monkeypatch.delenv(library.CACHE_DIR_ENV, raising=False)

    with pytest.raises(library.CacheDirNotConfigured) as excinfo:
        library.resolve_cache_dir()
    message = str(excinfo.value)
    assert library.CACHE_DIR_ENV in message      # names the environment variable
    assert library.CACHE_DIR_FLAG in message     # …and the command-line flag
    assert "cache_dir=" in message               # …and the constructor argument

    # The failure lands at construction, not at the first open() several calls later.
    with pytest.raises(library.CacheDirNotConfigured):
        library.Library(library.SDTMIG_3_4)


def test_no_module_level_absolute_default_survives():
    """Guard against a convenience default creeping back in.

    Directories outside the repository are local-only: not available on any remote
    system, not to be assumed to exist tomorrow. A module attribute holding one is
    exactly how the previous default was reintroduced-by-habit.
    """
    absolute_defaults = {
        name: value
        for name, value in vars(library).items()
        if isinstance(value, str) and value.startswith("/") and not name.startswith("__")
    }
    assert absolute_defaults == {}
