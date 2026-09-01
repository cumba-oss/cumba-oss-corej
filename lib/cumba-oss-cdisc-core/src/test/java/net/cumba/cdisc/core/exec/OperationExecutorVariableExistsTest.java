package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.OperationType;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@link OperationExecutor} {@code variable_exists} — the <em>reporting</em> carriage of the
 * {@code var_exists(X)} check function (see {@link OperationType#VARIABLE_EXISTS}).
 *
 * <p>
 * The point of every case here is <b>agreement</b>: the operation composes its column name and
 * delegates to {@link OperatorRegistry#existsAsVariable} — the same entry point
 * {@code var_exists(X)} compiles to — because a rule reports {@code $X} beside a verdict the
 * function decided. A divergence would put a {@code false} in the finding next to a violation that
 * only fires when the column is there. The cases below are the map of that agreement, and
 * {@code operationAgreesWithTheVarExistsFunctionOnEveryNameShape} is the one that would catch a
 * future hand-copy.
 * </p>
 *
 * <p>
 * ⚠ The absent-{@code domain}-dataset case is the one that is easy to get wrong: the operation
 * short-circuits ahead of {@code resolveTargetTable}, because the ordinary missing-target skip
 * would answer {@code null} ("no result") where the function answers {@code false}.
 * </p>
 */
class OperationExecutorVariableExistsTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    private static Operation op(String name, String domain)
    {
        Operation o = new Operation();
        o.setId("$exists");
        o.setOperator("variable_exists");
        o.setName(name);
        o.setDomain(domain);
        return o;
    }


    private static DatasetResolver resolver(Map<String, IDataTable> tables)
    {
        return tables::get;
    }


    private static Object run(Operation o, IDataTable t, DatasetResolver r)
    {
        return OperationExecutor.executeOne(o, t, r, null, new LinkedHashMap<>());
    }


    @Test
    void operatorMapsToTheOperationType()
    {
        assertEquals(OperationType.VARIABLE_EXISTS, op("EXVAMT", null).getOperationType(),
                "the operator string must resolve — a rule declaring it in Form B would otherwise "
                        + "fail to load with `unknown operation function`");
    }


    @Test
    void presentColumnOnTheEvaluatedDatasetIsTrue()
    {
        IDataTable ex = MockTable.of().name("EX").col("USUBJID", "S1").col("EXVAMT", "5").build();
        assertEquals(true, run(op("EXVAMT", null), ex, NO_RESOLVER));
    }


    @Test
    void absentColumnIsFalseNotNull()
    {
        IDataTable ex = MockTable.of().name("EX").col("USUBJID", "S1").build();
        // Totality is the property the ORIGINAL variable_exists operation lacked (it skipped on a
        // missing column, breaking negation) — a null here would report as "no value" instead of
        // the honest "the column is not there".
        assertEquals(false, run(op("EXVAMT", null), ex, NO_RESOLVER));
    }


    @Test
    void namelessDeclarationIsFalse()
    {
        IDataTable ex = MockTable.of().name("EX").col("USUBJID", "S1").build();
        assertEquals(false, run(op(null, null), ex, NO_RESOLVER));
    }


    @Test
    void domainQualifiedColumnResolvesAgainstTheForeignDataset()
    {
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S1").build();
        IDataTable ex = MockTable.of().name("EX").col("USUBJID", "S1").col("EXVAMTU", "mg").build();
        assertEquals(true, run(op("EXVAMTU", "EX"), dm, resolver(Map.of("DM", dm, "EX", ex))));
    }


    @Test
    void domainQualifiedColumnAbsentFromThePresentForeignDatasetIsFalse()
    {
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S1").build();
        IDataTable ex = MockTable.of().name("EX").col("USUBJID", "S1").build();
        assertEquals(false, run(op("EXVAMTU", "EX"), dm, resolver(Map.of("DM", dm, "EX", ex))));
    }


    /**
     * ⚠ The absent foreign dataset. {@code var_exists("EX.EXVAMTU")} answers {@code false} here
     * ({@code existsAsDottedDatasetColumn} resolves {@code EX} to null and falls through), so the
     * operation must too.
     *
     * <p>
     * ⚠ Since Q17-a this case is <b>no longer</b> the neuter-and-watch control for the
     * short-circuit: the absent-{@code domain} path now answers with the type's declared
     * {@link net.cumba.cdisc.core.model.EmptyResult}, and {@code VARIABLE_EXISTS} declares
     * {@code PREDICATE} — the same {@code false}. Deleting the short-circuit instead reddens
     * {@link #domainQualifiedColumnResolvesAgainstTheForeignDataset} and
     * {@link #domainQualifiedColumnSurfacingViaSuppQnamIsTrue}, because {@code dispatch} has no
     * {@code VARIABLE_EXISTS} arm at all — a <em>resolvable</em> domain would fall to its default
     * and yield {@code null}. The short-circuit is the operation's only evaluator, not a hand-coded
     * absence answer.
     * </p>
     */
    @Test
    void domainQualifiedColumnWithAnAbsentForeignDatasetIsFalseNotSkipped()
    {
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S1").build();
        Object result = run(op("EXVAMTU", "EX"), dm, resolver(Map.of("DM", dm)));
        assertNotNull(result, "an absent foreign dataset is a false answer, not no answer");
        assertEquals(false, result);
    }


    /**
     * Fix #39's lazy SUPP pivot: a qualifier delivered as {@code SUPPEX.QNAM == EXVAMTU} counts as
     * existing, exactly as the dotted function reads it. This is the deliberate broadening the
     * {@code var_exists} migration introduced (see {@code
     * plans/PLAN-variable-exists-cross-dataset.md}); the operation inherits it rather than
     * re-deciding, by calling {@link OperatorRegistry#existsInSuppQnam}.
     */
    @Test
    void domainQualifiedColumnSurfacingViaSuppQnamIsTrue()
    {
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S1").build();
        IDataTable ex = MockTable.of().name("EX").col("USUBJID", "S1").build();
        IDataTable suppex = MockTable.of().name("SUPPEX").col("USUBJID", "S1")
                .col("QNAM", "EXVAMTU").col("QVAL", "mg").build();
        assertEquals(true, run(op("EXVAMTU", "EX"), dm,
                resolver(Map.of("DM", dm, "EX", ex, "SUPPEX", suppex))));
    }


    @Test
    void suppDomainIsNotPivotedAgain()
    {
        // A SUPP-- target is already the qualifier table; there is no SUPPSUPPEX to consult.
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S1").build();
        IDataTable suppex = MockTable.of().name("SUPPEX").col("USUBJID", "S1").col("QNAM", "X")
                .build();
        assertEquals(false,
                run(op("EXVAMTU", "SUPPEX"), dm, resolver(Map.of("DM", dm, "SUPPEX", suppex))));
    }


    /**
     * ⚠⚠ The load-bearing case: the operation and the function must never disagree, asserted
     * against {@link OperatorRegistry#existsAsVariable} itself rather than against hand-written
     * expectations that can drift.
     *
     * <p>
     * The grid deliberately includes the shapes an earlier hand-copied evaluator got <b>wrong</b>,
     * because they are exactly the ones two independent implementations diverge on: a dotted name
     * carried in {@code name} with no {@code domain}; a lower-case {@code domain} (the function's
     * {@code DOTTED_DATASET_COLUMN} gate is upper-case-only, so it does <em>not</em> take the
     * cross-dataset path there); a {@code SUPP}-pivoted qualifier; and an absent foreign dataset.
     * Neuter-and-watch: replace the delegation in {@code evalVariableExists} with a literal
     * {@code getOptionalColumn} and rows 2, 4 and 5 go red.
     * </p>
     */
    @Test
    void operationAgreesWithTheVarExistsFunctionOnEveryNameShape()
    {
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S1").col("AGE", "42").build();
        IDataTable ex = MockTable.of().name("EX").col("USUBJID", "S1").col("EXVAMT", "5").build();
        IDataTable suppex = MockTable.of().name("SUPPEX").col("USUBJID", "S1")
                .col("QNAM", "EXVAMTU").build();
        DatasetResolver r = resolver(Map.of("DM", dm, "EX", ex, "SUPPEX", suppex));
        EvaluationContext ctx = EvaluationContext.builder().table(dm).datasetResolver(r)
                .variables(new LinkedHashMap<>()).build();

        String[][] grid =
        {
                {
                        "AGE", null
                },
                {
                        "NOSUCH", null
                },
                {
                        "EX.EXVAMT", null
                },
                {
                        "EXVAMT", "EX"
                },
                {
                        "EXVAMTU", "EX"
                },
                {
                        "EXVAMT", "ex"
                },
                {
                        "EXVAMT", "ZZ"
                },
                {
                        "EXVAMT", "SUPPEX"
                }
        };
        for (String[] pair : grid)
        {
            String name = pair[0];
            String domain = pair[1];
            String column = domain == null ? name : domain + "." + name;
            assertEquals(OperatorRegistry.existsAsVariable(ctx, column),
                    run(op(name, domain), dm, r), column);
        }
    }
}
