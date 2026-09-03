package net.cumba.corej.core.exec;

import java.util.BitSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Name-existence and metadata probes used by the compiled native plan.
 *
 * <p>
 * <strong>The class name is historical.</strong> This class no longer registers or dispatches
 * operators — the legacy per-row operator engine was removed once every shipped rule compiled to a
 * native {@code Expr}. What remains is the set of probes {@code ExprCompiler} calls when compiling
 * {@code exists} / {@code var_exists} / {@code ds_exists} / {@code var_is_null} /
 * {@code max_value_length} plans, plus two helpers shared with {@link CohortRunner} and
 * {@link ScopeVariableSource}. A rename is deliberately deferred.
 * </p>
 */
public final class OperatorRegistry
{

    /**
     * Plain dotted reference {@code <DOMAIN>.<col>}: pure metadata question — does column
     * {@code <col>} exist in the schema of dataset {@code <DOMAIN>}? Used by Variable Metadata
     * Check rules (Fix #18, CDISC-AD0641–0646).
     */
    private static final Pattern DOTTED_DATASET_COLUMN = Pattern
            .compile("^[A-Z][A-Z0-9]*\\.[A-Z][A-Z0-9_]*$");

    /**
     * Filter form {@code <DOMAIN>.<KEY>=<VALUE>}: does the resolved foreign dataset contain at
     * least one row whose {@code <KEY>} cell equals {@code <VALUE>}? Subsumes CDISC-AD0640's
     * {@code SUPPAE.QNAM=AETRTEM} (Fix #18).
     */
    private static final Pattern DOTTED_FILTER = Pattern
            .compile("^([A-Z][A-Z0-9]*)\\.([A-Z][A-Z0-9_]*)=([A-Z0-9_]+)$");

    private OperatorRegistry()
    {
    }


    /**
     * Dataset-presence fact for {@code ds_exists}/{@code ds_not_exists}: is the named dataset part
     * of the submission? Independent of any rule type (the retired generic {@code exists} was the
     * one call whose fact the type decided). The Boolean context-variable probe stays first: a
     * caller may pre-inject dataset names as boolean variables, and {@code ds_exists} honours them.
     */
    public static boolean existsAsDataset(EvaluationContext ctx, @Nullable String name)
    {
        Object varVal = ctx.resolveVariable(name);
        if (varVal instanceof Boolean b)
        {
            return b;
        }
        // Fix #358 (D7, widen both halves): a split domain (lbch/lbhe/lbur with no standalone LB)
        // IS part of the submission, so ds_exists("LB") answers true. This is the same widened
        // presence fact AbsentDatasetSkip tests, keeping SKIP and the presence rule in lockstep
        // on split submissions. An un-unionable split still counts as present — its data ships,
        // the union merely cannot be joined.
        return name != null
                && SplitDomainResolution.isPresentAsDomain(ctx.getDatasetResolver(), name);
    }


    /**
     * Variable-presence fact for {@code var_exists}/{@code var_not_exists}: does the named column
     * exist on the dataset under evaluation? Always the column form, never dataset presence. The
     * full column-form surface is supported: Boolean context variables, dotted cross-dataset names
     * incl. the SUPP QNAM pivot and the {@code DS.KEY=VALUE} filter form ({@link #dottedExists}),
     * {@code ${...}} substitution, and the plain local-schema lookup.
     */
    public static boolean existsAsVariable(EvaluationContext ctx, @Nullable String name)
    {
        Boolean common = existsCommon(ctx, name);
        if (common != null)
        {
            return common;
        }
        // existsCommon decides every null name, so the local-schema lookup sees a real one.
        String colName = java.util.Objects.requireNonNull(name);
        return ctx.getTable().getMetaData().getOptionalColumn(colName) != null;
    }


    /**
     * T5a — per-variable all-null: {@code true} when {@code name} is absent from the dataset under
     * evaluation, or present but empty ("" / missing) for <em>every</em> record; {@code false} as
     * soon as any row carries a value. A {@code null} name (an unresolved cursor) reads as
     * all-null. Mirrors {@code OperationExecutor}'s {@code variable_is_null} and the Python
     * reference engine's {@code variable_is_null}
     * ({@code (series.isnull() | (series == "")).all()}). Backs the native {@code var_is_null(X)}
     * cursor predicate.
     */
    public static boolean variableIsNull(EvaluationContext ctx, @Nullable String name)
    {
        if (name == null)
        {
            return true;
        }
        DataTableMeta meta = ctx.getTable().getMetaData();
        int colIdx = meta.getColumnIndex(name);
        if (colIdx < 0)
        {
            return true; // absent column => null (Python variable_is_null parity)
        }
        IDataTableColumn col = ctx.getTable().getColumn(colIdx);
        int rowCount = ctx.rowCount();
        for (int r = 0; r < rowCount; r++)
        {
            IDataValue dv = col.getDataValue(r);
            if (!dv.isMissingOrInvalid() && !dv.getValueAsString().isEmpty())
            {
                return false;
            }
        }
        return true;
    }


    /**
     * T5b (SD1082) — the maximum stored value length of the named variable: the largest codepoint
     * count over the column's non-missing values, or {@code 0} when the column is absent from the
     * dataset or every value is missing. Empty strings ({@code ""}) are counted (length&nbsp;0),
     * missing / invalid cells are skipped. A {@code null} name (an unresolved cursor) reads as
     * {@code 0}. Mirrors the Python reference engine's {@code variable_max_size}
     * ({@code df[var].dropna().astype(str).str.len().max()}, {@code 0} when empty) — codepoints,
     * not bytes, so a supplementary-plane character counts once, matching pandas. Backs the native
     * {@code max_value_length(X)} cursor value function.
     */
    public static long maxValueLength(EvaluationContext ctx, @Nullable String name)
    {
        if (name == null)
        {
            return 0L;
        }
        DataTableMeta meta = ctx.getTable().getMetaData();
        int colIdx = meta.getColumnIndex(name);
        if (colIdx < 0)
        {
            return 0L; // absent column => 0 (Python variable_max_size parity)
        }
        IDataTableColumn col = ctx.getTable().getColumn(colIdx);
        int rowCount = ctx.rowCount();
        long max = 0L;
        for (int r = 0; r < rowCount; r++)
        {
            IDataValue dv = col.getDataValue(r);
            if (dv.isMissingOrInvalid())
            {
                continue; // dropna(): missing cells do not contribute
            }
            String s = dv.getValueAsString();
            long len = s.codePointCount(0, s.length());
            if (len > max)
            {
                max = len;
            }
        }
        return max;
    }


    /**
     * The prefix of {@link #existsAsVariable}: Boolean context variable, dotted cross-dataset
     * forms, {@code ${…}} substitution, and the nameless-leaf guard. Returns {@code null} when none
     * of these decide, so the caller continues with the local-schema lookup.
     */
    private static @Nullable Boolean existsCommon(EvaluationContext ctx, @Nullable String name)
    {
        // Check pre-resolved boolean variable (Domain Presence Check)
        Object varVal = ctx.resolveVariable(name);
        if (varVal instanceof Boolean b)
        {
            return b;
        }

        // Fix #18 — metadata-level cross-dataset references. Both forms are
        // evaluated against the foreign dataset's schema/rows via
        // DatasetResolver, regardless of RuleType. They take precedence over
        // the per-rule-type branches below so a leaf like AE.AESTDY does not
        // get treated as a literal column name in the current (ADaM) dataset.
        Boolean dotted = dottedExists(ctx, name);
        if (dotted != null)
        {
            return dotted;
        }

        // Fix #37 — operand-template substitution in name position. For exists/not_exists
        // on a wildcard, returns true iff at least one matching column exists. For a scalar
        // substitution, the substituted column name is checked. This entry point is
        // dataset-level, so its result is broadcast to all rows by the caller; for
        // substitution that depends on per-row drivers the substituted check is evaluated
        // against row 0 only, and the per-row form lives in {@link #existsPerRowBits}.
        if (OperandSubstitutor.hasPlaceholder(name))
        {
            return existsForSubstitutedName(ctx, name);
        }

        // A leaf with no name cannot reference a column or dataset.
        if (name == null)
        {
            return Boolean.FALSE;
        }
        return null;
    }


    /**
     * Returns {@code true} if the data value is missing, invalid, null, or an empty string. In
     * clinical data (especially SAS), character variables cannot be null — they are represented as
     * empty strings instead. This method treats empty strings as missing for rule evaluation
     * purposes.
     */
    static boolean isMissing(IDataValue dv)
    {
        return ScalarSemantics.isMissing(dv);
    }


    /**
     * Fix #37 — per-row existence check for a name-position operand carrying substitution
     * placeholders. For a {@link OperandSubstitutor.Scalar} the substituted concrete column is
     * checked per row; for a {@link OperandSubstitutor.Wildcard} the foreign-dataset schema is
     * scanned for any column matching the pattern.
     *
     * @param ctx
     *            the evaluation context (primary table, resolver, per-row drivers)
     * @param name
     *            the operand carrying at least one {@code ${...}} placeholder
     * @param expected
     *            {@code true} for the exists form (row fires when the substituted column exists),
     *            {@code false} for the not_exists form
     * @return the per-row verdict BitSet over the context's full row range
     */
    public static BitSet existsPerRowBits(EvaluationContext ctx, @Nullable String name,
            boolean expected)
    {
        OperandSubstitutor.ParsedOperand parsed = OperandSubstitutor.parse(name);
        DataTableMeta meta = ctx.getTable().getMetaData();
        // Driver-column presence is a rule-authoring concern: a `${VAR}` whose VAR column is absent
        // from the schema is raised loudly (propagates to a rule ERROR), exactly as before. This
        // pre-check is what lets the per-row catch below stay narrow — a SubstitutionException
        // inside the loop can then only mean a present-but-missing driver VALUE on that row.
        for (String driver : OperandSubstitutor.driverColumns(parsed))
        {
            if (meta.getColumnIndex(driver) < 0)
            {
                throw new OperandSubstitutor.SubstitutionException("driver column `" + driver
                        + "` is not present in dataset `" + meta.getName() + "`");
            }
        }
        int rowCount = ctx.rowCount();
        BitSet result = new BitSet(rowCount);
        for (int r = 0; r < rowCount; r++)
        {
            boolean exists;
            try
            {
                if (parsed instanceof OperandSubstitutor.Scalar scalar)
                {
                    String resolvedName = OperandSubstitutor.substituteScalar(scalar, ctx, r);
                    exists = existsConcreteName(ctx, resolvedName);
                }
                else
                {
                    OperandSubstitutor.Wildcard wild = (OperandSubstitutor.Wildcard) parsed;
                    Pattern pat = OperandSubstitutor.toColumnPattern(wild, ctx, r);
                    exists = anyColumnMatches(ctx, wild.foreignDataset(), pat);
                }
            }
            catch (OperandSubstitutor.SubstitutionException _)
            {
                // A driver VALUE missing/invalid on this row makes the per-row reference
                // unresolvable — there is no column to test. Per the engine-wide "missing input
                // never fires" contract this is no violation for either polarity, so the row is
                // left unset (intentional, parity-whitelisted divergence: a missing driver value
                // is a data state, not a rule error). Driver-column absence is excluded above.
                continue;
            }
            if (exists == expected)
            {
                result.set(r);
            }
        }
        return result;
    }


    private static boolean existsConcreteName(EvaluationContext ctx, String name)
    {
        // Honour the dotted form first (foreign schema lookup).
        Boolean dotted = dottedExists(ctx, name);
        if (dotted != null)
        {
            return dotted;
        }
        // Local schema.
        return ctx.getTable().getMetaData().getOptionalColumn(name) != null;
    }


    private static boolean anyColumnMatches(EvaluationContext ctx, @Nullable String foreignDataset,
            Pattern pat)
    {
        IDataTable foreign;
        if (foreignDataset != null)
        {
            // Fix #358: a dotted wildcard over a split domain scans the union's column set.
            foreign = SplitDomainResolution.resolveTableOrThrow(ctx.getDatasetResolver(),
                    foreignDataset, ctx.getRuleId());
            if (foreign == null)
            {
                return false;
            }
        }
        else
        {
            foreign = ctx.getTable();
        }
        DataTableMeta meta = foreign.getMetaData();
        int colCount = meta.getColumnCount();
        for (int c = 0; c < colCount; c++)
        {
            if (pat.matcher(meta.getColumn(c).getName()).matches())
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Dataset-level fallback used by {@link #existsCommon} when the name carries a placeholder but
     * the engine reaches it via a non-per-row path. Evaluates against row 0 conservatively; per-row
     * evaluation is provided by {@link #existsPerRowBits}.
     */
    private static boolean existsForSubstitutedName(EvaluationContext ctx, @Nullable String name)
    {
        try
        {
            OperandSubstitutor.ParsedOperand parsed = OperandSubstitutor.parse(name);
            if (parsed instanceof OperandSubstitutor.Scalar scalar)
            {
                if (ctx.rowCount() <= 0)
                {
                    return false;
                }
                String resolvedName = OperandSubstitutor.substituteScalar(scalar, ctx, 0);
                return existsConcreteName(ctx, resolvedName);
            }
            OperandSubstitutor.Wildcard wild = (OperandSubstitutor.Wildcard) parsed;
            // For driver-free wildcards we can pattern-match without a row.
            long row = 0L;
            Pattern pat = OperandSubstitutor.toColumnPattern(wild, ctx, row);
            return anyColumnMatches(ctx, wild.foreignDataset(), pat);
        }
        catch (OperandSubstitutor.SubstitutionException _)
        {
            return false;
        }
    }


    /**
     * Fix #18 — entry point for dotted cross-dataset existence checks. Returns
     * {@code true}/{@code false} when {@code name} matches one of the supported dotted forms, or
     * {@code null} when it does not (so the caller can fall through to its existing logic). Shared
     * by every dotted-form consumer ({@link #existsAsVariable}, {@link #existsConcreteName}); they
     * must agree on the dotted-form semantics or Variable Metadata Check rules at Dataset
     * sensitivity (e.g. CDISC-AD0641–0646) silently mis-evaluate.
     */
    static @Nullable Boolean dottedExists(EvaluationContext ctx, @Nullable String name)
    {
        if (name == null || name.indexOf('.') <= 0)
        {
            return null;
        }
        if (DOTTED_DATASET_COLUMN.matcher(name).matches())
        {
            return existsAsDottedDatasetColumn(ctx, name);
        }
        Matcher fm = DOTTED_FILTER.matcher(name);
        if (fm.matches())
        {
            return existsAsDottedFilter(ctx, fm.group(1), fm.group(2), fm.group(3));
        }
        return null;
    }


    /**
     * Plain dotted form {@code <DOMAIN>.<col>}. Resolves {@code <DOMAIN>} via the
     * {@link DatasetResolver} and asks whether {@code <col>} is in its metadata. If the literal
     * column is absent, falls back to scanning the corresponding {@code SUPP<DOMAIN>} dataset for a
     * row with {@code QNAM == <col>} — Fix #39's lazy SUPP-pivot lookup. This brings the
     * dotted-form semantics into alignment with Python's {@code dataset_preprocessor} (which pivots
     * SUPP qualifiers onto the parent before evaluation): {@code AE.AETRTEM exists} returns true
     * whether AETRTEM is a literal AE column or surfaces via {@code SUPPAE.QNAM=AETRTEM}.
     * <p>
     * If neither the foreign dataset nor its SUPP partner has the column, returns {@code false}.
     * </p>
     * <p>
     * Used by CDISC-AD0640–0646 (Variable Metadata Check / Dataset sensitivity).
     * </p>
     */
    private static boolean existsAsDottedDatasetColumn(EvaluationContext ctx, String name)
    {
        int dot = name.indexOf('.');
        String domain = name.substring(0, dot);
        String col = name.substring(dot + 1);
        // Fix #358: exact name first, else the split-domain union, so `var_exists(`LB.LBSEQ`)`
        // is true on a submission that ships LB split — the asymmetry against the scope
        // resolver's split handling is gone (CDISC-AD0898's silent no-op, plan §1).
        IDataTable foreign = SplitDomainResolution.resolveTableOrThrow(ctx.getDatasetResolver(),
                domain, ctx.getRuleId());
        if (foreign != null && foreign.getMetaData().getOptionalColumn(col) != null)
        {
            return true;
        }
        // Fix #39: lazy SUPP-pivot lookup. If the foreign dataset doesn't carry the
        // column literally, the column may be delivered via supplemental qualifiers.
        // Scan SUPP<DOMAIN> for a row with QNAM == <col>. SUPP-only scope (no
        // SQAP/APFA); shipped ADaM rules don't reference those families in this
        // shape. (Routing through SplitDomainResolution keeps the site uniform; the
        // two-character bound means a SUPP<domain> name still resolves exactly — a split
        // SUPPLB never unions here, matching ScopeVariableSource's literal-half behaviour.)
        if (!domain.startsWith("SUPP"))
        {
            String suppName = "SUPP" + domain;
            IDataTable supp = SplitDomainResolution.resolveTableOrThrow(ctx.getDatasetResolver(),
                    suppName, ctx.getRuleId());
            if (supp != null && existsInSuppQnam(supp, col))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Returns {@code true} when the SUPP-- table has at least one row whose {@code QNAM} cell
     * equals {@code col}. Empty / missing QNAM cells don't match.
     * <p>
     * Fix #124 widened the visibility to package-private so {@link ScopeVariableSource} can reuse
     * the <em>same</em> implementation for a qualified {@code Scope.Variables} entry. Sharing it
     * rather than re-implementing is what makes the scope gate and the {@code Check}-side dotted
     * {@code exists} provably agree (including this method's case-sensitive comparison).
     * </p>
     *
     * @param supp
     *            the resolved {@code SUPP--} table
     * @param col
     *            the qualifier name being looked for
     * @return whether the table carries the qualifier
     */
    static boolean existsInSuppQnam(IDataTable supp, String col)
    {
        int qnamIdx = supp.getMetaData().getColumnIndex("QNAM");
        if (qnamIdx < 0)
        {
            return false;
        }
        long rowCount = supp.getRowCount();
        for (long r = 0; r < rowCount; r++)
        {
            IDataValue dv = supp.getDataValue(r, qnamIdx);
            if (isMissing(dv))
            {
                continue;
            }
            String s = dv.getValueAsString();
            if (s != null && !s.isEmpty() && s.equals(col))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Filter form {@code <DOMAIN>.<KEY>=<VALUE>}. Returns {@code true} iff the resolved foreign
     * dataset contains at least one row whose {@code <KEY>} cell value (string-compared,
     * missing-aware) equals {@code <VALUE>}. Returns {@code false} when the dataset is absent, has
     * no {@code <KEY>} column, or no row matches.
     * <p>
     * Used by CDISC-AD0640's {@code SUPPAE.QNAM=AETRTEM} pattern: "is AETRTEM delivered via the
     * SUPP-pivot mechanism?".
     * </p>
     */
    private static boolean existsAsDottedFilter(EvaluationContext ctx, String domain, String key,
            String value)
    {
        // Fix #358: exact name first, else the split-domain union (rows of every member).
        IDataTable foreign = SplitDomainResolution.resolveTableOrThrow(ctx.getDatasetResolver(),
                domain, ctx.getRuleId());
        if (foreign == null)
        {
            return false;
        }
        int colIdx = foreign.getMetaData().getColumnIndex(key);
        if (colIdx < 0)
        {
            return false;
        }
        long rowCount = foreign.getRowCount();
        for (long r = 0; r < rowCount; r++)
        {
            IDataValue dv = foreign.getDataValue(r, colIdx);
            if (isMissing(dv))
            {
                continue;
            }
            if (value.equals(dv.getValueAsString()))
            {
                return true;
            }
        }
        return false;
    }

}
