# Synthetic SDTM/SEND test-data generator

Generates clean, internally-consistent synthetic study data to exercise the CORE
rule engine against domains the existing test studies don't cover. See the plan
[`plans/done/PLAN-sdtm-testdata-gen.md`](../../../../plans/done/PLAN-sdtm-testdata-gen.md).

## Usage

```bash
PY=../../.venv-py-parity/bin/python   # has pyyaml + pytest
$PY generate.py --standard sdtmig --version 3-4 --out /data/testdata/synthetic/sdtmig-3-4/clean
$PY generate.py --standard sendig --version 3-1-1 --out /data/testdata/synthetic/sendig-3-1-1/clean
```

Each run also writes a full **Define-XML v2.1** (`define.xml`) into the output dir,
matching the generated data exactly (one `ItemGroupDef` per dataset, one `ItemDef`
per column, fully-enumerated `CodeList`s for every CT-bound variable). Pass it to
the engine via `-dxp define.xml -dv 2-1` so the define-metadata rules execute
instead of being `SKIPPED` (see below).

Options: `--subjects` (default 20), `--visits` (10), `--seed` (0). Output is
deterministic for a given (standard, version, subjects, visits, seed).

> **The pickle cache is a host path, not a repo path.** `library._default_cache_dir()`
> and the `-pc` flag both default to `/data/cdisc.metadata.library-cache-pkl`
> (`library.DEFAULT_CACHE_DIR`). On a host that keeps the cache elsewhere that
> directory does not exist and every entry point dies on the first `open()`. Point
> `CDISC_PICKLE_CACHE_DIR` at a materialised cache instead; both `library.py` and the
> two `verify*.py` harnesses read it, so the generator and the engine cannot end up on
> different metadata:
>
> ```bash
> export CDISC_PICKLE_CACHE_DIR=/data/cdisc.metadata.library-cache-pkl
> export CDISC_API_CACHE=/data/cdisc.metadata.library-cache
> ```

> **Shape constraints for a fully clean study.** Subjects are assigned
> round-robin to the 3 arms (2 active + placebo), so `--subjects` must be **>= 3**
> for every arm in TA/TX to be assigned to at least one subject — otherwise
> `FDA-SD1354` ("ARMCD defined in TA not present in DM") fires for the unpopulated
> arm(s). `--visits` must be **>= 2** (the schedule is 1 screening + >=1 treatment
> + 1 follow-up). The default 20/10 satisfies both. These are study-shape
> constraints, not generator bugs.

## Validate the generated study

```bash
cd /data/net.cumba.corej
java -Dcorej.maxErrorsPerRule=0 \
  -jar clients/corej-cdisc-cli/target/corej-cdisc-cli-0.1.0-SNAPSHOT.jar \
  -rp cdisc-sdtmig-3-4 -d /data/testdata/synthetic/sdtmig-3-4/clean \
  -dxp /data/testdata/synthetic/sdtmig-3-4/clean/define.xml -dv 2-1 \
  --rules-dir lib/corej-cdisc-rules/rules \
  -pc /data/cdisc.metadata.library-cache-pkl \
  -of json2 -o /tmp/report.json
# clean == report.v2.json "Issue_Summary" empty except the documented residuals.
# With -dxp, the define-metadata rule CORE-001081 ("Define Item Metadata Check
# against Library Metadata") executes (SUCCESS) instead of appearing in
# Skipped_Rules — SDTMIG Skipped drops 80 -> 41. (CORE-001081 is SDTMIG-only, so
# the SENDIG lane's define is accepted with no skip change and no new findings.)
```

## How it works

| Module | Responsibility |
|--------|----------------|
| `library.py` | Reads the engine pickle cache (`variables_metadata.pkl`, `variable_codelist_maps.pkl`, `sdtmct/sendct-*.pkl`) — the same metadata the engine validates against. |
| `rulescan.py` | Scans `rules-src/checks/CORE/*.yaml` for per-domain targets and referenced variables. |
| `copresence.py` | Derives, from the lane's **shipped** rule packages, which unpopulated Permissible columns may not be dropped — co-presence pairs (`--DTC` ⇒ `--DY`), cross-dataset presence counts (`--LNKID`), and column-metadata rules. |
| `study.py` | Deterministic subject + trial skeleton (subjects, arms, visits, epochs, reference dates). |
| `domains.py` | Which domains each lane generates; key/topic derivation. |
| `values.py` | Human-readable cell values (CT terms, decoded test names, per-test numeric ranges). |
| `generate.py` | Orchestrates: column selection (standard-bounded), per-domain row builders, study-day post-pass, dataset-json emission. |
| `emit.py` | Dataset-JSON 1.1 writer (always includes `datasetJSONCreationDateTime`). |
| `define.py` | Define-XML v2.1 writer — ItemGroupDefs/ItemDefs/CodeLists driven by the same library metadata + generated datasets, so it matches the data. Roles on `ItemRef` mirror the library role (keeps CORE-001081 green). |

## Design rules (learned from engine feedback)

- **Standard-bounded columns.** Only a standard's own variables are emitted, so
  cross-standard variables (e.g. `AGETXT` in an SDTMIG DM) never appear.
- **Populate Req/Exp, empty Perm.** Unneeded permissible qualifiers stay empty
  (conformant), which avoids most cross-field consistency rules.
- **Coherent groups.** Result group (`--ORRES/--STRESC/--STRESN/--STRESU`),
  timing (`--DTC` → `--DY`/`EPOCH`/`VISIT`), categories, and MedDRA dictionary
  vars are filled as consistent units.
- **No injected variables.** A variable the IG omits cannot be added — the engine
  rejects it (`GEN-DISALLOW`). See [`KNOWN-RESIDUALS.md`](KNOWN-RESIDUALS.md) for
  the findings this makes irreducible.

## Verify everything (Phase 6 harness)

`verify.py` is the one-command CI gate. Per lane it regenerates `clean/` +
`define.xml`, re-applies every violation injector, runs the Java CLI, asserts the
clean study is at its documented conformance floor (`expected_residuals.json` /
[`KNOWN-RESIDUALS.md`](KNOWN-RESIDUALS.md)), verifies every injector fires its
target rule, writes a coverage report to
`documentation/synthetic-testdata-coverage.md`, and exits non-zero on any failure.

```bash
$PY verify.py                    # both lanes, full regen
$PY verify.py --lane sdtmig      # one lane
$PY verify.py --skip-regen       # reuse the existing clean/ + violations/
```

A fired clean-study rule **not** in `expected_residuals.json` is a FAILURE; a
floor rule that stops firing (corpus drift) is reported as a notable change, not
a failure. Re-baseline `expected_residuals.json` when the rule corpus changes.

## Tests

```bash
$PY -m pytest tests/ -q
```

## Status & next steps (for whoever picks this up)

**Status:** the design plan
[`plans/done/PLAN-sdtm-testdata-gen.md`](../../../../plans/done/PLAN-sdtm-testdata-gen.md) is
**complete (Phases 0–7)** and independently verified. Both lanes (SDTMIG-3.4,
SENDIG-3.1.1) generate clean studies at their documented conformance floor
(9 / 6 findings, all irreducible CDISC rule-set/library defects — see
[`KNOWN-RESIDUALS.md`](KNOWN-RESIDUALS.md)), each with a matching `define.xml` and
per-rule violation sub-studies. `verify.py` is green; `pytest` = 87 passed,
1 skipped. **First thing to run to confirm the world still holds:** `verify.py`.

**Gotcha — the rule corpus drifts.** `lib/corej-cdisc-rules/rules/*.json` are
regenerated by other efforts in this repo (they changed several times mid-build).
If `verify.py` reports a floor change, that's usually corpus drift, not a
generator bug: re-baseline by updating `expected_residuals.json` (a *shrinking*
floor with `undocumented=0` is fine; investigate any *new* undocumented finding).

**Natural extensions (framework already supports them):**
- **More violation injectors** — 26 of ~142 domain-specific rules are covered;
  the framework makes adding more mechanical. See
  [`violations/README.md`](violations/README.md) "Adding more injectors" and
  "What is deferred" (`longer_than` on cross-referenced keys, `$`-operation / VLM
  / multi-record rules that need multi-row mutations).
- **More standards / versions** — the generator is `--standard`-parameterized. A
  new lane needs: a `library.StandardSpec` (std/ver/CT package), its domain list
  in `domains.py`, and a TS-parameter set in `generate.py`. Candidates: SDTMIG
  3.2/3.3, SENDIG-DART, TIG, ADaM (ADaM would need a BDS/ADSL-shaped `study.py`).
- **XPT output** — currently Dataset-JSON only; the PhUSE reference study uses
  `.xpt`, so an XPORT writer alongside `emit.py` would broaden reuse.
- **Java↔Python parity (Phase 6 stretch) — HISTORICAL, never performed and no
  longer performable** — the original plan was to run the Python reference
  engine (the vendored `cdisc-rules-engine/` fork) on these studies and assert
  Java==Python on the newly covered domains. The fork and its lane were deleted
  in wave 33, and the spec suite is Java-canonical
  (`lib/corej-cdisc-rules/rulespec/`) — there is no second engine to compare
  against.
  Kept as a record of the phase plan, not as an open extension.
- **CI** — wire `verify.py` in as a gate (it already exits non-zero on failure).

**Upstream value:** the documented-irreducible floors in `KNOWN-RESIDUALS.md` are
genuine CDISC conformance-rule / library-metadata contradictions (a rule requires
a variable the model forbids; a library label carries a literal `\n`). They are
worth reporting upstream — this synthetic study is a minimal reproducer for each.
