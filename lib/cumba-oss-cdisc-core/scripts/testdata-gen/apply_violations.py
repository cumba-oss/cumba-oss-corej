"""Driver: run a chosen set of per-rule violation injectors against a clean study.

For each selected injector this:

1. loads a **fresh** copy of the lane's clean study (the clean study is never
   mutated in place),
2. calls ``inject(study)`` — which applies the minimal single mutation that trips
   exactly that injector's CORE rule and returns an expectation dict,
3. writes the mutated Dataset-JSON files + ``expectation.json`` into
   ``<out>/<coreId>/``.

Usage::

    PY=../../.venv-py-parity/bin/python
    $PY apply_violations.py --standard sdtmig --all
    $PY apply_violations.py --standard sendig --rules CORE-000310
"""

from __future__ import annotations

import argparse
import importlib
import os
import pkgutil
import sys

# Make the generator modules (library, emit, …) and the violations package
# importable regardless of the caller's CWD.
_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

from violations import lib as vlib  # noqa: E402


_DEFAULT_CLEAN = {
    "sdtmig": "/data/testdata/synthetic/sdtmig-3-4/clean",
    "sendig": "/data/testdata/synthetic/sendig-3-1-1/clean",
}
_DEFAULT_OUT = {
    "sdtmig": "/data/testdata/synthetic/sdtmig-3-4/violations",
    "sendig": "/data/testdata/synthetic/sendig-3-1-1/violations",
}


class Injector:
    """A discovered injector module: its ``META`` plus its ``inject`` callable."""

    def __init__(self, module) -> None:
        self.module = module
        self.meta = module.META
        self.core_id = self.meta["coreId"]
        self.standard = self.meta["standard"]
        self.inject = module.inject

    def __repr__(self) -> str:  # pragma: no cover - debug aid
        return f"<Injector {self.core_id} {self.standard}>"


def discover() -> list[Injector]:
    """Import every injector module under ``violations/`` (modules expose META)."""
    import violations

    found: list[Injector] = []
    for info in pkgutil.iter_modules(violations.__path__):
        if info.name == "lib":
            continue
        module = importlib.import_module(f"violations.{info.name}")
        if hasattr(module, "META") and hasattr(module, "inject"):
            found.append(Injector(module))
    found.sort(key=lambda inj: (inj.standard, inj.core_id))
    return found


def select(injectors: list[Injector], standard: str, rules: set[str] | None) -> list[Injector]:
    out = [inj for inj in injectors if inj.standard == standard]
    if rules is not None:
        out = [inj for inj in out if inj.core_id in rules]
    return out


def apply_one(inj: Injector, clean_dir: str, out_root: str) -> str:
    """Run a single injector; return the sub-study directory written."""
    study = vlib.load_clean(clean_dir)
    expectation = inj.inject(study)
    expectation = {
        "coreId": inj.core_id,
        "standard": inj.standard,
        "summary": inj.meta.get("summary", ""),
        "expect_fires": True,
        "allowedCollateral": inj.meta.get("allowedCollateral", []),
        **expectation,
        "changes": study.changes,
    }
    out_dir = os.path.join(out_root, inj.core_id)
    vlib.write_substudy(out_dir, study, expectation, clean_dir)
    return out_dir


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--standard", required=True, choices=("sdtmig", "sendig"))
    ap.add_argument("--rules", help="comma-separated CORE ids; omit with --all")
    ap.add_argument("--all", action="store_true", help="run every injector for the lane")
    ap.add_argument("--clean", help="clean study dir (default: lane's clean dir)")
    ap.add_argument("--out", help="output root (default: lane's violations dir)")
    args = ap.parse_args(argv)

    clean_dir = args.clean or _DEFAULT_CLEAN[args.standard]
    out_root = args.out or _DEFAULT_OUT[args.standard]

    rules: set[str] | None
    if args.all:
        rules = None
    elif args.rules:
        rules = {r.strip() for r in args.rules.split(",") if r.strip()}
    else:
        ap.error("pass --all or --rules CORE-xxx,...")
        return 2

    injectors = select(discover(), args.standard, rules)
    if not injectors:
        print(f"no injectors selected for {args.standard} (rules={rules})")
        return 1

    for inj in injectors:
        out_dir = apply_one(inj, clean_dir, out_root)
        print(f"{inj.core_id:14s} -> {out_dir}")
    print(f"\n{len(injectors)} sub-study(ies) written under {out_root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
