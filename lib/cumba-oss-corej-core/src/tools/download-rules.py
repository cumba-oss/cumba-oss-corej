#!/usr/bin/env python3
"""Download a CDISC Library conformance-rules package and store it as a
pretty-printed JSON file matching the repo's existing rules-org/ format.

The CDISC Library rules endpoints are served publicly (no API key required):

    GET https://api.library.cdisc.org/api/mdr/rules/{standard}/{version}

The download is forced fresh: cache-defeating request headers plus a unique
cache-busting query parameter are sent so no intermediary (CDN / proxy) can
return a stale copy.

The response is re-emitted with the same whitespace style Jackson's
DefaultPrettyPrinter produces (2-space indent, "key" : value with a space on
both sides of the colon, single-line "[ ... ]" arrays, raw UTF-8 -- no \\uXXXX
escapes -- and no trailing newline). That style is verified byte-for-byte
against the committed rules-org/rules-sdtmig-3-4-2026-04-09-final.json, so
regenerated files diff cleanly against the originals.

Usage:
    download-rules.py <standard/version> <target> [options]

    <standard/version>  e.g. "sdtmig/3-4" (also accepts "sdtmig 3-4" via
                        --version, see below)
    <target>            either a directory -- in which case the file name
                        "rules-{standard}-{version}-{YYYY-MM-DD}-final.json"
                        is generated -- or an explicit *.json file path.

Examples:
    download-rules.py sdtmig/3-4 ../../rules-org/
    download-rules.py sdtmig/3-4 /tmp/sdtmig-3-4.json --date 2026-06-22
    download-rules.py sendig 3-1-1 ./out/ --version-arg
"""
import argparse
import datetime
import json
import os
import sys
import urllib.request

DEFAULT_BASE_URL = "https://api.library.cdisc.org/api/"

# Environment variables mirrored from the Java CdiscLibraryClient. An API key
# is NOT required for the rules endpoints, but if one is configured it is sent
# (harmless, and lets the tool reach key-gated standards too).
ENV_BASE_URL = "CDISC_API_URL"
ENV_API_KEY = "CDISC_API_KEY"


# --- Jackson DefaultPrettyPrinter-compatible serialization ---------------
#
# Verified to reproduce rules-org/rules-sdtmig-3-4-2026-04-09-final.json
# byte-for-byte (parse -> dump).


def _esc(value):
    """JSON-encode a scalar the way Jackson does: raw non-ASCII (ensure_ascii
    False) and no forward-slash escaping."""
    return json.dumps(value, ensure_ascii=False)


def _fmt(value, indent):
    if isinstance(value, dict):
        if not value:
            return "{ }"
        pad = " " * (indent + 2)
        items = [f"{pad}{_esc(k)} : {_fmt(v, indent + 2)}" for k, v in value.items()]
        return "{\n" + ",\n".join(items) + "\n" + " " * indent + "}"
    if isinstance(value, list):
        if not value:
            return "[ ]"
        # FixedSpaceIndenter: the whole array stays on one logical line; any
        # nested objects keep the array's own indent (arrays add no level).
        return "[ " + ", ".join(_fmt(e, indent) for e in value) + " ]"
    return _esc(value)


def jackson_pretty(obj):
    """Serialize *obj* in Jackson DefaultPrettyPrinter style, no trailing LF."""
    return _fmt(obj, 0)


# --- Download ------------------------------------------------------------


def download_rules(standard, version, base_url, api_key):
    """Fetch /mdr/rules/{standard}/{version} fresh (no caching) and return the
    parsed JSON object."""
    # Cache-busting query parameter: a unique-per-invocation token so any CDN
    # treats this as a never-before-seen URL.
    token = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dt%H%M%S%f")
    url = (
        base_url.rstrip("/")
        + f"/mdr/rules/{standard}/{version}?_cb={token}"
    )
    headers = {
        "Accept": "application/json",
        "Cache-Control": "no-cache, no-store, max-age=0",
        "Pragma": "no-cache",
    }
    if api_key:
        headers["api-key"] = api_key

    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=180) as response:
        raw = response.read()
    return json.loads(raw.decode("utf-8"))


# --- CLI -----------------------------------------------------------------


def resolve_target(target, standard, version, date_str):
    """Return the output file path. A directory target (or a path ending in a
    separator, or an existing dir) gets a generated, dated file name."""
    looks_like_dir = (
        target.endswith(os.sep)
        or target.endswith("/")
        or os.path.isdir(target)
        or not os.path.splitext(target)[1]
    )
    if looks_like_dir:
        name = f"rules-{standard}-{version}-{date_str}-final.json"
        return os.path.join(target, name)
    return target


def parse_standard_version(arg, version_arg):
    """Resolve (standard, version) from the combined token or the two-arg form."""
    if version_arg is not None:
        return arg, version_arg
    if "/" in arg:
        standard, _, version = arg.partition("/")
        return standard, version
    raise SystemExit(
        "error: provide the standard and version as 'standard/version' "
        "(e.g. sdtmig/3-4), or pass the version via --version."
    )


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Download a CDISC Library conformance-rules package and "
        "store it as a pretty-printed JSON file.",
    )
    parser.add_argument(
        "standard_version",
        metavar="standard/version",
        help="rules package to download, e.g. 'sdtmig/3-4'",
    )
    parser.add_argument(
        "target",
        help="output directory (file name is generated) or explicit *.json path",
    )
    parser.add_argument(
        "--version",
        dest="version_arg",
        default=None,
        help="version, when the first argument carries only the standard "
        "(e.g. standard_version='sdtmig' --version 3-4)",
    )
    parser.add_argument(
        "--date",
        default=None,
        help="date stamp for the generated file name (default: today, "
        "YYYY-MM-DD)",
    )
    parser.add_argument(
        "--base-url",
        default=os.environ.get(ENV_BASE_URL, DEFAULT_BASE_URL),
        help=f"API base URL (default: ${ENV_BASE_URL} or {DEFAULT_BASE_URL})",
    )
    parser.add_argument(
        "--api-key",
        default=os.environ.get(ENV_API_KEY),
        help=f"optional API key (default: ${ENV_API_KEY}); not required for "
        "the public rules endpoints",
    )
    args = parser.parse_args(argv)

    standard, version = parse_standard_version(
        args.standard_version, args.version_arg
    )
    if not standard or not version:
        raise SystemExit("error: both a standard and a version are required.")

    date_str = args.date or datetime.date.today().isoformat()
    out_path = resolve_target(args.target, standard, version, date_str)

    print(f"Downloading rules for {standard}/{version} (no cache) ...", file=sys.stderr)
    data = download_rules(standard, version, args.base_url, args.api_key)

    rules = data.get("rules") if isinstance(data, dict) else None
    rule_count = len(rules) if isinstance(rules, dict) else 0
    print(f"  received {rule_count} rules", file=sys.stderr)

    text = jackson_pretty(data)
    out_dir = os.path.dirname(out_path)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(text)

    print(f"Wrote {out_path} ({len(text.encode('utf-8'))} bytes)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
