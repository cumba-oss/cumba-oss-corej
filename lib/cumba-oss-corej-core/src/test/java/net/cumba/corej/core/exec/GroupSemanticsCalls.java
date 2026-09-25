package net.cumba.corej.core.exec;

import java.util.BitSet;
import java.util.List;
import java.util.function.IntUnaryOperator;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import org.jspecify.annotations.Nullable;

/**
 * Test shorthand for {@link GroupSemantics}: the defaulting overloads the tests call, forwarding to
 * the production full forms with the defaults the removed overloads used to supply — the shipped
 * {@link GroupKeyPolicy} of each operator, {@code includeEmpty = false},
 * {@link GroupSemantics#identityCorresponds}.
 *
 * <p>
 * ⭐ {@code PLAN-retire-dead-multi-match-lookup} U1 (A10–A16) removed them from {@code src/main}:
 * {@code ExprCompiler} calls only the full forms (explicit policy, explicit emptiness switch,
 * explicit relation). ⛔ Pure forwarders only — see {@link RuleRunnerCalls}.
 * </p>
 */
public final class GroupSemanticsCalls
{

    private GroupSemanticsCalls()
    {
    }


    /** {@link GroupSemantics#group} under {@link GroupKeyPolicy#DROP_MISSING_KEYS}. */
    public static List<int[]> partition(IDataTable table, List<String> withinCols)
    {
        return GroupSemantics.group(table, withinCols, GroupKeyPolicy.DROP_MISSING_KEYS);
    }


    public static List<int[]> partitionCoalesced(IDataTable table, List<List<String>> components)
    {
        return partitionCoalesced(table, components, GroupKeyPolicy.DROP_MISSING_KEYS);
    }


    public static List<int[]> partitionCoalesced(IDataTable table, List<List<String>> components,
            GroupKeyPolicy policy)
    {
        return GroupSemantics.partitionCoalesced(table, components, policy);
    }


    public static BitSet hasMultipleValuesForRows(IDataTableColumn nameCol,
            IDataTableColumn valueCol, IntUnaryOperator rowAt, int sequenceSize)
    {
        return hasMultipleValuesForRows(nameCol, valueCol, rowAt, sequenceSize, false);
    }


    public static BitSet hasMultipleValuesForRows(IDataTableColumn nameCol,
            IDataTableColumn valueCol, IntUnaryOperator rowAt, int sequenceSize,
            boolean includeEmpty)
    {
        return GroupSemantics.hasMultipleValuesForRows(nameCol, valueCol, rowAt, sequenceSize,
                includeEmpty);
    }


    public static void flagNoNextCorrespondingRecord(IDataTableColumn nameCol,
            IDataTableColumn valueCol, IDataTableColumn orderCol, int[] rows, BitSet result)
    {
        flagNoNextCorrespondingRecord(nameCol, valueCol, orderCol, rows, result,
                GroupSemantics::identityCorresponds);
    }


    public static void flagNoNextCorrespondingRecord(IDataTableColumn nameCol,
            IDataTableColumn valueCol, IDataTableColumn orderCol, int[] rows, BitSet result,
            GroupSemantics.NeighbourRelation relation)
    {
        GroupSemantics.flagNoNextCorrespondingRecord(nameCol, valueCol, orderCol, rows, result,
                relation);
    }


    public static BitSet targetIsNotSortedByViolations(IDataTable table, int rowCount,
            @Nullable String targetCol, List<String> sortVars, @Nullable String withinColName)
    {
        return targetIsNotSortedByViolations(table, rowCount, targetCol, sortVars, withinColName,
                GroupKeyPolicy.FOLD_BLANK_KEYS);
    }


    public static BitSet targetIsNotSortedByViolations(IDataTable table, int rowCount,
            @Nullable String targetCol, List<String> sortVars, @Nullable String withinColName,
            GroupKeyPolicy policy)
    {
        return GroupSemantics.targetIsNotSortedByViolations(table, rowCount, targetCol, sortVars,
                withinColName, policy);
    }


    public static BitSet inconsistentAcrossDatasetViolations(IDataTable table,
            @Nullable String nameColName, List<String> groupColNames, int rowCount)
    {
        return inconsistentAcrossDatasetViolations(table, nameColName, groupColNames, rowCount,
                false);
    }


    public static BitSet inconsistentAcrossDatasetViolations(IDataTable table,
            @Nullable String nameColName, List<String> groupColNames, int rowCount,
            boolean includeEmpty)
    {
        return inconsistentAcrossDatasetViolations(table, nameColName, groupColNames, rowCount,
                includeEmpty, GroupKeyPolicy.DROP_MISSING_KEYS);
    }


    public static BitSet inconsistentAcrossDatasetViolations(IDataTable table,
            @Nullable String nameColName, List<String> groupColNames, int rowCount,
            boolean includeEmpty, GroupKeyPolicy policy)
    {
        return GroupSemantics.inconsistentAcrossDatasetViolations(table, nameColName, groupColNames,
                rowCount, includeEmpty, policy);
    }
}
