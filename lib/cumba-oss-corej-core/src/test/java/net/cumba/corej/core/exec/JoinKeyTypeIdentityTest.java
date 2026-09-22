package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.eval.ColumnTypeGate;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * ⭐⭐ {@code D4} — is a character {@code "5"} the same join key as a numeric {@code 5}?
 *
 * <p>
 * Owner, 2026-09-22: <i>"I take B 'typed identity everywhere' as default but with optional C
 * 'declared coercion' to overrule … {@code Join_As_String: true} is agreed."</i> and <i>"B as I
 * read it means a rule errors out if the types do not match. The author should add
 * {@code Requirements.Variables.All} and express the typed requirements to avoid the error. I do
 * not want a silent mismatch."</i>
 * </p>
 *
 * <p>
 * ⛔⛔ <b>Before this change the answer was neither yes nor no — it was ASYMMETRIC PARTIAL
 * MATCHING.</b> {@code tuple()} put the <em>rendered</em> {@code KeyPart} in the key, so a
 * character {@code "5"} joined a numeric {@code 5} while {@code "05"}, {@code "5.0"} and
 * {@code " 5"} did not: whether a subject's records joined depended on the <b>spelling</b> of a
 * value whose type was already wrong. {@code keyTupleSpellingsNoLongerDecideTheJoin} is the pin on
 * that, and the corpus scenario {@code PMDA-AD0258-d4_key_type_divergence_char_vs_num-ADAE.cdt} is
 * its end-to-end twin.
 * </p>
 */
class JoinKeyTypeIdentityTest
{

    private static final String AE = "AE";

    private static final String USUBJID = "USUBJID";

    private static final String AESEQ = "AESEQ";

    private static MatchDataset md(String name, List<String> keys)
    {
        MatchDataset m = new MatchDataset();
        m.setName(name);
        m.setKeys(keys);
        m.setJoinType("left");
        return m;
    }


    private static MatchDataset bind(String json)
    {
        try
        {
            return new ObjectMapper().readValue(json, MatchDataset.class);
        }
        catch (Exception ex)
        {
            throw new IllegalStateException(ex);
        }
    }


    private static KeyMatchRowExpander.KeyMatchExpansion expand(IDataTable primary, IDataTable ae,
            MatchDataset entry)
    {
        KeyMatchRowExpander.KeyMatchExpansion exp = KeyMatchRowExpander.expand(primary,
                List.of(entry), Map.of("ADAE", primary, AE, ae)::get, "R-TEST");
        assertNotNull(exp, "the entry under test must be expandable");
        return exp;
    }


    private static List<String> matched(KeyMatchRowExpander.KeyMatchExpansion exp, String col)
    {
        IDataTable t = exp.table();
        JoinLookup lk = exp.lookups().get(AE);
        List<String> out = new ArrayList<>();
        for (long i = 0; i < t.getRowCount(); i++)
        {
            out.add(String.valueOf(lk.lookup(t, i, col)));
        }
        return out;
    }


    /** A character key against a numeric one: {@code D4-R1}/{@code D4-R2} — the rule ERRORS. */
    @Test
    void aCharacterKeyAgainstANumericKeyErrorsTheRule()
    {
        IDataTable adae = MockTable.of().col(USUBJID, "P1").col(AESEQ, "1").name("ADAE").build();
        IDataTable ae = MockTable.of().col(USUBJID, "P1").colLong(AESEQ, 1L).name(AE).build();
        JoinKeyTypeMismatchException ex = assertThrows(JoinKeyTypeMismatchException.class,
                () -> expand(adae, ae, md(AE, List.of(USUBJID, AESEQ))),
                "D4-R2: Char AESEQ against Num AESEQ must ERROR, not silently match on the rendered"
                        + " form and not silently match nothing");
        String msg = String.valueOf(ex.getMessage());
        assertTrue(msg.contains(AESEQ), "the message must name the offending key: " + msg);
        assertTrue(msg.contains("Character") && msg.contains("Numeric"),
                "the message must name BOTH kinds so the author knows which side to fix: " + msg);
    }


    /**
     * ⭐⭐ The error names <b>both</b> remedies, and the {@code Requirements} one is
     * <b>two-sided</b>.
     *
     * <p>
     * ⛔ This is the plan's review finding H4 pinned as a test. A qualified
     * {@code Requirements.Variables} entry is decided against the <em>foreign</em> dataset only, so
     * {@code "AE.AESEQ:N"} alone is <b>satisfied</b> on this very shape — the rule would not skip
     * and would error anyway. An error message advertising a remedy that does not work is worse
     * than one that advertises none.
     * </p>
     */
    @Test
    void theErrorNamesBothRemediesAndTheTwoSidedRequirementsSpelling()
    {
        IDataTable adae = MockTable.of().col(USUBJID, "P1").col(AESEQ, "1").name("ADAE").build();
        IDataTable ae = MockTable.of().col(USUBJID, "P1").colLong(AESEQ, 1L).name(AE).build();
        String msg = String.valueOf(assertThrows(JoinKeyTypeMismatchException.class,
                () -> expand(adae, ae, md(AE, List.of(USUBJID, AESEQ)))).getMessage());
        assertTrue(msg.contains("\"AESEQ:N\""),
                "the PRIMARY side's tag must be named — a one-sided declaration does not skip: "
                        + msg);
        assertTrue(msg.contains("not the one this study has"),
                "⛔⛔ the message must NOT advise declaring the OBSERVED types. On this very shape"
                        + " the primary is Char, so an \"AESEQ:C\" tag would be SATISFIED, the"
                        + " rule would not skip, and it would error again — advice that sends the"
                        + " author in a circle. Caught by reading the message the engine actually"
                        + " produced, not the one the plan described; was: " + msg);
        assertTrue(msg.contains("\"AE.AESEQ:N\""),
                "the JOINED side's tag must be named too: " + msg);
        assertTrue(msg.contains("Join_As_String"), "the other remedy must be named: " + msg);
    }


    /** Same kinds on both sides: untouched, and still joins. */
    @Test
    void twoNumericKeysStillJoin()
    {
        IDataTable adae = MockTable.of().col(USUBJID, "P1").colLong(AESEQ, 1L).name("ADAE").build();
        IDataTable ae = MockTable.of().col(USUBJID, "P1").colLong(AESEQ, 1L).col("AETERM", "HA")
                .name(AE).build();
        var exp = expand(adae, ae, md(AE, List.of(USUBJID, AESEQ)));
        assertNotNull(exp, "one keyed entry is expandable");
        assertEquals(List.of("HA"), matched(exp, "AETERM"),
                "same-typed keys must keep joining exactly as before D4");
    }


    /**
     * ⭐ {@code D4-R3} — {@code Join_As_String: true} is the author's override, and it restores the
     * pre-D4 rendered comparison for that entry: {@code "1"} joins {@code 1}, {@code "05"} does
     * not.
     */
    @Test
    void joinAsStringOverridesTheTypeCheckAndComparesTheRenderedForm()
    {
        IDataTable adae = MockTable.of().col(USUBJID, "P1", "P1").col(AESEQ, "1", "05").name("ADAE")
                .build();
        IDataTable ae = MockTable.of().col(USUBJID, "P1").colLong(AESEQ, 1L).col("AETERM", "HA")
                .name(AE).build();
        MatchDataset entry = bind("{\"Name\":\"AE\",\"Keys\":[\"USUBJID\",\"AESEQ\"],"
                + "\"Join_Type\":\"left\",\"Join_As_String\":true}");
        assertTrue(entry.joinKeysAsString(), "the flag must bind");
        var exp = expand(adae, ae, entry);
        assertNotNull(exp, "the flagged entry is still expandable");
        assertEquals(List.of("HA", "null"), matched(exp, "AETERM"),
                "D4-R3: \"1\" renders \"1\" and joins the numeric 1; \"05\" renders \"05\" and does"
                        + " not. Two nulls would mean the flag did not reach tuple(); two HAs would"
                        + " mean it coerced numerically, which D4-R6 forbids");
    }


    /**
     * ⛔⛔ {@code D4-R5} — a {@code Child:true} entry is EXCLUDED, and this is the case that would
     * break eleven shipped rules in five files if the exclusion ever leaked.
     */
    @Test
    void aChildEntryIsExcludedFromTheTypeCheck()
    {
        IDataTable suppae = MockTable.of().col(USUBJID, "P1").col("IDVAR", AESEQ)
                .col("IDVARVAL", "1").name("SUPPAE").build();
        IDataTable ae = MockTable.of().col(USUBJID, "P1").colLong(AESEQ, 1L).name(AE).build();
        MatchDataset entry = md(AE, List.of(USUBJID, AESEQ));
        entry.setChild(Boolean.TRUE);
        assertTrue(JoinKeyTypes.excludedFromKeyTypeCheck(entry),
                "a Child:true entry must be excluded");
        assertNull(
                KeyMatchRowExpander.expand(suppae, List.of(entry),
                        Map.of("SUPPAE", suppae, AE, ae)::get, "R-TEST"),
                "an excluded entry is not expandable at all, so keySpec -- where the D4 check lives"
                        + " -- never sees it. THIS is what keeps JKM R6 intact");
    }


    /** {@code D4-R5} — every clause of the exclusion, including the one a transcription dropped. */
    @Test
    void theExclusionPredicateCoversAllFiveClauses()
    {
        MatchDataset unnamed = new MatchDataset();
        unnamed.setKeys(List.of(USUBJID));
        assertTrue(JoinKeyTypes.excludedFromKeyTypeCheck(unnamed),
                "name == null -- the clause this plan's own hand transcription dropped");
        MatchDataset child = md(AE, List.of(USUBJID));
        child.setChild(Boolean.TRUE);
        assertTrue(JoinKeyTypes.excludedFromKeyTypeCheck(child), "Child:true");
        assertTrue(JoinKeyTypes.excludedFromKeyTypeCheck(md("RELREC", List.of(USUBJID))), "RELREC");
        assertTrue(JoinKeyTypes.excludedFromKeyTypeCheck(md("relrec", List.of(USUBJID))),
                "RELREC is matched case-insensitively");
        assertTrue(JoinKeyTypes.excludedFromKeyTypeCheck(md("SUPP--", List.of(USUBJID))), "SUPP--");
        assertTrue(JoinKeyTypes.excludedFromKeyTypeCheck(md("SUPPAE", List.of(USUBJID))), "SUPP*");
        assertTrue(JoinKeyTypes.excludedFromKeyTypeCheck(md("SQAPAE", List.of(USUBJID))), "SQ*");
        assertFalse(JoinKeyTypes.excludedFromKeyTypeCheck(md(AE, List.of(USUBJID))),
                "a plain named entry is NOT excluded -- without this the whole check is vacuous");
    }


    /**
     * ⭐⭐ The permanent guard for the routing invariant proven in phase 1 of
     * {@code PLAN-join-key-type-identity}.
     *
     * <p>
     * ⛔ {@code RuleRunner:918-920} removes every <em>expanded</em> entry before
     * {@code buildJoinedDatasets} runs, so the hashed arm only ever sees entries that are excluded,
     * keyless, or had no resolvable right-hand table. That is what licenses siting the D4 check in
     * {@code keySpec} alone and leaving {@code KeyHashing}/{@code DatasetLookup} untouched. If the
     * two predicates ever diverge, the check silently stops covering part of the corpus — so they
     * are asserted to be the same predicate here rather than trusted to stay in step.
     * </p>
     */
    @Test
    void everyKeyedEntryIsEitherExpandableOrExcluded()
    {
        List<MatchDataset> shapes = new ArrayList<>();
        shapes.add(md(AE, List.of(USUBJID)));
        shapes.add(md("RELREC", List.of(USUBJID)));
        shapes.add(md("SUPP--", List.of(USUBJID)));
        shapes.add(md("SQAPAE", List.of(USUBJID)));
        MatchDataset child = md("CO", List.of(USUBJID));
        child.setChild(Boolean.TRUE);
        shapes.add(child);
        MatchDataset unnamed = new MatchDataset();
        unnamed.setKeys(List.of(USUBJID));
        shapes.add(unnamed);
        int expandable = 0;
        for (MatchDataset shape : shapes)
        {
            boolean excluded = JoinKeyTypes.excludedFromKeyTypeCheck(shape);
            boolean isExpandable = !KeyMatchRowExpander.expandableEntries(List.of(shape)).isEmpty();
            assertEquals(!excluded, isExpandable, "the two predicates must be the same predicate;"
                    + " they disagreed on " + shape.getName());
            if (isExpandable)
            {
                expandable++;
            }
        }
        assertEquals(1, expandable,
                "exactly the plain named entry is expandable -- a 0 here would mean the population"
                        + " is degenerate and the assertion above proves nothing");
    }


    /**
     * ⭐⭐ {@code D4-R6a} — an unclassified kind is NOT a mismatch, and that arm is load-bearing.
     *
     * <p>
     * ⚠⚠ An all-{@code NA} R column is {@code logical}, which {@code RdataTableProvider.mapType}
     * maps to {@code BOOLEAN}; the engine itself publishes {@code MISSING} for an absent joined
     * column ({@code JoinLookup:264}). Were {@code null} treated as a mismatch, every such study
     * would ERROR on every keyed rule.
     * </p>
     */
    @Test
    void anUnclassifiedColumnKindIsNotAMismatch()
    {
        assertNull(ColumnTypeGate.kindOf(DataValueType.BOOLEAN),
                "BOOLEAN must stay unclassified -- an all-NA R `logical` key column arrives as"
                        + " this, and D4-R6a is what stops it erroring");
        assertNull(ColumnTypeGate.kindOf(DataValueType.MISSING),
                "MISSING must stay unclassified -- the engine publishes it for an absent joined"
                        + " column");
        assertNotNull(ColumnTypeGate.kindOf(DataValueType.STRING), "STRING is classified");
        assertNotNull(ColumnTypeGate.kindOf(DataValueType.LONG), "LONG is classified");
        assertNotNull(ColumnTypeGate.kindOf(DataValueType.DOUBLE), "DOUBLE is classified");
    }


    /**
     * ⭐⭐ §4.5 — the SECOND behaviour change, and it is not about types at all.
     *
     * <p>
     * {@code tuple()} used to render every key part through {@code reportingForm()}, which cleans a
     * double to <b>12 significant digits</b>. Two {@code Num} keys differing only at the 13th
     * therefore used to join. Dropping the rendering makes {@code PresentNumber} compare exactly,
     * so they no longer do — with no type divergence anywhere in sight. The corpus twin is
     * {@code CDISC-CG0033-d4_numeric_key_13_significant_digits-LB.cdt}.
     * </p>
     */
    @Test
    void twoNumericKeysDifferingBeyondTwelveSignificantDigitsNoLongerJoin()
    {
        IDataTable adae = MockTable.of().col(USUBJID, "P1").colDouble(AESEQ, 99.0000000000001)
                .name("ADAE").build();
        IDataTable ae = MockTable.of().col(USUBJID, "P1").colDouble(AESEQ, 99.0000000000002)
                .col("AETERM", "HA").name(AE).build();
        var exp = expand(adae, ae, md(AE, List.of(USUBJID, AESEQ)));
        assertNotNull(exp, "same-typed keys stay expandable");
        assertEquals(List.of("null"), matched(exp, "AETERM"),
                "after the reportingForm() drop the two doubles are distinct keys. 'HA' would mean"
                        + " the 12-significant-digit rendering is still deciding the join");
        MatchDataset flagged = bind("{\"Name\":\"AE\",\"Keys\":[\"USUBJID\",\"AESEQ\"],"
                + "\"Join_Type\":\"left\",\"Join_As_String\":true}");
        assertEquals(List.of("HA"), matched(expand(adae, ae, flagged), "AETERM"),
                "and Join_As_String RESTORES the 12-digit behaviour for an entry that asks for it");
    }


    /** {@code Join_As_String} binds strictly: the quoted strings are rejected, not coerced. */
    @Test
    void joinAsStringIsRejectedUnlessItIsAnActualBoolean()
    {
        assertTrue(bind("{\"Name\":\"AE\",\"Join_As_String\":true}").joinKeysAsString(),
                "an unquoted true enables it");
        assertFalse(bind("{\"Name\":\"AE\",\"Join_As_String\":false}").joinKeysAsString(),
                "an unquoted false disables it");
        assertFalse(bind("{\"Name\":\"AE\"}").joinKeysAsString(),
                "absent means false -- D4-R1, typed identity is the default");
        assertFalse(bind("{\"Name\":\"AE\"}").hasMalformedJoinAsString(),
                "absent is legal, not malformed");
        MatchDataset quoted = bind("{\"Name\":\"AE\",\"Join_As_String\":\"true\"}");
        assertTrue(quoted.hasMalformedJoinAsString(),
                "⛔ the STRING \"true\" must be REJECTED, not coerced: Jackson would otherwise"
                        + " accept it silently and the author would never learn the spelling is"
                        + " wrong");
        assertFalse(quoted.joinKeysAsString(),
                "and a malformed value must not take effect in the meantime");
        assertTrue(bind("{\"Name\":\"AE\",\"Join_As_String\":1}").hasMalformedJoinAsString(),
                "a number is malformed too");
    }

}
