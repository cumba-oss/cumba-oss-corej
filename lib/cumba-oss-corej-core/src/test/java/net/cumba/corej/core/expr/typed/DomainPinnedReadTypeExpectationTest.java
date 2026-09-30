package net.cumba.corej.core.expr.typed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.SyntheticDataTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A {@code domain=}-pinned registry call names <b>no primary operand</b> (W2a D10; D13 Q4): its
 * positional target, its {@code filter=} operands and its {@code domain=} reference are columns of
 * the PINNED dataset, while a plain {@code group=} member is read on the dataset under evaluation
 * (the broadcast key, D14) — the same split {@code StudyRuleClassifier}'s call arm makes (combined
 * review D-W7-16).
 *
 * <p>
 * Until {@code PLAN-rprfdy-offset-tp-join}'s review (round 1, lane 3 M1) the walk recorded the
 * pinned reads as PRIMARY value reads, so {@code StageBChecker} logged
 * {@code "RPRFDY absent from BW"} on every run of CDISC-SEND-0401/0402/0403 and
 * {@code UnqualifiedJoinedReadLintTest} counted the target as a candidate. The expectation itself
 * stays recorded under the name — a pinned {@code filter=}'s own evaluation reads it back for its
 * absent-column default ({@code GroupedAggregate} / {@code ReadValue}) — only the two
 * primary-metadata probe sets exclude it.
 * </p>
 */
class DomainPinnedReadTypeExpectationTest
{

    private static TypeExpectations expectations(String expression)
    {
        Expr parsed = CheckExpressionParser.parse(expression);
        assertTrue(parsed != null, "the parser must yield an expression for: " + expression);
        return TypeExpectations.of(List.of(parsed));
    }


    @Test
    void aPinnedReadValueTargetIsNoPrimaryReadButAPlainGroupMemberIs()
    {
        TypeExpectations te = expectations("read_value(RPRFDY, domain=\"TP\","
                + " group=[DM.RPATHCD, RPHASE], mode=\"MAX\") == 1");
        assertEquals(Set.of("RPHASE"), te.valueReadColumns(),
                "RPRFDY is TP's column (the pinned target); RPHASE is the broadcast key, read on the"
                        + " dataset under evaluation; DM.RPATHCD is dotted (never a primary probe)");
    }


    @Test
    void aPinnedFilterReadsThePinnedDatasetButKeepsItsExpectation()
    {
        TypeExpectations te = expectations(
                "record_count(domain=\"SJ\", filter=(SJSTDTC == SJENDTC and SJSEQ > 3)) > 0");
        assertEquals(Set.of(), te.valueReadColumns(), "every filter operand is SJ's");
        assertEquals(List.of(), te.equalityPairs(),
                "a pinned column pair cannot be judged against the primary's metadata");
        assertEquals(Set.of(TypeExpectations.Expectation.NUMERIC), te.expectationsOf("SJSEQ"),
                "the expectation stays: the filter's own evaluation reads it for its absent default");
    }


    @Test
    void theBareDomainSpellingPinsAndIsNoColumnRead()
    {
        TypeExpectations te = expectations(
                "not empty(min_date(SJSTDTC, domain=SJ, group=[USUBJID, RPHASE]))");
        assertEquals(Set.of("USUBJID", "RPHASE"), te.valueReadColumns(),
                "D10: `domain=SJ` names a dataset, and SJSTDTC is its column");
    }


    @ParameterizedTest
    @ValueSource(strings =
    {
            "read_value(X, domain=\"TP\", mode=\"MAX\")", "max(X, domain=\"TP\", group=[USUBJID])",
            "max_date(X, domain=\"TP\", group=[USUBJID])",
            "min_date(X, domain=\"TP\", group=[USUBJID])", "distinct(X, domain=\"TP\")",
            "record_count(domain=\"TP\", filter=(X != \"\"), group=[USUBJID])"
    })
    void everyDomainPinnedCallPinsItsTarget(String call)
    {
        TypeExpectations te = expectations("not empty(" + call + ")");
        assertTrue(!te.valueReadColumns().contains("X"), call + ": X is TP's column");
        // CONTROL: the same call without domain= reads the dataset under evaluation.
        String unpinned = call.replace("domain=\"TP\", ", "").replace("(domain=\"TP\")", "()")
                .replace(", domain=\"TP\")", ")");
        assertTrue(expectations("not empty(" + unpinned + ")").valueReadColumns().contains("X"),
                unpinned + ": without domain= the target IS a primary read");
    }


    @Test
    void anUnpinnedCallOutsideTheSixStillReadsThePrimary()
    {
        assertEquals(Set.of("AESEV", "AETERM"),
                expectations("AESEV == \"MILD\" and not empty(AETERM)").valueReadColumns());
    }


    @Test
    void stageBNoLongerLogsAPinnedTargetAsAnAbsentPrimaryColumn()
    {
        Rule rule = new Rule();
        rule.setCheckExpr(CheckExpressionParser
                .parse("not empty(BWRPDY) and BWRPDY != read_value(RPRFDY, domain=\"TP\","
                        + " group=[RPHASE], mode=\"MAX\")"));
        IDataTable bw = new SyntheticDataTable("BW", List.of("USUBJID", "RPHASE", "BWRPDY"),
                new String[]
                {
                        "S1", "S1", "S1"
                }, 1);
        StageBReport report = StageBChecker.check(rule, bw, true, null, Set.of());
        assertEquals(
                List.of(), report.findings().stream()
                        .filter(f -> f.kind() == StageBErrorKind.ABSENT_COLUMN).toList(),
                "RPRFDY is TP's column, not an absent BW column");
        // CONTROL: the same seam over the same table files the bare read of the same name.
        Rule bare = new Rule();
        bare.setCheckExpr(CheckExpressionParser.parse("BWRPDY != RPRFDY"));
        assertEquals(1, StageBChecker.check(bare, bw, true, null, Set.of()).findings().stream()
                .filter(f -> f.kind() == StageBErrorKind.ABSENT_COLUMN).count());
    }

}
