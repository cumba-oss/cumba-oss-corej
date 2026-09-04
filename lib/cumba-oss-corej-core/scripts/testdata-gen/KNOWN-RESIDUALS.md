# Known residual findings on the clean study

> ## ⚠⚠ 2026-08-05 — this floor is out of date, and it grew rather than shrank
>
> From **2026-07-03** (family sharding, `03b058bf5`) to **2026-08-05**, `verify.py` and
> `verify_violations.py` invoked the CLI without `-f/--family`, so the engine loaded only
> the default **CDISC** family. Every rule in the two tables below is CORE or FDA (bar the
> engine-generated `GEN-VMCALM-LBL`), so **the floor could not be measured at all** — the
> comparison ran against 386 loaded rules instead of 1838 and reported PASS while checking
> nothing. Both scripts now resolve the family list from `packages.json` and pass it.
>
> **The first honest re-measurement (2026-08-05) is `OVERALL: FAIL`.** Every documented
> residual below **still fires — 9/9 SDTMIG and 6/6 SENDIG, nothing disappeared** — but
> **31 further clean-study findings** now appear that no entry here covers: 21 on SDTMIG
> (`CDISC-CG0359`, `FDA-SD0037/1078/1082/1149/1240/1321`, and 14 `PMDA-SD*`, five of them
> exact twins of documented FDA entries) and 10 on SENDIG (8 `CDISC-SEND-*` plus
> `FDA-SD0062`). All of those rules existed in the pre-sharding corpus, so the cause is
> five weeks of engine and corpus change, not new rules.
>
> **Deliberately not re-baselined.** Adding the 31 to `expected_residuals.json` would make
> the harness green and destroy the only evidence that anything moved. Each needs the same
> "why it is irreducible" analysis the entries below carry — or a fix. Until then this
> document describes a floor the study no longer sits on, and `verify.py` says so, loudly.

> ## 2026-08-06/07 — 9 of the 31 accepted by ruling; the floor is **still FAIL**
>
> The two tables below are **no longer the whole floor**. On the owner's explicit ruling
> (`plans/done/PLAN-sdtm-testdata-gen.md` §R.5, the two `RESOLVED 2026-08-05 (owner)` blocks),
> **9** of the 31 were added to `expected_residuals.json`: `CDISC-CG0359`, `PMDA-SD2244`,
> `PMDA-SD0062` and the five PMDA family twins (`PMDA-SD0021/0022/1077/1282/1339`) on
> **sdtmig**, and `FDA-SD0062` on **sendig**. Floor size 9 / 6 → **17 / 7**. Four existing
> entries were corrected: `CORE-000080` re-cited onto `FDA-SD1282` (kept, not deleted — it
> no longer fires), `FDA-SD0021` → `DS, MH`, `FDA-SD1077` → `BS, GF`, `FDA-SD1339` → `SM`
> with a rewritten rationale (it is an **SM** EPOCH contradiction, not the BS one).
>
> **`FDA-SD1078` / `PMDA-SD1078` were NOT added and must not be.** The rule is right and the
> generator was wrong: `generate.drop_unpopulated_permissible` now emits a Permissible
> variable only when some record populates it (measured: 578 columns across 33 sdtmig
> domains, 100 across 14 sendig domains — exactly the SD1078 population).
>
> **The remaining 22 are still undocumented, so the harness still reports `FAIL`**, and none
> of this was re-measured with the engine. See §C of the plan for what must be run before
> anyone calls it PASS — including five floor entries the generator fix is *predicted* to
> silence (`CORE-000080`, `FDA-SD1282`, `PMDA-SD1282`, `CORE-000594`, `GEN-VMCALM-LBL`).
> `expected_residuals.json` is the system of record; this document is its rationale.

> ## 2026-08-07 — the Permissible drop is now co-presence aware
>
> The predictions in the block above were measured, and the plain "drop every unpopulated
> Permissible column" rule was **wrong**: it silenced `SD1078` but broke nine *co-presence*
> rules (`--DY` vs `--DTC`, `--TPTREF` vs `--RFTDTC`, `--LNKID` across datasets) and left
> `GEN-VMCALM-LBL` unloadable, which fails `verify.py`'s vacuity guard outright. Undocumented
> findings went **13 → 20** and floor matches **16/17 → 12/17**.
>
> `copresence.py` now derives, from the lane's **shipped** rule packages, every rule whose
> verdict can flip when a column is removed, and `generate.Generator._plan_permissible_drops`
> spares the columns those rules pin — dropping *both* halves of a pair whenever both are
> droppable, and keeping the orphan only when its partner cannot go. Measured A/B on the same
> corpus (2026-08-07): every floor metric returns to the no-drop baseline (16/17 matched, 13
> undocumented, `GEN-VMCALM-LBL` loaded), while `SD1078`-family findings fall **1156 → 28**.
>
> **`FDA-SD1078` / `PMDA-SD1078` therefore still fire**, on the 14 sdtmig columns the corpus
> forbids dropping. `Generator.kept_permissible` records each one with the rule id that pins
> it. Driving them to zero needs the *other* half of the owner's ruling — **populate** those
> columns — and every one of them is a variable the generator deliberately leaves empty today
> (`--DTC`-derived study days on dateless BS/GF, the time-point family, link ids), so that is
> a separate piece of work, not a tweak to the drop.

The clean study is generated **strictly from each standard's published variable
definition** and is otherwise driven to zero findings. The findings that remain
are **not generator defects** — they are CDISC rule-set contradictions or
library-metadata defects that *no conformant dataset can avoid*. Each is listed
here with the reason it is irreducible.

## SDTMIG 3.4 lane (9 findings, 5 domains)

| Domain | Rules | Why it cannot be fixed |
|--------|-------|------------------------|
| **BS** | FDA-SD1077, CORE-000701, FDA-SD1339 | These rules **require `EPOCH`** on a dated Findings record, but `EPOCH` is **not in BS's allowed-variable list** — adding it raises `GEN-DISALLOW-BS` ("variable not defined for the dataset"). One rule requires a variable a second rule forbids. |
| **PP** | CORE-000080, FDA-SD1282 | PP's IG includes `PPTPTREF` but **not** its companions `PPTPT`/`PPTPTNUM`/`PPELTM`; the rule flags `--TPTREF` present without them. The companions cannot be added (not in PP's allowed list), and `PPRFTDTC` is `Exp` (omitting it raises "expected variable missing"), so `PPTPTREF` cannot simply be dropped either. |
| **MH** | FDA-SD0021, FDA-SD0022 | The time-point rules require a start/end time point, but populating `MHSTDTC`/`MHENDTC` then requires `MHSTDY`/`MHENDY` columns the IG omits and the model forbids adding (FDA-SD1087/CORE-000328). Either way one finding remains — a dateless MH (current choice) trips FDA-SD0021/0022; a dated MH trips the study-day rules. |
| **IS** | CORE-000594 | The rule flags `ISMSCBCE`'s label, **"Molecule Secreted by Cells"**, as not title-case. This is the *actual CDISC library label*; the rule is stricter than the published metadata. |
| **GF** | GEN-VMCALM-LBL | `GFSEQID`'s library label is literally `"Sequence Identifier \n"` (a stray escape sequence — a **library metadata defect**). The generator emits the cleaned label `"Sequence Identifier"` for usability, which the library-match rule then flags. Emitting the raw label instead trips the title-case rule (CORE-000594). Either way the defect surfaces. |

> The exact floor rule-set is committed in `expected_residuals.json` (what
> `verify.py` asserts against); this table is its human-readable rationale.

### Confirmation that these are external defects, not generator bugs

- Removing the domain's data does not help — the BS/PP/MH findings are *structural*
  (variable presence/absence per the IG), independent of row values.
- Adding the "missing" variable (`EPOCH`, `PP` timepoints, `MH` study-days) is
  rejected by the engine's allowed-variable check (`GEN-DISALLOW`).
- The IS/GF labels are emitted from (or derived from) the CDISC Library metadata
  in `cdisc-rules-engine/resources/cache`.

These findings are therefore the **floor** for a conformant SDTMIG study that
includes BS, PP, MH, IS and GF; they are reported here rather than masked.

> **Re-baseline 2026-06-29 (corpus mtime 19:32:46).** The SDTMIG floor is now
> **9** `Issue_Summary` rules — the exact set above (BS ×3, PP ×2, IS, GF, MH
> FDA-SD0021/0022). The earlier **11**-rule count included a **DS** start/no-end
> time-point pair that **no longer fires** against the regenerated corpus — a
> shrinking of the floor, not a regression (no new undocumented finding). The
> harness `scripts/testdata-gen/verify.py` re-measures this set each run and
> fails only on a fired rule **not** in the documented floor
> (`expected_residuals.json`).

## SENDIG 3.1.1 lane (6 findings, 4 domains)

The SENDIG lane is generated by the same code and is driven to its floor of **6
`Issue_Summary` entries**. All six are CDISC rule-set contradictions that no
conformant SEND dataset can avoid:

| Domain | Rules | Why it cannot be fixed |
|--------|-------|------------------------|
| **SE** | CORE-000328/000776, FDA-SD1087/1091 | SEND SE has `SESTDTC`/`SEENDTC` (Req) but **no `SESTDY`/`SEENDY` columns** in the model; the study-day rules require them and the engine's allowed-variable check forbids adding them (`GEN-DISALLOW`, same shape as MH/BS). |
| **TF** | FDA-SD0029 | TF has `TFRESCAT` (Req) which forces `--STRESC` to be populated (else FDA-SD0045/1320 fire), and TF has no `--STAT` to express NOT DONE. We populate a categorical `TFORRES`/`TFSTRESC` (`"ADENOMA"`, `TFRESCAT="BENIGN"`), which clears FDA-SD0045/0047/1320 — but TF has **no `--STRESU`** column, so a populated `--STRESC` necessarily trips FDA-SD0029 (itself flagged *"Partially Executable – Possible Overreporting"*). This is the minimal residual (one finding instead of three). |
| **BG, FW** | FDA-SE2307 | BG/FW are interval findings with `--DTC` + `--ENDTC` but **no `--STDTC`**. FDA-SE2307 requires both `--DY` and `--ENDY` populated. `--ENDY` requires a complete `--ENDTC`; but populating `--ENDTC` without `--STDTC` trips CORE-000892 (end time-point without a start), and populating `--ENDY` without a complete `--ENDTC` trips FDA-SD1093 (imputed study day). Every path yields exactly one finding, so leaving `--ENDY` empty (FDA-SE2307) is the floor. |

### Confirmation that these are external defects, not generator bugs

- **SE**: `SESTDY`/`SEENDY` are simply not in the SENDIG 3.1.1 SE variable list
  (verified via the engine metadata cache); adding either is rejected.
- **TF**: the FDA-SD0029 / FDA-SD0045 pair is a genuine model contradiction for a
  Req-`--RESCAT`, no-`--STRESU`, no-`--STAT` findings domain — verified by
  toggling the categorical result (3 findings empty ⇄ 1 finding populated).
- **BG/FW**: the CORE-000892 ⇄ FDA-SD1093 ⇄ FDA-SE2307 trilemma was verified by
  test (populating `--ENDTC` produced CORE-000892 on BG/CL/FW and regressed the
  SDTMIG IS/LB/PC datasets), so the generator leaves the end timing empty.

These six findings are the **floor** for a conformant SENDIG study that includes
SE, TF, BG and FW; they are reported here rather than masked.

## Study-shape constraints (non-default `--subjects` / `--visits`)

These are not residual *findings* on the default deliverable (20 subjects, 10
visits, which is clean to the floors above) but constraints that surface only on
degenerate CLI configs, documented here so a reviewer running edge cases knows
they are expected:

- **`--subjects` must be >= 3.** Subjects are assigned round-robin to the 3 arms
  (2 active + placebo). With fewer than 3 subjects, at least one ARMCD declared in
  TA/TX is never assigned to a subject in DM, so **`FDA-SD1354`** ("ARMCD defined
  in TA not present in DM") fires. Verified: `--subjects 1 --visits 2` adds
  exactly `FDA-SD1354` over the floor; `--subjects 100 --visits 5` is exactly at
  the floor (0 undocumented). This is an arms-vs-subjects coverage property, not a
  generator defect.
- **`--visits` must be >= 2.** The schedule is 1 screening + max(1, visits-2)
  treatment + 1 follow-up. The follow-up VISITNUM is computed as `n_trt + 2` so it
  never collides with the treatment visit even when `visits < 3` (regression
  test: `test_visit_numbers_unique_for_small_configs`).

> **Rule-corpus stability note.** The `lib/corej-rules/rules/*.json` files
> were regenerated by a parallel process twice on 2026-06-29 (the SENDIG corpus
> `rules-sendig-3-1-1.json` mtime moved to 18:34:55 mid-session). The counts
> above were measured against that corpus. Re-validate against the current
> corpus before treating any count as final.
