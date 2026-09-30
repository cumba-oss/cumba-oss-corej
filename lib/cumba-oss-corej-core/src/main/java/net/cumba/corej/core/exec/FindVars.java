package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.TypedValue;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * ⭐ {@code find_vars("<entry>")} — the column-set selector of {@code PLAN-dynamic-column-functions}
 * §2.4 (owner Q5, Q11, Q12). The argument is ONE {@code Requirements.Variables} entry, parsed by
 * {@link ScopeVariableEntry#parse} and matched by {@link ScopeMatcher#scopeEntryPattern} on its
 * VARIABLE half — the very parser and matcher that surface uses, so {@code find_vars(e)} returns
 * precisely the columns that satisfy {@code e} in {@code Requirements.Variables} and the two
 * surfaces cannot drift:
 * <ul>
 * <li>template ({@code "ADSL.TRTxxPN"}: {@code xx}/{@code zz} two digits, {@code y} one or more,
 * {@code w} one), glob ({@code *}, {@code ?}), regex ({@code "ADSL./TRT[0-9]+PN/"} — the qualifier
 * outside the slashes, the regex over the variable name only) and literal ({@code "ADSL.TRT01PN"},
 * an existence test); precedence regex, glob, template, literal; anchored, case-insensitive;</li>
 * <li>unqualified ⇒ the evaluation table (after the {@code Child} pre-merge); qualified ⇒ the
 * joined dataset's <b>real</b> columns (the split-domain union included) — never the SUPP-QNAM
 * pivot (Q12: a pivoted name is not a column {@code colref} can read);</li>
 * <li>the result is a {@code list<string>} of NAMES in the dataset's column order, spelled
 * {@code QUALIFIER.COLUMN} (the qualifier as written, the column in the dataset's own case); only
 * {@code colref} dereferences them.</li>
 * </ul>
 *
 * <p>
 * A literal entry is evaluated once per execution (one broadcast list, dataset level); a computed
 * one per row, memoised by its text for the evaluation. The column matching goes through the run's
 * {@link WildcardForeignColumnCache}, keyed by (table, pattern), exactly as {@code ${*}} does. A
 * missing entry answers that missing (D36); {@code ""} names no column ({@code []}).
 * </p>
 */
public final class FindVars
{

    /** The function name. */
    public static final String NAME = "find_vars";

    private FindVars()
    {
    }


    /**
     * {@code find_vars(entry)} over the entry vector.
     *
     * @param run
     *            the evaluation run
     * @param entry
     *            the entry: a broadcast literal, or a per-row computed string
     * @param literal
     *            whether the argument was a string LITERAL — decided by the compiler from the
     *            expression, never from the vector: a constant-folded {@code concat("/T", "/")} is
     *            a broadcast vector too, and is computed
     * @return the per-row name lists (a broadcast one for a constant entry)
     */
    public static Vector evaluate(EvalRun run, Vector entry, boolean literal)
    {
        EvaluationContext ctx = run.ctx();
        if (entry instanceof ConstVector)
        {
            // Dataset level: computed ONCE for the execution, broadcast to every row.
            Object once = resolve(ctx, entry.value(0), literal);
            return once instanceof List<?> ? ConstVector.of(once)
                    : new ComputedVector(run.rowCount(), DataValueType.STRING, _ -> once);
        }
        Map<String, List<String>> memo = new HashMap<>();
        return new ComputedVector(run.rowCount(), DataValueType.STRING, row ->
        {
            TypedValue tv = entry.value(row);
            if (tv.missing() != null)
            {
                return tv.cell();
            }
            Object resolved = tv.resolved();
            if (!(resolved instanceof String text))
            {
                return ScalarSemantics.computedMissing();
            }
            return memo.computeIfAbsent(text, t -> names(ctx, t, false));
        });
    }


    /** One entry value: a present string's names, else that missing / the computed missing. */
    private static Object resolve(EvaluationContext ctx, TypedValue tv, boolean literal)
    {
        if (tv.missing() != null)
        {
            IDataValue cell = tv.cell();
            return cell;
        }
        return tv.resolved() instanceof String text ? names(ctx, text, literal)
                : ScalarSemantics.computedMissing();
    }


    /**
     * The column names {@code entryText} selects.
     *
     * @param ctx
     *            the evaluation context
     * @param entryText
     *            the entry
     * @param literal
     *            whether the entry was a string literal (a regex entry must be)
     * @return the names, in column order (immutable)
     */
    static List<String> names(EvaluationContext ctx, String entryText, boolean literal)
    {
        return names(ctx, entryText, literal, false);
    }


    /**
     * {@link #names(EvaluationContext, String, boolean)}, spelling a qualified name's qualifier as
     * the rule DECLARES it when {@code declaredSpelling} — the {@code Output_Variables} step's
     * form: the report reads each expanded name through the join, which the rule declared under its
     * own spelling (review round 2: {@code "adsl.TRTxxA"} reports {@code ADSL.TRT01A}).
     */
    static List<String> names(EvaluationContext ctx, String entryText, boolean literal,
            boolean declaredSpelling)
    {
        if (entryText.isEmpty())
        {
            return List.of();
        }
        ScopeVariableEntry entry = ScopeVariableEntry.parse(entryText);
        if (!literal && ScopeVariableEntry.isWholeEntryRegex(entry.variable()))
        {
            // D35 "written wrong": a regex entry must be a string LITERAL (SPEC: a regex is
            // literal-only, never computed). Column names cannot contain '/', so treating the
            // value as a literal name would silently match nothing.
            throw new RuleDefinitionException("find_vars: the computed entry '" + entryText
                    + "' is a /regex/ — a regex entry must be a string literal (a computed value"
                    + " may be a template, a glob or a literal name)");
        }
        IDataTable table;
        String prefix;
        String variable;
        if (entry.isQualified())
        {
            String qualifier = java.util.Objects.requireNonNull(entry.qualifier());
            String declared = declaredQualifier(ctx, qualifier);
            if (declared == null)
            {
                return List.of(); // undeclared or not supplied: no column colref could read
            }
            table = SplitDomainResolution.resolveTableOrThrow(ctx.getDatasetResolver(), declared,
                    ctx.getRuleId());
            if (table == null)
            {
                return List.of();
            }
            prefix = (declaredSpelling ? declared : qualifier) + ".";
            variable = entry.variable();
        }
        else
        {
            table = ctx.getTable();
            prefix = "";
            if (!literal && entry.variable().startsWith("--"))
            {
                // Review round 1, lane A M1: a COMPUTED entry spelling `--` is data, not a
                // specialiser defect — no column is spelled that way, so it selects nothing.
                return List.of();
            }
            // D77b: an unresolved `--` in a LITERAL entry reaching evaluation is a specialiser
            // defect.
            variable = net.cumba.corej.core.expr.eval.ExprCompiler
                    .resolveDomainPrefixForName(entry.variable(), ctx);
        }
        // A literal entry's pattern is memoised JVM-wide (a bounded, authored population); a
        // computed one only per evaluation (evaluate's memo) — per-row DATA must not grow a
        // JVM-wide map.
        Pattern pattern = literal ? patternOf(variable) : compile(variable);
        DataTableMeta meta = table.getMetaData();
        int[] columns = ctx.getWildcardColumns().matchingColumns(table, pattern);
        List<String> out = new ArrayList<>(columns.length);
        for (int c : columns)
        {
            out.add(prefix + meta.getColumn(c).getName());
        }
        return List.copyOf(out);
    }


    /**
     * The anchored, case-insensitive pattern of an entry's variable half —
     * {@link ScopeMatcher#scopeEntryPattern}'s, or a quoted literal — compiled once per distinct
     * text for the JVM ({@link #PATTERNS}): the Output_Variables step asks for it on every reported
     * row.
     */
    private static Pattern patternOf(String variable)
    {
        return PATTERNS.computeIfAbsent(variable, FindVars::compile);
    }


    private static Pattern compile(String variable)
    {
        Pattern p = ScopeMatcher.scopeEntryPattern(variable);
        return p != null ? p : Pattern.compile(Pattern.quote(variable), Pattern.CASE_INSENSITIVE);
    }


    /**
     * ⭐ {@code PLAN-dynamic-column-functions} §2.6 (owner Q6 (a)): whether an
     * {@code Output_Variables} entry is a {@code Requirements.Variables} PATTERN entry — a
     * template, a glob or a {@code /regex/}, bare or qualified — that the step expands into every
     * existing matching column. A {@code $}-binding, a {@code !}-exclusion, a {@code ${…}} entry
     * (its own expansion, unchanged — Q8), the rule expansion's own vocabulary (a leading {@code *}
     * capture, a RELREC {@code **}) and a literal name are not; an engine name
     * ({@code variable_name}) is none either, because the shared matcher finds no template, glob or
     * regex in it.
     *
     * <p>
     * ⚑ Review round 2: a LOWER-case first letter no longer excludes an entry. Names match ignoring
     * case on every surface, templates included (owner 2026-09-28, CIT §1), so a lower-case
     * qualifier ({@code "adsl.TRTxxP"}) or glob ({@code "trt0?p"}) is a pattern entry exactly as it
     * is in {@code Requirements.Variables}; the test is the matcher's alone.
     * </p>
     *
     * @param entry
     *            the entry
     * @return whether it is expanded by the pattern step
     */
    public static boolean isOutputVariablePattern(@Nullable String entry)
    {
        if (entry == null || entry.isEmpty())
        {
            return false;
        }
        return OUTPUT_PATTERN.computeIfAbsent(entry, e ->
        {
            char first = e.charAt(0);
            // Not a pattern entry of this step: a $-binding, a !-exclusion, a ${…} entry (its own
            // unchanged expansion, Q8), and the rule-EXPANSION vocabulary — a leading `*` ADaM
            // capture (`*DTM`, `*GRyN`) and a RELREC `**` reference, which the WildcardExpander /
            // RelrecExpandedLookup resolve (measured: 97 such corpus entries).
            if (first == '$' || first == '!' || first == '*' || e.contains("${")
                    || e.contains("**"))
            {
                return false;
            }
            return ScopeMatcher.scopeEntryPattern(ScopeVariableEntry.parse(e).variable()) != null;
        });
    }


    /**
     * The {@code Output_Variables} pattern step: every {@linkplain #isOutputVariablePattern pattern
     * entry} replaced, in place, by the EXISTING columns it matches ({@code JVA 2c}: an absent one
     * is never reported), through {@link #names} — the very matcher {@code find_vars} uses, so the
     * report names exactly the columns {@code find_vars} would. Every other entry is kept verbatim:
     * a {@code $}-binding, a {@code !}-exclusion, an engine name (no template, glob or regex), a
     * {@code ${…}} entry (its own unchanged expansion, Q8), a leading-{@code *} ADaM capture and a
     * RELREC {@code **} reference ({@link #isOutputVariablePattern}'s carve-outs), a literal name —
     * and a pattern-shaped entry that is ALSO the literal name of an existing column
     * ({@link #namesAColumn}).
     *
     * <p>
     * ⚑ Measured at review round 1 (lane C F5): the corpus carries 691 template-shaped entries —
     * 594 pattern entries by this step's own test plus 97 carved-out leading-{@code *} / {@code **}
     * ones — and every one of the 594 sits in a rule whose Check or Bindings carry the same name,
     * so {@code WildcardExpander} renames it before {@code RuleRunner} sees it. Over the whole
     * corpus suite (scenarios, rulespec, findings snapshot) the step expanded NO entry; the only
     * entries that reached it — ten, in nine rulespec fixtures — were kept by
     * {@link #namesAColumn}.
     * </p>
     *
     * @param outputVars
     *            the entries, after the {@code --} and {@code ${*}} steps
     * @param ctx
     *            the evaluation context
     * @return the expanded entries — the same list when no entry is a pattern
     */
    public static List<String> expandOutputVariables(List<String> outputVars, EvaluationContext ctx)
    {
        List<String> out = null;
        for (int i = 0; i < outputVars.size(); i++)
        {
            String v = outputVars.get(i);
            if (!isOutputVariablePattern(v) || namesAColumn(ctx, v))
            {
                if (out != null)
                {
                    out.add(v);
                }
                continue;
            }
            if (out == null)
            {
                out = new ArrayList<>(outputVars.subList(0, i));
            }
            out.addAll(names(ctx, v, true, true));
        }
        return out == null ? outputVars : out;
    }


    /**
     * Whether a pattern-shaped entry is ALSO the literal name of an existing column — then it is
     * that column, and the step keeps it verbatim, exactly as the values loop reads it.
     *
     * <p>
     * ⚑ Kept on purpose (review round 1, lane C F5, tried and reverted): its only carriers are nine
     * rulespec fixtures (ten entries: {@code TRTxxP}, {@code TRTxxA}, {@code TRTPGyN},
     * {@code CRITy}, {@code TRTAGyN}, {@code TRxxPGy}, {@code BCHGCATy}, {@code PBCHGCAy}) whose
     * table names a column after the template. That is deliberate there: the rulespec harness runs
     * a rule WITHOUT the {@code WildcardExpander} ({@code SpecRunner} → {@code RuleRunner}), so the
     * template-named column stands in for the expanded member and the spec pins the POST-expansion
     * Check. Reshaped to real members ({@code TRT02P}), all nine specs lost their violation — their
     * claim — so the fixtures and this carve-out stay.
     * </p>
     */
    private static boolean namesAColumn(EvaluationContext ctx, String entry)
    {
        ScopeVariableEntry parsed = ScopeVariableEntry.parse(entry);
        if (!parsed.isQualified())
        {
            return ctx.getTable().getMetaData().getColumnIndex(entry) >= 0;
        }
        String declared = declaredQualifier(ctx,
                java.util.Objects.requireNonNull(parsed.qualifier()));
        if (declared == null)
        {
            return false;
        }
        IDataTable table = SplitDomainResolution.resolveTableOrThrow(ctx.getDatasetResolver(),
                declared, ctx.getRuleId());
        return table != null && table.getMetaData().getColumnIndex(parsed.variable()) >= 0;
    }


    /**
     * The rule's {@code Match_Datasets} name a qualifier stands for, matched IGNORING letter case
     * (owner 2026-09-28, CIT §1 — review round 2: {@code "adsl.TRTxxA"} enumerates the declared
     * {@code ADSL}), or {@code null} when the rule declares no such dataset.
     */
    private static @Nullable String declaredQualifier(EvaluationContext ctx, String qualifier)
    {
        if (ctx.getJoinedDatasets().containsKey(qualifier))
        {
            return qualifier;
        }
        for (String name : ctx.getJoinedDatasets().keySet())
        {
            if (name.equalsIgnoreCase(qualifier))
            {
                return name;
            }
        }
        return null;
    }

    /** {@link #patternOf}'s JVM-wide memo, keyed by the variable half's text. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Pattern> PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();

    /** {@link #isOutputVariablePattern}'s JVM-wide memo. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> OUTPUT_PATTERN = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The load-time check of a string-literal entry (§2.4 / §3.1: armed compiler load errors
     * through the static-string seam): a type suffix, a whole-entry regex that spells its qualifier
     * INSIDE the slashes (it would be unqualified and match nothing), a {@code --} in a qualified
     * variable half, and an invalid regex. An unknown qualifier is NOT a load error — it is Stage
     * A's observe-only {@code DOTTED_REF_UNDECLARED}, as for the authored {@code DS.X}.
     *
     * @param entry
     *            the literal entry
     * @return the error message, or {@code null} when the entry is well formed
     */
    public static @Nullable String literalEntryError(String entry)
    {
        if (ScopeVariableEntry.hasTypeSuffix(entry)
                || ScopeVariableEntry.malformedTypeSuffix(entry) != null)
        {
            return "find_vars entry \"" + entry + "\" carries a type suffix (:N / :C) — a"
                    + " Requirements.Variables feature with no meaning for a list of names; drop"
                    + " the suffix";
        }
        if (ScopeVariableEntry.isWholeEntryRegex(entry))
        {
            String body = entry.substring(1, entry.length() - 1);
            java.util.regex.Matcher qualified = LEADING_QUALIFIER.matcher(body);
            boolean leading = qualified.lookingAt();
            if (body.contains("\\.") || leading)
            {
                String qualifier = leading ? qualified.group(1) : "DS";
                String rest = leading ? body.substring(qualified.end()) : body;
                return "find_vars entry \"" + entry + "\" spells a dataset qualifier inside the"
                        + " regex, where it is part of the pattern and the entry is UNQUALIFIED (it"
                        + " would match nothing): write the qualifier outside the slashes, \""
                        + qualifier + "./" + rest + "/\" (anchor with ^ if the '.' is meant as"
                        + " a pattern)";
            }
        }
        ScopeVariableEntry parsed = ScopeVariableEntry.parse(entry);
        if (parsed.isQualified() && parsed.variable().contains("--"))
        {
            return "find_vars entry \"" + entry + "\" has a -- in a qualified variable half — a"
                    + " qualified entry names another dataset's column, which no domain prefix"
                    + " resolves";
        }
        try
        {
            ScopeMatcher.scopeEntryPattern(parsed.variable());
        }
        catch (PatternSyntaxException bad)
        {
            return "find_vars entry \"" + entry + "\" is not a valid regular expression: "
                    + bad.getDescription();
        }
        return null;
    }


    /**
     * Whether a literal entry is a regex entry — exempt from the {@code --} specialisation like
     * {@code name_pattern=} (D92b; SPEC §7.2's sixth item).
     *
     * @param entry
     *            the literal entry
     * @return whether its variable half is a {@code /regex/}
     */
    public static boolean isRegexEntry(String entry)
    {
        return ScopeVariableEntry.isWholeEntryRegex(entry)
                || ScopeVariableEntry.isWholeEntryRegex(ScopeVariableEntry.parse(entry).variable());
    }

    /** An identifier followed by a '.' — escaped or not — at the start of a regex body. */
    private static final Pattern LEADING_QUALIFIER = Pattern
            .compile("([A-Za-z][A-Za-z0-9_]*)\\\\?\\.");
}
