package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.Map;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * EC-43 — a leaf over an <b>absent</b> column evaluates exactly like the same leaf over a column
 * that is <b>present but blank on every row</b>.
 *
 * <p>
 * That equality is the house contract (<em>an absent column is a column whose values are all
 * missing</em>) and it is the cheapest possible regression pin: it is independent of every
 * operator's polarity, so no per-operator table has to be maintained and a future change cannot
 * drift without failing here. It is the Java twin of the fork's
 * {@code test_absent_target_behaves_exactly_like_a_present_all_blank_column}.
 * </p>
 *
 * <p>
 * Before this fix the two differed on every directly-compiled leaf: an absent column made
 * {@code nameRefPlan} return {@code null} and the enclosing predicate short-circuited to an empty
 * {@code BitSet}, so {@code TSVAL !~ /^[1-9]\d*$/} silently did not fire while the parity fork
 * fired every row.
 * </p>
 */
class AbsentColumnFoldTest
{

    private static final String COL = "TSVAL";

    /** TS with TSPARMCD populated and TSVAL entirely absent. */
    private static IDataTable absent()
    {
        return MockTable.of().name("TS").col("TSPARMCD", "PLANSUB", "PLANSUB").build();
    }


    /** The same table with TSVAL present and blank on every row. */
    private static IDataTable blank()
    {
        return MockTable.of().name("TS").col("TSPARMCD", "PLANSUB", "PLANSUB").col(COL, "", "")
                .build();
    }


    /**
     * Three rows for the EC-49 affix-negative family: the affix matches, the affix differs, the
     * cell is blank. Rows 0 and 1 are the controls that prove the operator still discriminates; row
     * 2 is the one Fix #148 changed.
     */
    private static IDataTable affixMixed()
    {
        return MockTable.of().name("TS").col("TSPARMCD", "PLANSUB", "PLANSUB", "PLANSUB")
                .col(COL, "2020-01-01", "1999-06-15", "").build();
    }


    /**
     * The Fix #149 num()-tag fixture. The extracted affix of row 0 is {@code "07"}, which is
     * <b>numerically</b> equal to the comparand {@code 7} but <b>textually</b> different from
     * {@code "7"} — so a string verdict and a numeric verdict DISAGREE on that row, which is the
     * only thing that lets a test tell the two modes apart. Row 1 differs under both modes and row
     * 2 is blank; together they prove the operator still discriminates rather than having gone
     * silent. {@code TSREF} carries the same comparand as a per-row STRING column, for the
     * right-hand tag route.
     */
    private static IDataTable numAffix()
    {
        return MockTable.of().name("TS").col("TSPARMCD", "PLANSUB", "PLANSUB", "PLANSUB")
                .col(COL, "0707", "1212", "").col("TSREF", "7", "7", "7").build();
    }


    /** One row carrying a complete ISO date twice — the type-dispatch fixture. */
    private static IDataTable isoPair()
    {
        return MockTable.of().name("TS").col("TSPARMCD", "PLANSUB").col("TSDTC", "2020-05-17")
                .col("TSREF", "2020-05-17").build();
    }


    private static BitSet eval(String expression, IDataTable table)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expression),
                EvaluationContext.builder().table(table).build());
    }


    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }

    // -----------------------------------------------------------------------
    // The contract
    // -----------------------------------------------------------------------


    @DisplayName("absent column == present-but-all-blank column, for every affected surface")
    @ParameterizedTest(name = "{0}")
    @CsvSource(quoteCharacter = '\'', value =
    {
            // negative leaves — the EC-43 shapes, all of which must FIRE
            "'TSVAL !~ /^[1-9]\\d*$/'", "'TSVAL != \"X\"'", "'TSVAL not in [\"Y\", \"N\"]'",
            "'not equalsIgnoreCase(TSVAL, \"x\")'", "'len(TSVAL) != 4'", "'len(TSVAL) < 200'",
            "'not is_integer(TSVAL)'", "'not contains(TSVAL, \"Q\")'",
            // positive leaves — Q1 = uniform brings these into scope too
            "'TSVAL == \"X\"'", "'TSVAL =~ /^[1-9]\\d*$/'", "'TSVAL in [\"Y\", \"N\"]'",
            "'len(TSVAL) > 3'", "'len(TSVAL) == 0'", "'is_integer(TSVAL)'",
            "'contains(TSVAL, \"Q\")'",
            // presence predicates — excluded from the fold, and must still agree
            "'empty(TSVAL)'", "'not empty(TSVAL)'",
    })
    void absentEqualsAllBlank(String expression)
    {
        assertEquals(eval(expression, blank()), eval(expression, absent()),
                () -> "absent and present-but-all-blank must agree for: " + expression);
    }


    @DisplayName("the shapes that name the EC now fire on an absent column")
    @ParameterizedTest(name = "{0}")
    @CsvSource(quoteCharacter = '\'', value =
    {
            "'TSVAL !~ /^[1-9]\\d*$/'", "'TSVAL != \"X\"'", "'TSVAL not in [\"Y\", \"N\"]'",
            "'len(TSVAL) != 4'",
    })
    void negativeLeafFiresOnAbsentColumn(String expression)
    {
        assertEquals(bits(0, 1), eval(expression, absent()),
                () -> "must fire on every row: " + expression);
    }


    @Test
    @DisplayName("len(absent) is 0, not missing — so `< n` fires and `> n` does not")
    void lengthOfAbsentColumnIsZero()
    {
        assertEquals(bits(0, 1), eval("len(TSVAL) < 200", absent()));
        assertEquals(bits(0, 1), eval("len(TSVAL) == 0", absent()));
        assertEquals(new BitSet(), eval("len(TSVAL) > 3", absent()));
    }


    @Test
    @DisplayName("a positive leaf still does not fire — the fold is not a blanket flip")
    void positiveLeafDoesNotFire()
    {
        assertEquals(new BitSet(), eval("TSVAL == \"X\"", absent()));
        assertEquals(new BitSet(), eval("TSVAL in [\"Y\", \"N\"]", absent()));
    }


    @Test
    @DisplayName("both operands absent: the negative does NOT fire (both fold to \"\")")
    void bothSidesAbsentDoNotFire()
    {
        IDataTable table = MockTable.of().name("TS").col("TSPARMCD", "PLANSUB", "PLANSUB").build();
        assertEquals(new BitSet(), eval("TSVAL != TSVALNF", table),
                "both columns absent fold to \"\" and compare equal — the legacy both-missing "
                        + "contract compileNot's case-insensitive interception exists to protect");
    }


    @Test
    @DisplayName("affix negatives FIRE on an empty operand — absent == blank, and BOTH fire")
    void affixNegativeFiresOnAbsentAndBlankAlike()
    {
        // EC-49 / Fix #148. compileComparison used to intersect this family's NEQ result with
        // Primitives.nonEmpty(lv), so it never fired on a missing value — the engine's last
        // operator-level exception to §17's absent-column contract. The mask is gone: an empty
        // extracted affix differs from "20", so the row fires like every other negative leaf.
        //
        // The fork agrees, and always did: DataframeType.prefix_not_equal_to is
        // `~self.prefix_equal_to(...)` with no missingness gate, documented as "Complement of
        // prefix_equal_to". Removing the mask retired the EC-49 parity divergence rather than
        // creating one.
        assertEquals(bits(0, 1), eval("prefix(TSVAL, 2) != \"20\"", absent()));
        assertEquals(eval("prefix(TSVAL, 2) != \"20\"", blank()),
                eval("prefix(TSVAL, 2) != \"20\"", absent()));
    }


    @Test
    @DisplayName("the affix negative still discriminates — it fires on empty, not on everything")
    void affixNegativeStillDiscriminatesPopulatedRows()
    {
        // Without this the fixture above cannot tell "fires on empty" from "fires unconditionally".
        // Row 0 matches the affix and must stay silent; row 1 differs and fired before Fix #148
        // too; row 2 is the blank the fix changed.
        assertEquals(bits(1, 2), eval("prefix(TSVAL, 2) != \"20\"", affixMixed()));
    }


    @Test
    @DisplayName("non_empty is a WORKING opt-out for the affix negatives again")
    void nonEmptyIsTheOptOutForAffixNegatives()
    {
        // ⚠ The GUARDED expression on its own pins nothing: the mask suppressed exactly the rows a
        // non_empty guard suppresses, so `non_empty(X) and prefix(X,2) != "20"` yields bits(1)
        // with the mask and bits(1) without it. Measured — restoring the mask leaves that single
        // assertion green. Two filters that reject the same input make each other untestable.
        //
        // The CONTRAST is what carries the meaning, so both halves are asserted together: without
        // the guard the blank row fires, with it the blank row does not. Before Fix #148 the two
        // lines were identical and the guard was a silent no-op for this family (the retired
        // JAVA-EXTENSIONS §17 called it "redundant" — see expression-docs-disposition.md §A);
        // the difference between them IS the opt-out being restored.
        // The unguarded half is the mask-sensitive one — it goes red the moment the mask returns.
        assertEquals(bits(1, 2), eval("prefix(TSVAL, 2) != \"20\"", affixMixed()),
                "unguarded: the blank row 2 fires");
        assertEquals(bits(1), eval("non_empty(TSVAL) and prefix(TSVAL, 2) != \"20\"", affixMixed()),
                "guarded: non_empty gates row 2 back out — the author's opt-out does work again");
    }


    @Test
    @DisplayName("under a structural `not` the affix negative no longer manufactures a finding")
    void affixNegativeUnderNotDoesNotFireOnTheBlankRow()
    {
        // The regression this fix actually prevents. A masked leaf is not a truth value: at the
        // leaf the mask made coreJ UNDER-fire (safe), but under a structural `not` the polarity
        // inverted and coreJ fired where the fork did not — a manufactured finding. With the mask
        // gone the blank row is false at the leaf, so `not` leaves it false here too; only row 0,
        // whose affix genuinely matches, fires.
        assertEquals(bits(0), eval("not (prefix(TSVAL, 2) != \"20\")", affixMixed()));
    }


    @Test
    @DisplayName("`suffix` too — the family is two operators and only `prefix` ships")
    void suffixNegativeFiresOnTheBlankRowAsWell()
    {
        // isAffixCall matches prefix AND suffix, but all 34 affix-negative occurrences in the
        // shipped rules/ corpus are `prefix(dataset_name, 2) != ...`, so a prefix-only test would
        // leave the suffix half of the family unpinned.
        assertEquals(bits(1, 2), eval("suffix(TSVAL, 2) != \"01\"", affixMixed()));
    }


    @Test
    @DisplayName("dispatch guard: an affix NEQ stays on PLAIN equality, it never routes to date")
    void affixNegativeDoesNotFallThroughToTheDateFamily()
    {
        // ⚠ The interception in compileComparison survives the mask's removal ON PURPOSE, and this
        // is the test that says so. family(lt, rt) falls back to the RIGHT operand's type tag when
        // the left has none (ExprCompiler.family), and an affix call carries no tag — so deleting
        // the branch outright would route `prefix(X, 4) != date(D)` into compileDate. That is a
        // type-dispatch change, not a mask removal.
        //
        // The two routes are distinguishable because the date route reads a PARTIAL operand as a
        // range (IsoDateComparison's hull rule — "2020" and "2020-05" are month/year precision, so
        // neither pair below ever reaches the complete/complete fast path): a value inside the
        // range is neither equal nor different-from it, so the NEQ does not fire, where plain
        // string equality sees a textual difference and fires.
        assertEquals(new BitSet(), eval("date(TSDTC) != \"2020-05\"", isoPair()),
                "control: the date route really does read the partial as a range, so the "
                        + "assertion below is not vacuous — routed there, the NEQ would NOT fire");
        assertEquals(bits(0), eval("prefix(TSDTC, 4) != date(TSREF)", isoPair()),
                "the affix interception keeps this on plain equality: \"2020\" != \"2020-05-17\"");
    }


    @Test
    @DisplayName("the POSITIVE affix form is untouched — the mask was only ever on NEQ")
    void affixPositiveIsUnchanged()
    {
        assertEquals(bits(0), eval("prefix(TSVAL, 2) == \"20\"", affixMixed()));
        assertEquals(new BitSet(), eval("prefix(TSVAL, 2) == \"20\"", absent()));
        assertEquals(eval("prefix(TSVAL, 2) == \"20\"", blank()),
                eval("prefix(TSVAL, 2) == \"20\"", absent()));
    }

    // -----------------------------------------------------------------------
    // Fix #149 — the affix NEQ interception honours the num() type tag
    // -----------------------------------------------------------------------
    //
    // ⚠ EVERY test in this block is SYNTHETIC and was NEUTER-VERIFIED (the change reverted to the
    // hard-coded `false`, each test watched go red, then restored). It has to be: `num(` has ZERO
    // occurrences in the shipped the shipped rule corpus corpus (measured against the same
    // grep that finds `prefix(` 77×, `suffix(` 52× and `date(` 1292×), so the corpus cannot reach
    // any of this and a green suite on its own would prove nothing.


    @Test
    @DisplayName("num() on an affix NEQ compares NUMERICALLY — the tag is no longer thrown away")
    void numTaggedAffixNegativeComparesNumerically()
    {
        // Fix #149. compileComparison's affix-NEQ interception used to call
        // compilePlain(..., false), discarding the surrounding numTag. untag() strips the tag
        // before isAffixCall sees the operand, so `num(prefix(X, 2)) != Y` — the ONLY way an author
        // can ask for a numeric affix comparison — was exactly the shape that lost it. The EQ half
        // never had the defect (it falls through to the switch, which already passed numTag), so
        // the pair was not complementary.
        assertEquals(bits(0, 1, 2), eval("prefix(TSVAL, 2) != \"7\"", numAffix()),
                "control: untagged, the comparison is textual, so \"07\" != \"7\" fires on row 0 — "
                        + "without this the assertion below cannot be read as \"numeric\"");
        assertEquals(bits(1, 2), eval("num(prefix(TSVAL, 2)) != \"7\"", numAffix()),
                "tagged: 07 equals 7 numerically, so row 0 must NOT fire");
        assertEquals(bits(0), eval("num(prefix(TSVAL, 2)) == \"7\"", numAffix()),
                "the EQ half already honoured the tag; the NEQ is now its exact complement");
    }


    @Test
    @DisplayName("the RIGHT-hand num() tag reaches the affix NEQ as well")
    void numTaggedRightOperandAffixNegativeComparesNumerically()
    {
        // numTag is `"num".equals(lt) || "num".equals(rt)` — either side arms it, and the
        // right-hand
        // route was broken identically. TSREF is a STRING column holding "7", so numeric mode is
        // NOT reachable via the `target instanceof Number` trigger in
        // ScalarSemantics.equalsNumericAware; only forceNumeric can get there. That is what makes
        // the pair below a genuine discriminator instead of two spellings of the same verdict.
        assertEquals(bits(0, 1, 2), eval("prefix(TSVAL, 2) != TSREF", numAffix()),
                "control: an untagged per-row column RHS compares textually");
        assertEquals(bits(1, 2), eval("prefix(TSVAL, 2) != num(TSREF)", numAffix()),
                "tagged on the right: row 0's \"07\" equals \"7\" numerically and must not fire");
    }


    @Test
    @DisplayName("`suffix` too — the family is two operators and only `prefix` ships")
    void numTaggedSuffixNegativeComparesNumerically()
    {
        // isAffixCall matches prefix AND suffix, but every affix-NEQ occurrence in the shipped
        // corpus is `prefix(dataset_name, 2) != DOMAIN`, so a prefix-only test would leave half the
        // family unpinned — the same gap Fix #148 had to close for the empty-value mask.
        assertEquals(bits(0, 1, 2), eval("suffix(TSVAL, 2) != \"7\"", numAffix()),
                "control: untagged, the trailing \"07\" differs textually from \"7\"");
        assertEquals(bits(1, 2), eval("num(suffix(TSVAL, 2)) != \"7\"", numAffix()));
        assertEquals(bits(1, 2), eval("suffix(TSVAL, 2) != num(TSREF)", numAffix()));
    }


    @Test
    @DisplayName("num() INSIDE the affix is still a STRING comparison — the cut is char-out")
    void numInsideTheAffixStaysAStringComparison()
    {
        // The regression guard on the case that was ALREADY right, and the reason the fix is safe:
        // prefix/suffix are char-in, char-out, so a number is coerced to text BEFORE the cut and
        // the result of the cut is text. tagOf() only matches a ONE-argument tag call, so the
        // two-argument affix call carries no tag and numTag stays false here — `num()` inside the
        // affix arguments never reaches the comparison.
        //
        // ⚠ The first assertion alone is neuter-INSENSITIVE by construction (this path passed
        // `false` before and computes `false` now). The contrast below is the neuter-sensitive
        // half: before Fix #149 the two spellings collapsed to the same verdict, which is precisely
        // the bug — the tag had no way to be expressed.
        assertEquals(bits(0, 1, 2), eval("prefix(num(TSVAL), 2) != \"7\"", numAffix()),
                "the cut yields text, so \"07\" != \"7\" fires — tagging the ARGUMENT is not a way "
                        + "to ask for a numeric comparison");
        assertNotEquals(eval("prefix(num(TSVAL), 2) != \"7\"", numAffix()),
                eval("num(prefix(TSVAL, 2)) != \"7\"", numAffix()),
                "the two spellings are NOT synonyms: the tag outside the affix selects numeric "
                        + "equality, the tag inside it does not");
    }


    @Test
    @DisplayName("the num() tag does not disturb the date-dispatch guard or the empty-operand fold")
    void numTagDoesNotDisturbTheOtherAffixContracts()
    {
        // ⚠ MEASURED: this is the ONE test in the block that stays GREEN under the neuter, and it
        // does so by design — it pins the two neighbouring contracts the fix must NOT move, both of
        // which compute the same verdict either way. It is a guard against a future change, not a
        // pin of this one; the four tests above are what carry Fix #149.
        //
        // Two neighbours that must not move. The date interception (Fix #148) is reached with a
        // date tag on the right, where numTag is false by construction — family() would have routed
        // it to compileDate, and the branch still keeps it on plain equality.
        assertEquals(bits(0), eval("prefix(TSDTC, 4) != date(TSREF)", isoPair()),
                "the date-tag route is untouched: numTag is false when the tag is `date`");
        // And the EC-49 contract survives: a blank operand still fires, tagged or not. Row 2 is
        // blank, dvMissing short-circuits equalsNumericAware's numeric branch, and the textual fold
        // ("" vs "7") fires exactly as it does untagged.
        assertEquals(bits(0, 1), eval("num(prefix(TSVAL, 2)) != \"7\"", absent()),
                "an absent column is an all-missing column, in numeric mode too");
        assertEquals(eval("num(prefix(TSVAL, 2)) != \"7\"", blank()),
                eval("num(prefix(TSVAL, 2)) != \"7\"", absent()));
    }


    @Test
    @DisplayName("date negatives fire — routing an absent column into an ACCEPTED blank divergence")
    void dateNegativeFiresOnAbsentColumn()
    {
        // Primitives.dateComparison returns `negate` on a missing LHS, and negate is set only for
        // NEQ, so this fires while a positive date comparison does not.
        //
        // The fork does NOT fire here — but that is already known, pinned and ACCEPTED as a
        // deliberate honest-engine divergence: spec EMPTYSTR-date-not-equal-empty, baselined under
        // PLAN-empty-string-membership-parity.md Category C.1 ("coreJ fires on '' and stays that
        // way"). EC-43 does not create it; it routes the absent column into the same accepted
        // blank-column behaviour, which is exactly the contract.
        assertEquals(bits(0, 1), eval("date(TSVAL) != \"2020-01-01\"", absent()));
        assertEquals(new BitSet(), eval("date(TSVAL) == \"2020-01-01\"", absent()));
        assertEquals(eval("date(TSVAL) != \"2020-01-01\"", blank()),
                eval("date(TSVAL) != \"2020-01-01\"", absent()));
    }

    // -----------------------------------------------------------------------
    // The empty/is_missing exclusion, and how far it reaches
    // -----------------------------------------------------------------------


    @Test
    @DisplayName("empty and non_empty stay COMPLEMENTARY over an absent column")
    void emptyAndNonEmptyDoNotBothFire()
    {
        // `empty`/`is_missing` are excluded from the fold via FIRES_ON_ABSENT_COLUMN, and that
        // exclusion disables the fold for the WHOLE arg0 subtree, not just a bare column. A review
        // raised the concern that `empty(E)` and `non_empty(E)` could then BOTH fire on every row.
        // They cannot: `empty`'s all-rows answer comes from compileBoolCall's v0 == null branch,
        // and its complement is produced by `not`/`invert` over that same BitSet.
        assertEquals(bits(0, 1), eval("empty(TSVAL)", absent()));
        assertEquals(new BitSet(), eval("not empty(TSVAL)", absent()));
        assertEquals(bits(0, 1), eval("empty(upper(TSVAL))", absent()),
                "the subtree is unfolded, so upper(absent) is null and empty fires — as it does "
                        + "over a blank column, where upper(\"\") is \"\"");
        assertEquals(new BitSet(), eval("not empty(upper(TSVAL))", absent()));
        assertEquals(eval("empty(upper(TSVAL))", blank()), eval("empty(upper(TSVAL))", absent()));
    }


    @Test
    @DisplayName("scope limit: empty(len(ABSENT)) fires where empty(len(BLANK)) does not")
    void emptyOverATotalFunctionIsTheOneAbsentVsBlankResidual()
    {
        // The one measured place where the Table B exclusion breaks absent == blank. `len` is
        // TOTAL — it maps a missing cell to 0, which is not missing — so over a present-but-blank
        // column `empty(len(X))` is false, while over an absent column the unfolded subtree yields
        // null and `empty` takes its all-rows branch.
        //
        // Left as-is deliberately: the plan's §2.6 Table B decision is to leave `empty`/
        // `is_missing` untouched, the shape requires `empty`/`is_missing` wrapped around a total
        // value function (0 occurrences in the shipped corpus), and BroadcastFold's
        // firesEmptyOnAbsentColumn would have to move in lockstep. Pinned so it is a known
        // boundary rather than a latent surprise.
        assertEquals(bits(0, 1), eval("empty(len(TSVAL))", absent()));
        assertEquals(new BitSet(), eval("empty(len(TSVAL))", blank()));
    }

    // -----------------------------------------------------------------------
    // The nulls that are deliberately NOT folded
    // -----------------------------------------------------------------------


    @Test
    @DisplayName("a $-name absent from the context is NOT an absent column (guard-residual D4)")
    void absentOperationReferenceIsNotFolded()
    {
        IDataTable table = MockTable.of().name("TS").col("TSPARMCD", "PLANSUB", "PLANSUB").build();
        EvaluationContext ctx = EvaluationContext.builder().table(table).variables(Map.of())
                .build();
        BitSet verdict = NativeExprEvaluator
                .evaluate(CheckExpressionParser.parse("$never_ran != \"X\""), ctx);
        assertEquals(new BitSet(), verdict,
                "an Operation that never ran is \"variable not in context\", not an absent column; "
                        + "folding it would fire every row of every dataset");
    }


    @Test
    @DisplayName("a --prefix that cannot be resolved is NOT an absent column (EC-36)")
    void unresolvedDomainWildcardIsNotFolded()
    {
        // No domainPrefix and no variableWildcardPrefix on the context, so `--TERM` never resolves.
        IDataTable table = MockTable.of().name("TS").col("TSPARMCD", "PLANSUB", "PLANSUB").build();
        BitSet verdict = NativeExprEvaluator.evaluate(
                CheckExpressionParser.parse("--TERM != \"X\""),
                EvaluationContext.builder().table(table).build());
        assertEquals(new BitSet(), verdict,
                "the name was never resolved at all, so it is not a column that is absent");
    }


    @DisplayName("an engine-meta bareword is not a column, so it is NOT folded")
    @ParameterizedTest(name = "{0}")
    @CsvSource(quoteCharacter = '\'', value =
    {
            "'variable_label != \"X\"'", "'library_variable_core != \"X\"'",
            "'define_variable_label != \"X\"'", "'dataset_name != \"X\"'",
            "'len(variable_label) > 40'",
    })
    void engineMetaBarewordIsNotFolded(String expression)
    {
        // The engine's OTHER absent-column decision point, BroadcastFold.isFoldableColumnReference,
        // has always rejected engine-meta operands (they start lowercase). nameRefPlan's fold now
        // uses the same predicate, so the two layers agree on what "an absent column" is.
        //
        // Without it, a metadata operand that the loader's canonicalization pass did not rewrite
        // into its var_*/ds_* accessor form — that pass runs for the metadata-check rule types only
        // (RulePackageLoader.installNativeExpr), so a mis-derived Rule_Type is enough — would be
        // materialised as an absent COLUMN and `len(variable_label) > 40` (CORE-000019,
        // CDISC-CG0311, +26 more) would fire on every row of every dataset.
        assertEquals(new BitSet(), eval(expression, absent()),
                () -> "engine meta is not a data column: " + expression);
    }

    // -----------------------------------------------------------------------
    // The fold is observable, and bounded
    // -----------------------------------------------------------------------


    @Test
    @DisplayName("the fold records the column on the context, once, for the caller's INFO line")
    void foldIsRecordedOnTheContext()
    {
        EvaluationContext ctx = EvaluationContext.builder().table(absent()).build();
        NativeExprEvaluator.evaluate(CheckExpressionParser.parse("TSVAL != \"X\""), ctx);
        assertTrue(ctx.getAbsentColumnFolds().contains(COL),
                "the column name is only in scope inside nameRefPlan, so it is recorded there and "
                        + "aggregated once per (rule, dataset) by the caller");
    }
}
