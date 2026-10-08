package net.cumba.corej.core.expr.eval.spi;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.cumba.corej.core.exec.ArithmeticSemantics;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.LibraryAnswerability;
import net.cumba.corej.core.exec.ScalarMetadataFunctions;
import net.cumba.corej.core.exec.ScalarSemantics;
import net.cumba.corej.core.expr.eval.CalendarDates;
import net.cumba.corej.core.expr.eval.ColumnVector;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.DynamicColumnRead;
import net.cumba.corej.core.expr.eval.EvalFunction;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionKind;
import net.cumba.corej.core.expr.eval.FunctionProvider;
import net.cumba.corej.core.expr.eval.IsoDateBounds;
import net.cumba.corej.core.expr.eval.IsoDateComparison;
import net.cumba.corej.core.expr.eval.IsoDurationBounds;
import net.cumba.corej.core.expr.eval.ListVector;
import net.cumba.corej.core.expr.eval.Parameter;
import net.cumba.corej.core.expr.eval.Primitives;
import net.cumba.corej.core.expr.eval.TemporalPredicates;
import net.cumba.corej.core.expr.eval.TextFunctions;
import net.cumba.corej.core.expr.eval.TypedValue;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.expr.typed.ExprType.ListOf;
import net.cumba.corej.core.expr.typed.ExprType.Primitive;
import net.cumba.corej.core.expr.typed.ExprType.Unknown;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * The v1 built-in {@link FunctionProvider}, discovered via the project SPI
 * ({@code GenericServiceFactory}).
 *
 * <p>
 * Registers the canonical generic spellings (decision #14) with the legacy operator spellings kept
 * as accepted aliases, at their fixed arities:
 * </p>
 * <ul>
 * <li><b>Conversions and formatting</b> ({@code PLAN-dynamic-column-functions}, owner Q2/Q10):
 * {@code num}/{@code str}/{@code date}/{@code time} (registered; {@code num}/{@code date}/
 * {@code time} keep their compiler routing, which selects a comparison family), {@code printf} and
 * {@code lpad} ({@link TextFunctions}).</li>
 * <li><b>VALUE transforms</b>: {@code lower}/{@code lowcase}, {@code upper}/{@code upcase},
 * {@code len}/{@code length} (integer length), {@code colref} (the dynamic column read,
 * {@link DynamicColumnRead}), and the native-only helpers
 * {@code abs}/{@code round}/{@code floor}/{@code ceil} (numeric), {@code trim}, {@code concat}
 * (arity 2/3), {@code coalesce} (arity 2/3), {@code substring} (arity 2/3, 1-based start),
 * {@code prefix}/{@code suffix} (arity 2 — the first/last n characters, the legacy
 * {@code prefix_*}/{@code suffix_*} comparison operand), and the ISO-8601 date-component extractors
 * {@code year}/{@code month}/{@code day} (LONG).</li>
 * <li><b>BOOLEAN predicates</b>: {@code empty} (missing or {@code ""}), {@code is_missing} (a
 * {@code MissingValue} only — register D50, owner 2026-10-05), {@code non_empty}/{@code present}/
 * {@code is_present}, {@code contains}, {@code does_not_contain}, {@code starts_with},
 * {@code ends_with}, {@code equalsIgnoreCase}, {@code prefix_matches}/{@code suffix_matches} (arity
 * 2 = whole operand; arity 3 = length-bounded affix), {@code imatches} (case-insensitive regex
 * search), {@code is_integer}, {@code is_not_integer}, {@code invalid_duration},
 * {@code is_valid_duration}, and the three-predicate calendar-validating date family
 * {@code is_valid_date}/ {@code is_complete_date}/{@code is_partial_date} (alias
 * {@code is_incomplete_date}) plus {@code invalid_date} (= not valid) and the date-portion-only
 * pair {@code is_complete_date_part}/{@code is_not_complete_date_part} (Fix #157).</li>
 * </ul>
 *
 * <p>
 * Not registered here (handled by the compiler as core, since they are not pure row-value
 * predicates): the comparison/membership/regex-match operators ({@code == != < > <= >= in =~ !~}),
 * the boolean combinators, the erased type-tag markers {@code date_part}/{@code time_part} (they
 * change comparison <i>dispatch</i> rather than transform a value), and
 * {@code exists}/{@code not_exists} (dataset/column existence facts that need the
 * {@link net.cumba.corej.core.exec.EvaluationContext} and the operand name, not its row values).
 * </p>
 *
 * <p>
 * <b>A missing input to a VALUE function is handed through as its own cell</b> (register D36 for
 * the string transforms, {@code PLAN-case-fold-missing-d36}; D85c / D86a for the non-string ones,
 * {@code PLAN-missing-identity-nonstring-functions}): {@code upper}/{@code lower}, {@code trim},
 * {@code normalize_space}, {@code prefix}/{@code suffix}, {@code substring}, {@code concat}, and
 * {@code len}, {@code char}, {@code abs}/{@code round}/{@code floor}/{@code ceil},
 * {@code year}/{@code month}/{@code day}, {@code earliest_possible}/{@code latest_possible},
 * {@code coalesce} (once every operand is skipped), {@code split_by} and {@code colref} answer the
 * input's {@link MissingValue} — identity kept ({@code .A} stays {@code .A}), several distinct
 * identities collapsing to {@code MIS} per D86a ({@link ArithmeticSemantics#combinedMissing} /
 * {@link ArithmeticSemantics#carrierCell}) — never {@code ""} and never a fresh {@code MIS}. The
 * identity is decided before any parse. An empty string is a present value (D34 #1): it stays
 * {@code ""} for a string transform, and a function with nothing to compute from it ({@code char},
 * {@code abs}, a date component, …) answers the computed {@code MIS},
 * {@link ScalarSemantics#computedMissing()} — never {@code null}. The boundary is
 * {@link TypedValue#missing()}, never the F3 {@code Vector.isMissing} fold. The untyped
 * {@link ComputedVector} carries an {@code IDataValue} row as a typed cell whatever its declared
 * type (the identity is read from the cell), which is what lets a LONG or DOUBLE producer return
 * the cell. ⚠ The two date extremes {@code earliest_date} / {@code latest_date}
 * ({@link net.cumba.corej.core.exec.ScalarDateExtremes}) follow the <b>extremes'</b> candidate rule
 * (SPEC §4(10)), not propagation: a missing argument is skipped and the other decides alone; only
 * when both are missing do they carry their identity (D85c / D86a).
 * </p>
 */
public final class BuiltinFunctions implements FunctionProvider
{

    @Override
    public List<FunctionDescriptor> functions()
    {
        List<FunctionDescriptor> fns = new ArrayList<>();

        // -- VALUE transforms ------------------------------------------------
        // ⭐ Element-wise (PLAN-elementwise-list-functions §3.9, runbook W1a E4): the fold's
        // list form — a dataset-level list folded once, a per-row list element by element, now
        // also values([X, Y]) — is the descriptor's `liftingElementwise()` lift over the SCALAR
        // kernel
        // caseFold, so upper / lower and their aliases carry the flag the compiler, stage A and
        // the language surface read, instead of a name set (StageAChecker.CASE_FOLDS keeps only
        // its type-source meaning).
        EvalFunction lower = (run, args) -> caseFold(run.rowCount(), args.get(0), true);
        elementwise(fns, "lower", lower);
        elementwise(fns, "lowcase", lower);
        EvalFunction upper = (run, args) -> caseFold(run.rowCount(), args.get(0), false);
        elementwise(fns, "upper", upper);
        elementwise(fns, "upcase", upper);
        EvalFunction len = (run, args) ->
        {
            Vector x = args.get(0);
            // ⭐ D13 / SPEC §4(4)'s len limb: a genuine MissingValue HAS NO LENGTH, so
            // len(«missing») is MISSING, not 0 — and it is THAT missing: the input's own cell is
            // handed through, so len(.A) is .A (D85c,
            // PLAN-missing-identity-nonstring-functions). len("") is still 0: an empty string is
            // a present value of length zero (D34 #1), and an ABSENT character column is all-""
            // (D76/D131a), so
            // `len(ABSENT) == 0` and `len(BLANK) == 0` are both TRUE and the EC-43
            // absent-equals-blank contract (D96a) holds. ⚠⚠ That equality is exactly what this
            // limb was BLOCKED on: while nameRefPlan minted ALL_MISSING for a character-expected
            // absent column, this change made `len(ABSENT) == 0` false while `len(BLANK) == 0`
            // stayed true. It is free only because D131a closed that half first.
            //
            // ⚑ Free on the authored corpus for a second, independent reason: 81 of the 88 `len(`
            // sites compare against a literal and NOT ONE is `== 0` or `!= 0`; every other literal
            // answers the same under "length 0" and under D34 #5's "a missing sorts low".
            //
            // PLAN-engine-gap-byte-length §5.1-5.2 (runbook W1b BL1, owner-ruled 2026-10-07):
            // unit="bytes" counts the value encoded in `encoding` — the written charset, or the
            // one the COMPILER resolved from the evaluated table's own Encoding metadata into
            // the encoding operand (ExprCompiler.tableEncodingOperand; a table without one, or
            // declaring MIXED, hands a missing operand and every row answers missing). The
            // kernel reads its arguments only — never the run context — so it stays pure for
            // the element-wise lift E5 flags it with in W2 (ElementwiseLiftRosterDriftTest).
            // unit="chars" (the default) keeps today's UTF-16 count until BL2 (W2) switches it
            // to code points.
            boolean bytes = countsBytes(args.size() > 1 ? args.get(1) : null);
            Charset charset = bytes ? charsetOf(args.size() > 2 ? args.get(2) : null) : null;
            if (bytes && charset == null)
            {
                return ConstVector.of(null);
            }
            return new ComputedVector(run.rowCount(), DataValueType.LONG, row ->
            {
                TypedValue tv = x.value(row);
                if (tv.missing() != null)
                {
                    return tv.cell();
                }
                String text = tv.cell().getValueAsString();
                return charset == null ? (long) text.length()
                        : (long) text.getBytes(charset).length;
            });
        };
        List<Parameter> lenParameters = List.of(p("x"), opt(UNIT_PARAMETER, Primitive.STRING),
                opt(ENCODING_PARAMETER, Primitive.STRING));
        value(fns, "len", lenParameters, len);
        value(fns, "length", lenParameters, len);
        // count(x) / size(x): the number of ELEMENTS in a list-valued operand — 1 for a present
        // scalar, 0 for a missing one.
        //
        // ⛔⛔ This exists because `len` is STRING length and silently answers nonsense on a list.
        // Measured (Plan 2, review finding R-1): len(["Unique Subject Identifier"]) is
        // "[Unique Subject Identifier]".length() == 27, not 1 — so a `len(list) > 1` cardinality
        // test is true for EVERY non-empty list, and the rule built on it fired on data that
        // conformed. There was no list-cardinality function in the registry at all, and the one
        // rule that needed it was the only list-valued `len()` in the whole corpus, so there was
        // no precedent to inherit correctness from either.
        //
        // ⚑ An empty / missing cell counts 0, following ScalarSemantics.isMissing (the F3 fold):
        // cardinality, so a missing has no identity to keep here — unlike len, where len("") is 0
        // but len(«missing») is that missing (D85c). A present scalar counts 1.
        EvalFunction count = (run, args) ->
        {
            Vector x = args.get(0);
            if (x instanceof ListVector list)
            {
                // E9 (PLAN-elementwise-list-functions §3.8): a ListVector — values([X, Y]), a
                // lifted upper(values(…)) — is K members on every row (a missing member is a
                // member), so its cardinality is answered once, without materialising a row's list
                return ConstVector.of((long) list.size());
            }
            return new ComputedVector(run.rowCount(), DataValueType.LONG, row ->
            {
                if (x.isMissing(row))
                {
                    return 0L;
                }
                Object resolved = x.value(row).resolved();
                return resolved instanceof Collection<?> c ? (long) c.size() : 1L;
            });
        };
        value(fns, "count", count);
        value(fns, "size", count);
        // normalize_space(x): trim, then collapse every internal run of whitespace to one space.
        //
        // ⛔ Review finding R-12 — the whitespace defence was ASYMMETRIC. The engine normalised the
        // labels it PUBLISHES with trim + internal-run collapse, while the rule could only `trim`
        // the dataset's label, because no collapse function existed. A dataset label with a doubled
        // internal space therefore raised a false ERROR against an identical published label. This
        // is the missing half, so both sides can apply exactly the same normalisation.
        value(fns, "normalize_space", (run, args) ->
        {
            Vector x = args.get(0);
            // normalize_space(«missing») is that missing (D36, identity kept);
            // normalize_space("") = "" (D34 #1 — the boundary is TypedValue.missing(), not the F3
            // Vector.isMissing fold, which used to turn "" into MIS here).
            return new ComputedVector(run.rowCount(), DataValueType.STRING, row ->
            {
                TypedValue tv = x.value(row);
                return tv.missing() != null ? tv.cell()
                        : tv.cell().getValueAsString().strip().replaceAll("\\s+", " ");
            });
        });
        // char(x): the Unicode code point of the first character of x, as a LONG. A missing x ⇒
        // that missing (D85c, the input's own cell — char(.A) is .A); a present "" has no first
        // character ⇒ the computed MIS. Either way char(value()) <= 32 (the leading-space test)
        // does not fire on a blank on its own (numeric <= over a missing LHS yields no violation).
        value(fns, "char", (run, args) ->
        {
            Vector x = args.get(0);
            return new ComputedVector(run.rowCount(), DataValueType.LONG, row ->
            {
                // One carrier per row: every test below reads this tv's cell, never x again (a
                // ColumnVector builds a fresh carrier on each value(row) call).
                TypedValue tv = x.value(row);
                IDataValue cell = tv.cell();
                if (tv.missing() != null)
                {
                    return cell;
                }
                if (ScalarSemantics.isMissing(cell))
                {
                    return ScalarSemantics.computedMissing();
                }
                String s = cell.getValueAsString();
                return s.isEmpty() ? ScalarSemantics.computedMissing() : (long) s.codePointAt(0);
            });
        });

        // -- VALUE dynamic column read (colref) ------------------------------
        // colref(X): per row, the column X names (PLAN-dynamic-column-functions §2.3) — a bare
        // name on the evaluation table (the primary and its SUPP qualifiers), DS.X the merged
        // column (CDISC-CG0371 builds RDOMAIN.IDVAR for its Child parent), the cell typed, a list
        // of
        // names dereferenced element-wise (Q4), a present non-string first hop a computed missing
        // (Q7). ⚠ ExprCompiler dispatches colref through its own arm (colrefPlan), which knows the
        // CALL SITE and so the site's absent-column kind (Q14 (b)); this implementation is the
        // site-less entry a direct registry caller reaches, and resolves through the SAME
        // DynamicColumnRead with no site kind — only the resolved name's rule-wide expectation.
        // ⚠ Declared a ROW READER (FunctionDescriptor.readingRows): a literal colref("X") has no
        // column operand, so the level calculus would otherwise read it DATASET and broadcast
        // row 0's cell to every row — the row_max shape of XCUT H1. Stage A types colref RECORD
        // in its own arm; DomainScan and the hand-over contract read this flag.
        // ⭐ x is a NAME SLOT (PLAN-string-literal-prefix-rewrite S2): colref("--SEQ") names a
        // column, so the bind-time specialiser rewrites it (and a list literal's members, S4).
        fns.add(new FunctionDescriptor("colref", List.of(p("x").asNameSlot()), FunctionKind.VALUE,
                (run, args) -> DynamicColumnRead.vector(run, args.get(0), false, false))
                        .readingRows());

        // -- VALUE per-row list of named columns (values) ----------------------
        // values([X, Y]): per row, the list of the named columns' cells in input order
        // (PLAN-elementwise-list-functions §3.2, owner Q14, runbook W1a E1) — a missing cell stays
        // at its position as its own missing (DCF Q4), an absent column reads as its type default
        // (D34 #3/#4). Its argument is a list of column NAMES — a bare name, a `--` name, a
        // qualified DS.X — never values: `colref` reads the column whose name a CELL holds,
        // `values` the columns the rule names. ⚠ ExprCompiler dispatches values through its own
        // arm (valuesPlan): each written element compiles through operandPlan (the read a bare
        // operand takes), a `$` member or a computed expression of names is spliced through the
        // one GroupSplice (`values($keys)` ≡ `values([$keys])`, C5) and the K element vectors are
        // held in a ListVector (Q10, the fast form). This implementation is the site-less entry a
        // direct registry caller reaches: a vector of names, dereferenced through the same
        // DynamicColumnRead as colref(list).
        // ⚠ Declared a ROW READER (FunctionDescriptor.readingRows): values($keys) has no column
        // operand, so the level calculus would otherwise read it DATASET and broadcast row 0's
        // list to every row (the XCUT H1 shape colref records). Stage A types it RECORD through
        // the same flag; DomainScan and the hand-over contract read it.
        fns.add(new FunctionDescriptor(net.cumba.corej.core.exec.GroupSplice.VALUES_CALL,
                List.of(p(net.cumba.corej.core.exec.GroupSplice.VALUES_PARAMETER,
                        new ListOf(Primitive.COLUMN_REFERENCE))),
                FunctionKind.VALUE,
                (run, args) -> DynamicColumnRead.vector(run, args.get(0), false, false))
                        .readingRows());

        // -- VALUE column-set selector (PLAN-dynamic-column-functions §2.4, owner Q5/Q11/Q12) --
        // find_vars("<entry>"): the column NAMES one Requirements.Variables entry selects —
        // template / glob / DS./regex/ / literal, one parser and one matcher with that surface.
        fns.add(new FunctionDescriptor(net.cumba.corej.core.exec.FindVars.NAME,
                // ⭐ entry is a NAME SLOT: its template / glob / literal entries are specialised
                // (D93c); a /regex/ entry is kept by the specialiser itself (SPEC §7.2 item 6).
                List.of(p("entry", Primitive.STRING).asNameSlot()), FunctionKind.VALUE,
                // ExprCompiler dispatches find_vars through its own arm, which knows whether the
                // argument is a string LITERAL; a direct registry caller passes a vector only,
                // so a broadcast one is taken as the literal it almost always is.
                (run, args) -> net.cumba.corej.core.exec.FindVars.evaluate(run, args.get(0),
                        args.get(0) instanceof ConstVector)));

        // -- VALUE conversions (PLAN-dynamic-column-functions §2.1, owner Q10) ----------------
        // num / str / date / time are REGISTERED functions: each has a descriptor, so each is in
        // the roster and the generated function reference. num / date / time keep their compiler
        // routing (ExprCompiler.family / numTag select the comparison family) and their one
        // evaluation each (Primitives.numConversion; the interpretive ISO identity of
        // ExprCompiler.isoConversion), which the compiler arm and this implementation share.
        value(fns, "num", (run, args) -> Primitives.numConversion(args.get(0), run.rowCount()));
        value(fns, "date", (run, args) -> ExprCompiler.isoConversion(args.get(0), "date()"));
        value(fns, "time", (run, args) -> ExprCompiler.isoConversion(args.get(0), "time()"));
        // -- the duration family (PLAN-engine-gap-dur-order §5, runbook W1b DO) --------------
        // duration(x): the comparison marker / conversion (Q2) — interpretive like date(): the
        // carrier is the ISO text verbatim, read by the family's hull (IsoDurationBounds: months
        // and years as ranges, Q1). ExprCompiler compiles the written marker to the identity
        // itself; this body serves the registry's own paths.
        value(fns, "duration", (run, args) -> args.get(0));
        // duration_between(start, end): the duration hull between two ISO instants (Q4) — each
        // instant its own hull, complete or partial (R5 B-M2), so the range
        // [lower(end) - upper(start), upper(end) - lower(start)], rendered as the family's
        // lower/upper carrier (a point where both are at second precision). Compared through the
        // duration family from either side (D6).
        value(fns, "duration_between", List.of(p("start"), p("end")),
                (run, args) -> durationBetween(run.rowCount(), args.get(0), args.get(1)));
        value(fns, TextFunctions.STR,
                (run, args) -> TextFunctions.str(run.rowCount(), args.get(0)));

        // -- VALUE formatting (PLAN-dynamic-column-functions §2.2, owner Q2) -------------------
        // printf(format, args…): Locale.ROOT, %s %d %f %e %x %% only; the format is a string
        // literal and every static check is a compiler load error (ExprCompiler's static-string
        // seam). lpad(x, width, fill=" "): left-pad, never truncate.
        fns.add(new FunctionDescriptor(TextFunctions.PRINTF,
                List.of(p("format", Primitive.STRING),
                        Parameter.collector("args", new ListOf(Unknown.UNKNOWN))),
                FunctionKind.VALUE, (run, args) -> TextFunctions.printf(run.rowCount(), args.get(0),
                        args.subList(1, args.size()))));
        fns.add(new FunctionDescriptor(TextFunctions.LPAD,
                List.of(p("x"), p("width", Primitive.NUMBER), opt("fill", Primitive.STRING)),
                FunctionKind.VALUE, (run, args) -> TextFunctions.lpad(run.rowCount(), args.get(0),
                        args.get(1), args.get(2))));

        // -- VALUE current-variable accessors (varname / value) -------------
        // varname(): the NAME of the "current variable" — the per-column cursor the metadata /
        // value-with-metadata paths set on the EvaluationContext (variables["variable_name"]). It
        // is
        // a broadcast scalar (one value per variable, constant across rows), so it mirrors the
        // standalone variable_name operand exactly (Expr.Ref("variable_name") resolves the same
        // cursor). Missing cursor ⇒ null.
        fns.add(new FunctionDescriptor("varname", List.of(), FunctionKind.VALUE, (run, _) ->
        {
            Object name = run.ctx().resolveVariable("variable_name");
            return ConstVector.of(name);
        }));
        // (record_count() — the primary table's row count, the dataset-level fact the retired
        // legacy dataset fold read — is the ungrouped, unfiltered branch of the compiler-dispatched
        // record_count since runbook W6: CompilerDispatchedCalls / RecordCount.)
        // value(): the per-row VALUE of the "current variable" — the cells of the column named by
        // the cursor (variables["variable_name"]). Mirrors the legacy variable_value operand, which
        // the retired CheckConditionOptimizer.bindVariableValue rewrote to the current column. A
        // missing cursor ⇒ a broadcast null (MIS — no variable is named). A cursor naming a column
        // the table lacks (a Define-universe ItemDef the data does not carry) ⇒ the shared marker
        // ColumnTypedAbsence.ABSENT_CURSOR_COLUMN — itself the all-MIS broadcast — which the
        // compiler's value() arm resolves to the call site's use-derived default
        // (PLAN-column-typed-function-absent-type A5: MIS where numeric-expected, "" otherwise).
        fns.add(new FunctionDescriptor("value", List.of(), FunctionKind.VALUE, (run, _) ->
        {
            EvaluationContext ctx = run.ctx();
            Object cursor = ctx.resolveVariable("variable_name");
            if (!(cursor instanceof String colName) || colName.isEmpty())
            {
                return ConstVector.of(null);
            }
            DataTableMeta meta = ctx.getTable().getMetaData();
            int idx = meta.getColumnIndex(colName);
            if (idx < 0)
            {
                return net.cumba.corej.core.exec.ColumnTypedAbsence.ABSENT_CURSOR_COLUMN;
            }
            return new ColumnVector(null, ctx.getTable().getColumn(idx),
                    meta.getColumn(idx).getType());
        }));

        // -- VALUE numeric helpers (native-only; no legacy operator) ---------
        // A missing input ⇒ that missing (its own cell, D85c: abs(.A) is .A); a present
        // non-numeric input ⇒ the computed MIS. abs preserves fractions (DOUBLE);
        // round/floor/ceil yield an integral LONG. round is half-up toward +∞ (Math.round).
        value(fns, "abs", (run, args) -> numericValue(run.rowCount(), args.get(0),
                DataValueType.DOUBLE, Math::abs));
        value(fns, "round", (run, args) -> numericValue(run.rowCount(), args.get(0),
                DataValueType.LONG, Math::round));
        value(fns, "floor", (run, args) -> numericValue(run.rowCount(), args.get(0),
                DataValueType.LONG, Math::floor));
        value(fns, "ceil", (run, args) -> numericValue(run.rowCount(), args.get(0),
                DataValueType.LONG, Math::ceil));

        // -- VALUE string / null helpers (native-only) -----------------------
        value(fns, "trim", (run, args) ->
        {
            Vector x = args.get(0);
            // trim(«missing») is that missing (D36, identity kept); trim("") = "" (D34 #1).
            return new ComputedVector(run.rowCount(), DataValueType.STRING, row ->
            {
                TypedValue tv = x.value(row);
                return tv.missing() != null ? tv.cell() : tv.cell().getValueAsString().strip();
            });
        });
        // concat(a, b[, c]): string concatenation. A missing operand makes the result missing
        // (register D36 names concat: one missing part poisons the result), with the identity
        // combined per D86a — one distinct identity ⇒ that identity, two or more ⇒ MIS. A present
        // "" contributes nothing, and an ABSENT char column is "" (D34 #3), so an absent operand
        // still contributes nothing. coalesce(a, b[, c]): the first
        // operand that is neither missing nor "" — resolved — else, once every operand is
        // skipped, D86a over the operands that are genuinely missing (see coalesce(Vector…)).
        // ⚠ Vector.isMissing is
        // empty()'s scalar predicate (DataValueSupport.isEmptyOrMissing), so an absent char
        // column, which folds to "" (D34 #3), is skipped and the next operand is consulted; a
        // numeric 0 is a real value and is kept (PLAN-coalesce-empty-semantics, design A, owner
        // 2026-09-25; pinned by CoalesceEmptySemanticsTest). ⭐ Phase 6b (D19a/D59): the former
        // arity-2/arity-3 overloads are ONE descriptor with an optional third parameter — an
        // absent `c` is byte-identical to the retired arity-2 registration.
        fns.add(new FunctionDescriptor("concat", List.of(p("a"), p("b"), opt("c", Unknown.UNKNOWN)),
                FunctionKind.VALUE, (run, args) ->
                {
                    Vector a = args.get(0);
                    Vector b = args.get(1);
                    Vector c = args.get(2);
                    return new ComputedVector(run.rowCount(), DataValueType.STRING, row ->
                    {
                        TypedValue ta = a.value(row);
                        TypedValue tb = b.value(row);
                        TypedValue tc = c == null ? null : c.value(row);
                        MissingValue missing = ArithmeticSemantics.combinedMissing(ta, tb, tc);
                        if (missing != null)
                        {
                            return ArithmeticSemantics.carrierCell(missing, ta, tb, tc);
                        }
                        String s = ta.cell().getValueAsString() + tb.cell().getValueAsString();
                        return tc == null ? s : s + tc.cell().getValueAsString();
                    });
                }));
        fns.add(new FunctionDescriptor("coalesce",
                List.of(p("a"), p("b"), opt("c", Unknown.UNKNOWN)), FunctionKind.VALUE,
                (run, args) ->
                {
                    Vector a = args.get(0);
                    Vector b = args.get(1);
                    Vector c = args.get(2);
                    return new ComputedVector(run.rowCount(), DataValueType.STRING,
                            row -> coalesce(a, b, c, row));
                }));

        // -- VALUE substring (native-only; 1-based start, SAS/CDISC convention) ---
        // substring(x, start): the suffix of x beginning at the 1-based character position `start`.
        // substring(x, start, length): at most `length` characters from that position. A missing
        // operand (x, start or length) makes the result that missing, identity combined per D86a
        // (D36; one distinct identity ⇒ that identity, two or more ⇒ MIS); a non-integral start
        // (or length), a start < 1, or a start beyond x's length yield the computed missing MIS.
        // A length <= 0 yields the empty string; a length running past the end of x is clamped
        // to x's end (no exception).
        fns.add(new FunctionDescriptor("substring",
                List.of(p("x"), p("start", Primitive.NUMBER), opt("length", Primitive.NUMBER)),
                FunctionKind.VALUE, (run, args) ->
                {
                    Vector x = args.get(0);
                    Vector start = args.get(1);
                    Vector length = args.get(2);
                    // Gate hoisted to vector construction (a function of the VECTOR, not of a
                    // row): a missing x takes the D36 early return before start/length are read,
                    // so a per-row gate would let a Char start/length column pass on data where
                    // x is missing on every row.
                    net.cumba.corej.core.expr.eval.ColumnTypeGate.requireNumericRead(start,
                            "a numeric function operand");
                    net.cumba.corej.core.expr.eval.ColumnTypeGate.requireNumericRead(length,
                            "a numeric function operand");
                    return new ComputedVector(run.rowCount(), DataValueType.STRING,
                            row -> substring(x, start, length, row));
                }));

        // -- VALUE delimiter split (T9; native-only) -------------------------
        // split_by(x, delimiter): the per-row token LIST produced by splitting x on the literal
        // delimiter, keeping trailing empty tokens — mirroring the Python reference engine's
        // split_by operation (pandas Series.str.split, which keeps trailing empties). Each row's
        // cell is a List<String>, so a list-consuming operator (the per-row not_contains_all token
        // branch) reads it element-wise. The delimiter is a broadcast literal (2nd positional arg).
        // A missing x yields that missing, its own cell — a scalar, not a list (D85c; no tokens ⇒
        // no violation); a present "" the computed MIS. Populated rows are the only ones a split
        // rule targets (its non_empty Precondition gates blanks). This is the native
        // lowering of a split_by OPERATION (the retired inliner): coreJ never had a SPLIT_BY
        // operation
        // because a broadcast operation cannot carry a per-row-varying list.
        fns.add(new FunctionDescriptor("split_by",
                List.of(p("x"), p("delimiter", Primitive.STRING)), FunctionKind.VALUE,
                (run, args) ->
                {
                    Vector x = args.get(0);
                    String delimiter = constString(args.get(1));
                    return new ComputedVector(run.rowCount(), DataValueType.STRING,
                            row -> splitBy(x, delimiter, row));
                }));

        // -- VALUE composite key (T3; native-only) ---------------------------
        // tuple(c1, c2, ...): the current row's composite key as a List<Object> cell of key
        // components (one per argument column: a present cell's text, a missing cell as its
        // Primitives.MissingMember identity, a present blank as ""). Used as the left operand of
        // the
        // composite cross-dataset membership `tuple(c1, c2) [not] in distinct([c1, c2],
        // domain="D")`
        // — the row fires when its tuple is (not) a member of the reference dataset's distinct
        // row-tuple set built by the list-target `distinct` operation. ⭐ Phase 6b (D59/D91): the
        // per-arity registrations (2..6, with 4..6 never used) are ONE descriptor whose trailing
        // collector parameter is the `list<column-reference>` composite key — `tuple(A, B, …)`
        // stays spellable as §1.5's sugar, and the two-column minimum is the leading required
        // parameter. The component rules match evalDistinctTuples so a row tuple and a reference
        // tuple compare List-equal.
        fns.add(new FunctionDescriptor("tuple",
                List.of(p("c1", Primitive.COLUMN_REFERENCE), p("c2", Primitive.COLUMN_REFERENCE),
                        Parameter.collector("columns", new ListOf(Primitive.COLUMN_REFERENCE))),
                FunctionKind.VALUE, (run, args) -> new ComputedVector(run.rowCount(),
                        DataValueType.STRING, row -> tupleKey(args, row))));

        // -- VALUE ISO-8601 date-component extraction (native-only) ----------
        // year(x) / month(x) / day(x): the requested component of an ISO-8601 date as a LONG. A
        // leading `YYYY[-MM[-DD]]` prefix is parsed (any `T…` time part and anything after the day
        // is ignored). A missing value yields that missing (its own cell, D85c: year(.A) is .A);
        // a present "" / unparseable value, or a value that does not carry the requested
        // component, yields the computed MIS — e.g. year("2024")=2024, month("2024")=missing,
        // day("2024-03")=missing, year("2024-03-15T08:00")=2024. The components are NOT
        // calendar-validated here (use is_valid_date for that); a syntactically well-formed but
        // impossible value such as "2024-13" still yields month=13.
        value(fns, "year", (run, args) -> dateComponent(run.rowCount(), args.get(0), 0));
        value(fns, "month", (run, args) -> dateComponent(run.rowCount(), args.get(0), 1));
        value(fns, "day", (run, args) -> dateComponent(run.rowCount(), args.get(0), 2));

        // -- BOOLEAN range (native-only) -------------------------------------
        // between(x, lo, hi): fires where x is numeric and lo <= x <= hi (inclusive). lo/hi may be
        // literals or per-row columns; a missing/non-numeric x, lo, or hi never fires.
        bool(fns, "between", List.of(p("x", Primitive.NUMBER), p("lo", Primitive.NUMBER),
                p("hi", Primitive.NUMBER)), (run, args) ->
                {
                    Vector x = args.get(0);
                    Vector lo = args.get(1);
                    Vector hi = args.get(2);
                    // ⭐ Gate hoisted out of numeric(): ColumnTypeGate.requireNumericRead is a pure
                    // function of the VECTOR, so asking it per row asked the same question rowCount
                    // times
                    // to get the same answer. Raised here, before the loop, so an operand of the
                    // wrong
                    // declared type still errors the rule exactly as before — just once.
                    net.cumba.corej.core.expr.eval.ColumnTypeGate.requireNumericRead(x,
                            "a numeric function operand");
                    net.cumba.corej.core.expr.eval.ColumnTypeGate.requireNumericRead(lo,
                            "a numeric function operand");
                    net.cumba.corej.core.expr.eval.ColumnTypeGate.requireNumericRead(hi,
                            "a numeric function operand");
                    BitSet result = new BitSet(run.rowCount());
                    for (int row = 0; row < run.rowCount(); row++)
                    {
                        Double xv = numeric(x, row);
                        Double lov = numeric(lo, row);
                        Double hiv = numeric(hi, row);
                        if (xv != null && lov != null && hiv != null && xv >= lov && xv <= hiv)
                        {
                            result.set(row);
                        }
                    }
                    return result;
                });

        // -- BOOLEAN presence ------------------------------------------------
        EvalFunction empty = (run, args) -> Primitives.empty(args.get(0), run.rowCount());
        bool(fns, "empty", List.of(p("x")), empty);
        // D50 (owner 2026-10-05): is_missing is for missing, empty for missing or "" — no alias.
        bool(fns, "is_missing", List.of(p("x")),
                (run, args) -> Primitives.isMissing(args.get(0), run.rowCount()));
        EvalFunction nonEmpty = (run, args) -> Primitives.nonEmpty(args.get(0), run.rowCount());
        bool(fns, "non_empty", List.of(p("x")), nonEmpty);
        bool(fns, "present", List.of(p("x")), nonEmpty);
        bool(fns, "is_present", List.of(p("x")), nonEmpty);

        // -- BOOLEAN library skip-gate (§9.C) --------------------------------
        // library_available(): true iff a Library MetadataProvider is configured AND — Fix #369 —
        // its CDISC Library could actually be consulted. The degraded arm matters because the
        // paired available(<op>) term is only injected when the check does more than test
        // emptiness (RulePackageLoader.testsOnlyEmptiness); for an emptiness-only check
        // library_available() IS the whole gate, and a degraded provider would otherwise pass it
        // and let the rule evaluate against the empty result it exists to prevent.
        // available(<call>): true iff the (broadcast) result is usable — not an empty (unresolved)
        // list, and not a capability-carrying function's unusable answer (caught into the empty
        // result, ExprCompiler.unusableAsUnavailable). Injected into a rule's Precondition so an
        // inline provider call SKIPS the rule (rather than passing) when the provider cannot
        // answer.
        bool(fns, "library_available", List.of(), (run, _) -> allRows(run.rowCount(),
                LibraryAnswerability.libraryAnswerable(run.ctx().getLibraryProvider())));
        // define_available() (PLAN-engine-gap-define-presence §4, runbook W1b DP1a; owner-ruled
        // 2026-10-07): true iff a Define-XML was SUPPLIED to the run — also one that failed to
        // parse (Q2: supplied is supplied; the parse failure is DP1b's study-level ERROR), false
        // otherwise. Authorable (Q4), a study-level fact (StudyRuleClassifier), a run constant
        // folded like library_available() (BroadcastFold.isLibraryGateCall). It declares NO
        // provider need, so the rule that reports the absence is never SKIPPED for it. It reads
        // the presence FACT, never `getDefineProvider() != null`: on a parse failure the provider
        // is the datatable fallback or null, and both say nothing about "supplied".
        bool(fns, "define_available", List.of(), (run, _) -> allRows(run.rowCount(),
                run.ctx().getDefinePresence() != net.cumba.corej.core.exec.DefinePresence.ABSENT));
        bool(fns, "available", List.of(p("x")), (run, args) -> allRows(run.rowCount(),
                resultAvailable(args.get(0).value(0).resolved())));
        // T1: dictionary_available(<type>): true iff a dictionary of the named type is loaded into
        // the runtime dictionary provider. Injected as a Precondition gate for every inline
        // dictionary-backed call (a registry function declaring the DICTIONARY capability,
        // injectInlineOperationGates) so the rule SKIPs (rather than false-passes) when no
        // dictionary of that type is supplied.
        bool(fns, "dictionary_available", List.of(p("type", Primitive.STRING)),
                (run, args) -> allRows(run.rowCount(),
                        run.ctx().getDictionaryProvider() != null && run.ctx()
                                .getDictionaryProvider().isAvailable(constString(args.get(0)))));

        // -- VALUE list: the CT attributes of the CT packages the rows name (wave 0) ------------
        // get_codelist_attributes(TSVCDREF, TSVCDVER, ct_attribute="Term CCODE") — the list-valued
        // exemplar of PLAN-binding-expressions (CDISC-CG0288), ported from the retired operation.
        // Target `name` only (runbook R6), the version column its second positional, ct_attribute a
        // string keyword. An AGGREGATE (one list for the dataset, broadcast) carrying the LIBRARY
        // provider capability: no provider / no usable answer ⇒ the rule SKIPs, never PASSes.
        fns.add(new FunctionDescriptor(net.cumba.corej.core.exec.CodelistAttributes.NAME,
                List.of(p("name"), p("version"), p("ct_attribute", Primitive.STRING)),
                FunctionKind.VALUE, net.cumba.corej.core.exec.CodelistAttributes::evaluate)
                        .withProvider(net.cumba.corej.core.expr.eval.ProviderNeed.LIBRARY)
                        .aggregating());

        // -- VALUE study day (wave 1) -----------------------------------------
        // dy(--DTC, DM.RFSTDTC) — the SDTM study day, ported from the retired DY operation. Both
        // parameters are COLUMN references (a quoted "RFSTDTC" is a STRING literal and fails to
        // compile — R1): the record's date, and the reference date read through the rule's
        // declared join. The reference is REQUIRED: the operation's RFSTDTC default hid a
        // hard-coded DM read that disagreed with the Check on a duplicated DM.USUBJID.
        fns.add(new FunctionDescriptor(net.cumba.corej.core.exec.StudyDay.NAME,
                List.of(p("name", Primitive.COLUMN_REFERENCE),
                        p("reference", Primitive.COLUMN_REFERENCE)),
                FunctionKind.VALUE, net.cumba.corej.core.exec.StudyDay::evaluate));
        // date_diff_days(--DTC, min_date(SJSTDTC, domain=SJ, group=[USUBJID, RPHASE])) — the days
        // from a reference date to the record's date, ported from the retired DATE_DIFF_DAYS
        // operation (runbook W2b). `name` is a COLUMN reference (R1: a quoted name fails to
        // compile); `reference` is any per-row VALUE — a column, a joined DATASET.COLUMN, or a
        // named aggregation (min_date / max_date), which replaces the operation's Mode 2
        // (domain= / group= / reference_extreme). No offset parameter: an offset is arithmetic.
        fns.add(new FunctionDescriptor(net.cumba.corej.core.exec.DateDiffDays.NAME,
                List.of(p("name", Primitive.COLUMN_REFERENCE), p("reference")), FunctionKind.VALUE,
                net.cumba.corej.core.exec.DateDiffDays::evaluate));

        // -- BOOLEAN external-dictionary functions (wave 1) -------------------
        // valid_external_dictionary_code_term_pair(TSVALCD, TSVAL, external_dictionary_type="unii")
        // and valid_external_dictionary_hierarchy(--HLT, --SOC, external_dictionary_type="meddra"):
        // two COLUMN references (a quoted name fails to compile — R1), the dictionary type a
        // required STRING literal, case_sensitive optional. Both declare the DICTIONARY provider
        // capability keyed on the type parameter (D-W1-3), which is what makes the no-dictionary
        // SKIP gate, the injected inline gate and the forecast see them.
        List<Parameter> pairParams = List.of(p("name", Primitive.COLUMN_REFERENCE),
                p("external_dictionary_term_variable", Primitive.COLUMN_REFERENCE),
                p(net.cumba.corej.core.exec.DictionaryFunctions.TYPE_PARAMETER, Primitive.STRING),
                opt("case_sensitive", Primitive.BOOLEAN));
        fns.add(new FunctionDescriptor(net.cumba.corej.core.exec.DictionaryFunctions.CODE_TERM_PAIR,
                pairParams, FunctionKind.BOOLEAN,
                net.cumba.corej.core.exec.DictionaryFunctions::codeTermPair)
                        .withProvider(net.cumba.corej.core.expr.eval.ProviderNeed.dictionary(
                                net.cumba.corej.core.exec.DictionaryFunctions.TYPE_PARAMETER)));
        List<Parameter> hierarchyParams = List.of(p("name", Primitive.COLUMN_REFERENCE),
                p("dictionary_parent", Primitive.COLUMN_REFERENCE),
                p(net.cumba.corej.core.exec.DictionaryFunctions.TYPE_PARAMETER, Primitive.STRING),
                opt("case_sensitive", Primitive.BOOLEAN));
        fns.add(new FunctionDescriptor(net.cumba.corej.core.exec.DictionaryFunctions.HIERARCHY,
                hierarchyParams, FunctionKind.BOOLEAN,
                net.cumba.corej.core.exec.DictionaryFunctions::hierarchy)
                        .withProvider(net.cumba.corej.core.expr.eval.ProviderNeed.dictionary(
                                net.cumba.corej.core.exec.DictionaryFunctions.TYPE_PARAMETER)));

        // -- BOOLEAN / VALUE per-row functions (wave 3, PLAN-per-row-functions) ---------------
        // valid_external_dictionary_value / _code(--DECOD, external_dictionary_type="meddra",
        // dictionary_term_type="PT", case_sensitive=false): ONE implementation under both authored
        // names (runbook R6), the value a COLUMN reference, the type and the level required STRING
        // literals (a non-literal level is a load error — ExprCompiler's literal seam), the
        // DICTIONARY
        // capability keyed on the type exactly as the wave-1 pair declares it (D-W1-3).
        for (String membership : List.of(net.cumba.corej.core.exec.DictionaryFunctions.VALUE,
                net.cumba.corej.core.exec.DictionaryFunctions.CODE))
        {
            fns.add(new FunctionDescriptor(membership,
                    List.of(p("name", Primitive.COLUMN_REFERENCE),
                            p(net.cumba.corej.core.exec.DictionaryFunctions.TYPE_PARAMETER,
                                    Primitive.STRING),
                            p(net.cumba.corej.core.exec.DictionaryFunctions.LEVEL_PARAMETER,
                                    Primitive.STRING),
                            opt("case_sensitive", Primitive.BOOLEAN)),
                    FunctionKind.BOOLEAN,
                    (run, args) -> net.cumba.corej.core.exec.DictionaryFunctions.termMembership(run,
                            args, membership)).withProvider(
                                    net.cumba.corej.core.expr.eval.ProviderNeed.dictionary(
                                            net.cumba.corej.core.exec.DictionaryFunctions.TYPE_PARAMETER)));
        }
        // dictionary_has_decode(CMTRT, external_dictionary_type="whodrug") — decode presence.
        fns.add(new FunctionDescriptor(net.cumba.corej.core.exec.DictionaryFunctions.HAS_DECODE,
                List.of(p("name", Primitive.COLUMN_REFERENCE),
                        p(net.cumba.corej.core.exec.DictionaryFunctions.TYPE_PARAMETER,
                                Primitive.STRING),
                        opt("case_sensitive", Primitive.BOOLEAN)),
                FunctionKind.BOOLEAN, net.cumba.corej.core.exec.DictionaryFunctions::hasDecode)
                        .withProvider(net.cumba.corej.core.expr.eval.ProviderNeed.dictionary(
                                net.cumba.corej.core.exec.DictionaryFunctions.TYPE_PARAMETER)));
        // interval_uncertainty_precision_mismatch(--DTC, delimiter="/") — CDISC-SEND-0070.
        fns.add(new FunctionDescriptor(net.cumba.corej.core.exec.IntervalPrecision.NAME,
                List.of(p("name", Primitive.COLUMN_REFERENCE), opt("delimiter", Primitive.STRING)),
                FunctionKind.BOOLEAN, net.cumba.corej.core.exec.IntervalPrecision::evaluate));
        // referenced_domain_class(RDOMAIN) — the Library class of the domain each record names; the
        // column is REQUIRED (the operation's RDOMAIN default is not carried), LIBRARY capability.
        fns.add(new FunctionDescriptor(net.cumba.corej.core.exec.ReferencedDomainClass.NAME,
                List.of(p("name", Primitive.COLUMN_REFERENCE)), FunctionKind.VALUE,
                net.cumba.corej.core.exec.ReferencedDomainClass::evaluate)
                        .withProvider(net.cumba.corej.core.expr.eval.ProviderNeed.LIBRARY));
        // referenced_dataset_variables(RDOMAIN) — the variable names of the dataset each record
        // names, per row (runbook W7, PLAN-distinct-function D-W7-6: the retired
        // distinct(IDVAR, value_is_reference=true), whose target was never read); the column is
        // REQUIRED and explicit. No provider: the study's own datasets are read through the run's
        // resolver, never the Library.
        // qualifiers=true (runbook W1b VA, V3) adds the SUPP companions' QNAM values — a literal
        // boolean, read once per call (ListOperandLevel.PER_CALL_PARAMETERS).
        fns.add(new FunctionDescriptor(net.cumba.corej.core.exec.ReferencedDatasetVariables.NAME,
                List.of(p("name", Primitive.COLUMN_REFERENCE), opt(
                        net.cumba.corej.core.exec.ReferencedDatasetVariables.QUALIFIERS_PARAMETER,
                        Primitive.BOOLEAN)),
                FunctionKind.VALUE,
                net.cumba.corej.core.exec.ReferencedDatasetVariables::evaluate));
        // row_max(name_pattern="^TR(0[1-9]|[1-9][0-9])EDT$") — the per-row maximum over the
        // columns the static regex names (R6: no target; a non-literal or invalid pattern is a
        // load error — ExprCompiler's literal seam). A ROW READER: its only operand is the static
        // pattern, so without the flag the level calculus classified it dataset-level and a
        // `$trxx_max` binding handed row 0's maximum to every row (combined review XCUT H1).
        fns.add(new FunctionDescriptor(net.cumba.corej.core.exec.RowMax.NAME,
                List.of(p(net.cumba.corej.core.exec.RowMax.PATTERN_PARAMETER, Primitive.STRING)),
                FunctionKind.VALUE, net.cumba.corej.core.exec.RowMax::evaluate).readingRows());

        // -- VALUE lists: the dataset-level list functions (wave 4, PLAN-list-functions) ---------
        // Each answers ONE list for the dataset in W0's list shape (a ConstVector holding the List)
        // and is therefore an AGGREGATE; the provider-backed ones carry the LIBRARY / DEFINE
        // capability (no provider ⇒ the rule SKIPs before any row is read). Every callable keeps
        // its own empty / unusable arm — see each class. R6: none declares a target parameter.
        registerListFunctions(fns);

        // -- VALUE scalars: the dataset-level scalar / metadata functions (wave 4b) ------------
        // One value for the dataset — a Boolean, a text, a count, a metadata value, or one
        // per-variable map — and therefore each an AGGREGATE; the two Library-backed ones carry the
        // LIBRARY capability. Every argument is a static string / integer literal (the compile seam
        // holds it so, D-W4b-6).
        registerScalarFunctions(fns);

        // -- BOOLEAN substring -----------------------------------------------
        bool(fns, "contains", List.of(p("x"), p("value")), (run, args) -> Primitives
                .contains(args.get(0), args.get(1), run.rowCount(), false));
        bool(fns, "does_not_contain", List.of(p("x"), p("value")),
                (run, args) -> Primitives.contains(args.get(0), args.get(1), run.rowCount(), true));
        bool(fns, "starts_with", List.of(p("x"), p("value")),
                (run, args) -> Primitives.startsWith(args.get(0), args.get(1), run.rowCount()));
        bool(fns, "ends_with", List.of(p("x"), p("value")),
                (run, args) -> Primitives.endsWith(args.get(0), args.get(1), run.rowCount()));
        // contains_any(x, list) — PLAN-engine-gap-list-elementwise §6.1 Q1 (b), runbook W1b LE1′:
        // x contains any element of list as a substring; contains' kernel per element, its edges
        // (Primitives.containsAny). A plain boolean, NOT element-wise: x is a string (a list there
        // is stage A's PARAMETER_TYPE, never contains' membership arm) and list a list of strings
        // (dataset-level or per row; a scalar is PARAMETER_TYPE). Negation: not contains_any(…).
        bool(fns, "contains_any",
                List.of(p("x", Primitive.STRING), p("list", new ListOf(Primitive.STRING))),
                (run, args) -> Primitives.containsAny(args.get(0), args.get(1), run.rowCount()));

        // -- the ordered-list functions (PLAN-engine-gap-row-ordinal R2 / R4 / R6, runbook W1b
        // RO; coordinator D11): ordinary registry functions over vectors — a per-row list
        // (sequence's output) and a dataset-level list alike
        // (net.cumba.corej.core.exec.OrderedLists)
        List<Parameter> twoLists = List.of(p("a", new ListOf(Unknown.UNKNOWN)),
                p("b", new ListOf(Unknown.UNKNOWN)));
        bool(fns, "is_prefix_of", twoLists, (run, args) -> net.cumba.corej.core.exec.OrderedLists
                .isPrefixOf(args.get(0), args.get(1), run.rowCount(), false));
        bool(fns, "is_not_prefix_of", twoLists,
                (run, args) -> net.cumba.corej.core.exec.OrderedLists.isPrefixOf(args.get(0),
                        args.get(1), run.rowCount(), true));
        bool(fns, "is_increasing", List.of(p("list", new ListOf(Unknown.UNKNOWN))),
                (run, args) -> net.cumba.corej.core.exec.OrderedLists.isIncreasing(args.get(0),
                        run.rowCount()));
        value(fns, "collapse_runs", List.of(p("list", new ListOf(Unknown.UNKNOWN))),
                (run, args) -> net.cumba.corej.core.exec.OrderedLists.collapseRuns(args.get(0),
                        run.rowCount()));

        // -- BOOLEAN case-insensitive equality -------------------------------
        bool(fns, "equalsIgnoreCase", List.of(p("a"), p("b")), (run, args) -> Primitives
                .equality(args.get(0), args.get(1), run.rowCount(), false, true, false, false));

        // -- BOOLEAN label template (PLAN-library-template-label-resolution, D6 (b)) ----------
        // matches_label_template(label, template): the label conforms to the resolved library
        // label template (label_template("LIBRARY")) — fixed text exactly, `...` any text, a
        // {phrase} somewhere in the label, `Imput.` ≡ `Imputation`. Two STRING parameters (stage A
        // PARAMETER_TYPE: a non-string argument is a load error); a missing operand ⇒ false, as
        // contains. Pure: it reads only its two argument Vectors (DatasetExpressionCache).
        bool(fns, "matches_label_template",
                List.of(p("label", Primitive.STRING), p("template", Primitive.STRING)),
                (run, args) -> Primitives.matchesLabelTemplate(args.get(0), args.get(1),
                        run.rowCount()));

        // -- BOOLEAN affix regex (anchored full-match) -----------------------
        // prefix_matches(x, /re/[, n]) / suffix_matches(x, /re/[, n]): anchored full-match on the
        // whole operand, or — when the optional n (raised from a (not_)(prefix|suffix)_
        // matches_regex leaf carrying an affix length) is bound — on the first/last n characters
        // (a value shorter than n, or a non-integral n, uses the whole string). ⭐ Phase 6b
        // (D19a/D59): the former arity-2/arity-3 overloads are ONE descriptor with `n` optional;
        // an absent n is byte-identical to the retired arity-2 registration.
        List<Parameter> affixParams = List.of(p("x"), p("pattern", Primitive.REGEX),
                opt("n", Primitive.NUMBER));
        bool(fns, "prefix_matches", affixParams, (run, args) -> args.get(2) == null
                ? Primitives.affixRegex(args.get(0), Pattern.compile(constString(args.get(1))),
                        run.rowCount(), true, (Integer) null, false)
                : Primitives.affixRegex(args.get(0), Pattern.compile(constString(args.get(1))),
                        run.rowCount(), true, args.get(2), false));
        bool(fns, "suffix_matches", affixParams, (run, args) -> args.get(2) == null
                ? Primitives.affixRegex(args.get(0), Pattern.compile(constString(args.get(1))),
                        run.rowCount(), false, (Integer) null, false)
                : Primitives.affixRegex(args.get(0), Pattern.compile(constString(args.get(1))),
                        run.rowCount(), false, args.get(2), false));

        // -- VALUE affix substrings (prefix / suffix) -------------------------
        // prefix(x, n) / suffix(x, n): the first/last n characters of x, raised from the legacy
        // prefix_/suffix_(not_)equal_to / _is_(not_)contained_by comparison leaves. Semantics
        // affix extraction: a value shorter than n (or a non-positive / non-integral n) yields
        // the WHOLE string; a missing x or a missing n yields that missing, identity combined per
        // D86a (D36; see affixValue).
        fns.add(new FunctionDescriptor("prefix", List.of(p("x"), p("n", Primitive.NUMBER)),
                FunctionKind.VALUE,
                (run, args) -> affixValue(run.rowCount(), args.get(0), args.get(1), true)));
        fns.add(new FunctionDescriptor("suffix", List.of(p("x"), p("n", Primitive.NUMBER)),
                FunctionKind.VALUE,
                (run, args) -> affixValue(run.rowCount(), args.get(0), args.get(1), false)));

        // -- BOOLEAN case-insensitive regex search (native-only) -------------
        // imatches(x, /regex/): unanchored case-insensitive search (Matcher.find with
        // Pattern.CASE_INSENSITIVE), mirroring the `=~` operator's find() semantics. A missing x
        // never fires. The 2nd arg is a /regex/ literal bound to a broadcast const string (see
        // ExprCompiler.LITERAL_ARG1).
        bool(fns, "imatches", List.of(p("x"), p("pattern", Primitive.REGEX)),
                (run, args) -> Primitives.regexFind(args.get(0),
                        Pattern.compile(constString(args.get(1)), Pattern.CASE_INSENSITIVE),
                        run.rowCount(), false));

        // -- BOOLEAN integer -------------------------------------------------
        predicate(fns, "is_integer", List.of(p("x")),
                (run, args) -> Primitives.isInteger(args.get(0), run.rowCount(), false));
        predicate(fns, "is_not_integer", List.of(p("x")),
                (run, args) -> Primitives.isInteger(args.get(0), run.rowCount(), true));

        // -- BOOLEAN numeric -------------------------------------------------
        // is_numeric(x): finite-decimal hand-rolled scan; the negated form is written
        // `not is_numeric(X)`, so no is_not_numeric is registered.
        predicate(fns, "is_numeric", List.of(p("x")),
                (run, args) -> Primitives.isNumeric(args.get(0), run.rowCount(), false));

        // -- BOOLEAN valid test code / variable name -------------------------
        // is_valid_testcd(x): findings-domain test code — first char [A-Za-z_], rest
        // [A-Za-z0-9_], length 1..8 (mixed case). is_valid_name(x): SAS/CDISC variable name —
        // first char [A-Z_], rest [A-Z0-9_], length 1..8 (uppercase only). Both are hand-rolled
        // scans (no regex); a missing/"" cell does not fire (so `not is_valid_*` fires on a blank,
        // matching the legacy not_matches_regex).
        predicate(fns, "is_valid_testcd", List.of(p("x")),
                (run, args) -> Primitives.isValidTestcd(args.get(0), run.rowCount()));
        predicate(fns, "is_valid_name", List.of(p("x")),
                (run, args) -> Primitives.isValidName(args.get(0), run.rowCount()));

        // -- BOOLEAN has-letter / has-digit ----------------------------------
        // has_alpha(x): contains >= 1 ASCII letter [A-Za-z]. has_digit(x): contains >= 1 ASCII
        // digit [0-9]. Both mirror the legacy unanchored matches_regex ".*[a-zA-Z].*" / ".*[0-9].*"
        // a missing/"" cell does not fire. (No shipped rule authors either as of 2026-09-19 —
        // the sole consumer went with the CORE family; the twin authors a regex instead.)
        predicate(fns, "has_alpha", List.of(p("x")),
                (run, args) -> Primitives.hasAlpha(args.get(0), run.rowCount()));
        predicate(fns, "has_digit", List.of(p("x")),
                (run, args) -> Primitives.hasDigit(args.get(0), run.rowCount()));

        // -- BOOLEAN duration ------------------------------------------------
        // EC-20/EC-22: absent negative= defaults to true (accept the signed grammar), matching the
        // Python reference engine and the aligned legacy operator. The arity-1 form is the fallback
        // for a bare invalid_duration(X); ExprCompiler.compileBoolCall intercepts the
        // kwarg-carrying
        // form and passes the parsed negative= flag through explicitly.
        // invalid_duration(x[, negative=]): the compiler intercepts the kwarg-carrying arity-1
        // form (EC-22) and routes the parsed flag explicitly; this registered fn consumes a BOUND
        // negative argument too (a positional/named boolean literal compiled to a broadcast
        // constant), so both spellings share one default (DEFAULT_NEGATIVE = accept the signed
        // grammar, EC-20 alignment).
        predicate(fns, "invalid_duration", List.of(p("x"), opt("negative", Primitive.BOOLEAN)),
                (run, args) -> Primitives.invalidDuration(args.get(0), run.rowCount(),
                        args.get(1) == null
                                || !(args.get(1).value(0).resolved() instanceof Boolean b) || b));
        predicate(fns, "is_valid_duration", List.of(p("x")),
                (run, args) -> Primitives.stringPredicate(args.get(0), run.rowCount(),
                        s -> !ScalarSemantics.isInvalidDuration(s, false)));

        // -- BOOLEAN date validity (calendar-validating, decision #4) --------
        predicate(fns, "is_valid_date", List.of(p("x", Primitive.STRING)), (run, args) -> Primitives
                .stringPredicate(args.get(0), run.rowCount(), CalendarDates::isValidDate));
        predicate(fns, "is_complete_date", List.of(p("x", Primitive.STRING)),
                (run, args) -> Primitives.stringPredicate(args.get(0), run.rowCount(),
                        CalendarDates::isCompleteDate));
        EvalFunction partialDate = (run, args) -> Primitives.stringPredicate(args.get(0),
                run.rowCount(), CalendarDates::isPartialDate);
        predicate(fns, "is_partial_date", List.of(p("x", Primitive.STRING)), partialDate);
        predicate(fns, "is_incomplete_date", List.of(p("x", Primitive.STRING)), partialDate);
        // invalid_date: calendar-validating AND firing on a missing/blank cell. The previous
        // stringPredicate wiring carried a !isMissing guard that silently SUPPRESSED the blank case
        // (a blank is not a valid date, so it must be reported — never hidden); invalidDateCalendar
        // drops that guard while keeping calendar validation (decision in BuiltinFunctionsTest).
        predicate(fns, "invalid_date", List.of(p("x", Primitive.STRING)),
                (run, args) -> Primitives.invalidDateCalendar(args.get(0), run.rowCount()));
        // is_complete_date_part(x) / is_not_complete_date_part(x) — Fix #157. Judges ONLY the
        // leading YYYY-MM-DD date portion, so a truncated time ("2020-01-01T10") is complete here
        // while is_complete_date rejects it and is_incomplete_date fires on it. Equivalent to
        // is_complete_date(prefix(x, 10)) without the magic number. A missing/"" cell folds to ""
        // (not a complete date part), so the negative form fires on a blank — mirrors
        // is_integer/is_not_integer, NOT the is_complete_date/is_incomplete_date pair (which is
        // deliberately non-exhaustive: an invalid date is neither).
        predicate(fns, "is_complete_date_part", List.of(p("x", Primitive.STRING)),
                (run, args) -> Primitives.isCompleteDatePart(args.get(0), run.rowCount(), false));
        predicate(fns, "is_not_complete_date_part", List.of(p("x", Primitive.STRING)),
                (run, args) -> Primitives.isCompleteDatePart(args.get(0), run.rowCount(), true));

        // -- VALUE date hull bounds (earliest_possible / latest_possible) -----
        // Q16's escape hatch. A `date_*` comparison means "definitely" — it quantifies over EVERY
        // candidate of a partial operand — which is the safe default but not always the reading an
        // author wants. These two expose the bounds themselves, so the loose readings become
        // expressible per rule instead of being engine policy:
        //
        // date(latest_possible(A)) >= earliest_possible(B) possibly on-or-after
        // date(earliest_possible(A)) <= latest_possible(B) possibly on-or-before
        //
        // ⚠⚠ The comparison over the bounds MUST carry the date() tag: untagged, it compiles to
        // the numeric-only plain comparison and never fires on an ISO string (measured through
        // RuleRunner — HullBoundsBareOperandProbeTest, plan C phase 5b step 0). And the tagged
        // bound spelling is NOT a respelling of the default ∀ reading: bounds are rendered at
        // each value's own precision and re-enter the complete-vs-complete fast path, which
        // over-fires >=/<= at partial-vs-timed boundary days where the default's pair-common
        // hull clipping stays silent (probe row R7). "Definitely" is spelled date(A) >= B —
        // exactly the operator itself — never through these builtins.
        // ⚠⚠ Builtins, not operations: an operation value is broadcast to every row and cannot
        // carry a per-row-varying result, and a bound is per-row by construction. The precedent is
        // exact — prefix/suffix/is_complete_date/is_complete_date_part are all per-cell date
        // builtins.
        // A cell that cannot be positioned (blank, junk, calendar-impossible, year-masked) yields
        // the computed MISSING rather than a saturated sentinel: "the earliest date this could be"
        // has no answer, and 9999-12-31 is a real clinical value that must never be manufactured.
        // A missing cell yields that missing, its own cell (D85c: earliest_possible(.A) is .A).
        fns.add(new FunctionDescriptor("earliest_possible", List.of(p("x")), FunctionKind.VALUE,
                (run, args) -> hullBound(run.rowCount(), args.get(0), false)));
        fns.add(new FunctionDescriptor("latest_possible", List.of(p("x")), FunctionKind.VALUE,
                (run, args) -> hullBound(run.rowCount(), args.get(0), true)));

        // -- VALUE two-value date extremes (earliest_date / latest_date) -----
        // PLAN-scalar-date-extremes (SDE D1 the names, S1–S7 the contract): the earlier / later of
        // two dates with exactly min_date / max_date's semantics over a two-cell block — EC-51's
        // candidate rule (a missing or blank argument is skipped; both missing carry their
        // identity) and EC-46's determinability, one accumulator (ScalarDateExtremes). DATE in,
        // DATE out (S3): a string literal argument is a PARAMETER_TYPE finding — ARMED since
        // PLAN-stage-a-parameter-type-arming, so the rule fails to load: write date("…").
        // The winning CELL is handed back; unequal argument types declare STRING and are NOT
        // refused (a number never wins — IsoDateBounds.isDetermined needs a calendar-complete
        // core), and a numeric COLUMN argument is a stage-B fact (D10): stage A types a column
        // as unknown, so it is observed at evaluation as D55's date(NUM) is (observe-only).
        // Two arguments exactly, positional or by name; no missing_values= (S8).
        fns.add(new FunctionDescriptor(net.cumba.corej.core.exec.ScalarDateExtremes.EARLIEST,
                List.of(p("a", Primitive.DATE), p("b", Primitive.DATE)), FunctionKind.VALUE,
                net.cumba.corej.core.exec.ScalarDateExtremes::earliest));
        fns.add(new FunctionDescriptor(net.cumba.corej.core.exec.ScalarDateExtremes.LATEST,
                List.of(p("a", Primitive.DATE), p("b", Primitive.DATE)), FunctionKind.VALUE,
                net.cumba.corej.core.exec.ScalarDateExtremes::latest));

        // -- BOOLEAN interval predicates (SPEC §5.3, D27 — phase 3b) ---------
        // Container-first, matching contains(haystack, needle). date_overlaps is by construction
        // the negation of the date operator's `!=`, so `not date_overlaps(A, B)` IS today's `!=`
        // — the identity Review 0's 21 KEEP decisions rest on; see TemporalPredicates for the
        // three stated consequences (an unpositionable operand overlaps NOTHING since H1b —
        // `A != junk` is true, so `not date_overlaps(A, junk)` fires; a missing operand overlaps
        // nothing; and contains reaches the same false by its own guard). A null second
        // operand (an unresolvable reference) yields the empty verdict, exactly as the compiled
        // comparison's null-plan short-circuit does.
        bool(fns, "date_contains", List.of(p("outer", Primitive.DATE), p("inner", Primitive.DATE)),
                (run, args) -> args.get(1) == null ? new BitSet()
                        : TemporalPredicates.dateContains(args.get(0), args.get(1),
                                run.rowCount()));
        bool(fns, "date_overlaps", List.of(p("a", Primitive.DATE), p("b", Primitive.DATE)),
                (run, args) -> args.get(1) == null ? new BitSet()
                        : TemporalPredicates.dateOverlaps(args.get(0), args.get(1),
                                run.rowCount()));
        bool(fns, "time_contains", List.of(p("outer", Primitive.TIME), p("inner", Primitive.TIME)),
                (run, args) -> args.get(1) == null ? new BitSet()
                        : TemporalPredicates.timeContains(args.get(0), args.get(1),
                                run.rowCount()));
        bool(fns, "time_overlaps", List.of(p("a", Primitive.TIME), p("b", Primitive.TIME)),
                (run, args) -> args.get(1) == null ? new BitSet()
                        : TemporalPredicates.timeOverlaps(args.get(0), args.get(1),
                                run.rowCount()));

        return fns;
    }


    /**
     * Per-row numeric transform: parses {@code x} as a double and applies {@code op}. A missing
     * cell yields that missing — its own cell, identity kept (D85c: {@code abs(.A)} is {@code .A})
     * — decided before the parse; a present {@code ""} or non-numeric cell yields the computed
     * {@code MIS} ({@link ScalarSemantics#computedMissing()}). Used by {@code abs}/{@code round}/
     * {@code floor}/{@code ceil} (the LONG ones round the {@code op} result to an integral value
     * via the vector's declared type). ⚠ {@link #numeric} itself is untouched: {@code between} and
     * {@link #integral} (the {@code substring} / {@code prefix} length) read its F3 fold.
     */
    private static ComputedVector numericValue(int rowCount, Vector x, DataValueType type,
            java.util.function.DoubleUnaryOperator op)
    {
        // Gate hoisted out of numeric() — pure in the vector, so once is enough. ⚠ Raised at plan
        // construction rather than on first row access, which is strictly earlier and therefore
        // still cannot let a mistyped operand through.
        net.cumba.corej.core.expr.eval.ColumnTypeGate.requireNumericRead(x,
                "a numeric function operand");
        return new ComputedVector(rowCount, type, row ->
        {
            TypedValue tv = x.value(row);
            if (tv.missing() != null)
            {
                return tv.cell();
            }
            Double d = numeric(tv);
            if (d == null)
            {
                return ScalarSemantics.computedMissing();
            }
            double result = op.applyAsDouble(d);
            if (type == DataValueType.LONG)
            {
                return (long) result; // autoboxes to Long
            }
            return result; // autoboxes to Double
        });
    }


    /**
     * The numeric value of {@code x} at {@code row}, or {@code null} when missing / non-numeric.
     * Phase 3 (R3/R4, PLAN-column-type-conformance): every numeric read of a function operand runs
     * the column-type gate first — a resolved {@code Char} column ({@code abs(X)},
     * {@code between(X, lo, hi)}, a {@code substring}/{@code prefix}/{@code suffix} length arg)
     * errors instead of silently yielding all-missing; the authoring is {@code num(X)}. Literals,
     * computed values, {@code num()} conversions and the {@code value()} cursor pass.
     */
    private static @Nullable Double numeric(Vector x, int row)
    {
        if (x.isMissing(row))
        {
            return null;
        }
        double d = x.asDouble(row);
        return Double.isNaN(d) ? null : d;
    }


    /**
     * {@link #numeric(Vector, int)} over a carrier the caller already holds: the same F3 fold and
     * {@code NaN} test on {@code tv}'s cell, so a per-row caller that has read {@code value(row)}
     * once does not build the carrier again (a {@code ColumnVector} allocates one per call).
     */
    private static @Nullable Double numeric(TypedValue tv)
    {
        IDataValue cell = tv.cell();
        if (ScalarSemantics.isMissing(cell))
        {
            return null;
        }
        double d = cell.getValueAsDouble();
        return Double.isNaN(d) ? null : d;
    }


    /**
     * Per-row {@code split_by(x, delimiter)}: the token list from splitting {@code x} on the
     * <em>literal</em> {@code delimiter} (quoted so it is never a regex), keeping trailing empty
     * tokens — bit-for-bit pandas {@code Series.str.split(delimiter)} (Python reference engine). A
     * missing {@code x} yields that missing — its own cell, a scalar, never a list (D85c;
     * {@code FINDINGS-unowned-residuals} I1's missing half) — and a present {@code ""} the computed
     * {@code MIS}; the operator treats either as no tokens ⇒ no violation. An empty delimiter
     * yields the single-element list {@code [x]}. A returned list is immutable and never contains
     * {@code null} (splitting produces strings only).
     */
    private static Object splitBy(Vector x, String delimiter, int row)
    {
        TypedValue tv = x.value(row);
        IDataValue cell = tv.cell();
        if (tv.missing() != null)
        {
            return cell;
        }
        if (ScalarSemantics.isMissing(cell))
        {
            return ScalarSemantics.computedMissing();
        }
        String s = cell.getValueAsString();
        if (delimiter.isEmpty())
        {
            return List.of(s);
        }
        return List.of(s.split(Pattern.quote(delimiter), -1));
    }


    /**
     * Per-row {@code tuple(c1, c2, ...)}: the row's composite key as an immutable
     * {@code List<Object>} of key components (one per argument, never {@code null}): a present
     * cell's text, a missing cell as its {@link Primitives.MissingMember} identity (D11 / D34
     * #5-2), a present blank as {@code ""}. The rules match the retired executor's
     * {@code evalDistinctTuples} so a row tuple and a reference tuple compare {@link List#equals
     * List-equal} in the composite membership branch (T3).
     */
    private static List<Object> tupleKey(List<Vector> args, int row)
    {
        List<Object> key = new ArrayList<>(args.size());
        for (Vector arg : args)
        {
            // A MissingValue component keeps its identity (D11 / D34 #5-2 / NVE §4.4) as a
            // MissingMember, exactly as evalDistinctTuples builds the reference side; only a
            // present blank folds to "". An ABSENT column arrives already folded to its type
            // default by the operand plan ("" for character, the all-missing constant — i.e.
            // MIS — for a numeric expectation), the same rule evalDistinctTuples applies.
            MissingValue m = TypedValue.missingIdentityOf(arg.value(row).cell());
            key.add(m != null ? new Primitives.MissingMember(m)
                    : arg.isMissing(row) ? "" : arg.asString(row));
        }
        return java.util.Collections.unmodifiableList(key);
    }


    /**
     * Per-row {@code earliest_possible(x)} / {@code latest_possible(x)}: the earliest / latest
     * instant the cell could denote, rendered at the cell's own precision but never coarser than a
     * whole day, or missing when the cell cannot be positioned on the calendar.
     *
     * <p>
     * Rendering at the value's own precision is what keeps the explicit spelling agreeing with the
     * default operator: a complete {@code 2026-01-17} yields {@code 2026-01-17} from <em>both</em>
     * bounds, so {@code earliest_possible(A) >= latest_possible(B)} still answers true for two
     * equal complete dates.
     * </p>
     *
     * <p>
     * A missing cell yields that missing, its own cell (D85c); a present {@code ""} or a cell that
     * cannot be positioned yields the computed {@code MIS}, never {@code null}.
     * </p>
     */
    private static ComputedVector hullBound(int rowCount, Vector x, boolean high)
    {
        return new ComputedVector(rowCount, DataValueType.STRING, row ->
        {
            TypedValue tv = x.value(row);
            IDataValue cell = tv.cell();
            if (tv.missing() != null)
            {
                return cell;
            }
            String bound = ScalarSemantics.isMissing(cell) ? null
                    : IsoDateComparison.bound(cell.getValueAsString(), high);
            return bound != null ? bound : ScalarSemantics.computedMissing();
        });
    }


    /**
     * Per-row {@code earliest_possible(x)} / {@code latest_possible(x)} over a {@code duration(…)}
     * operand (runbook W1b DO, Q6): the hull's end as a point duration
     * ({@link IsoDurationBounds#lower} / {@link IsoDurationBounds#upper} — {@code P1M} bounds as
     * {@code P28D} / {@code P31D}), or the computed {@code MIS} where the cell is no duration; a
     * missing cell yields that missing, its own cell (D85c). Routed by {@code ExprCompiler} on the
     * written marker — the registry's own {@code hullBound} reads a date.
     *
     * @param rowCount
     *            the run's row count
     * @param x
     *            the marked operand's vector
     * @param high
     *            {@code true} for the upper bound
     * @param carrier
     *            whether the operand is {@code duration_between}'s own result, whose
     *            {@code lower/upper} carrier positions (runbook W1b fold, R5 B-L1; a data cell
     *            spelled so is no duration)
     * @return the per-row bound
     */
    public static ComputedVector durationHullBound(int rowCount, Vector x, boolean high,
            boolean carrier)
    {
        return new ComputedVector(rowCount, DataValueType.STRING, row ->
        {
            TypedValue tv = x.value(row);
            IDataValue cell = tv.cell();
            if (tv.missing() != null)
            {
                return cell;
            }
            IsoDurationBounds.Hull hull = ScalarSemantics.isMissing(cell) ? null
                    : IsoDurationBounds.hull(cell.getValueAsString(), carrier);
            if (hull == null)
            {
                return ScalarSemantics.computedMissing();
            }
            return IsoDurationBounds.render(high ? hull.upper() : hull.lower());
        });
    }


    /**
     * Per-row {@code duration_between(start, end)} (runbook W1b DO, Q4): the duration from
     * {@code start} to {@code end} as the family's carrier — the hull
     * {@code [lower(end) - upper(start), upper(end) - lower(start)]} of the two instants' own hulls
     * ({@link IsoDateBounds}), rendered {@code lower/upper}, a point ({@code P1DT12H},
     * {@code -P2D}) only where both instants are at second precision. ⭐ Two COMPLETE instants take
     * their hulls too (dur-order §5 Q4, "each instant's own hull"; runbook W1b fold, R5 B-M2): a
     * day-precision date spans its day, so {@code 2020-01-01} to {@code 2020-01-02T10:00} is
     * {@code [PT10H1S, P1DT10H59S]}, which holds {@code PT34H} — never the coarser-precision point
     * {@code P1D}, which made {@code !=} fire where the difference is not definite. A missing
     * operand yields that missing (identity combined per D86a); a blank or unpositionable instant
     * the computed {@code MIS}.
     */
    private static ComputedVector durationBetween(int rowCount, Vector start, Vector end)
    {
        return new ComputedVector(rowCount, DataValueType.STRING, row ->
        {
            TypedValue ts = start.value(row);
            TypedValue te = end.value(row);
            MissingValue missing = ArithmeticSemantics.combinedMissing(ts, te, null);
            if (missing != null)
            {
                return ArithmeticSemantics.carrierCell(missing, ts, te, null);
            }
            if (ScalarSemantics.isMissing(ts.cell()) || ScalarSemantics.isMissing(te.cell()))
            {
                return ScalarSemantics.computedMissing();
            }
            String carrier = durationBetween(ts.cell().getValueAsString(),
                    te.cell().getValueAsString());
            return carrier != null ? carrier : ScalarSemantics.computedMissing();
        });
    }


    /** The carrier of the duration from {@code start} to {@code end}, or {@code null}. */
    static @Nullable String durationBetween(String start, String end)
    {
        if (!IsoDateBounds.canPosition(start) || !IsoDateBounds.canPosition(end))
        {
            return null;
        }
        Double lowerStart = epochSeconds(IsoDateBounds.lower(start));
        Double upperStart = epochSeconds(IsoDateBounds.upper(start));
        Double lowerEnd = epochSeconds(IsoDateBounds.lower(end));
        Double upperEnd = epochSeconds(IsoDateBounds.upper(end));
        if (lowerStart == null || upperStart == null || lowerEnd == null || upperEnd == null)
        {
            return null;
        }
        return IsoDurationBounds.renderHull(lowerEnd - upperStart, upperEnd - lowerStart);
    }


    /**
     * The epoch seconds of an {@link IsoDateBounds} bound ({@code yyyy-MM-ddTHH:mm:ss}, UTC), or
     * {@code null} when it does not parse.
     */
    private static @Nullable Double epochSeconds(@Nullable String bound)
    {
        if (bound == null)
        {
            return null;
        }
        try
        {
            return (double) java.time.LocalDateTime.parse(bound)
                    .toEpochSecond(java.time.ZoneOffset.UTC);
        }
        catch (java.time.format.DateTimeParseException e)
        {
            return null;
        }
    }


    /**
     * Per-row {@code prefix(x, n)} / {@code suffix(x, n)}: the first/last {@code n} characters of
     * {@code x}, with the affix-extraction edge semantics (shorter-than-n / non-positive /
     * non-integral n ⇒ whole string). A missing {@code x} or a missing {@code n} ⇒ that missing,
     * the operand's own cell handed through (D36, identity combined per D86a);
     * {@code prefix("", n)} is {@code ""} (D34 #1).
     */
    private static ComputedVector affixValue(int rowCount, Vector x, Vector n, boolean isPrefix)
    {
        // Gate hoisted to vector construction, as between/numericValue do: a missing x takes the
        // D36 early return before n is read, so the per-row gate in integral() alone would let a
        // Char n column pass wherever x is missing on every row.
        net.cumba.corej.core.expr.eval.ColumnTypeGate.requireNumericRead(n,
                "a numeric function operand");
        return new ComputedVector(rowCount, DataValueType.STRING, row ->
        {
            TypedValue tx = x.value(row);
            TypedValue tn = n.value(row);
            MissingValue missing = ArithmeticSemantics.combinedMissing(tx, tn, null);
            if (missing != null)
            {
                return ArithmeticSemantics.carrierCell(missing, tx, tn, null);
            }
            Integer len = integral(n, row);
            String s = tx.cell().getValueAsString();
            return isPrefix ? Primitives.extractPrefix(s, len) : Primitives.extractSuffix(s, len);
        });
    }


    /**
     * Per-row {@code substring} with a 1-based {@code start} (SAS/CDISC convention) and an optional
     * {@code length}. A missing operand — {@code x}, {@code start} or {@code length} — yields the
     * cell of {@link ArithmeticSemantics#carrierCell} for its
     * {@link ArithmeticSemantics#combinedMissing} identity (D36 with the D86a identity rule), as
     * the operand's own cell. Returns {@link ScalarSemantics#computedMissing()} (the computed
     * missing, {@code MIS}) when a present {@code start} is non-integral / {@code < 1} / past the
     * end of {@code x}, or a present {@code length} is non-integral — never {@code null}, so every
     * missing row is an {@code IDataValue} and every present one a {@code String}. A
     * {@code length <= 0} yields the empty string; a {@code length} running past the end of
     * {@code x} is clamped. {@code substring("", 1)} is a start past the end of a length-0 string,
     * so it stays the computed missing (the documented bounds rule, unchanged).
     */
    private static Object substring(Vector x, Vector start, @Nullable Vector length, int row)
    {
        TypedValue tx = x.value(row);
        TypedValue tstart = start.value(row);
        TypedValue tlen = length == null ? null : length.value(row);
        MissingValue missing = ArithmeticSemantics.combinedMissing(tx, tstart, tlen);
        if (missing != null)
        {
            return ArithmeticSemantics.carrierCell(missing, tx, tstart, tlen);
        }
        Integer startIdx = integral(start, row);
        if (startIdx == null || startIdx < 1)
        {
            return ScalarSemantics.computedMissing();
        }
        String s = tx.cell().getValueAsString();
        int from = startIdx - 1; // 1-based -> 0-based
        if (from >= s.length())
        {
            return ScalarSemantics.computedMissing(); // start past the end ⇒ computed MIS
        }
        if (length == null)
        {
            return s.substring(from);
        }
        Integer len = integral(length, row);
        if (len == null)
        {
            return ScalarSemantics.computedMissing();
        }
        if (len <= 0)
        {
            return "";
        }
        int to = Math.min(s.length(), from + len); // clamp past-the-end length
        return s.substring(from, to);
    }


    /**
     * The integral value of {@code v} at {@code row}, or {@code null} when missing, non-numeric, or
     * not an exact integer.
     */
    private static @Nullable Integer integral(Vector v, int row)
    {
        // ⚠ integral() is itself per-row; its callers (affixValue, the substring descriptor) hoist
        // the gate to vector construction, which is what makes it data-independent. Kept here as
        // a belt-and-braces raise for any future caller that forgets — requireNumericRead is
        // idempotent.
        net.cumba.corej.core.expr.eval.ColumnTypeGate.requireNumericRead(v,
                "a numeric function operand");
        Double d = numeric(v, row);
        if (d == null || Double.compare(d, Math.rint(d)) != 0 || Double.isInfinite(d))
        {
            return null;
        }
        return (int) (double) d;
    }


    /**
     * Extracts the {@code component} (0 = year, 1 = month, 2 = day) of a leading ISO-8601
     * {@code YYYY[-MM[-DD]]} prefix as a LONG. A missing {@code x} yields that missing — its own
     * cell, identity kept (D85c); a present {@code ""}, a malformed prefix, or a component absent
     * at the value's precision yields the computed {@code MIS}, never {@code null}.
     */
    private static ComputedVector dateComponent(int rowCount, Vector x, int component)
    {
        return new ComputedVector(rowCount, DataValueType.LONG, row ->
        {
            TypedValue tv = x.value(row);
            IDataValue cell = tv.cell();
            if (tv.missing() != null)
            {
                return cell;
            }
            if (ScalarSemantics.isMissing(cell))
            {
                return ScalarSemantics.computedMissing();
            }
            Integer v = isoComponent(cell.getValueAsString(), component);
            return v == null ? ScalarSemantics.computedMissing() : (long) (int) v;
        });
    }


    /**
     * Parses the {@code component} (0 = year, 1 = month, 2 = day) of the leading
     * {@code YYYY[-MM[-DD]]} prefix of {@code value}, ignoring any {@code T…} time part and any
     * trailing text. Returns {@code null} when the prefix is malformed or the component is absent.
     */
    private static @Nullable Integer isoComponent(String value, int component)
    {
        String date = value;
        int t = date.indexOf('T');
        if (t >= 0)
        {
            date = date.substring(0, t);
        }
        // YYYY
        if (date.length() < 4 || !isDigits(date, 0, 4))
        {
            return null;
        }
        if (component == 0)
        {
            return Integer.parseInt(date.substring(0, 4));
        }
        // YYYY-MM
        if (date.length() < 7 || date.charAt(4) != '-' || !isDigits(date, 5, 7))
        {
            return null;
        }
        if (component == 1)
        {
            return Integer.parseInt(date.substring(5, 7));
        }
        // YYYY-MM-DD
        if (date.length() < 10 || date.charAt(7) != '-' || !isDigits(date, 8, 10))
        {
            return null;
        }
        return Integer.parseInt(date.substring(8, 10));
    }


    /** {@code true} iff every character in {@code [from, to)} of {@code s} is an ASCII digit. */
    private static boolean isDigits(String s, int from, int to)
    {
        for (int i = from; i < to; i++)
        {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9')
            {
                return false;
            }
        }
        return true;
    }


    /**
     * {@code upper(x)} / {@code lower(x)}, the <b>scalar kernel</b>: {@link #foldValue} per row —
     * or ONCE when {@code x} is a {@link ConstVector}. A scalar constant ({@code upper("a")})
     * answers a {@code ConstVector} of its folded value, so the result stays dataset-level: a
     * per-row {@code ComputedVector} over a constant has no row 0 on a zero-row table
     * ({@code ComputedVector.value(0)} throws) and is not the constant hand-over form a {@code {}}
     * binding promises (combined review of runbook W2–W8, W4 M2 — the root cause of {@code minus}
     * reading an empty subtrahend over a zero-row dataset). A missing constant is handed through as
     * the very same vector (identity kept, register D36).
     *
     * <p>
     * ⭐ The <b>list form</b> — {@code upper($dataset_variables)} over a broadcast list folded once
     * into one constant, a per-row list ({@code split_by}) folded element by element, a MISSING
     * element carried through unchanged in its position (register D36), and since runbook W1a E4
     * {@code upper(values([X, Y]))} — is no longer this kernel's: it is the descriptor's
     * {@link FunctionDescriptor#liftingElementwise() element-wise lift} ({@code ElementwiseLift}),
     * which hands this kernel one element at a time and regroups. Confirmed as the list form of
     * upper / lower (owner, 2026-09-28, {@code PLAN-case-insensitive-templates} §1 / register CIT
     * §3: "upper allows a list of strings as parameter and returns a list of these strings
     * converted to upper case", "implement lower(...) for the same list as well"): a rule
     * normalises the DATASET side of a name comparison —
     * {@code upper(get_column_order_from_dataset())}, {@code upper(varname())} — against the
     * library's upper-case names (EC-28(a) / Fix #131: the case-insensitive contains twins are
     * spelled {@code contains(upper(ref), upper(lit))}, so a collection must stay a collection for
     * the membership branch in Primitives.substring to see it).
     * </p>
     */
    private static Vector caseFold(int rowCount, Vector x, boolean toLower)
    {
        if (x instanceof ConstVector constant)
        {
            TypedValue tv = constant.value(0);
            return tv.missing() != null ? constant : ConstVector.of(foldValue(tv, toLower));
        }
        return new ComputedVector(rowCount, DataValueType.STRING,
                row -> foldValue(x.value(row), toLower));
    }


    /** One value of {@link #caseFold}: a missing kept, a string folded. */
    private static Object foldValue(TypedValue tv, boolean toLower)
    {
        // upper(«missing») is that missing (register D36, identity kept — D85c: the input's
        // own cell is handed through); upper("") = "" (D34 #1, an empty string is present).
        // The boundary is TypedValue.missing(), never Vector.isMissing — the latter is the F3
        // fold and would make upper("") missing.
        if (tv.missing() != null)
        {
            return tv.cell();
        }
        String s = tv.cell().getValueAsString();
        return toLower ? s.toLowerCase(Locale.ROOT) : s.toUpperCase(Locale.ROOT);
    }


    /**
     * Per-row {@code coalesce(a, b[, c])}: the first operand that {@code empty()} would not flag —
     * neither a missing nor {@code ""} ({@link Vector#isMissing}, CO1) — resolved. Once every
     * operand is skipped, the answer is D86a over the operands that are genuinely missing
     * ({@link TypedValue#missing()} non-null; a present {@code ""} contributes no identity): one
     * distinct identity ⇒ that operand's own cell, two or more ⇒ {@code MIS}, none (every operand
     * {@code ""}) ⇒ the computed {@code MIS} ({@code PLAN-missing-identity-nonstring-functions}).
     * So {@code coalesce(.A, "")} is {@code .A}, {@code coalesce(.A, .B)} is {@code MIS}. Never
     * {@code null}: a kept operand is present, so its resolved payload is non-null.
     */
    private static Object coalesce(Vector a, Vector b, @Nullable Vector c, int row)
    {
        // Each operand's carrier is read once; ScalarSemantics.isMissing over its cell is exactly
        // Vector.isMissing (the CO1 skip predicate), without building the carrier a second time.
        TypedValue ta = a.value(row);
        if (!ScalarSemantics.isMissing(ta.cell()))
        {
            return Objects.requireNonNull(ta.resolved());
        }
        TypedValue tb = b.value(row);
        if (!ScalarSemantics.isMissing(tb.cell()))
        {
            return Objects.requireNonNull(tb.resolved());
        }
        TypedValue tc = c == null ? null : c.value(row);
        if (tc != null && !ScalarSemantics.isMissing(tc.cell()))
        {
            return Objects.requireNonNull(tc.resolved());
        }
        MissingValue missing = ArithmeticSemantics.combinedMissing(ta, tb, tc);
        return missing != null ? ArithmeticSemantics.carrierCell(missing, ta, tb, tc)
                : ScalarSemantics.computedMissing();
    }


    /**
     * Extracts a broadcast constant string from a (literal) operand vector — the literal text used
     * by {@code contains}/{@code starts_with}/{@code ends_with} and the affix-regex patterns
     * (mirroring the legacy {@code resolveLiteral} path). The compiler binds these operands as
     * broadcast {@code ConstVector}s, so any row index reads the same value.
     */
    private static String constString(Vector v)
    {
        Object o = v.value(0).resolved();
        return o != null ? o.toString() : "";
    }


    private static void value(List<FunctionDescriptor> fns, String name, EvalFunction fn)
    {
        fns.add(new FunctionDescriptor(name, List.of(p("x")), FunctionKind.VALUE, fn));
    }


    /** As {@link #value(List, String, EvalFunction)} with the declared parameters. */
    private static void value(List<FunctionDescriptor> fns, String name, List<Parameter> params,
            EvalFunction fn)
    {
        fns.add(new FunctionDescriptor(name, params, FunctionKind.VALUE, fn));
    }

    /** The {@code unit=} parameter of {@code len} / {@code max_value_length} (runbook W1b BL1). */
    public static final String UNIT_PARAMETER = "unit";

    /** The {@code encoding=} parameter of {@code len} / {@code max_value_length} (BL1). */
    public static final String ENCODING_PARAMETER = "encoding";

    /** {@code unit="bytes"}: the value encoded in the charset. */
    public static final String UNIT_BYTES = "bytes";

    /** {@code unit="chars"} (the default): characters. */
    public static final String UNIT_CHARS = "chars";

    /** The closed vocabulary of {@code unit=}. */
    public static final Set<String> UNITS = Set.of(UNIT_BYTES, UNIT_CHARS);

    /** Whether a {@code unit=} operand says {@code "bytes"} (absent / {@code "chars"}: no). */
    public static boolean countsBytes(@Nullable Vector unit)
    {
        return unit != null && UNIT_BYTES.equals(scalarText(unit));
    }


    /**
     * The charset a byte count encodes with (runbook W1b BL1): the one the encoding operand names,
     * or {@code null} when that operand is missing, blank or {@code MIXED} — the table declared no
     * single decode charset, so the byte count is unknown and the answer is missing. A written
     * literal was validated at load ({@code ExprCompiler.rejectNonLiteralStaticStrings}); a table's
     * own charset name the JVM does not know answers missing too.
     */
    public static @Nullable Charset charsetOf(@Nullable Vector encoding)
    {
        String name = encoding == null ? null : scalarText(encoding);
        if (name == null || name.isBlank() || "MIXED".equalsIgnoreCase(name.trim()))
        {
            return null;
        }
        try
        {
            return Charset.forName(name.trim());
        }
        catch (IllegalArgumentException unknown)
        {
            return null;
        }
    }


    /** The text of a scalar operand's first cell, or {@code null} when missing. */
    private static @Nullable String scalarText(Vector v)
    {
        TypedValue tv = v.value(0);
        if (tv.missing() != null)
        {
            return null;
        }
        Object o = tv.resolved();
        return o == null ? null : o.toString();
    }


    /**
     * As {@link #value(List, String, EvalFunction)}, the one-parameter transform declared
     * {@linkplain FunctionDescriptor#liftingElementwise() element-wise}: {@code fn} is the scalar
     * kernel, the registered function its lift over a list at {@code x}.
     */
    private static void elementwise(List<FunctionDescriptor> fns, String name, EvalFunction fn)
    {
        fns.add(new FunctionDescriptor(name, List.of(p("x")), FunctionKind.VALUE, fn)
                .liftingElementwise());
    }


    private static void bool(List<FunctionDescriptor> fns, String name, List<Parameter> params,
            EvalFunction fn)
    {
        fns.add(new FunctionDescriptor(name, params, FunctionKind.BOOLEAN, fn));
    }


    /**
     * As {@link #bool}, the predicate declared {@linkplain FunctionDescriptor#liftingElementwise()
     * element-wise} ({@code PLAN-elementwise-boolean-predicates} Q1 / Q5, runbook W1a EB1): the 16
     * one-subject well-formedness predicates the owner ruled into the initial roster. {@code fn} is
     * the scalar kernel (a {@code BitSet} over its run); over a list at parameter 0 the registered
     * function answers a {@code BoolListVector} the quantifiers {@code any} / {@code all} /
     * {@code none} fold. The two-operand predicates stay scalar: the list-elementwise gap plan
     * lifts none of their parameters (its Q1 (b)) — "x contains any element of a list" is the
     * dedicated {@code contains_any(x, list)}, registered with the substring block.
     */
    private static void predicate(List<FunctionDescriptor> fns, String name, List<Parameter> params,
            EvalFunction fn)
    {
        fns.add(new FunctionDescriptor(name, params, FunctionKind.BOOLEAN, fn)
                .liftingElementwise());
    }


    /**
     * The 21 dataset-level list functions of wave 4 ({@code PLAN-list-functions} §2.2): the LIBRARY
     * walks ({@link net.cumba.corej.core.exec.LibraryLists}), the per-row parent walk
     * ({@link net.cumba.corej.core.exec.ParentModelColumnOrder}), the DEFINE walks
     * ({@link net.cumba.corej.core.exec.DefineLists}), the inventory / schema walks
     * ({@link net.cumba.corej.core.exec.InventoryLists}) and
     * {@link net.cumba.corej.core.exec.Minus}.
     */
    private static void registerListFunctions(List<FunctionDescriptor> fns)
    {
        net.cumba.corej.core.expr.eval.ProviderNeed library = net.cumba.corej.core.expr.eval.ProviderNeed.LIBRARY;
        net.cumba.corej.core.expr.eval.ProviderNeed define = net.cumba.corej.core.expr.eval.ProviderNeed.DEFINE;
        // the zero-argument LIBRARY walks
        listFn(fns, net.cumba.corej.core.exec.LibraryLists.REQUIRED_VARIABLES, List.of(),
                net.cumba.corej.core.exec.LibraryLists::requiredVariables, library);
        listFn(fns, net.cumba.corej.core.exec.LibraryLists.EXPECTED_VARIABLES, List.of(),
                net.cumba.corej.core.exec.LibraryLists::expectedVariables, library);
        listFn(fns, net.cumba.corej.core.exec.LibraryLists.GET_COLUMN_ORDER_FROM_LIBRARY, List.of(),
                net.cumba.corej.core.exec.LibraryLists::columnOrderFromLibrary, library);
        listFn(fns, net.cumba.corej.core.exec.LibraryLists.GET_MODEL_COLUMN_ORDER, List.of(),
                net.cumba.corej.core.exec.LibraryLists::modelColumnOrder, library);
        listFn(fns, net.cumba.corej.core.exec.LibraryLists.GET_PERMITTED_COLUMN_ORDER, List.of(),
                net.cumba.corej.core.exec.LibraryLists::permittedColumnOrder, library);
        listFn(fns, net.cumba.corej.core.exec.LibraryLists.VARIABLE_NAMES, List.of(),
                net.cumba.corej.core.exec.LibraryLists::variableNames, library);
        listFn(fns, net.cumba.corej.core.exec.LibraryLists.STANDARD_DOMAINS, List.of(),
                net.cumba.corej.core.exec.LibraryLists::standardDomains, library);
        listFn(fns, net.cumba.corej.core.exec.LibraryLists.NATURAL_KEY_VARIABLES, List.of(),
                net.cumba.corej.core.exec.LibraryLists::naturalKeyVariables, library);
        // the LIBRARY walks with keyword parameters read once per call: the closed vocabularies
        // (key_name, model_class, level, returntype) are literals checked by the compile seam
        // ExprCompiler.rejectNonLiteralListArguments (D-W4-3); key_value, codelists and
        // ct_package_types take any dataset-level expression since W1a BS1, judged by their level
        // (ListOperandLevel.PER_CALL_PARAMETERS)
        listFn(fns, net.cumba.corej.core.exec.LibraryLists.GET_DATASET_FILTERED_VARIABLES, List.of(
                opt(net.cumba.corej.core.exec.LibraryLists.KEY_NAME_PARAMETER, Primitive.STRING),
                opt(net.cumba.corej.core.exec.LibraryLists.KEY_VALUE_PARAMETER, Primitive.STRING)),
                net.cumba.corej.core.exec.LibraryLists::datasetFilteredVariables, library);
        listFn(fns, net.cumba.corej.core.exec.LibraryLists.GET_MODEL_FILTERED_VARIABLES, List.of(
                opt(net.cumba.corej.core.exec.LibraryLists.KEY_NAME_PARAMETER, Primitive.STRING),
                opt(net.cumba.corej.core.exec.LibraryLists.KEY_VALUE_PARAMETER, Primitive.STRING),
                opt(net.cumba.corej.core.exec.LibraryLists.MODEL_CLASS_PARAMETER,
                        Primitive.STRING)),
                net.cumba.corej.core.exec.LibraryLists::modelFilteredVariables, library);
        listFn(fns, net.cumba.corej.core.exec.LibraryLists.VALID_CODELIST_DATES,
                List.of(opt(net.cumba.corej.core.exec.LibraryLists.CT_PACKAGE_TYPES_PARAMETER,
                        new ListOf(Primitive.STRING))),
                net.cumba.corej.core.exec.LibraryLists::validCodelistDates, library);
        listFn(fns, net.cumba.corej.core.exec.LibraryLists.CODELIST_TERMS,
                List.of(p(net.cumba.corej.core.exec.LibraryLists.CODELISTS_PARAMETER,
                        new ListOf(Primitive.STRING)),
                        p(net.cumba.corej.core.exec.LibraryLists.LEVEL_PARAMETER, Primitive.STRING),
                        opt(net.cumba.corej.core.exec.LibraryLists.RETURNTYPE_PARAMETER,
                                Primitive.STRING)),
                net.cumba.corej.core.exec.LibraryLists::codelistTerms, library);
        // get_parent_model_column_order(RDOMAIN) — the one PER-ROW list: the parent column is an
        // explicit COLUMN reference (D-W4-2), whose ROW demand is what makes the binding per-row;
        // not an aggregate.
        fns.add(new FunctionDescriptor(net.cumba.corej.core.exec.ParentModelColumnOrder.NAME,
                List.of(p("name", Primitive.COLUMN_REFERENCE)), FunctionKind.VALUE,
                net.cumba.corej.core.exec.ParentModelColumnOrder::evaluate).withProvider(library));
        // the DEFINE walks
        listFn(fns, net.cumba.corej.core.exec.DefineLists.DEFINE_VARIABLE_NAMES, List.of(),
                net.cumba.corej.core.exec.DefineLists::defineVariableNames, define);
        listFn(fns, net.cumba.corej.core.exec.DefineLists.DEFINE_DATASET_NAMES, List.of(),
                net.cumba.corej.core.exec.DefineLists::defineDatasetNames, define);
        listFn(fns, net.cumba.corej.core.exec.DefineLists.DEFINE_KEY_VARIABLES, List.of(),
                net.cumba.corej.core.exec.DefineLists::defineKeyVariables, define);
        // the inventory / schema walks (no provider)
        listFn(fns, net.cumba.corej.core.exec.InventoryLists.GET_COLUMN_ORDER_FROM_DATASET,
                List.of(), net.cumba.corej.core.exec.InventoryLists::columnOrderFromDataset, null);
        listFn(fns, net.cumba.corej.core.exec.InventoryLists.DATASET_NAMES, List.of(),
                net.cumba.corej.core.exec.InventoryLists::datasetNames, null);
        listFn(fns, net.cumba.corej.core.exec.InventoryLists.STUDY_DOMAINS, List.of(),
                net.cumba.corej.core.exec.InventoryLists::studyDomains, null);
        listFn(fns, net.cumba.corej.core.exec.InventoryLists.SPLIT_SIBLING_LENGTH_MISMATCH,
                List.of(), net.cumba.corej.core.exec.InventoryLists::splitSiblingLengthMismatch,
                null);
        listFn(fns, net.cumba.corej.core.exec.InventoryLists.DUPLICATE_LABEL_VARIABLES, List.of(),
                net.cumba.corej.core.exec.InventoryLists::duplicateLabelVariables, null);
        // minus(value, subtract=) — two list operands ($-bindings or static list literals),
        // untyped so that a list-shaped binding typed STRING by ElementTable (upper($list)) binds
        // without a stage-A parameter conflict; subtract is REQUIRED (D-W4-4).
        listFn(fns, net.cumba.corej.core.exec.Minus.NAME,
                List.of(p(net.cumba.corej.core.exec.Minus.VALUE_PARAMETER),
                        p(net.cumba.corej.core.exec.Minus.SUBTRACT_PARAMETER)),
                net.cumba.corej.core.exec.Minus::evaluate, null);
        // union(a, b, more...) — PLAN-union-function (runbook W1a U1): two or more dataset-level
        // list operands, typed list<unknown> so stage A refuses a scalar-typed operand
        // (PARAMETER_TYPE) and joins the element types; the collector takes the 3rd operand on.
        listFn(fns, net.cumba.corej.core.exec.Union.NAME, List.of(
                p(net.cumba.corej.core.exec.Union.FIRST_PARAMETER, new ListOf(Unknown.UNKNOWN)),
                p(net.cumba.corej.core.exec.Union.SECOND_PARAMETER, new ListOf(Unknown.UNKNOWN)),
                Parameter.collector(net.cumba.corej.core.exec.Union.MORE_PARAMETER,
                        new ListOf(new ListOf(Unknown.UNKNOWN)))),
                net.cumba.corej.core.exec.Union::evaluate, null);
    }


    /**
     * The six dataset-level scalar / metadata functions of wave 4b
     * ({@code PLAN-scalar-metadata-functions} §2.2, {@link ScalarMetadataFunctions}). Registered
     * through {@link #listFn} for its shape (VALUE, aggregate, optional capability) — none of them
     * answers a list.
     */
    private static void registerScalarFunctions(List<FunctionDescriptor> fns)
    {
        net.cumba.corej.core.expr.eval.ProviderNeed library = net.cumba.corej.core.expr.eval.ProviderNeed.LIBRARY;
        listFn(fns, ScalarMetadataFunctions.DOMAIN_IS_CUSTOM, List.of(),
                ScalarMetadataFunctions::domainIsCustom, library);
        listFn(fns, ScalarMetadataFunctions.DATASET_CLASS_FROM_LIBRARY, List.of(),
                ScalarMetadataFunctions::datasetClassFromLibrary, library);
        // extract_metadata("file_format") — the metadata KEY, any dataset-level string since W1a
        // BS1 (R6: KEEP-name).
        listFn(fns, ScalarMetadataFunctions.EXTRACT_METADATA,
                List.of(p(ScalarMetadataFunctions.NAME_PARAMETER, Primitive.STRING)),
                ScalarMetadataFunctions::extractMetadata, null);
        // cross_dataset_variable_metadata("label", domain="ADSL" | "*") — the attribute KEY and the
        // source dataset (or the `*` pattern), both string literals: a DATASET_REFERENCE (D10)
        // cannot hold `*`. The answer is the per-variable map (D-W4b-1). ⭐ domain is a NAME SLOT
        // (PLAN-string-literal-prefix-rewrite phase 0: a dataset name, measured by behaviour);
        // the attribute key is a value.
        fns.add(new FunctionDescriptor(ScalarMetadataFunctions.CROSS_DATASET_VARIABLE_METADATA,
                List.of(p(ScalarMetadataFunctions.NAME_PARAMETER, Primitive.STRING),
                        p(ScalarMetadataFunctions.DOMAIN_PARAMETER, Primitive.STRING).asNameSlot()),
                FunctionKind.VALUE, ScalarMetadataFunctions::crossDatasetVariableMetadata)
                        .answeringPerVariable());
        // variable_count("--LNKGRP") | variable_count(name_pattern="^.+FL$") | variable_count():
        // the template and the pattern are mutually exclusive (a load error, D-W4b-4).
        listFn(fns, ScalarMetadataFunctions.VARIABLE_COUNT,
                List.of(opt(ScalarMetadataFunctions.NAME_PARAMETER, Primitive.STRING),
                        opt(ScalarMetadataFunctions.PATTERN_PARAMETER, Primitive.STRING)),
                ScalarMetadataFunctions::variableCount, null);
        // column_series_metadata("COVAL", name_pattern="^COVAL\\d+$", min_length=200) — ⭐ name,
        // the un-numbered base column, is a NAME SLOT (PLAN-string-literal-prefix-rewrite phase
        // 0); variable_count's name is not marked — D92a keeps its fold template.
        listFn(fns, ScalarMetadataFunctions.COLUMN_SERIES_METADATA,
                List.of(opt(ScalarMetadataFunctions.NAME_PARAMETER, Primitive.STRING).asNameSlot(),
                        p(ScalarMetadataFunctions.PATTERN_PARAMETER, Primitive.STRING),
                        opt(ScalarMetadataFunctions.MIN_LENGTH_PARAMETER, Primitive.NUMBER)),
                ScalarMetadataFunctions::columnSeriesMetadata, null);
    }


    /** A dataset-level list function: VALUE kind, an aggregate, with an optional capability. */
    private static void listFn(List<FunctionDescriptor> fns, String name, List<Parameter> params,
            EvalFunction fn, net.cumba.corej.core.expr.eval.@Nullable ProviderNeed need)
    {
        FunctionDescriptor d = new FunctionDescriptor(name, params, FunctionKind.VALUE, fn)
                .aggregating();
        fns.add(need == null ? d : d.withProvider(need));
    }


    /** A required parameter of unspecified type (stage B / the element table decide). */
    private static Parameter p(String name)
    {
        return Parameter.required(name, Unknown.UNKNOWN);
    }


    /** A required parameter of the given declared type. */
    private static Parameter p(String name, ExprType type)
    {
        return Parameter.required(name, type);
    }


    /** An optional parameter of the given declared type. */
    private static Parameter opt(String name, ExprType type)
    {
        return Parameter.optional(name, type);
    }


    /**
     * A broadcast BOOLEAN result: every row set when {@code on}, else none (§9.C gate builtins).
     */
    private static BitSet allRows(int rowCount, boolean on)
    {
        BitSet b = new BitSet(rowCount);
        if (on)
        {
            b.set(0, rowCount);
        }
        return b;
    }


    /**
     * Whether a broadcast binding {@code result} is usable — not {@code null} and not an empty list
     * (an unresolved lookup, or a capability-carrying function's unusable answer caught into the
     * empty result). The {@code available(<x>)} gate folds this to a Precondition verdict. (Moved
     * from the retired operation executor in runbook W8.)
     */
    private static boolean resultAvailable(@Nullable Object result)
    {
        return result != null && !(result instanceof Collection<?> c && c.isEmpty());
    }

}
