package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.BitSet;
import java.util.List;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.exec.GroupKeyPolicy;
import net.cumba.cdisc.core.exec.GroupSemantics;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.ExprLowering;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@code not is_unique_set(TARGET, keys=[…])} over an <b>absent target</b> column — EC-53 / Fix
 * #142, the answer <b>(c)</b>: the target is dropped and the check regroups on the surviving key
 * columns, exactly as an absent key already is.
 *
 * <p>
 * <b>Why (c).</b> The house contract is that an absent column is a column whose values are all
 * missing (EC-43 / Fix #139), and {@code is_unique_set} counts {@code ""} as a real key component
 * (operator-examples.md D.1). So a present-but-all-blank target contributes the same value to every
 * row's tuple and cannot discriminate — dropping it yields the identical partition. That is the
 * same argument that already justifies dropping an absent <em>key</em>, and the target/key
 * asymmetry it used to create is gone. Before Fix #143 {@link GroupSemantics#uniqueSetViolations}
 * answered an absent target with an empty {@code BitSet} for both polarities ("not applicable").
 * </p>
 *
 * <p>
 * <b>Three answers, and why the fixtures pin exact rows.</b> On the CORE-000213 shape (rows 0/1
 * share the surviving key tuple, row 2 does not):
 * </p>
 *
 * <pre>
 *                        row 0   row 1   row 2
 *  (a) pre-Fix-#143      .       .       .      not applicable
 *  (b) structural flip   FIRE    FIRE    FIRE   the over-firing DEFECT — row 2 is the tell
 *  (c) this contract     FIRE    FIRE    .      regroup on the survivors
 * </pre>
 *
 * <p>
 * (b) is what a {@code flip(0, rowCount)} over (a)'s empty {@code BitSet} produced, and it is the
 * defect CORE-000213 / CORE-001034 were filed for. It flags a row that duplicates nothing.
 * Distinguishing (c) from (b) therefore needs the <b>exact</b> {@code BitSet} — never merely
 * "something fired" — so every assertion below names the rows.
 * </p>
 *
 * <p>
 * <b>The historical defects do not return on the shipped rules either</b>, for a second and
 * independent reason: both anchors guard their target. {@code CORE-000213} opens with {@code {name:
 * EPOCH, operator: exists}} and {@code CORE-001034} with {@code {name: --REPNUM, operator: exists}}
 * (its Description: <em>"when REPNUM is in the dataset"</em>). (c) is unobservable on them —
 * asserted directly by {@link #guardedRuleIsUnaffectedByTheChange()}.
 * </p>
 *
 * <p>
 * The Java lane deliberately diverges from the parity fork here, which keeps
 * {@code is_(not_)unique_set} in {@code ABSENT_TARGET_AWARE_OPERATORS} and returns
 * all-{@code False} for both polarities. Filed as a {@code known_divergences} entry with
 * {@code lane: "python"} and {@code fix_ref: "EC-53 java-only-accepted"}; spec
 * {@code CORE-000144-absent-taetord}.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class AbsentTargetUniqueSetTest
{

    /** CORE-000213: rows 0/1 share (USUBJID, DSSCAT); row 2 is distinct on both. */
    private static final String CORE_000213 = "not is_unique_set([EPOCH, USUBJID, DSSCAT])";

    /** FDA-SD1060, the minimal single-key shape (two members). */
    private static final String FDA_SD1060 = "not is_unique_set([VISITNUM, USUBJID])";

    private static BitSet nativePath(String expression, IDataTable table)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expression),
                EvaluationContext.builder().table(table).build());
    }


    private static BitSet legacyPath(IDataTable table, String target, String... keys)
    {
        List<String> members = new java.util.ArrayList<>();
        members.add(target);
        members.addAll(List.of(keys));
        return GroupSemantics.uniqueSetViolations(table, 3, members, null, true,
                GroupKeyPolicy.FOLD_BLANK_KEYS);
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


    /** DS with EPOCH absent — the CORE-000213 over-firing shape. */
    private static IDataTable dsWithoutEpoch()
    {
        return MockTable.of().name("DS").col("USUBJID", "S1", "S1", "S2")
                .col("DSCAT", "DISPOSITION EVENT", "DISPOSITION EVENT", "DISPOSITION EVENT")
                .col("DSSCAT", "STUDY PARTICIPATION", "STUDY PARTICIPATION", "STUDY TREATMENT")
                .build();
    }


    /** The same DS with EPOCH PRESENT but blank on every row — the all-missing twin. */
    private static IDataTable dsWithBlankEpoch()
    {
        return MockTable.of().name("DS").col("USUBJID", "S1", "S1", "S2")
                .col("DSCAT", "DISPOSITION EVENT", "DISPOSITION EVENT", "DISPOSITION EVENT")
                .col("DSSCAT", "STUDY PARTICIPATION", "STUDY PARTICIPATION", "STUDY TREATMENT")
                .col("EPOCH", "", "", "").build();
    }


    /** DS with the KEY column DSSCAT absent but the target EPOCH present. */
    private static IDataTable dsWithoutDsscat()
    {
        return MockTable.of().name("DS").col("USUBJID", "S1", "S1", "S2")
                .col("DSCAT", "DISPOSITION EVENT", "DISPOSITION EVENT", "DISPOSITION EVENT")
                .col("EPOCH", "TREATMENT", "TREATMENT", "SCREENING").build();
    }


    @Test
    void absentTargetRegroupsOnTheSurvivorsCore000213()
    {
        IDataTable ds = dsWithoutEpoch();
        BitSet nativeBits = nativePath(CORE_000213, ds);

        assertEquals(bits(0, 1), nativeBits,
                "EC-53: an absent TARGET is dropped and the check regroups on (USUBJID, DSSCAT) — "
                        + "rows 0/1 share that tuple. ROW 2 MUST NOT FIRE: it duplicates nothing, "
                        + "and flagging it is exactly the over-firing defect (b) that CORE-000213 "
                        + "was filed for");
        assertEquals(legacyPath(ds, "EPOCH", "USUBJID", "DSSCAT"), nativeBits,
                "native must agree with the legacy is_not_unique_set leaf");
    }


    @Test
    void absentTargetEqualsPresentButAllBlankTarget()
    {
        // The whole argument for (c) in one assertion: absent == all-missing, and an all-blank
        // target is a constant key component that cannot tell two rows apart. If these two ever
        // disagree, the carve-out is back.
        assertEquals(nativePath(CORE_000213, dsWithBlankEpoch()),
                nativePath(CORE_000213, dsWithoutEpoch()),
                "an absent target must evaluate exactly like a present-but-all-blank one");
        assertEquals(bits(0, 1), nativePath(CORE_000213, dsWithBlankEpoch()),
                "…and the shared answer is the regrouped one, not the empty one");
    }


    @Test
    void absentTargetRegroupsOnTheSurvivorsFdaSd1060()
    {
        IDataTable sv = MockTable.of().name("SV").col("USUBJID", "S1", "S1", "S2")
                .col("SVSTDTC", "2024-01-01", "2024-02-01", "2024-01-01").build();
        BitSet nativeBits = nativePath(FDA_SD1060, sv);

        assertEquals(bits(0, 1), nativeBits,
                "absent target, positional key => regroup on USUBJID alone; S2 is a singleton");
        assertEquals(legacyPath(sv, "VISITNUM", "USUBJID"), nativeBits);
    }


    @Test
    void absentTargetAndAbsentKeysCollapseToOneGroup()
    {
        // Both key components absent: the tuple is empty, every row carries the same key, so they
        // are all duplicates of one another — the same answer an all-blank table would give.
        IDataTable sv = MockTable.of().name("SV").col("SVSTDTC", "a", "b", "c").build();
        assertEquals(bits(0, 1, 2), nativePath(FDA_SD1060, sv),
                "with no surviving key column every row shares the (empty) tuple");
        assertEquals(new BitSet(), nativePath("is_unique_set([VISITNUM, USUBJID])", sv),
                "…so nothing is unique, and the positive polarity is the exact complement");
    }


    @Test
    void absentKeyStillDropsAndRegroups()
    {
        IDataTable ds = dsWithoutDsscat();
        BitSet nativeBits = nativePath(CORE_000213, ds);

        assertEquals(bits(0, 1), nativeBits,
                "an absent KEY column is dropped and the check regroups on the survivors — "
                        + "unchanged by EC-53, which merely gave the TARGET the same treatment");
        assertEquals(legacyPath(ds, "EPOCH", "USUBJID", "DSSCAT"), nativeBits);
    }


    @Test
    void presentColumnsAreUnaffected()
    {
        IDataTable ds = MockTable.of().name("DS").col("USUBJID", "S1", "S1", "S2")
                .col("DSSCAT", "STUDY PARTICIPATION", "STUDY PARTICIPATION", "STUDY TREATMENT")
                .col("EPOCH", "TREATMENT", "TREATMENT", "SCREENING").build();
        BitSet nativeBits = nativePath(CORE_000213, ds);

        assertEquals(bits(0, 1), nativeBits, "rows 0/1 share the whole key tuple");
        assertEquals(legacyPath(ds, "EPOCH", "USUBJID", "DSSCAT"), nativeBits);

        IDataTable sv = MockTable.of().name("SV").col("USUBJID", "S1", "S1", "S2")
                .col("VISITNUM", "1", "1", "1").build();
        assertEquals(bits(0, 1), nativePath(FDA_SD1060, sv));
        assertEquals(legacyPath(sv, "VISITNUM", "USUBJID"), nativePath(FDA_SD1060, sv));
    }


    @Test
    void guardedRuleIsUnaffectedByTheChange()
    {
        // CORE-000213 as it ships: the var_exists guards short-circuit the whole `and`, so the
        // historical over-firing shape still reports nothing under (c). Both anchors of the
        // original defect are guarded this way — CORE-001034 opens with `--REPNUM exists`.
        String shipped = "var_exists(\"EPOCH\") and var_exists(\"DSCAT\") and "
                + "DSCAT == \"DISPOSITION EVENT\" and var_exists(\"DSSCAT\") and " + CORE_000213;
        assertEquals(new BitSet(), nativePath(shipped, dsWithoutEpoch()));
        assertEquals(new BitSet(), nativePath(shipped, dsWithoutDsscat()));
    }


    @Test
    void notIsUniqueSetLowersToTheNegativeLeaf()
    {
        // The lowering the native mapping mirrors: both paths must compute the same check.
        CheckConditionLeaf leaf = assertInstanceOf(CheckConditionLeaf.class,
                ExprLowering.toCheckCondition(CheckExpressionParser.parse(CORE_000213)));
        assertEquals("is_not_unique_set", leaf.getOperator());
        assertEquals("EPOCH", leaf.getName());
    }


    @Test
    void polaritiesPartitionTheRowsOnAnAbsentTarget()
    {
        // Q2's premise, asserted rather than assumed: under (c) the negative and the positive do
        // partition every row even on an absent target, which is what makes compileNot's
        // straight-to-negative mapping REDUNDANT here (it is still needed for an unresolved name
        // operand — see compileNot). Before Fix #143 both sides were empty and this failed.
        IDataTable ds = dsWithoutEpoch();
        BitSet negative = nativePath(CORE_000213, ds);
        BitSet positive = nativePath("is_unique_set([EPOCH, USUBJID, DSSCAT])", ds);

        BitSet union = (BitSet) negative.clone();
        union.or(positive);
        BitSet intersection = (BitSet) negative.clone();
        intersection.and(positive);

        assertEquals(bits(0, 1, 2), union, "every row is either a duplicate or unique");
        assertEquals(new BitSet(), intersection, "and no row is both");
        assertEquals(bits(2), positive, "row 2 is the only unique (USUBJID, DSSCAT) tuple");
    }


    @Test
    void barePositiveStillReadsAsTheNaturalPositive()
    {
        // No bare use ships in the corpus, but the mapping must not silently redefine it: on a
        // PRESENT column is_unique_set flags the rows whose key tuple is unique (row 2 only).
        IDataTable sv = MockTable.of().name("SV").col("USUBJID", "S1", "S1", "S2")
                .col("VISITNUM", "1", "1", "1").build();
        assertEquals(bits(2), nativePath("is_unique_set([VISITNUM, USUBJID])", sv));
    }
}
