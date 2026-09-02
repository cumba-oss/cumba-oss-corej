package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.model.EmptyResult;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.OperationType;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * EC-45 §1.4 / OQ4 — the classification table <b>is</b> the contract, and this is the gate on it.
 *
 * <p>
 * Two halves. The first asserts the declaration itself: every {@link OperationType} carries an
 * {@link EmptyResult}, and each classification's value really is the value its codomain names, so
 * the table cannot rot into a set of labels that no longer mean anything. (Omitting the declaration
 * entirely is already impossible — {@code OperationType}'s generated constructor requires it — so
 * this half guards the remaining failure mode: a declaration that is present but wrong.)
 * </p>
 *
 * <p>
 * The second half asserts that the operators actually <em>publish</em> what they declare, by
 * running each grouped evaluator against a table where the probed key is absent and reading
 * {@link GroupedResult#defaultForMissingKey()}. That is what stops the pre-EC-45 drift returning:
 * the defect was never a missing label, it was five construction sites hard-coding a default that
 * disagreed with the operator's codomain.
 * </p>
 */
class OperationEmptyResultClassificationTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    private static Operation op(String id, String operator)
    {
        Operation o = new Operation();
        o.setId(id);
        o.setOperator(operator);
        return o;
    }


    private static GroupedResult run(Operation o, IDataTable table)
    {
        Object v = OperationExecutor.executeOne(o, table, NO_RESOLVER, null, new HashMap<>());
        return assertInstanceOf(GroupedResult.class, v,
                "operation " + o.getOperator() + " should yield a GroupedResult");
    }

    // ------------------------------------------------------------------
    // Half 1 — the declaration
    // ------------------------------------------------------------------


    /**
     * Every operator carries a classification, and every classification is <em>reachable</em> —
     * i.e. the table is a live decision per operator, not four constants and a default nobody
     * revisits.
     *
     * <p>
     * The "carries one" half is already guaranteed by {@code OperationType}'s generated constructor
     * and is asserted only so a future refactor that makes the field nullable trips here rather
     * than silently. The half that pins something is the reachability count: 54 operators spread
     * over all five classifications, so a mechanical "declare everything MISSING" pass fails.
     * </p>
     */
    @Test
    void everyOperationTypeDeclaresAnEmptyResultAndEveryClassificationIsUsed()
    {
        List<String> missing = new ArrayList<>();
        EnumMap<EmptyResult, Integer> used = new EnumMap<>(EmptyResult.class);
        for (OperationType t : OperationType.values())
        {
            EmptyResult declared = t.getEmptyResult();
            if (declared == null)
            {
                missing.add(t.name());
            }
            else
            {
                used.merge(declared, 1, Integer::sum);
            }
        }
        assertEquals(List.of(), missing, "OperationType(s) with no EmptyResult classification");
        assertEquals(EmptyResult.values().length, used.size(),
                "every classification must be reached by at least one operator; got " + used);
        assertEquals(OperationType.values().length,
                used.values().stream().mapToInt(Integer::intValue).sum());
    }


    /** Each classification's value is the value its codomain names — the table cannot drift. */
    @Test
    void eachClassificationCarriesTheValueItsCodomainNames()
    {
        Map<EmptyResult, Object> expected = new EnumMap<>(EmptyResult.class);
        expected.put(EmptyResult.COUNT, 0L);
        expected.put(EmptyResult.SET, List.of());
        expected.put(EmptyResult.PREDICATE, false);
        expected.put(EmptyResult.EMPTY_TEXT, "");
        assertEquals(expected.keySet().size() + 1, EmptyResult.values().length,
                "a new EmptyResult constant needs a value assertion here");
        expected.forEach((cls, value) -> assertEquals(value, cls.value(), cls.name()));
        assertNull(EmptyResult.MISSING.value(), "MISSING is the absence of a value");
    }


    /** An unparseable {@code operator} has no declaration and reads as "no answer". */
    @Test
    void unknownOperationTypeFallsBackToMissing()
    {
        assertNull(OperationType.emptyValueOf(null));
        assertSame(EmptyResult.COUNT, OperationType.RECORD_COUNT.getEmptyResult());
        assertEquals(0L, OperationType.emptyValueOf(OperationType.RECORD_COUNT));
    }

    // ------------------------------------------------------------------
    // Half 2 — the operators publish what they declare
    // ------------------------------------------------------------------


    /**
     * {@code record_count} keeps its {@code 0L}: no record for the group really is zero records, so
     * {@code $count <= 1} must fire on an unmatched subject rather than be silently skipped.
     */
    @Test
    void recordCountGroupedDeclaresZero()
    {
        IDataTable t = MockTable.of().name("AE").col("USUBJID", "S1", "S2").build();
        Operation o = op("$cnt", "record_count");
        o.setGroup(List.of("USUBJID"));
        assertEquals(0L, run(o, t).defaultForMissingKey());
    }


    /**
     * EC-45 §1.3(1) — grouped {@code distinct} declares the empty set, ending the split where the
     * <em>same</em> operator answered {@code List.of()} ungrouped and {@code null} grouped.
     */
    @Test
    void distinctGroupedDeclaresTheEmptySet()
    {
        IDataTable t = MockTable.of().name("SV").col("USUBJID", "S1", "S2")
                .col("VISITNUM", "1", "2").build();
        Operation o = op("$d", "distinct");
        o.setName("VISITNUM");
        o.setGroup(List.of("USUBJID"));
        GroupedResult gr = run(o, t);
        assertEquals(List.of(), gr.defaultForMissingKey());
        // and the ungrouped sibling has always said the same thing for an absent column
        Operation ungrouped = op("$u", "distinct");
        ungrouped.setName("NOPE");
        assertEquals(List.of(),
                OperationExecutor.executeOne(ungrouped, t, NO_RESOLVER, null, new HashMap<>()));
    }


    /**
     * EC-45 §1.3(2) — {@code has_mixed_emptiness_within_group} is a boolean predicate, so an
     * unmatched group is {@code false} ("evaluated, did not hold"), never "unknown".
     */
    @Test
    void hasMixedEmptinessDeclaresFalse()
    {
        IDataTable t = MockTable.of().name("ADLB").col("PARAMCD", "A", "A").col("BASETYPE", "X", "")
                .build();
        Operation o = op("$m", "has_mixed_emptiness_within_group");
        o.setName("BASETYPE");
        o.setGroup(List.of("PARAMCD"));
        assertEquals(false, run(o, t).defaultForMissingKey());
    }


    /** {@code is_last_in_group} is likewise a predicate. */
    @Test
    void isLastInGroupDeclaresFalse()
    {
        IDataTable t = MockTable.of().name("SE").col("USUBJID", "S1", "S1")
                .col("SESTDTC", "2020-01-01", "2020-02-01").build();
        Operation o = op("$last", "is_last_in_group");
        o.setGroup(List.of("USUBJID"));
        o.setOrdering("SESTDTC");
        assertEquals(false, run(o, t).defaultForMissingKey());
    }


    /**
     * EC-45 §1.2 / OQ1 — {@code referenced_domain_class} is a closed-world <em>scalar</em> lookup,
     * so its empty value is the empty string, not "no value": an unknown {@code RDOMAIN} means the
     * library holds no class for it, and {@code $class not_equal_to "EVENTS"} fires vacuously.
     */
    @Test
    void referencedDomainClassDeclaresTheEmptyString()
    {
        IDataTable t = MockTable.of().name("SUPPAE").col("RDOMAIN", "AE").build();
        Operation o = op("$c", "referenced_domain_class");
        o.setName("RDOMAIN");
        MetadataProvider p = mock(MetadataProvider.class);
        lenient().when(p.getDatasetClass("AE", "AE")).thenReturn("EVENTS");
        Object v = OperationExecutor.executeOne(o, t, NO_RESOLVER, p, new HashMap<>());
        GroupedResult gr = assertInstanceOf(GroupedResult.class, v);
        assertEquals("EVENTS", gr.results().get("AE"));
        assertEquals("", gr.defaultForMissingKey());
    }


    /**
     * The five aggregates the table classifies as {@link EmptyResult#MISSING} keep declaring "no
     * value": the calculation was not possible, the comparison folds it to {@code ""} and the check
     * fires on its own terms. Fix #142 (EC-46) widened <em>when</em> a group publishes no key — an
     * indeterminate extreme now joins the all-blank case — but not <em>what</em> the absent key
     * resolves to, which is what this pins.
     */
    @Test
    void extremaAndDerivedValuesDeclareMissing()
    {
        IDataTable t = MockTable.of().name("EX").col("USUBJID", "S1", "S2")
                .col("EXSTDTC", "2020-01-01", "2020-02-01").col("VISITNUM", "1", "2")
                .col("TR01EDT", "2020-03-01", "2020-03-02").build();

        Operation maxDate = op("$max", "max_date");
        maxDate.setName("EXSTDTC");
        maxDate.setGroup(List.of("USUBJID"));
        assertNull(run(maxDate, t).defaultForMissingKey());

        Operation minDate = op("$min", "min_date");
        minDate.setName("EXSTDTC");
        minDate.setGroup(List.of("USUBJID"));
        assertNull(run(minDate, t).defaultForMissingKey());

        Operation max = op("$mx", "max");
        max.setName("VISITNUM");
        max.setGroup(List.of("USUBJID"));
        assertNull(run(max, t).defaultForMissingKey());

        Operation rowMax = op("$rm", "row_max");
        rowMax.setNamePattern("^TR\\d+EDT$");
        assertNull(run(rowMax, t).defaultForMissingKey());

        Operation diff = op("$dd", "date_diff_days");
        diff.setName("EXSTDTC");
        diff.setReference("EXSTDTC");
        assertNull(run(diff, t).defaultForMissingKey());
    }


    /**
     * The whole point of the declaration: an absent group key resolves to it on the scalar path, so
     * a comparison operand and the rendered {@code $var} read the same thing. {@code record_count}
     * answers 0 for a subject the group map never saw; a date extreme answers nothing.
     */
    @Test
    void anAbsentKeyResolvesToTheDeclaredValueOnTheScalarPath()
    {
        IDataTable producer = MockTable.of().name("AE").col("USUBJID", "S1").build();
        Operation cnt = op("$cnt", "record_count");
        cnt.setGroup(List.of("USUBJID"));
        GroupedResult counts = run(cnt, producer);

        // A row whose subject the producer never saw.
        IDataTable consumer = MockTable.of().name("AE").col("USUBJID", "S9").build();
        EvaluationContext ctx = EvaluationContext.builder().table(consumer).build();
        assertNull(counts.getForRow(ctx, 0), "no entry for S9");
        assertEquals(0L, counts.getForRowOrDefault(ctx, 0), "but the declared answer is 0 records");

        Operation maxDate = op("$max", "max_date");
        maxDate.setName("AESTDTC");
        maxDate.setGroup(List.of("USUBJID"));
        GroupedResult extremes = run(maxDate, MockTable.of().name("AE").col("USUBJID", "S1")
                .col("AESTDTC", "2020-01-01").build());
        assertNull(extremes.getForRowOrDefault(ctx, 0), "an extremum over nothing has no value");
    }


    /**
     * {@code evalSuppQnamJoin} is the one deliberate deviation, and the model the classification
     * was derived from: it conditions the default on what is <em>knowable</em> — SUPP present but
     * this record carries no qualifier is a real {@code false} for the PRESENCE operator and no
     * basis at all for the VALUE one. A per-call refinement of the static declaration, not a
     * contradiction of it.
     *
     * <p>
     * ⚠ Q17-a moved the third case. An entirely absent SUPP dataset used to short-circuit to an
     * unclassified {@code null} before dispatch; it now answers {@code supp_qnam_present}'s own
     * declared {@code PREDICATE} — {@code false} — as a plain scalar (there is no dataset to build
     * a {@link GroupedResult} over). That makes "SUPPPC is not in the study" agree with "SUPPPC is
     * present but holds no such QNAM", which were two different verdicts for the same study fact.
     * The knowability refinement above is untouched: it still distinguishes the presence operator
     * from the value operator whenever the dataset IS there.
     * </p>
     */
    @Test
    void suppQnamJoinRefinesItsDefaultByKnowability()
    {
        IDataTable parent = MockTable.of().name("PC").col("USUBJID", "S1").col("PCSEQ", "1")
                .build();
        // SUPP is present but carries no QNAM/IDVAR structure: the qualifier is genuinely absent
        // for every parent record, which is a real `false` for the PRESENCE operator and no basis
        // at all for the VALUE one. One join, two policies, chosen by what is knowable.
        IDataTable supp = MockTable.of().name("SUPPPC").col("USUBJID", "S1").build();
        DatasetResolver resolver = name -> "SUPPPC".equals(name) ? supp : null;

        Operation present = op("$q", "supp_qnam_present");
        present.setDomain("SUPPPC");
        present.setKeyValue("PCCALCN");
        Object presentResult = OperationExecutor.executeOne(present, parent, resolver, null,
                new HashMap<>());
        assertEquals(false,
                assertInstanceOf(GroupedResult.class, presentResult).defaultForMissingKey(),
                "SUPP present ⇒ 'this record has no qualifier' is a real answer");

        Operation value = op("$v", "supp_qnam_value");
        value.setDomain("SUPPPC");
        value.setKeyValue("PCCALCN");
        Object valueResult = OperationExecutor.executeOne(value, parent, resolver, null,
                new HashMap<>());
        assertNull(assertInstanceOf(GroupedResult.class, valueResult).defaultForMissingKey(),
                "there is no qualifier VALUE to report");

        // Q17-a: an entirely absent SUPP dataset never reaches dispatch, so there is no
        // GroupedResult to refine — the operator publishes its static declaration instead, and
        // supp_qnam_present declares PREDICATE. `false` is the same answer the SUPP-present-but-
        // empty case above produces, which is the point: absence of the dataset and absence of the
        // qualifier are the same study fact and must not report differently.
        assertEquals(false,
                OperationExecutor.executeOne(present, parent, NO_RESOLVER, null, new HashMap<>()),
                "an absent SUPP dataset publishes the declared PREDICATE, not an unclassified null");
        assertEquals(EmptyResult.PREDICATE.value(),
                OperationType.emptyValueOf(OperationType.SUPP_QNAM_PRESENT),
                "and that value comes from the declaration, not from this test");
    }


    /**
     * EC-45 §1.2 — {@code get_parent_model_column_order} is the one changed operator whose
     * evaluator takes no {@code Operation}, so it names its {@link OperationType} directly instead
     * of routing through {@code declaredGrouped}. Pinned here precisely because that makes it the
     * easiest site to let drift again.
     */
    @Test
    void parentModelColumnOrderDeclaresTheEmptySet()
    {
        IDataTable supp = MockTable.of().name("SUPPAE").col("RDOMAIN", "AE").col("QNAM", "X")
                .build();
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1").build();
        MetadataProvider p = mock(MetadataProvider.class);
        lenient()
                .when(p.getStandardModelVariables(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of("USUBJID", "AETERM"));

        Object v = OperationExecutor.executeOne(op("$model", "get_parent_model_column_order"), supp,
                name -> "AE".equals(name) ? ae : null, p, new HashMap<>());
        GroupedResult gr = assertInstanceOf(GroupedResult.class, v);
        assertEquals(List.of("USUBJID", "AETERM"), gr.results().get("AE"));
        assertEquals(List.of(), gr.defaultForMissingKey());
    }


    /**
     * The five operators EC-45 re-declared, read off the table itself. This asserts the
     * <em>declaration</em>; the behavioural half — that each evaluator publishes it — is the
     * per-operator tests above.
     */
    @Test
    void theFiveChangedOperatorsDeclareTheirNewValues()
    {
        assertEquals(List.of(), OperationType.emptyValueOf(OperationType.DISTINCT));
        assertEquals(List.of(),
                OperationType.emptyValueOf(OperationType.GET_PARENT_MODEL_COLUMN_ORDER));
        assertEquals(false,
                OperationType.emptyValueOf(OperationType.HAS_MIXED_EMPTINESS_WITHIN_GROUP));
        assertEquals("", OperationType.emptyValueOf(OperationType.REFERENCED_DOMAIN_CLASS));
        assertNotNull(OperationType.DATE_DIFF_DAYS.getEmptyResult());
    }
}
