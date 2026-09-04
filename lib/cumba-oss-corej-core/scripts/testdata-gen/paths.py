"""Where the generated synthetic study tree lives.

The synthetic studies are an **output** of this generator, not content of this
repository: no clone, CI runner or release carries them, and their location
differs per host. They therefore have no built-in default. Every entry point
resolves the root from its own ``--synth-root`` flag or from
:data:`SYNTH_ROOT_ENV`, and fails immediately — naming both — when neither is
given, rather than falling back to an absolute path that exists on one machine
only.
"""

from __future__ import annotations

import os

#: Environment variable naming the root of the generated synthetic study tree —
#: the directory that holds the per-lane ``sdtmig-3-4/``, ``sendig-3-1-1/`` … .
SYNTH_ROOT_ENV = "CDISC_SYNTHETIC_TESTDATA_ROOT"

#: The command-line flag every harness exposes for the same value.
SYNTH_ROOT_FLAG = "--synth-root"

#: Set from ``--synth-root`` by an entry point's ``main()``; takes precedence
#: over the environment. A module-level override rather than a threaded
#: parameter because the lane roots are read from helpers that take only a lane
#: name — threading it would change signatures the harnesses call each other by.
_synth_root_override: str | None = None


class SynthRootNotConfigured(RuntimeError):
    """No synthetic-testdata root was supplied by any supported route."""


def set_synth_root(value: str | None) -> None:
    """Record a ``--synth-root`` value. An empty value leaves the environment in charge."""
    global _synth_root_override
    if value:
        _synth_root_override = value


def synth_root() -> str:
    """The synthetic-testdata root: ``--synth-root`` first, then the environment."""
    root = _synth_root_override or os.environ.get(SYNTH_ROOT_ENV)
    if not root:
        raise SynthRootNotConfigured(
            "No synthetic-testdata root configured. The generated study tree is "
            "not part of this repository and has no location that can be "
            f"assumed, so there is no default. Pass {SYNTH_ROOT_FLAG} <dir> or "
            f"set the {SYNTH_ROOT_ENV} environment variable."
        )
    return root


def lane_root(lane_dir: str) -> str:
    """``<synthetic-testdata root>/<lane_dir>``, e.g. ``…/sdtmig-3-4``."""
    return os.path.join(synth_root(), lane_dir)


def add_synth_root_argument(parser) -> None:
    """Register the shared ``--synth-root`` flag on an ``argparse`` parser."""
    parser.add_argument(
        SYNTH_ROOT_FLAG,
        help=f"root of the generated synthetic study tree (else ${SYNTH_ROOT_ENV})",
    )
