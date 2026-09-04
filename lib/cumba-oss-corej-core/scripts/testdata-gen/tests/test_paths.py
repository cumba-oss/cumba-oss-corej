"""Unit tests for paths — where the generated synthetic study tree lives.

The counterpart to ``test_library``'s cache-dir contract: the study tree is this
generator's *output*, is host-local, and must therefore never be guessed. These
tests need no cache, no engine and no generated data.
"""

import pytest

import paths


@pytest.fixture(autouse=True)
def _no_flag_override(monkeypatch):
    """Each test starts with no recorded ``--synth-root`` (the module keeps one)."""
    monkeypatch.setattr(paths, "_synth_root_override", None)


def test_resolution_order_flag_beats_environment(monkeypatch, tmp_path):
    monkeypatch.setenv(paths.SYNTH_ROOT_ENV, str(tmp_path / "from-env"))
    assert paths.synth_root() == str(tmp_path / "from-env")

    paths.set_synth_root(str(tmp_path / "from-flag"))
    assert paths.synth_root() == str(tmp_path / "from-flag")


def test_empty_flag_value_leaves_the_environment_in_charge(monkeypatch, tmp_path):
    # argparse hands main() None when --synth-root is omitted; that must not
    # overwrite a perfectly good environment value with nothing.
    monkeypatch.setenv(paths.SYNTH_ROOT_ENV, str(tmp_path))
    paths.set_synth_root(None)
    assert paths.synth_root() == str(tmp_path)


def test_lane_root_joins_under_the_configured_root(monkeypatch, tmp_path):
    monkeypatch.setenv(paths.SYNTH_ROOT_ENV, str(tmp_path))
    assert paths.lane_root("sdtmig-3-4") == str(tmp_path / "sdtmig-3-4")


def test_unconfigured_root_fails_immediately_and_says_how(monkeypatch):
    """No configuration => a loud failure naming both routes, not a hidden default.

    The previous behaviour was an absolute path baked into three scripts. It
    resolved on one machine; anywhere else the harness either wrote studies into a
    directory nobody asked for or died on the first open() with no hint of what to
    set.
    """
    monkeypatch.delenv(paths.SYNTH_ROOT_ENV, raising=False)

    with pytest.raises(paths.SynthRootNotConfigured) as excinfo:
        paths.synth_root()
    message = str(excinfo.value)
    assert paths.SYNTH_ROOT_ENV in message     # names the environment variable
    assert paths.SYNTH_ROOT_FLAG in message    # …and the command-line flag

    with pytest.raises(paths.SynthRootNotConfigured):
        paths.lane_root("sdtmig-3-4")


def test_no_module_level_absolute_default_survives():
    """Guard against a convenience default creeping back in (see test_library)."""
    absolute_defaults = {
        name: value
        for name, value in vars(paths).items()
        if isinstance(value, str) and value.startswith("/") and not name.startswith("__")
    }
    assert absolute_defaults == {}
