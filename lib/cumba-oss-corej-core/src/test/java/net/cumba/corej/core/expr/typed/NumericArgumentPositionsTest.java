package net.cumba.corej.core.expr.typed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.SyntheticDataTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every length / position argument the engine gates with {@code ColumnTypeGate.requireNumericRead}
 * is a {@link TypeExpectations.Expectation#NUMERIC NUMERIC} position of {@link TypeExpectations} —
 * so an ABSENT column there takes the D76 default {@code MissingValue.MIS} (not {@code ""}), and a
 * {@code Char} column there files the stage-B {@code COLUMN_TYPE_MISMATCH} shadow of the gate's
 * error.
 *
 * <p>
 * ⭐ Written for review round 2 of {@code PLAN-case-fold-missing-d36} (LOW-5): the {@code n} of
 * {@code prefix_matches} / {@code suffix_matches} (gated in {@code Primitives.affixRegex}) and the
 * length of {@code has_equal_length} / {@code has_not_equal_length} (gated in
 * {@code Primitives.lengthEquality}) were missing from {@code NUMERIC_ARGS}, so an absent column
 * there defaulted to {@code ""} and a {@code Char} column there filed no stage-B finding although
 * the gate itself errors on it. The four already-listed spellings ({@code prefix} / {@code suffix}
 * {@code n}, {@code substring} start and length) are pinned beside them so the roster is read from
 * one place.
 * </p>
 *
 * <p>
 * Mockito-free: the tables are the testkit's real {@link SyntheticDataTable}, whose columns are all
 * declared {@code Char}.
 * </p>
 */
class NumericArgumentPositionsTest
{

    /**
     * One expression per gated position, the gated operand always the column {@code N}; {@code X}
     * is the (character) subject.
     */
    static List<String> gatedPositions()
    {
        return List.of("prefix_matches(X, /^A/, N)", "suffix_matches(X, /^A/, N)",
                "has_equal_length(X, N)", "has_not_equal_length(X, N)", "prefix(X, N) == \"A\"",
                "suffix(X, N) == \"A\"", "substring(X, N) == \"A\"", "substring(X, 1, N) == \"A\"");
    }


    @AfterEach
    void clearObserver()
    {
        StageBChecker.setObserver(null);
    }


    private static Rule rule(String expression)
    {
        Rule rule = new Rule();
        rule.setCheckExpr(CheckExpressionParser.parse(expression));
        return rule;
    }


    private static List<StageBFinding> findings(String expression, IDataTable table,
            StageBErrorKind kind)
    {
        return StageBChecker.check(rule(expression), table, true, null, Set.of()).findings()
                .stream().filter(f -> f.kind() == kind).toList();
    }


    /** A one-row, all-{@code Char} real table holding {@code columns}. */
    private static IDataTable table(String... columns)
    {
        return new SyntheticDataTable("LB", List.of(columns), new String[]
        {
                "3"
        }, 1);
    }


    @ParameterizedTest
    @MethodSource("gatedPositions")
    void theGatedArgumentIsANumericPosition(String expression)
    {
        TypeExpectations te = TypeExpectations.of(List.of(CheckExpressionParser.parse(expression)));
        assertEquals(Set.of(TypeExpectations.Expectation.NUMERIC), te.expectationsOf("N"),
                () -> expression
                        + ": N is gated numeric, so it must carry the NUMERIC expectation");
        assertTrue(te.numericDefaultColumns().contains("N"),
                () -> expression + ": an absent N must take the D76 numeric default");
    }


    @ParameterizedTest
    @MethodSource("gatedPositions")
    void anAbsentGatedArgumentDefaultsToMisNotTheEmptyString(String expression)
    {
        List<StageBFinding> absent = findings(expression, table("X"),
                StageBErrorKind.ABSENT_COLUMN);
        assertEquals(1, absent.size(), () -> expression + ": exactly N is absent, got " + absent);
        assertEquals("N", absent.get(0).binding());
        assertTrue(absent.get(0).message().contains("MissingValue.MIS"), () -> expression
                + ": D76 numeric default expected, got: " + absent.get(0).message());
    }


    @ParameterizedTest
    @MethodSource("gatedPositions")
    void aCharColumnInTheGatedArgumentIsAStageBMismatch(String expression)
    {
        List<StageBFinding> mismatches = findings(expression, table("X", "N"),
                StageBErrorKind.COLUMN_TYPE_MISMATCH);
        assertEquals(1, mismatches.size(),
                () -> expression + ": exactly the Char N is mismatched, got " + mismatches);
        assertEquals("N", mismatches.get(0).binding());
        assertTrue(mismatches.get(0).message().contains("num(N)"), () -> expression
                + ": the finding names the num() remedy, got: " + mismatches.get(0).message());
    }
}
