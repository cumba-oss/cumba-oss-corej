package net.cumba.corej.core.expr.eval.spi;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.OperationExecutor;
import net.cumba.corej.core.exec.ScalarSemantics;
import net.cumba.corej.core.expr.eval.CalendarDates;
import net.cumba.corej.core.expr.eval.ColumnVector;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalFunction;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionKind;
import net.cumba.corej.core.expr.eval.FunctionProvider;
import net.cumba.corej.core.expr.eval.IsoDateComparison;
import net.cumba.corej.core.expr.eval.Primitives;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.values.DataValueType;
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
 * <li><b>VALUE transforms</b>: {@code lower}/{@code lowcase}, {@code upper}/{@code upcase},
 * {@code len}/{@code length} (integer length), {@code colref} (the two-hop dereference that ports
 * the legacy {@code value_is_reference:true} comparison operand), and the native-only helpers
 * {@code abs}/{@code round}/{@code floor}/{@code ceil} (numeric), {@code trim}, {@code concat}
 * (arity 2/3), {@code coalesce} (arity 2/3), {@code substring} (arity 2/3, 1-based start),
 * {@code prefix}/{@code suffix} (arity 2 — the first/last n characters, the legacy
 * {@code prefix_*}/{@code suffix_*} comparison operand), and the ISO-8601 date-component extractors
 * {@code year}/{@code month}/{@code day} (LONG).</li>
 * <li><b>BOOLEAN predicates</b>: {@code empty}/{@code is_missing},
 * {@code non_empty}/{@code present}/ {@code is_present}, {@code contains},
 * {@code does_not_contain}, {@code starts_with}, {@code ends_with}, {@code equalsIgnoreCase},
 * {@code prefix_matches}/{@code suffix_matches} (arity 2 = whole operand; arity 3 = length-bounded
 * affix), {@code imatches} (case-insensitive regex search), {@code is_integer},
 * {@code is_not_integer}, {@code invalid_duration}, {@code is_valid_duration}, and the
 * three-predicate calendar-validating date family {@code is_valid_date}/
 * {@code is_complete_date}/{@code is_partial_date} (alias {@code is_incomplete_date}) plus
 * {@code invalid_date} (= not valid) and the date-portion-only pair
 * {@code is_complete_date_part}/{@code is_not_complete_date_part} (Fix #157).</li>
 * </ul>
 *
 * <p>
 * Not registered here (handled by the compiler as core, since they are not pure row-value
 * predicates): the comparison/membership/regex-match operators ({@code == != < > <= >= in =~ !~}),
 * the boolean combinators, the type-tag markers {@code date}/{@code date_part}/{@code time_part}/
 * {@code num} (they change comparison <i>dispatch</i> rather than transform a value), and
 * {@code exists}/{@code not_exists} (dataset/column existence facts that need the
 * {@link net.cumba.corej.core.exec.EvaluationContext} and the operand name, not its row values).
 * </p>
 */
public final class BuiltinFunctions implements FunctionProvider
{

    @Override
    public List<FunctionDescriptor> functions()
    {
        List<FunctionDescriptor> fns = new ArrayList<>();

        // -- VALUE transforms ------------------------------------------------
        EvalFunction lower = (run, args) -> caseFold(run.rowCount(), args.get(0), true);
        value(fns, "lower", lower);
        value(fns, "lowcase", lower);
        EvalFunction upper = (run, args) -> caseFold(run.rowCount(), args.get(0), false);
        value(fns, "upper", upper);
        value(fns, "upcase", upper);
        EvalFunction len = (run, args) ->
        {
            Vector x = args.get(0);
            // len("") = 0 and len(«missing») = 0: a genuine missing folds to "" (length 0),
            // matching Python (see function-examples.md "Length — len / length").
            return new ComputedVector(run.rowCount(), DataValueType.LONG,
                    row -> (long) (x.isMissing(row) ? "" : x.asString(row)).length());
        };
        value(fns, "len", len);
        value(fns, "length", len);
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
        // ⚑ An empty / missing cell counts 0, following ScalarSemantics.isMissing — the same
        // convention that makes len("") and len(missing) both 0. A present scalar counts 1.
        EvalFunction count = (run, args) ->
        {
            Vector x = args.get(0);
            return new ComputedVector(run.rowCount(), DataValueType.LONG, row ->
            {
                if (x.isMissing(row))
                {
                    return 0L;
                }
                Object resolved = x.resolvedObject(row);
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
            return new ComputedVector(run.rowCount(), DataValueType.STRING,
                    row -> x.isMissing(row) ? null
                            : x.asString(row).strip().replaceAll("\\s+", " "));
        });
        // char(x): the Unicode code point of the first character of x, as a LONG. A missing/"" cell
        // ⇒ missing, so char(value()) <= 32 (the leading-space test) does not fire on a blank on
        // its
        // own (numeric <= over a missing LHS yields no violation).
        value(fns, "char", (run, args) ->
        {
            Vector x = args.get(0);
            return new ComputedVector(run.rowCount(), DataValueType.LONG, row ->
            {
                if (x.isMissing(row))
                {
                    return null;
                }
                String s = x.asString(row);
                return s.isEmpty() ? null : (long) s.codePointAt(0);
            });
        });

        // -- VALUE two-hop dereference (colref) ------------------------------
        // colref(X): the first-hop vector X yields, per row, a string that names a column; colref
        // reads that named column's value on the same row. Faithful port of the legacy
        // value_is_reference:true two-hop ("Fix #6" — CORE-000206 et al.).
        // The named column (e.g. a parent-domain key) is pre-merged into the evaluation table by
        // ChildMatchPreMerger before the EvaluationContext is built, so it is reachable here.
        value(fns, "colref", (run, args) ->
        {
            Vector firstHop = args.get(0);
            var ctx = run.ctx();
            return new ComputedVector(run.rowCount(), DataValueType.STRING, row ->
            {
                Object name = firstHop.resolvedObject(row);
                // Mirror ValueResolver: a non-string / empty first hop is returned as-is.
                if (!(name instanceof String colName) || colName.isEmpty())
                {
                    return name;
                }
                int colIdx = ctx.getTable().getMetaData().getColumnIndex(colName);
                if (colIdx < 0)
                {
                    return null; // named column absent (e.g. parent col not pre-merged)
                }
                // Blank resolves by the column's declared type — see
                // ScalarSemantics.resolvedString.
                return ScalarSemantics.resolvedString(ctx.getTable(), colIdx, row);
            });
        });

        // -- VALUE current-variable accessors (varname / value) -------------
        // varname(): the NAME of the "current variable" — the per-column cursor the metadata /
        // value-with-metadata paths set on the EvaluationContext (variables["variable_name"]). It
        // is
        // a broadcast scalar (one value per variable, constant across rows), so it mirrors the
        // standalone variable_name operand exactly (Expr.Ref("variable_name") resolves the same
        // cursor). Missing cursor ⇒ null.
        fns.add(new FunctionDescriptor("varname", 0, FunctionKind.VALUE, (run, _) ->
        {
            Object name = run.ctx().resolveVariable("variable_name");
            return ConstVector.of(name);
        }));
        // record_count(): the primary table's row count — the dataset-level fact the legacy
        // dataset fold reads (CheckConditionOptimizer.evaluateDatasetLeaf, name "record_count").
        // Broadcast-constant and numeric; comparisons mirror the fold's compareNumeric, equality
        // the fold's string-equality (identical verdicts for the integral counts involved).
        fns.add(new FunctionDescriptor("record_count", 0, FunctionKind.VALUE,
                (run, _) -> ConstVector.of(run.ctx().getTable().getRowCount())));
        // value(): the per-row VALUE of the "current variable" — the cells of the column named by
        // the cursor (variables["variable_name"]). Mirrors the legacy variable_value operand, which
        // CheckConditionOptimizer.bindVariableValue rewrites to the current column. A missing
        // cursor
        // or an absent column ⇒ a broadcast null (no row fires), matching the legacy resolution.
        fns.add(new FunctionDescriptor("value", 0, FunctionKind.VALUE, (run, _) ->
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
                return ConstVector.of(null);
            }
            return new ColumnVector(ctx.getTable().getColumn(idx), meta.getColumn(idx).getType());
        }));

        // -- VALUE numeric helpers (native-only; no legacy operator) ---------
        // Missing or non-numeric input ⇒ missing output. abs preserves fractions (DOUBLE);
        // round/floor/ceil yield an integral LONG. round is half-up toward +∞ (Math.round).
        value(fns, "abs", (run, args) -> numericValue(run.rowCount(), args.get(0),
                DataValueType.DOUBLE, Math::abs));
        value(fns, "round", (run, args) -> numericValue(run.rowCount(), args.get(0),
                DataValueType.LONG, d -> (double) Math.round(d)));
        value(fns, "floor", (run, args) -> numericValue(run.rowCount(), args.get(0),
                DataValueType.LONG, d -> Math.floor(d)));
        value(fns, "ceil", (run, args) -> numericValue(run.rowCount(), args.get(0),
                DataValueType.LONG, d -> Math.ceil(d)));

        // -- VALUE string / null helpers (native-only) -----------------------
        value(fns, "trim", (run, args) ->
        {
            Vector x = args.get(0);
            // trim("") = "" and trim(«missing») = "": a genuine missing folds to "" and is
            // stripped literally (see function-examples.md "Case & whitespace").
            return new ComputedVector(run.rowCount(), DataValueType.STRING,
                    row -> (x.isMissing(row) ? "" : x.asString(row)).strip());
        });
        // concat(a, b): string concatenation; a missing operand contributes the empty string, so
        // the
        // result is never missing (empty when both are). coalesce(a, b): the first non-missing
        // operand's resolved value, else missing.
        fns.add(new FunctionDescriptor("concat", 2, FunctionKind.VALUE, (run, args) ->
        {
            Vector a = args.get(0);
            Vector b = args.get(1);
            return new ComputedVector(run.rowCount(), DataValueType.STRING,
                    row -> orEmpty(a, row) + orEmpty(b, row));
        }));
        fns.add(new FunctionDescriptor("coalesce", 2, FunctionKind.VALUE, (run, args) ->
        {
            Vector a = args.get(0);
            Vector b = args.get(1);
            return new ComputedVector(run.rowCount(), DataValueType.STRING,
                    row -> !a.isMissing(row) ? a.resolvedObject(row)
                            : b.isMissing(row) ? null : b.resolvedObject(row));
        }));
        // concat(a, b, c) / coalesce(a, b, c): arity-3 variants, same per-operand semantics as the
        // arity-2 forms above (concat never missing; coalesce yields the first non-missing
        // operand).
        fns.add(new FunctionDescriptor("concat", 3, FunctionKind.VALUE, (run, args) ->
        {
            Vector a = args.get(0);
            Vector b = args.get(1);
            Vector c = args.get(2);
            return new ComputedVector(run.rowCount(), DataValueType.STRING,
                    row -> orEmpty(a, row) + orEmpty(b, row) + orEmpty(c, row));
        }));
        fns.add(new FunctionDescriptor("coalesce", 3, FunctionKind.VALUE, (run, args) ->
        {
            Vector a = args.get(0);
            Vector b = args.get(1);
            Vector c = args.get(2);
            return new ComputedVector(run.rowCount(), DataValueType.STRING, row ->
            {
                if (!a.isMissing(row))
                {
                    return a.resolvedObject(row);
                }
                if (!b.isMissing(row))
                {
                    return b.resolvedObject(row);
                }
                return c.isMissing(row) ? null : c.resolvedObject(row);
            });
        }));

        // -- VALUE substring (native-only; 1-based start, SAS/CDISC convention) ---
        // substring(x, start): the suffix of x beginning at the 1-based character position `start`.
        // substring(x, start, length): at most `length` characters from that position. A missing x
        // folds to "" and is judged literally; a missing/non-integral start (or length), a
        // start < 1, or a start beyond x's length all
        // yield a MISSING result. A length <= 0 yields the empty string; a length running past the
        // end of x is clamped to x's end (no exception).
        fns.add(new FunctionDescriptor("substring", 2, FunctionKind.VALUE, (run, args) ->
        {
            Vector x = args.get(0);
            Vector start = args.get(1);
            return new ComputedVector(run.rowCount(), DataValueType.STRING,
                    row -> substring(x, start, null, row));
        }));
        fns.add(new FunctionDescriptor("substring", 3, FunctionKind.VALUE, (run, args) ->
        {
            Vector x = args.get(0);
            Vector start = args.get(1);
            Vector length = args.get(2);
            return new ComputedVector(run.rowCount(), DataValueType.STRING,
                    row -> substring(x, start, length, row));
        }));

        // -- VALUE delimiter split (T9; native-only) -------------------------
        // split_by(x, delimiter): the per-row token LIST produced by splitting x on the literal
        // delimiter, keeping trailing empty tokens — mirroring the Python reference engine's
        // split_by operation (pandas Series.str.split, which keeps trailing empties). Each row's
        // cell is a List<String>, so a list-consuming operator (the per-row not_contains_all token
        // branch) reads it element-wise. The delimiter is a broadcast literal (2nd positional arg).
        // A missing x yields a null cell (no tokens ⇒ no violation); populated rows are the only
        // ones a split rule targets (its non_empty Precondition gates blanks). This is the native
        // lowering of a split_by OPERATION (SplitByInliner): coreJ has no SPLIT_BY OperationType
        // because a broadcast operation cannot carry a per-row-varying list.
        fns.add(new FunctionDescriptor("split_by", 2, FunctionKind.VALUE, (run, args) ->
        {
            Vector x = args.get(0);
            String delimiter = constString(args.get(1));
            return new ComputedVector(run.rowCount(), DataValueType.STRING,
                    row -> splitBy(x, delimiter, row));
        }));

        // -- VALUE composite key (T3; native-only) ---------------------------
        // tuple(c1, c2, ...): the current row's composite key as a List<String> cell (one element
        // per argument column, a missing cell contributing ""). Used as the left operand of the
        // composite cross-dataset membership `tuple(c1, c2) [not] in distinct([c1, c2],
        // domain="D")`
        // — the row fires when its tuple is (not) a member of the reference dataset's distinct
        // row-tuple set built by the list-target `distinct` operation. Registered per arity (the
        // FunctionRegistry keys on exact (name, arity)); 2..6 columns cover every composite key in
        // the corpus. The empty-string missing convention matches evalDistinctTuples so a row tuple
        // and a reference tuple compare List-equal.
        for (int arity = 2; arity <= 6; arity++)
        {
            fns.add(new FunctionDescriptor("tuple", arity, FunctionKind.VALUE,
                    (run, args) -> new ComputedVector(run.rowCount(), DataValueType.STRING,
                            row -> tupleKey(args, row))));
        }

        // -- VALUE ISO-8601 date-component extraction (native-only) ----------
        // year(x) / month(x) / day(x): the requested component of an ISO-8601 date as a LONG. A
        // leading `YYYY[-MM[-DD]]` prefix is parsed (any `T…` time part and anything after the day
        // is ignored). A missing/unparseable value, or a value that does not carry the requested
        // component, yields MISSING — e.g. year("2024")=2024, month("2024")=missing,
        // day("2024-03")=missing, year("2024-03-15T08:00")=2024. The components are NOT
        // calendar-validated here (use is_valid_date for that); a syntactically well-formed but
        // impossible value such as "2024-13" still yields month=13.
        value(fns, "year", (run, args) -> dateComponent(run.rowCount(), args.get(0), 0));
        value(fns, "month", (run, args) -> dateComponent(run.rowCount(), args.get(0), 1));
        value(fns, "day", (run, args) -> dateComponent(run.rowCount(), args.get(0), 2));

        // -- BOOLEAN range (native-only) -------------------------------------
        // between(x, lo, hi): fires where x is numeric and lo <= x <= hi (inclusive). lo/hi may be
        // literals or per-row columns; a missing/non-numeric x, lo, or hi never fires.
        bool(fns, "between", 3, (run, args) ->
        {
            Vector x = args.get(0);
            Vector lo = args.get(1);
            Vector hi = args.get(2);
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
        bool(fns, "empty", 1, empty);
        bool(fns, "is_missing", 1, empty);
        EvalFunction nonEmpty = (run, args) -> Primitives.nonEmpty(args.get(0), run.rowCount());
        bool(fns, "non_empty", 1, nonEmpty);
        bool(fns, "present", 1, nonEmpty);
        bool(fns, "is_present", 1, nonEmpty);

        // -- BOOLEAN library skip-gate (§9.C) --------------------------------
        // library_available(): true iff a Library MetadataProvider is configured AND — Fix #369 —
        // its CDISC Library could actually be consulted. The degraded arm matters because the
        // paired available(<op>) term is only injected when the check does more than test
        // emptiness (RulePackageLoader.testsOnlyEmptiness); for an emptiness-only check
        // library_available() IS the whole gate, and a degraded provider would otherwise pass it
        // and let the rule evaluate against the empty result it exists to prevent.
        // available(<op>): true iff the (broadcast) operation result is usable — not the
        // LIBRARY_NOT_AVAILABLE skip sentinel nor an empty (unresolved) list. Authored into a
        // rule's
        // Precondition so an inlined library operation SKIPS the rule (rather than passing) when
        // the
        // Library cannot answer, mirroring the legacy $-ref + Operations early-skip.
        bool(fns, "library_available", 0, (run, _) -> allRows(run.rowCount(),
                OperationExecutor.libraryAnswerable(run.ctx().getLibraryProvider())));
        bool(fns, "available", 1, (run, args) -> allRows(run.rowCount(),
                OperationExecutor.isResultAvailable(args.get(0).resolvedObject(0))));
        // T1: dictionary_available(<type>): true iff a dictionary of the named type is loaded into
        // the runtime dictionary provider. Injected as a Precondition gate for every inlined
        // external-dictionary operation so the rule SKIPs (rather than false-passes) when no
        // dictionary of that type is supplied.
        bool(fns, "dictionary_available", 1,
                (run, args) -> allRows(run.rowCount(),
                        run.ctx().getDictionaryProvider() != null && run.ctx()
                                .getDictionaryProvider().isAvailable(constString(args.get(0)))));

        // -- BOOLEAN substring -----------------------------------------------
        bool(fns, "contains", 2, (run, args) -> Primitives.contains(args.get(0), args.get(1),
                run.rowCount(), false));
        bool(fns, "does_not_contain", 2,
                (run, args) -> Primitives.contains(args.get(0), args.get(1), run.rowCount(), true));
        bool(fns, "starts_with", 2,
                (run, args) -> Primitives.startsWith(args.get(0), args.get(1), run.rowCount()));
        bool(fns, "ends_with", 2,
                (run, args) -> Primitives.endsWith(args.get(0), args.get(1), run.rowCount()));

        // -- BOOLEAN case-insensitive equality -------------------------------
        bool(fns, "equalsIgnoreCase", 2, (run, args) -> Primitives.equality(args.get(0),
                args.get(1), run.rowCount(), false, true, false, false));

        // -- BOOLEAN affix regex (anchored full-match on whole operand) ------
        bool(fns, "prefix_matches", 2,
                (run, args) -> Primitives.affixRegex(args.get(0),
                        Pattern.compile(constString(args.get(1))), run.rowCount(), true,
                        (Integer) null, false));
        bool(fns, "suffix_matches", 2,
                (run, args) -> Primitives.affixRegex(args.get(0),
                        Pattern.compile(constString(args.get(1))), run.rowCount(), false,
                        (Integer) null, false));

        // -- BOOLEAN affix regex, length-bounded (anchored full-match on the n-char affix) ---
        // prefix_matches(x, /re/, n) / suffix_matches(x, /re/, n): the 3-arg forms raised from a
        // (not_)(prefix|suffix)_matches_regex leaf carrying a prefix/suffix length. The regex is
        // matched (anchored) against the first/last n characters; a value shorter than n (or a
        // non-integral n) uses the whole string.
        bool(fns, "prefix_matches", 3,
                (run, args) -> Primitives.affixRegex(args.get(0),
                        Pattern.compile(constString(args.get(1))), run.rowCount(), true,
                        args.get(2), false));
        bool(fns, "suffix_matches", 3,
                (run, args) -> Primitives.affixRegex(args.get(0),
                        Pattern.compile(constString(args.get(1))), run.rowCount(), false,
                        args.get(2), false));

        // -- VALUE affix substrings (prefix / suffix) -------------------------
        // prefix(x, n) / suffix(x, n): the first/last n characters of x, raised from the legacy
        // prefix_/suffix_(not_)equal_to / _is_(not_)contained_by comparison leaves. Semantics
        // affix extraction: a value shorter than n (or a null /
        // non-positive / non-integral n) yields the WHOLE string; a missing x folds to "" (see
        // affixValue) — matching the legacy missing→"" fold so an affix compare agrees across
        // lanes.
        fns.add(new FunctionDescriptor("prefix", 2, FunctionKind.VALUE,
                (run, args) -> affixValue(run.rowCount(), args.get(0), args.get(1), true)));
        fns.add(new FunctionDescriptor("suffix", 2, FunctionKind.VALUE,
                (run, args) -> affixValue(run.rowCount(), args.get(0), args.get(1), false)));

        // -- BOOLEAN case-insensitive regex search (native-only) -------------
        // imatches(x, /regex/): unanchored case-insensitive search (Matcher.find with
        // Pattern.CASE_INSENSITIVE), mirroring the `=~` operator's find() semantics. A missing x
        // never fires. The 2nd arg is a /regex/ literal bound to a broadcast const string (see
        // ExprCompiler.LITERAL_ARG1).
        bool(fns, "imatches", 2,
                (run, args) -> Primitives.regexFind(args.get(0),
                        Pattern.compile(constString(args.get(1)), Pattern.CASE_INSENSITIVE),
                        run.rowCount(), false));

        // -- BOOLEAN integer -------------------------------------------------
        bool(fns, "is_integer", 1,
                (run, args) -> Primitives.isInteger(args.get(0), run.rowCount(), false));
        bool(fns, "is_not_integer", 1,
                (run, args) -> Primitives.isInteger(args.get(0), run.rowCount(), true));

        // -- BOOLEAN numeric -------------------------------------------------
        // is_numeric(x): finite-decimal hand-rolled scan; the negated form is written
        // `not is_numeric(X)`, so no is_not_numeric is registered.
        bool(fns, "is_numeric", 1,
                (run, args) -> Primitives.isNumeric(args.get(0), run.rowCount(), false));

        // -- BOOLEAN valid test code / variable name -------------------------
        // is_valid_testcd(x): findings-domain test code — first char [A-Za-z_], rest
        // [A-Za-z0-9_], length 1..8 (mixed case). is_valid_name(x): SAS/CDISC variable name —
        // first char [A-Z_], rest [A-Z0-9_], length 1..8 (uppercase only). Both are hand-rolled
        // scans (no regex); a missing/"" cell does not fire (so `not is_valid_*` fires on a blank,
        // matching the legacy not_matches_regex).
        bool(fns, "is_valid_testcd", 1,
                (run, args) -> Primitives.isValidTestcd(args.get(0), run.rowCount()));
        bool(fns, "is_valid_name", 1,
                (run, args) -> Primitives.isValidName(args.get(0), run.rowCount()));

        // -- BOOLEAN has-letter / has-digit ----------------------------------
        // has_alpha(x): contains >= 1 ASCII letter [A-Za-z]. has_digit(x): contains >= 1 ASCII
        // digit [0-9]. Both mirror the legacy unanchored matches_regex ".*[a-zA-Z].*" / ".*[0-9].*"
        // (CORE-000169); a missing/"" cell does not fire.
        bool(fns, "has_alpha", 1, (run, args) -> Primitives.hasAlpha(args.get(0), run.rowCount()));
        bool(fns, "has_digit", 1, (run, args) -> Primitives.hasDigit(args.get(0), run.rowCount()));

        // -- BOOLEAN duration ------------------------------------------------
        // EC-20/EC-22: absent negative= defaults to true (accept the signed grammar), matching the
        // Python reference engine and the aligned legacy operator. The arity-1 form is the fallback
        // for a bare invalid_duration(X); ExprCompiler.compileBoolCall intercepts the
        // kwarg-carrying
        // form and passes the parsed negative= flag through explicitly.
        bool(fns, "invalid_duration", 1,
                (run, args) -> Primitives.invalidDuration(args.get(0), run.rowCount(), true));
        bool(fns, "is_valid_duration", 1, (run, args) -> Primitives.stringPredicate(args.get(0),
                run.rowCount(), s -> !ScalarSemantics.isInvalidDuration(s, false)));

        // -- BOOLEAN date validity (calendar-validating, decision #4) --------
        bool(fns, "is_valid_date", 1, (run, args) -> Primitives.stringPredicate(args.get(0),
                run.rowCount(), CalendarDates::isValidDate));
        bool(fns, "is_complete_date", 1, (run, args) -> Primitives.stringPredicate(args.get(0),
                run.rowCount(), CalendarDates::isCompleteDate));
        EvalFunction partialDate = (run, args) -> Primitives.stringPredicate(args.get(0),
                run.rowCount(), CalendarDates::isPartialDate);
        bool(fns, "is_partial_date", 1, partialDate);
        bool(fns, "is_incomplete_date", 1, partialDate);
        // invalid_date: calendar-validating AND firing on a missing/blank cell. The previous
        // stringPredicate wiring carried a !isMissing guard that silently SUPPRESSED the blank case
        // (a blank is not a valid date, so it must be reported — never hidden); invalidDateCalendar
        // drops that guard while keeping calendar validation (decision in BuiltinFunctionsTest).
        bool(fns, "invalid_date", 1,
                (run, args) -> Primitives.invalidDateCalendar(args.get(0), run.rowCount()));
        // is_complete_date_part(x) / is_not_complete_date_part(x) — Fix #157. Judges ONLY the
        // leading YYYY-MM-DD date portion, so a truncated time ("2020-01-01T10") is complete here
        // while is_complete_date rejects it and is_incomplete_date fires on it. Equivalent to
        // is_complete_date(prefix(x, 10)) without the magic number. A missing/"" cell folds to ""
        // (not a complete date part), so the negative form fires on a blank — mirrors
        // is_integer/is_not_integer, NOT the is_complete_date/is_incomplete_date pair (which is
        // deliberately non-exhaustive: an invalid date is neither).
        bool(fns, "is_complete_date_part", 1,
                (run, args) -> Primitives.isCompleteDatePart(args.get(0), run.rowCount(), false));
        bool(fns, "is_not_complete_date_part", 1,
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
        // a MISSING result rather than a saturated sentinel: "the earliest date this could be" has
        // no answer, and 9999-12-31 is a real clinical value that must never be manufactured.
        fns.add(new FunctionDescriptor("earliest_possible", 1, FunctionKind.VALUE,
                (run, args) -> hullBound(run.rowCount(), args.get(0), false)));
        fns.add(new FunctionDescriptor("latest_possible", 1, FunctionKind.VALUE,
                (run, args) -> hullBound(run.rowCount(), args.get(0), true)));

        return fns;
    }


    /**
     * Per-row numeric transform: parses {@code x} as a double and applies {@code op}; a missing or
     * non-numeric cell yields a missing result. Used by {@code abs}/{@code round}/{@code floor}/
     * {@code ceil} (the LONG ones round the {@code op} result to an integral value via the vector's
     * declared type).
     */
    private static ComputedVector numericValue(int rowCount, Vector x, DataValueType type,
            java.util.function.DoubleUnaryOperator op)
    {
        return new ComputedVector(rowCount, type, row ->
        {
            Double d = numeric(x, row);
            if (d == null)
            {
                return null;
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


    /** The string form of {@code x} at {@code row}, or the empty string when missing. */
    private static String orEmpty(Vector x, int row)
    {
        return x.isMissing(row) ? "" : x.asString(row);
    }


    /**
     * Per-row {@code split_by(x, delimiter)}: the token list from splitting {@code x} on the
     * <em>literal</em> {@code delimiter} (quoted so it is never a regex), keeping trailing empty
     * tokens — bit-for-bit pandas {@code Series.str.split(delimiter)} (Python reference engine). A
     * missing {@code x} yields {@code null} (the operator treats a null cell as no tokens ⇒ no
     * violation); an empty delimiter yields the single-element list {@code [x]}. The returned list
     * is immutable and never contains {@code null} (splitting produces strings only).
     */
    private static @Nullable List<String> splitBy(Vector x, String delimiter, int row)
    {
        if (x.isMissing(row))
        {
            return null;
        }
        String s = x.asString(row);
        if (delimiter.isEmpty())
        {
            return List.of(s);
        }
        return List.of(s.split(Pattern.quote(delimiter), -1));
    }


    /**
     * Per-row {@code tuple(c1, c2, ...)}: the row's composite key as an immutable
     * {@code List<String>} (one element per argument, a missing cell contributing the empty string,
     * never {@code null}). The empty-string-for-missing convention matches
     * {@code OperationExecutor.evalDistinctTuples} so a row tuple and a reference tuple compare
     * {@link List#equals List-equal} in the composite membership branch (T3).
     */
    private static List<String> tupleKey(List<Vector> args, int row)
    {
        List<String> key = new ArrayList<>(args.size());
        for (Vector arg : args)
        {
            key.add(arg.isMissing(row) ? "" : arg.asString(row));
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
     */
    private static ComputedVector hullBound(int rowCount, Vector x, boolean high)
    {
        return new ComputedVector(rowCount, DataValueType.STRING,
                row -> x.isMissing(row) ? null : IsoDateComparison.bound(x.asString(row), high));
    }


    /**
     * Per-row {@code prefix(x, n)} / {@code suffix(x, n)}: the first/last {@code n} characters of
     * {@code x}, with the affix-extraction edge semantics (shorter-than-n / null / non-positive /
     * non-integral n ⇒ whole string). Missing ⇒ missing.
     */
    private static ComputedVector affixValue(int rowCount, Vector x, Vector n, boolean isPrefix)
    {
        return new ComputedVector(rowCount, DataValueType.STRING, row ->
        {
            // prefix("", n) = "" and prefix(«missing», n) = "": a genuine missing folds to "",
            // then the affix is extracted literally (see function-examples.md "Affix extraction").
            Integer len = integral(n, row);
            String s = x.isMissing(row) ? "" : x.asString(row);
            return isPrefix ? Primitives.extractPrefix(s, len) : Primitives.extractSuffix(s, len);
        });
    }


    /**
     * Per-row {@code substring} with a 1-based {@code start} (SAS/CDISC convention) and an optional
     * {@code length}. A missing {@code x} folds to {@code ""} and is judged literally. Returns
     * {@code null} (missing) when {@code start} is missing / non-integral / {@code < 1} / past the
     * end of {@code x}, or (when present) {@code length} is missing / non-integral. A
     * {@code length <= 0} yields the empty string; a {@code length} running past the end of
     * {@code x} is clamped.
     */
    private static @Nullable String substring(Vector x, Vector start, @Nullable Vector length,
            int row)
    {
        Integer startIdx = integral(start, row);
        if (startIdx == null || startIdx < 1)
        {
            return null;
        }
        // substring("", …) and substring(«missing», …): a genuine missing folds to "" and is then
        // subject to the unchanged bounds rules below (start past the end ⇒ missing). See
        // function-examples.md "Substring".
        String s = x.isMissing(row) ? "" : x.asString(row);
        int from = startIdx - 1; // 1-based -> 0-based
        if (from >= s.length())
        {
            return null; // start past the end ⇒ missing
        }
        if (length == null)
        {
            return s.substring(from);
        }
        Integer len = integral(length, row);
        if (len == null)
        {
            return null;
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
        Double d = numeric(v, row);
        if (d == null || Double.compare(d, Math.rint(d)) != 0 || Double.isInfinite(d))
        {
            return null;
        }
        return (int) (double) d;
    }


    /**
     * Extracts the {@code component} (0 = year, 1 = month, 2 = day) of a leading ISO-8601
     * {@code YYYY[-MM[-DD]]} prefix as a LONG, or {@code null} (missing) when {@code x} is missing,
     * the prefix is malformed, or the requested component is absent at the value's precision.
     */
    private static ComputedVector dateComponent(int rowCount, Vector x, int component)
    {
        return new ComputedVector(rowCount, DataValueType.LONG, row ->
        {
            if (x.isMissing(row))
            {
                return null;
            }
            Integer v = isoComponent(x.asString(row), component);
            return v == null ? null : (long) (int) v;
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


    private static ComputedVector caseFold(int rowCount, Vector x, boolean toLower)
    {
        return new ComputedVector(rowCount, DataValueType.STRING, row ->
        {
            // EC-28(a) / Fix #131: a COLLECTION-valued operand is folded ELEMENT-WISE and stays a
            // collection. The case-insensitive contains twins lower to
            // `contains(upper(ref), upper(lit))` (CheckToExpr:276-279), so without this the
            // set would be flattened to its toString() here and `contains` could only ever do a
            // substring probe on the rendered list — the very defect EC-28 fixes for the
            // case-sensitive pair. Keeping it a collection lets the membership branch in
            // Primitives.substring see it, giving case-insensitive EXACT membership.
            Object raw = x.resolvedObject(row);
            if (raw instanceof Collection<?> col)
            {
                List<String> folded = new ArrayList<>(col.size());
                for (Object item : col)
                {
                    String s = item != null ? item.toString() : "";
                    folded.add(toLower ? s.toLowerCase(Locale.ROOT) : s.toUpperCase(Locale.ROOT));
                }
                return folded;
            }
            // upper("") = "" and upper(«missing») = "": a genuine missing folds to "" and is
            // case-folded literally (see function-examples.md "Case & whitespace").
            String s = x.isMissing(row) ? "" : x.asString(row);
            return toLower ? s.toLowerCase(Locale.ROOT) : s.toUpperCase(Locale.ROOT);
        });
    }


    /**
     * Extracts a broadcast constant string from a (literal) operand vector — the literal text used
     * by {@code contains}/{@code starts_with}/{@code ends_with} and the affix-regex patterns
     * (mirroring the legacy {@code resolveLiteral} path). The compiler binds these operands as
     * broadcast {@code ConstVector}s, so any row index reads the same value.
     */
    private static String constString(Vector v)
    {
        Object o = v.resolvedObject(0);
        return o != null ? o.toString() : "";
    }


    private static void value(List<FunctionDescriptor> fns, String name, EvalFunction fn)
    {
        fns.add(new FunctionDescriptor(name, 1, FunctionKind.VALUE, fn));
    }


    private static void bool(List<FunctionDescriptor> fns, String name, int arity, EvalFunction fn)
    {
        fns.add(new FunctionDescriptor(name, arity, FunctionKind.BOOLEAN, fn));
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

}
