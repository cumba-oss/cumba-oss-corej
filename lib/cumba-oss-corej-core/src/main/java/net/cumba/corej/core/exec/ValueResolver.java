package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.List;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
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
     * @return the matched column values for the row (never {@code null}); a {@code String} for a
     *         present value, a {@link MissingValue} for a missing one — the identity, never a
     *         rendering, so {@code Primitives.MemberSet} can classify it
     */
    public static List<Object> resolveWildcardValues(OperandSubstitutor.Wildcard wild,
            java.util.regex.@Nullable Pattern cachedPattern, EvaluationContext ctx, long row)
    {
        java.util.regex.Pattern pattern = cachedPattern != null ? cachedPattern
                : OperandSubstitutor.toColumnPattern(wild, ctx, row);
        return collectWildcardValues(wild, pattern, ctx, row);
    }


    private static List<Object> collectWildcardValues(OperandSubstitutor.Wildcard wild,
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
            List<Object> result = new ArrayList<>(matchingColIdx.length);
            for (int c : matchingColIdx)
            {
                String colName = meta.getColumn(c).getName();
                // JOINED arm — the TYPED channel (PLAN-joined-value-accessor §2a; owner 2026-09-21:
                // "I rule it's the same kind, so it's empty string or MIS as well"). This used to
                // read the RAW lookup and drop a null, which was never a semantic — only an
                // accident
                // of a @Nullable accessor. lookupValue answers the column's TYPE DEFAULT for an
                // unmatched row (D72/D72a-1), which IS the ruling.
                //
                // numericExpected=false decides the ABSENT-COLUMN arm only, and the column cannot
                // be
                // absent here: every colName comes from this very foreign dataset's metadata.
                // Passing
                // the rule's expectation would be unreachable-by-construction dressed as config.
                IDataValue dv = lookup.lookupValue(ctx.getTable(), row, colName, false);
                // The member's IDENTITY, never its rendering: MissingValue.toString() renders its
                // display string, so adding the rendering would let a PRESENT text cell holding "."
                // match a missing member — the JKM R5 collision class (Primitives.MemberSet).
                result.add(dv.getValue() instanceof MissingValue mv ? mv : dv.getValueAsString());
            }
            return result;
        }
        // Local-table wildcard. Same pattern: cache by (table, Pattern).
        // ⛔⛔ The LOCAL arm is DELIBERATELY UNCHANGED, and the divergence is recorded rather than
        // silently harmonised. It drops a blank because that is its OWN owner ruling (2026-09-18,
        // quoted below): a blank char cell is MissingValue.MIS and "contributes nothing, exactly
        // like
        // a blank numeric cell". §2a ruled the UNMATCHED-JOIN case, which has no counterpart here —
        // a local column is never "unmatched".
        // ⚠ The two arms therefore still disagree on a MATCHED-but-blank cell: the joined arm
        // contributes its value, the local arm drops it. That predates this change — do not "align"
        // it without a ruling.
        net.cumba.datatable.IDataTable localTable = ctx.getTable();
        int[] matchingColIdx = WildcardForeignColumnCache.matchingColumns(localTable, pattern);
        List<Object> result = new ArrayList<>(matchingColIdx.length);
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
