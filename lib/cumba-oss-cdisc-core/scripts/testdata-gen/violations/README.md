# Per-rule violation injectors (Phase 4)

Each injector takes the lane's **clean** study, applies the *minimal single
mutation* that trips exactly one CORE rule, and emits a self-contained "dirty"
sub-study plus an `expectation.json`. The clean study is never mutated in place.

See the design in
[`plans/done/PLAN-sdtm-testdata-gen.md`](../../../../../plans/done/PLAN-sdtm-testdata-gen.md)
("Phase 4 — Per-rule violation injector scripts").

## Layout

```
violations/
  lib.py              # Study (change-tracking), load_clean, write_substudy,
                      # build_presence_dataset (DART TP/TT/SJ)
  c<digits>_<lane>.py # one injector per targeted rule (module name maps to META.coreId)
apply_violations.py   # driver: run a chosen subset of injectors for a lane
verify_violations.py  # engine verification (runs the Java CLI; not in pytest)
tests/test_violations.py
```

Module names can't start with a digit, so `CORE-000068` → module `c000068_sdtmig`;
the real id lives in `META["coreId"]`. The same rule can have an injector per lane
(e.g. `c000310_sdtmig` and `c000310_sendig`), keyed by `(standard, coreId)`.

## Running

```bash
PY=../../.venv-py-parity/bin/python
# write all sub-studies for a lane (default out: /data/testdata/synthetic/<lane>/violations)
$PY apply_violations.py --standard sdtmig --all
$PY apply_violations.py --standard sendig --rules CORE-000310
# engine verification (requires the built corej-cdisc-cli jar)
$PY verify_violations.py --standard sdtmig
```

Each injector exposes:

```python
META = {"coreId", "standard", "domain", "summary", "allowedCollateral"?}
def inject(study) -> dict   # mutates study, returns an expectation fragment
```

`expectation.json` = `{coreId, standard, summary, expect_fires, allowedCollateral,
domain, variable, expect_status, changes:[...]}`. `changes` is the auto-recorded
mutation log (cell / add_column / add_dataset), so a sub-study can be diffed back
against clean.

## Verification result (against the current rules corpus, 2026-06-29)

> ⚠ **STALE — this table describes the 26-injector set as it stood on
> 2026-06-29, before 11 of the 12 SENDIG injectors were removed (see below).
> It has NOT been re-measured against the surviving 15.** Treat the numbers as
> historical until `verify_violations.py` is re-run.

Every injector's target rule **fires**; "PASS" = the target is the *only* new
finding; "PASS\*" = the only other new findings are **documented same-condition
twins** (see below).

| Lane   | injectors | fire target | strict (exactly one) |
|--------|-----------|-------------|----------------------|
| SDTMIG | 14        | 14/14       | 3 (TP/TT/SJ presence) |
| SENDIG | 12        | 12/12       | 4 (CORE-100093/100099/100144/100181) |
| total  | **26**    | **26/26**   | 7                    |

### 2026-08-08 — 11 SENDIG injectors removed as dead

Commit `95ef971f7` (*"removed wrong CORE rules. These have never been authored in
CORE."*) deleted the `CORE-1xxxxx` rules these injectors targeted. All 11 were
re-confirmed absent from **every** org under `rules-src/checks/`
(CDISC / CORE / FDA / PMDA), deleted rather than renamed, so there was no new id
to re-point at:

`CORE-100074` `CORE-100075` `CORE-100093` `CORE-100094` `CORE-100098`
`CORE-100099` `CORE-100120` `CORE-100121` `CORE-100142` `CORE-100144`
`CORE-100181`

⇒ the injector set is now **15** (14 SDTMIG + 1 SENDIG, `CORE-000310`).

⚠ **The SENDIG lane is therefore down to a single injector.** Restoring SENDIG
coverage means authoring injectors against rules that actually ship — the
`CDISC-SEND-*` family is the natural source — and is deliberately **not** done
here.

### Why "documented twins" exist

The active corpus contains many **redundant rules** that flag the *same* condition
(CORE rules paired with FDA twins, plus generic `ALL`-scope checks). A single
minimal mutation therefore often trips a small, fixed family of rules, e.g.:

- `AGE = -1` → `CORE-000310` **and** `FDA-SD0084` (`AGE <= 0`) — any value `< 0` is
  also `<= 0`, so the twin is irreducible.
- a forbidden **variable** (e.g. `AGETXT`) → the target presence rule **plus** the
  engine's disallowed-variable check (`GEN-DISALLOW-<DOM>`) and the
  observation-class column-order rules (`CORE-000852` / `FDA-SD1079`). These fire on
  the column's *existence* and cannot be avoided while injecting the variable.
- blanking a **Required** SEND variable → the specific rule **plus** the generic
  `CORE-000356` / `FDA-SD0002` ("required variable empty").
- blanking a result **unit** → `CORE-000699` / `FDA-SD0007` (unit inconsistent
  across the test) and `FDA-SD0026` (numeric `--ORRES` without `--ORRESU`).

Each injector lists its irreducible twins in `META["allowedCollateral"]` with a
comment explaining *why* they co-fire. `verify_violations.py` passes a sub-study
when the target fires and the only extra new findings are those declared twins.
Mutations were still made as surgical as possible — e.g. the cross-dataset date
injector recomputes `--STDY`, and the `BWTESTCD` membership injector also renames
`BWTEST` to keep the `TESTCD<->TEST` relationship 1:1 — so collateral is genuinely
the redundant-rule floor, not sloppy injection.

## Coverage by check shape

| Shape | rules | lane |
|-------|-------|------|
| Domain Presence Check (add DART dataset) | CORE-000043 TP, CORE-000042 TT, CORE-000044 SJ | SDTMIG |
| Variable presence (`exists`, add forbidden column) | CORE-000068 AGETXT, CORE-000069 SPECIES, CORE-000003 TRLOC, CORE-000076 TRPORTOT, CORE-000048 EXMETHOD, CORE-000012 AEOCCUR | SDTMIG |
| Numeric range (`less_than`) | CORE-000310 AGE<0 | SDTMIG + SENDIG |
| Empty (single `empty`) | CORE-000109 SMSTDTC, CORE-000522 DSCAT | SDTMIG |
| Format / `not_matches_regex` (duration) | CORE-000779 TDSTOFF | SDTMIG |
| Cross-dataset date (`date_less_than`) | CORE-000086 DVSTDTC<RFICDTC | SDTMIG |

⚠ **Four check shapes lost their only coverage** when the 11 dead SENDIG
injectors were removed on 2026-08-08, because every injector for them targeted a
`CORE-1xxxxx` rule that no longer exists:

| Shape (now UNCOVERED) | removed injectors |
|-------|-------|
| Empty (single `empty`, Required) | CORE-100074 EXDOSFRQ, CORE-100075 EXROUTE, CORE-100120 OMSPEC, CORE-100142 TFDETECT |
| Empty/non-empty consistency (two-leaf) | CORE-100121 OMSTRESU, CORE-100094 BW units, CORE-100098 BG units, CORE-100099 CLSEV |
| Equality (`equal_to` / `not_equal_to`) | CORE-100181 SNDIGVER, CORE-100144 TFRESCAT |
| Membership (`is_not_contained_by` list) | CORE-100093 BWTESTCD |

## Adding more injectors

Drop a `c<digits>_<lane>.py` exposing `META` + `inject(study)` into this package;
the driver and tests discover it automatically. Use the `lib.Study` primitives
(`find_row`, `set_cell`, `add_column`, `add_dataset`) so the change log — and thus
`expectation.json` and the diff test — stay accurate.

## What is deferred

This is a **representative** set (~2–3 rules per missing domain across both lanes,
spanning every common operator family), not the full ~142 domain-specific rules.
The framework makes adding the rest incremental. Deliberately **not** yet covered:

- **`length` / `longer_than` on key variables** (`ARMCD`, `ACTARMCD`, `SETCD`,
  `ETCD`, `TSPARMCD`): these are cross-referenced (TA/TX membership, `--`-pairings)
  so a one-cell length change cascades into membership/uniqueness collateral. They
  need a coordinated multi-row mutation, out of scope for the single-isolated-row
  contract here.
- **Rules requiring an operation/`$`-variable, define.xml VLM, or multi-record
  context** (`is_not_unique_set`, terminal-count thresholds, RELREC partners): not
  triggerable by one isolated row; left for a later batch once Phase 5 (define)
  lands.
- **`PE` two-leaf result rule (CORE-000082):** the clean PE domain is emitted as
  `PESTAT = "NOT DONE"` with empty results, so the rule cannot be tripped without a
  second, study-shaping mutation; dropped in favour of the SENDIG two-leaf set.
- **CORE-000326 (EXMETHOD)** is not in the active rules corpus; the equivalent
  `--METHOD` presence is exercised via **CORE-000048** instead.

Adding any of the above is a matter of writing one more injector module (and, for
the multi-row cases, a small helper) — no framework changes required.
