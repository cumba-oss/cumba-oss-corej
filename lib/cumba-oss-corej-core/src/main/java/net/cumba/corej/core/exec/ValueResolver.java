package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.List;
import net.cumba.datatable.DataTableMeta;
import org.jspecify.annotations.Nullable;

/**
 * Wildcard-operand value collection for the native engine.
 *
 * <p>
 * <strong>The class name is historical.</strong> General value-position resolution moved into
 * {@code ExprCompiler} when the legacy per-row operator engine was removed; what remains here is
 * the per-row wildcard column enumeration that the compiled plan still delegates, because it has to
 * enumerate matching columns against the row's driver values. A rename is deliberately deferred.
 * </p>
 */
public final class ValueResolver
{

    private ValueResolver()
    {
    }


    /**
     * Fix #37 / Epic B1 — entry point for the native evaluator's {@code ${*}} wildcard membership
     * plan. Resolves a {@link OperandSubstitutor.Wildcard} operand at the given row to the list of
     * foreign / local column values whose names match the (driver-substituted) anchored pattern.
     *
     * @param wild
     *            the parsed wildcard operand
     * @param cachedPattern
     *            a pre-compiled column-name {@link java.util.regex.Pattern} (driver-free
     *            wildcards), or {@code null} to compile it from the row's drivers
     * @param ctx
     *            the evaluation context
     * @param row
     *            the 0-based row index
     * @return the matched column values for the row (never {@code null})
     */
    public static List<String> resolveWildcardValues(OperandSubstitutor.Wildcard wild,
            java.util.regex.@Nullable Pattern cachedPattern, EvaluationContext ctx, long row)
    {
        java.util.regex.Pattern pattern = cachedPattern != null ? cachedPattern
                : OperandSubstitutor.toColumnPattern(wild, ctx, row);
        return collectWildcardValues(wild, pattern, ctx, row);
    }


    private static List<String> collectWildcardValues(OperandSubstitutor.Wildcard wild,
            java.util.regex.Pattern pattern, EvaluationContext ctx, long row)
    {
        // Foreign-dataset wildcard — enumerate columns of the foreign dataset and pull values
        // through the JoinLookup for the row's join keys.
        if (wild.foreignDataset() != null)
        {
            String foreign = wild.foreignDataset();
            JoinLookup lookup = ctx.getJoinedDatasets().get(foreign);
            if (lookup == null)
            {
                // ⚠ This wording is matched from ANOTHER repository — the sibling rules
                // repository's NativeCorpusFullCoverageTest allow-lists the three shipped
                // CDISC-AD0720 rules by a String.contains on "requires a JoinLookup for foreign
                // dataset" (KNOWN_UNDISPATCHABLE). Reword it and that gate reds over there, naming
                // nothing here. EngineErrorMessageContractTest pins the fragment so you find out
                // in this build instead; change all three in one wave.
                throw new OperandSubstitutor.SubstitutionException(
                        "wildcard operand requires a JoinLookup for foreign dataset `" + foreign
                                + "` but none was provided (Match_Datasets missing?)");
            }
            // Fix #358: exact name first, else the split-domain union — without this, fixing the
            // join alone would merely move the failure from the lookup check above to this
            // resolve (same exception, new line, still no values).
            net.cumba.datatable.IDataTable foreignTable = SplitDomainResolution
                    .resolveTableOrThrow(ctx.getDatasetResolver(), foreign, ctx.getRuleId());
            if (foreignTable == null)
            {
                throw new OperandSubstitutor.SubstitutionException(
                        "wildcard operand could not resolve foreign dataset `" + foreign + "`");
            }
            // Phase 7: cache the matching column-name lookup once per (foreignTable, Pattern).
            // Without this we'd regex-match every foreign-dataset column on every row.
            int[] matchingColIdx = WildcardForeignColumnCache.matchingColumns(foreignTable,
                    pattern);
            DataTableMeta meta = foreignTable.getMetaData();
            List<String> result = new ArrayList<>(matchingColIdx.length);
            for (int c : matchingColIdx)
            {
                String colName = meta.getColumn(c).getName();
                String v = lookup.lookup(ctx.getTable(), row, colName);
                if (v != null)
                {
                    result.add(v);
                }
            }
            return result;
        }
        // Local-table wildcard. Same pattern: cache by (table, Pattern).
        net.cumba.datatable.IDataTable localTable = ctx.getTable();
        int[] matchingColIdx = WildcardForeignColumnCache.matchingColumns(localTable, pattern);
        List<String> result = new ArrayList<>(matchingColIdx.length);
        for (int c : matchingColIdx)
        {
            // A blank resolves per ScalarSemantics.resolvedString, which is type-INDEPENDENT.
            // ⭐ CORRECTED (owner ruling, 2026-09-18): this comment used to say a blank character
            // cell "still contributes '' … whether the file wrote an empty string or an explicit
            // null". The explicit-null half is now false — a null char cell is MissingValue.MIS
            // (the datatable repository's d4edd59) and contributes nothing, exactly like a blank
            // numeric cell. A genuinely STORED "" is still a present value and still contributes
            // "".
            String v = ScalarSemantics.resolvedString(localTable, c, row);
            if (v != null)
            {
                result.add(v);
            }
        }
        return result;
    }

}
