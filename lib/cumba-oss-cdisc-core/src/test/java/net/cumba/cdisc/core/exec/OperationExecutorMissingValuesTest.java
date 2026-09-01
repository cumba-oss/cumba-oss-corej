package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * EC-51 Half B (Fix #145) — the authorable {@code missing_values:} disposition.
 *
 * <p>
 * Half A made "a missing cell is not a candidate" one shared predicate; Half B lets a rule declare
 * that a missing cell instead makes the extreme <b>undeterminable</b>, so a negative consuming
 * check reports rather than silently computing the second-best date. The default is {@code "skip"}
 * — today's behaviour — so every assertion below is paired: the same fixture is run once undeclared
 * and once declared, and the two must differ only where the disposition says so.
 * </p>
 */
class OperationExecutorMissingValuesTest
{

    private static final String NUL = "\0";

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    private static Operation makeOp(String id, String operator)
    {
        Operation op = new Operation();
        op.setId(id);
        op.setOperator(operator);
        return op;
    }

    // -----------------------------------------------------------------------
    // min_date / max_date — ungrouped
    // -----------------------------------------------------------------------


    @Test
    void ungrouped_defaultSkipsTheBlank_indeterminateYieldsNoValue()
    {
        IDataTable table = MockTable.of().col("DTC", "", "2024-06-01", "2024-07-01").build();

        Operation skip = makeOp("$min", "min_date");
        skip.setName("DTC");
        assertEquals("2024-06-01",
                OperationExecutor.execute(List.of(skip), table, NO_RESOLVER).get("$min"),
                "with no declaration the blank is skipped, exactly as before Half B");

        Operation explicitSkip = makeOp("$min", "min_date");
        explicitSkip.setName("DTC");
        explicitSkip.setMissingValues(Operation.MISSING_VALUES_SKIP);
        assertEquals("2024-06-01",
                OperationExecutor.execute(List.of(explicitSkip), table, NO_RESOLVER).get("$min"),
                "`skip` declared explicitly must equal the undeclared default");

        Operation indeterminate = makeOp("$min", "min_date");
        indeterminate.setName("DTC");
        indeterminate.setMissingValues(Operation.MISSING_VALUES_INDETERMINATE);
        assertNull(
                OperationExecutor.execute(List.of(indeterminate), table, NO_RESOLVER).get("$min"),
                "one missing candidate makes the whole ungrouped extreme undeterminable");
    }


    /**
     * The max side is worth its own case: a blank never <em>won</em> a max (it sorts below every
     * digit), so the only observable effect there is that the result disappears.
     */
    @Test
    void ungrouped_maxAlsoDisappears_notMerelyReordered()
    {
        IDataTable table = MockTable.of().col("DTC", "2024-06-01", "", "2024-07-01").build();

        Operation op = makeOp("$max", "max_date");
        op.setName("DTC");
        assertEquals("2024-07-01",
                OperationExecutor.execute(List.of(op), table, NO_RESOLVER).get("$max"));

        op.setMissingValues(Operation.MISSING_VALUES_INDETERMINATE);
        assertNull(OperationExecutor.execute(List.of(op), table, NO_RESOLVER).get("$max"));
    }


    /**
     * "missing" is "empty", and Half A settled that a whitespace-only cell is empty
     * ({@code strip().isEmpty()}, OQ2). Half B must inherit that boundary rather than re-draw it,
     * or {@code " "} would be a <em>present</em> value and win the group instead of killing it.
     */
    @Test
    void whitespaceOnlyCellCountsAsMissing()
    {
        IDataTable table = MockTable.of().col("DTC", " ", "2024-06-01").build();

        Operation op = makeOp("$min", "min_date");
        op.setName("DTC");
        op.setMissingValues(Operation.MISSING_VALUES_INDETERMINATE);

        assertNull(OperationExecutor.execute(List.of(op), table, NO_RESOLVER).get("$min"),
                "a whitespace-only cell is missing, so it makes the extreme undeterminable");
    }


    /**
     * The all-missing control: both dispositions already produce no value there, so the declaration
     * must not be credited with a change it did not make.
     */
    @Test
    void allMissing_isNoValueUnderBothDispositions()
    {
        IDataTable table = MockTable.of().col("DTC", "", " ").build();

        Operation skip = makeOp("$min", "min_date");
        skip.setName("DTC");
        assertNull(OperationExecutor.execute(List.of(skip), table, NO_RESOLVER).get("$min"));

        skip.setMissingValues(Operation.MISSING_VALUES_INDETERMINATE);
        assertNull(OperationExecutor.execute(List.of(skip), table, NO_RESOLVER).get("$min"));
    }


    /**
     * A row the operation's own {@code filter:} excludes is not a candidate at all, so it can never
     * be "a missing candidate" — otherwise every filtered rule would go undeterminable the moment
     * one excluded row happened to be blank.
     */
    @Test
    void filteredOutRowIsNotAMissingCandidate()
    {
        IDataTable table = MockTable.of().col("DSDECOD", "RANDOMIZED", "OTHER")
                .col("DTC", "2024-06-01", "").build();

        Operation op = makeOp("$min", "min_date");
        op.setName("DTC");
        op.setFilter(Map.of("DSDECOD", "RANDOMIZED"));
        op.setMissingValues(Operation.MISSING_VALUES_INDETERMINATE);

        assertEquals("2024-06-01",
                OperationExecutor.execute(List.of(op), table, NO_RESOLVER).get("$min"),
                "the blank row is filtered out, so the surviving candidate still determines it");
    }

    // -----------------------------------------------------------------------
    // min_date / max_date — grouped
    // -----------------------------------------------------------------------


    /**
     * The shape all 27 shipped {@code min_date}/{@code max_date} rules take. Only the group holding
     * the missing cell loses its key; its siblings are untouched — a per-group verdict, not a
     * per-operation one.
     */
    @Test
    void grouped_onlyTheGroupWithAMissingCandidateLosesItsKey()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S01", "S02", "S02")
                .col("EXSTDTC", "", "2024-03-01", "2024-05-01", "2024-06-01").build();

        Operation op = makeOp("$min_ex", "min_date");
        op.setName("EXSTDTC");
        op.setGroup(List.of("USUBJID"));

        GroupedResult skipped = assertInstanceOf(GroupedResult.class,
                OperationExecutor.execute(List.of(op), table, NO_RESOLVER).get("$min_ex"));
        assertEquals("2024-03-01", skipped.results().get("S01"),
                "the default still lets the populated sibling win");
        assertEquals("2024-05-01", skipped.results().get("S02"));

        op.setMissingValues(Operation.MISSING_VALUES_INDETERMINATE);
        GroupedResult declared = assertInstanceOf(GroupedResult.class,
                OperationExecutor.execute(List.of(op), table, NO_RESOLVER).get("$min_ex"));
        assertFalse(declared.results().containsKey("S01"),
                "S01 holds a missing candidate, so its extreme is undeterminable — no key");
        assertEquals("2024-05-01", declared.results().get("S02"),
                "S02 is fully populated and must be unaffected");
    }


    /**
     * The disposition survives {@code expandGroupRefs}. A {@code $}-ref in the {@code group} list
     * rebuilds the operation field by field, and that copy routine is where {@code offset} was lost
     * once already (Fix #99) — a loss here would silently revert the declaration to {@code skip} on
     * exactly the operations that carry a {@code $}-ref group.
     */
    @Test
    void grouped_dispositionSurvivesGroupRefExpansion()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S01")
                .col("EXSTDTC", "", "2024-03-01").build();

        Operation op = makeOp("$min_ex", "min_date");
        op.setName("EXSTDTC");
        op.setGroup(List.of("$grp"));
        op.setMissingValues(Operation.MISSING_VALUES_INDETERMINATE);

        Map<String, Object> priors = new HashMap<>();
        priors.put("$grp", List.of("USUBJID"));
        GroupedResult grouped = assertInstanceOf(GroupedResult.class,
                OperationExecutor.executeOne(op, table, NO_RESOLVER, null, priors));

        assertTrue(grouped.results().isEmpty(),
                "the declaration must survive the field-by-field rebuild in expandGroupRefs");
    }


    /**
     * The disposition survives {@code resolvePrefixes} — the second hand-written copy routine, and
     * the one that actually lost {@code offset} in Fix #99. A {@code --}-prefixed {@code min_date}
     * is the common corpus shape, so a loss here would be live, not defensive.
     */
    @Test
    void dispositionSurvivesPrefixResolution()
    {
        Operation op = makeOp("$min", "min_date");
        op.setName("--STDTC");
        op.setMissingValues(Operation.MISSING_VALUES_INDETERMINATE);

        Operation resolved = OperationExecutor.resolvePrefixes(op, "EX");

        assertEquals("EXSTDTC", resolved.getName(), "sanity: the wildcard really did resolve");
        assertEquals(Operation.MISSING_VALUES_INDETERMINATE, resolved.getMissingValues());
    }

    // -----------------------------------------------------------------------
    // date_diff_days Mode 2 — the grouped subtrahend (§5.3)
    // -----------------------------------------------------------------------


    /**
     * Mode 2's subtrahend was <em>unfilterable by construction</em> before this change:
     * {@code buildGroupedExtremeDate} receives no {@link Operation}, so no per-rule declaration
     * could reach it. Threading the disposition through its signature is what puts the 7
     * {@code date_diff_days} rules within reach of the parameter at all.
     */
    @Test
    void dateDiffDays_mode2_missingReferenceMakesTheGroupUndeterminable()
    {
        IDataTable target = MockTable.of().col("USUBJID", "S1", "S2")
                .col("MYDTC", "2020-01-10", "2020-02-05").name("XX").build();
        IDataTable sj = MockTable.of().col("USUBJID", "S1", "S1", "S2")
                .col("SJSTDTC", "", "2020-01-01", "2020-02-01").name("SJ").build();
        DatasetResolver resolver = name -> "SJ".equals(name) ? sj : null;

        Operation op = makeOp("$d", "date_diff_days");
        op.setName("MYDTC");
        op.setDomain("SJ");
        op.setReference("SJSTDTC");
        op.setGroup(List.of("USUBJID"));

        GroupedResult skipped = (GroupedResult) OperationExecutor.executeOne(op, target, resolver,
                null, new HashMap<>());
        assertEquals(9L, skipped.results().get("2020-01-10" + NUL + "S1"),
                "the default skips S1's blank reference and uses 2020-01-01");
        assertEquals(4L, skipped.results().get("2020-02-05" + NUL + "S2"));

        op.setMissingValues(Operation.MISSING_VALUES_INDETERMINATE);
        GroupedResult declared = (GroupedResult) OperationExecutor.executeOne(op, target, resolver,
                null, new HashMap<>());
        assertFalse(declared.results().containsKey("2020-01-10" + NUL + "S1"),
                "S1's reference group holds a missing candidate ⇒ no subtrahend ⇒ no day count");
        assertEquals(4L, declared.results().get("2020-02-05" + NUL + "S2"),
                "S2's reference group is fully populated and must be unaffected");
    }


    /**
     * Mode 1 reads the subtrahend from the same record, so a missing reference already yields no
     * value for that row and the disposition has nothing to add. Pinned so a future reader does not
     * "fix" Mode 1 into rejecting the whole operation.
     */
    @Test
    void dateDiffDays_mode1_isUnaffectedByTheDeclaration()
    {
        IDataTable table = MockTable.of().col("REF", "2020-01-01", "")
                .col("MYDTC", "2020-01-10", "2020-01-20").name("XX").build();

        Operation op = makeOp("$d", "date_diff_days");
        op.setName("MYDTC");
        op.setReference("REF");
        op.setMissingValues(Operation.MISSING_VALUES_INDETERMINATE);

        GroupedResult gr = (GroupedResult) OperationExecutor.executeOne(op, table, NO_RESOLVER,
                null, new HashMap<>());
        assertEquals(9L, gr.results().get("2020-01-10" + NUL + "2020-01-01"),
                "the populated row still gets its day count");
    }

    // -----------------------------------------------------------------------
    // Operators outside the allowlist keep the generic behaviour
    // -----------------------------------------------------------------------


    /**
     * The generic {@code max} string fallback is <b>not</b> in the allowlist (declaring
     * {@code missing_values} on it is a load error), so its accumulator is constructed with the
     * disposition permanently off. This pins that a stray declaration reaching the executor by some
     * other route — a hand-built {@link Operation} in a tool or test — cannot change the generic
     * operator's semantics, which also serve Char <em>category</em> columns.
     */
    @Test
    void genericMax_ignoresTheDeclaration()
    {
        IDataTable table = MockTable.of().col("ANRIND", "", "HIGH", "LOW").build();

        Operation op = makeOp("$max", "max");
        op.setName("ANRIND");
        op.setMissingValues(Operation.MISSING_VALUES_INDETERMINATE);

        assertEquals("LOW", OperationExecutor.execute(List.of(op), table, NO_RESOLVER).get("$max"),
                "the generic string extreme stays lexicographic and blank-skipping");
    }

}
