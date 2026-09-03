package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.OperationType;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Q17-a — an operation whose {@code domain:} names a dataset the study does not contain publishes
 * the operator's <b>own declared</b> {@link net.cumba.corej.core.model.EmptyResult}, instead of the
 * unclassified {@code null} it used to return.
 *
 * <p>
 * The defect this closes is not "the empty set is wrong" — it is that {@code executeOne} answered
 * one {@code null} for two different situations (the operation <em>ran</em> and matched nothing;
 * the operation <em>could not resolve its dataset</em>) and the consumers disagreed about what that
 * {@code null} meant. {@code ExprCompiler.buildSet} folded it to the empty set, the operand plans
 * broadcast it as "no value so no row fires", and {@code isResultAvailable} called it unavailable.
 * A {@code record_count} over a dataset that is not in the study genuinely counted <b>zero</b>
 * records and a {@code supp_qnam_present} genuinely found <b>no</b> such QNAM; declining to say so
 * is the silent-override the engine's standing policy objects to.
 * </p>
 *
 * <p>
 * <b>Applicability is scope's job, never the algebra's</b> ({@code EmptyResult}'s own javadoc): a
 * rule that should not run when its foreign dataset is absent is SKIPped by {@code Scope.Variables}
 * (including the qualified {@code DATASET.VARIABLE} form), not silenced inside a leaf. This class
 * therefore pins <em>totality</em>, not applicability.
 * </p>
 *
 * <p>
 * ⚠ Neuter-and-watch: restore {@code return null;} at the missing-target branch of
 * {@code executeOne} and {@link #recordCountOverAnAbsentDomainCountsZero},
 * {@link #suppQnamPresentOverAnAbsentDomainIsFalse}, {@link #distinctOverAnAbsentDomainIsEmpty} and
 * {@link #everyTypeReachingTheAbsentDomainPathPublishesItsDeclaredEmptyResult} all go red.
 * </p>
 */
class OperationExecutorAbsentDomainTest
{

    /** A study inventory that contains nothing — every foreign {@code domain:} is absent. */
    private static final DatasetResolver EMPTY_STUDY = _ -> null;

    /**
     * The three types that return <em>before</em> {@code resolveTargetTable} and so never reach the
     * absent-domain branch at all: {@code MINUS} reads only prior {@code $}-refs,
     * {@code VARIABLE_EXISTS} answers a question <em>about</em> a dataset through its own
     * evaluator, and {@code DATE_DIFF_DAYS} keeps the primary table as its target (its
     * {@code domain} names only the foreign lookup it performs itself).
     */
    private static final java.util.Set<OperationType> BYPASSES = EnumSet.of(OperationType.MINUS,
            OperationType.VARIABLE_EXISTS, OperationType.DATE_DIFF_DAYS);

    private static Operation op(String operator, String domain)
    {
        Operation o = new Operation();
        o.setId("$probe");
        o.setOperator(operator);
        o.setDomain(domain);
        o.setName("USUBJID");
        return o;
    }


    private static Object run(Operation o, IDataTable table, DatasetResolver resolver)
    {
        return OperationExecutor.executeOne(o, table, resolver, null, new LinkedHashMap<>());
    }


    private static IDataTable dm()
    {
        return MockTable.of().name("DM").col("USUBJID", "S1", "S2").col("ARMCD", "A", "B").build();
    }

    // ---------------------------------------------------------------- the 47 that move


    /**
     * The {@code COUNT} codomain. {@code FDA-SD0070} asks {@code $ex_record_count < 1} for every DM
     * subject; when the study has no EX dataset at all, every subject really does have zero EX
     * records. Before Q17-a the operation answered {@code null}, the operand plan broadcast
     * {@code null}, and the rule silently produced nothing on a study that plainly violates it.
     */
    @Test
    void recordCountOverAnAbsentDomainCountsZero()
    {
        Object result = run(op("record_count", "EX"), dm(), EMPTY_STUDY);
        assertNotNull(result,
                "an absent dataset holds zero records — that is an answer, not a gap");
        assertEquals(0L, result);
        assertEquals(OperationType.emptyValueOf(OperationType.RECORD_COUNT), result,
                "the value must come from the type's declaration, not be hand-coded here");
    }


    /**
     * The grouped shape is the one that actually ships ({@code record_count(domain="EX",
     * group=[USUBJID])}). ⚑ The change makes "EX is not in the study" agree with "EX is present but
     * holds no row for this subject" — the latter already yields {@code 0} today via
     * {@code GroupedResult.missingKeyDefault}, so before Q17-a the same study fact produced two
     * different verdicts depending on whether the dataset existed.
     */
    @Test
    void groupedRecordCountOverAnAbsentDomainCountsZero()
    {
        Operation o = op("record_count", "EX");
        o.setGroup(List.of("USUBJID"));
        assertEquals(0L, run(o, dm(), EMPTY_STUDY));
    }


    /** The {@code PREDICATE} codomain — {@code FDA-SE2244} / {@code FDA-SE2234}. */
    @Test
    void suppQnamPresentOverAnAbsentDomainIsFalse()
    {
        Object result = run(op("supp_qnam_present", "SUPPCL"), dm(), EMPTY_STUDY);
        assertNotNull(result, "no SUPPCL dataset means no such QNAM — false, not unknown");
        assertEquals(false, result);
    }

    // ------------------------------------------------------- the 247 that must NOT move


    /**
     * ⚑⚑ The load-bearing negative. {@code distinct} is 189 of the 294 domained occurrences in
     * {@code rules-src} and the whole of the membership-flood population, and it must not change
     * <em>verdict</em>: {@code EmptyResult.SET} is {@code List.of()}, and
     * {@code ExprCompiler.toSet} already folds both {@code null} and an empty collection to the
     * empty set, so {@code X not in $ref} stays vacuously true exactly as before.
     *
     * <p>
     * ⚠ The raw value at this boundary <em>does</em> move, {@code null → []}, which is visible
     * wherever a {@code $}-ref is rendered into a finding ({@code RuleRunner.scalarToString}:
     * {@code "" → "[]"}). That is a reporting change, not a verdict change, and it makes the
     * absent-dataset case render the same as the genuinely-empty one — which is what
     * {@code evalDistinct} already returns for a present dataset with no matching value.
     * </p>
     */
    @Test
    void distinctOverAnAbsentDomainIsEmpty()
    {
        Object result = run(op("distinct", "POOLDEF"), dm(), EMPTY_STUDY);
        Collection<?> set = assertInstanceOf(Collection.class, result);
        assertTrue(set.isEmpty(), "an absent dataset contributes no distinct values");
        assertEquals(List.of(), result);
    }


    /**
     * ⚠ The empty-set semantics themselves are untouched: a dataset that IS present and simply
     * holds no matching value already answered {@code List.of()} before Q17-a. Pinning both halves
     * side by side is what proves the change is a re-classification of the absent case and not a
     * weakening of {@code EmptyResult.SET}.
     */
    @Test
    void genuinelyEmptyDistinctIsUnchanged()
    {
        IDataTable pooldef = MockTable.of().name("POOLDEF").col("POOLID").build();
        DatasetResolver resolver = name -> "POOLDEF".equals(name) ? pooldef : null;
        Operation o = op("distinct", "POOLDEF");
        o.setName("POOLID");
        assertEquals(List.of(), run(o, dm(), resolver),
                "a present-but-empty dataset answered the empty set before Q17-a and still does");
    }


    /**
     * The {@code MISSING} codomain — 57 of the 294 occurrences ({@code max_date} 24,
     * {@code ts_parameter_value} 10, {@code min_date} 7, {@code date_diff_days} 7,
     * {@code cross_dataset_variable_metadata} 6, {@code max} 3). {@code EmptyResult.MISSING}'s
     * declared value is {@code null}, so these return byte-identically to before. ⚠ If one of them
     * is ever re-classified (Q17-c) this test is where that lands, deliberately.
     */
    @Test
    void missingCodomainOperatorsStillAnswerNull()
    {
        for (String operator : List.of("max_date", "min_date", "max", "ts_parameter_value",
                "cross_dataset_variable_metadata"))
        {
            assertNull(run(op(operator, "ZZ"), dm(), EMPTY_STUDY),
                    operator + " declares EmptyResult.MISSING, whose value is null");
        }
    }


    /**
     * {@code cross_dataset_variable_metadata} with the {@code "*"} inventory wildcard has no single
     * source dataset and takes an explicit bypass that dispatches with the primary table. Q17-a
     * must not swallow it — the {@code "*"} arm is checked first and only its {@code else} branch
     * changed.
     */
    @Test
    void crossDatasetVariableMetadataWildcardBypassIsUnchanged()
    {
        Object result = run(op("cross_dataset_variable_metadata", "*"), dm(), EMPTY_STUDY);
        assertNotNull(result, "the \"*\" wildcard scans the inventory itself and must still run");
    }


    /**
     * ⚠ {@code variable_exists} short-circuits ahead of {@code resolveTargetTable} and Q17-a does
     * <b>not</b> make that redundant, even though {@code PREDICATE}'s {@code false} now coincides
     * with what the absent-domain path would answer. {@code dispatch} has no
     * {@code VARIABLE_EXISTS} arm at all, so a <em>resolvable</em> domain would fall to its default
     * and yield {@code null} — the short-circuit is the operation's only evaluator. Both sides are
     * pinned here so the "collapse the carve-out" simplification cannot be applied without a red
     * test.
     */
    @Test
    void variableExistsKeepsItsOwnEvaluatorOnBothSidesOfTheAbsentDomainSplit()
    {
        IDataTable ex = MockTable.of().name("EX").col("USUBJID", "S1").col("EXVAMTU", "mg").build();
        DatasetResolver present = name -> "EX".equals(name) ? ex : null;
        Operation probe = op("variable_exists", "EX");
        probe.setName("EXVAMTU");
        assertEquals(true, run(probe, dm(), present),
                "a resolvable domain must reach evalVariableExists, not dispatch's default");
        assertEquals(false, run(probe, dm(), EMPTY_STUDY),
                "an absent domain is a legitimate false, as it was before Q17-a");
    }

    // -------------------------------------------------------------- the contract itself


    /**
     * ⚑ The generalisation, asserted against {@link OperationType#emptyValueOf} rather than against
     * a hand-written table that can drift: <em>every</em> operator that reaches the absent-domain
     * branch answers its own declaration. This is what makes the disposition per-operation by
     * construction — a 55th operator cannot be added with a different absence policy, because the
     * constructor already forced it to choose an {@link net.cumba.corej.core.model.EmptyResult}.
     *
     * <p>
     * ⚠ Library- / define- / dictionary-dependent operators are included: their
     * {@code LIBRARY_NOT_AVAILABLE} sentinel is about an unavailable <em>provider</em>, a different
     * cause from an absent dataset, and the two must not be collapsed. No shipped rule declares a
     * {@code domain:} on one of those operators (measured over {@code rules-src}, the generated
     * {@code rules/} corpus and {@code cumba-oss-corej-define-conformance}), so this arm documents
     * a contract with an empty population rather than a behaviour anything relies on.
     * </p>
     */
    @Test
    void everyTypeReachingTheAbsentDomainPathPublishesItsDeclaredEmptyResult()
    {
        IDataTable table = dm();
        for (OperationType type : OperationType.values())
        {
            if (BYPASSES.contains(type))
            {
                continue;
            }
            Operation o = op(type.getJsonValue(), "NOSUCHDATASET");
            Object result = run(o, table, EMPTY_STUDY);
            assertEquals(OperationType.emptyValueOf(type), result,
                    type + " must publish its declared EmptyResult for an absent domain");
        }
    }


    /**
     * The three bypasses, stated as behaviour rather than as a comment: they never consult the
     * resolver for a target table, so an empty study inventory cannot turn them into an
     * {@code EmptyResult}. {@code minus} in particular declares {@code SET} but must answer from
     * its operands.
     */
    @Test
    void theThreePreResolutionBypassesDoNotTakeTheAbsentDomainPath()
    {
        Operation minus = op("minus", "ZZ");
        minus.setName("$a");
        minus.setSubtract("$b");
        Map<String, Object> priors = new LinkedHashMap<>();
        priors.put("$a", List.of("X", "Y"));
        priors.put("$b", List.of("Y"));
        assertEquals(List.of("X"),
                OperationExecutor.executeOne(minus, dm(), EMPTY_STUDY, null, priors),
                "minus reads prior $-refs and never resolves a target dataset");

        assertEquals(false, run(op("variable_exists", "ZZ"), dm(), EMPTY_STUDY),
                "variable_exists answers through its own evaluator");
    }
}
