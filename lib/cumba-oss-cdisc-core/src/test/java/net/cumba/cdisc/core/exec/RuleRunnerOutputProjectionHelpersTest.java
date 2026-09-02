package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.model.MatchDataset;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Outcome-level pins for the {@code RuleRunner} output-projection helpers — the code that decides
 * <b>what a finding says</b>: {@code extractOutputValues} (which columns / $-results / joined
 * values a violation reports), {@code scalarToString} / {@code renderCollection} (how an
 * Operation-resolved value is rendered into the report), {@code readRowIdentity} (the USUBJID/--SEQ
 * identity attached to a row finding), {@code buildJoinedDatasets} (which key a joined lookup is
 * registered under) and the Output_Variables inference over {@code any} / {@code not} Check trees.
 * A mutant in any of these changes the content of a reported finding while the rule still "fires" —
 * the invisible-harm case — so every test asserts exact rendered values, including the negative
 * side of each guard.
 */
class RuleRunnerOutputProjectionHelpersTest
{

    private static EvaluationContext ctx(IDataTable table)
    {
        return EvaluationContext.builder().table(table).build();
    }


    private static Map<String, String> extract(IDataTable table, EvaluationContext ctx,
            List<String> ovs, long row)
    {
        return RuleRunner.extractOutputValues(table, ctx, ovs, row);
    }

    // -----------------------------------------------------------------------
    // scalarToString via the $-variable branch of extractOutputValues
    // -----------------------------------------------------------------------


    /** Renders one $-variable through extractOutputValues and returns the reported string. */
    private static String render(Object value)
    {
        IDataTable t = MockTable.of().name("AE").col("AETERM", "x").build();
        EvaluationContext ctx = EvaluationContext.builder().table(t).variables(Map.of("$v", value))
                .build();
        Map<String, String> out = extract(t, ctx, List.of("$v"), 0);
        assertTrue(out.containsKey("$v"), "the $-variable must be reported");
        return out.get("$v");
    }


    /**
     * A numeric operation result renders like a numeric cell ("10", never "10.0") — EC-39: a
     * finding whose {@code max} disagrees with the cell it summarises is incoherent.
     */
    @Test
    void numericOperationResultRendersCanonically()
    {
        assertEquals("10", render(10.0d));
    }


    /**
     * A long String renders in full through the CharSequence branch — the 1024-char opaque-type
     * truncation must NOT apply to genuine strings (a truncated codelist term would misreport the
     * offending value).
     */
    @Test
    void longStringRendersUntruncated()
    {
        String s = "s".repeat(2000);
        assertEquals(s, render(s));
    }


    /**
     * Collection rendering: exact bracket/comma form, and a 100-char element is truncated at 64
     * with an ellipsis — but ONLY beyond 64 (an exactly-64-char element stays intact). Pins the
     * {@code rendered.length() > 64} boundary and the per-element separator increment; a mutant in
     * either garbles every list-valued finding.
     */
    @Test
    void collectionElementsAreCommaSeparatedAndCappedAt64Chars()
    {
        assertEquals("[a, b]", render(List.of("a", "b")));
        String at64 = "e".repeat(64);
        assertEquals("[" + at64 + "]", render(List.of(at64)));
        String over = "f".repeat(100);
        assertEquals("[" + "f".repeat(64) + "…]", render(List.of(over)));
    }


    /** An empty collection renders as the literal {@code []}, never as an empty string. */
    @Test
    void emptyCollectionRendersAsBrackets()
    {
        assertEquals("[]", render(List.of()));
    }


    /** A Map renders through its entry set; an Object[] through its list view. */
    @Test
    void mapAndArrayRenderThroughTheCollectionPath()
    {
        assertEquals("[k=v]", render(Map.of("k", "v")));
        assertEquals("[x, y]", render(new Object[]
        {
                "x", "y"
        }));
    }


    /**
     * The 1M-element OOM backstop: rendering stops at exactly 1_000_000 elements and reports the
     * true remainder count ({@code size - i}). A mutant flipping the subtraction would report a
     * fabricated remainder in the finding.
     */
    @Test
    void millionElementCollectionIsCappedWithTrueRemainder()
    {
        List<String> big = new ArrayList<>(1_000_002);
        for (int i = 0; i < 1_000_002; i++)
        {
            big.add("x");
        }
        String rendered = render(big);
        assertTrue(rendered.startsWith("[x, x, "), rendered.substring(0, 20));
        assertTrue(rendered.endsWith(", … (2 more)]"), rendered.substring(rendered.length() - 30));
    }


    /**
     * Opaque (non-String, non-Collection) values fall back to {@code toString()} bounded at 1024
     * chars — 1024 exactly stays intact (boundary), 1025 truncates with an ellipsis.
     */
    @Test
    void opaqueToStringIsBoundedAt1024()
    {
        Object at1024 = new Object()
        {

            @Override
            public String toString()
            {
                return "o".repeat(1024);
            }
        };
        assertEquals("o".repeat(1024), render(at1024));
        Object over = new Object()
        {

            @Override
            public String toString()
            {
                return "p".repeat(1025);
            }
        };
        assertEquals("p".repeat(1024) + "…", render(over));
    }

    // -----------------------------------------------------------------------
    // extractOutputValues — joined-dataset and virtual-name resolution
    // -----------------------------------------------------------------------


    private static JoinLookup lookupReturning(String columnName, String value, boolean hasColumn)
    {
        return new JoinLookup()
        {

            @Override
            public String lookup(IDataTable primaryTable, long row, String col)
            {
                return columnName.equals(col) ? value : null;
            }


            @Override
            public boolean hasColumn(IDataTable primaryTable, long row, String col)
            {
                return hasColumn && columnName.equals(col);
            }


            @Override
            public String getDatasetName()
            {
                return "DM";
            }
        };
    }


    /**
     * A dot-qualified joined output variable: present-with-value reports the value;
     * present-but-missing (lookup null, column exists) reports empty; column absent from the merged
     * frame is omitted entirely (Python merged-frame parity). All three arms of the
     * {@code val == null && !hasColumn} gate — a negation of either conjunct swaps "omitted" and
     * "empty", silently changing what the sponsor sees.
     */
    @Test
    void dotQualifiedJoinedValuePresentMissingAndAbsent()
    {
        IDataTable t = MockTable.of().name("AE").col("AETERM", "x").build();
        EvaluationContext present = EvaluationContext.builder().table(t)
                .joinedDatasets(Map.of("DM", lookupReturning("ARM", "PLACEBO", true))).build();
        assertEquals(Map.of("DM.ARM", "PLACEBO"), extract(t, present, List.of("DM.ARM"), 0));

        // The lookup only knows OTHER, so ARM is absent from the merged frame entirely.
        EvaluationContext missingValue = EvaluationContext.builder().table(t)
                .joinedDatasets(Map.of("DM", lookupReturning("OTHER", "v", true))).build();
        assertEquals(Map.of(), extract(t, missingValue, List.of("DM.ARM"), 0),
                "a column absent from the merged frame is omitted");

        JoinLookup nullValueButPresent = new JoinLookup()
        {

            @Override
            public String lookup(IDataTable primaryTable, long row, String col)
            {
                return null;
            }


            @Override
            public String getDatasetName()
            {
                return "DM";
            }
        };
        EvaluationContext presentButMissing = EvaluationContext.builder().table(t)
                .joinedDatasets(Map.of("DM", nullValueButPresent)).build();
        assertEquals(Map.of("DM.ARM", ""), extract(t, presentButMissing, List.of("DM.ARM"), 0),
                "present-but-missing keeps the key with an empty value");
    }


    /**
     * An UNqualified name that only exists in a joined dataset resolves through the joined-lookup
     * fallback (e.g. RFSTDTC referenced without the DM. prefix); an unresolvable name is omitted,
     * never emitted as a null-valued key.
     */
    @Test
    void unqualifiedJoinedFallbackResolvesAndUnresolvedIsOmitted()
    {
        IDataTable t = MockTable.of().name("AE").col("AETERM", "x").build();
        EvaluationContext ctx = EvaluationContext.builder().table(t)
                .joinedDatasets(Map.of("DM", lookupReturning("RFSTDTC", "2020-01-01", true)))
                .build();
        Map<String, String> out = extract(t, ctx, List.of("RFSTDTC", "NOSUCH"), 0);
        assertEquals("2020-01-01", out.get("RFSTDTC"));
        assertFalse(out.containsKey("NOSUCH"), "unresolved entries are omitted");
        assertEquals(1, out.size());
    }


    /**
     * The {@code Fix #18} arm: a dot-qualified name with NO joined dataset reports its own
     * identifier — but only for a per-variable ({VAR}) evaluation domain; a row-domain rule keeps
     * the pre-leaf-scope omission. Negating the domain test swaps the two, fabricating an output on
     * every row rule that joins nothing.
     */
    @Test
    void dotQualifiedWithoutJoinReportsIdentifierOnlyOnVariableDomain()
    {
        IDataTable t = MockTable.of().name("AE").col("AETERM", "x").build();
        EvaluationContext varDomain = EvaluationContext.builder().table(t)
                .evaluationDomain(net.cumba.cdisc.core.expr.eval.Domain.VARIABLE).build();
        assertEquals(Map.of("SUPPAE.QNAM", "SUPPAE.QNAM"),
                extract(t, varDomain, List.of("SUPPAE.QNAM"), 0));

        EvaluationContext rowDomain = EvaluationContext.builder().table(t)
                .evaluationDomain(net.cumba.cdisc.core.expr.eval.Domain.ROW).build();
        assertEquals(Map.of(), extract(t, rowDomain, List.of("SUPPAE.QNAM"), 0));
    }


    /**
     * A column whose name begins with a dot is NOT a dataset-qualified reference ({@code dotIdx >
     * 0}, strictly): it resolves through the plain column lookup. The boundary mutant would send it
     * down the join branch and drop it from the finding.
     */
    @Test
    void leadingDotNameResolvesAsPlainColumn()
    {
        IDataTable t = MockTable.of().name("AE").col(".X", "val").build();
        assertEquals(Map.of(".X", "val"), extract(t, ctx(t), List.of(".X"), 0));
    }


    /**
     * Plain column projection: a present cell reports its value, a missing cell reports empty, and
     * a dataset-level violation row beyond the row count reports empty rather than reading a
     * non-existent row.
     */
    @Test
    void plainColumnPresentMissingAndBeyondRowCount()
    {
        IDataTable t = MockTable.of().name("AE").col("AESEV", "MILD", "").build();
        assertEquals(Map.of("AESEV", "MILD"), extract(t, ctx(t), List.of("AESEV"), 0));
        assertEquals(Map.of("AESEV", ""), extract(t, ctx(t), List.of("AESEV"), 1));
        assertEquals(Map.of("AESEV", ""), extract(t, ctx(t), List.of("AESEV"), 2),
                "beyond-row-count reads must not dereference a row");
    }


    /**
     * EC-36 wildcard resolution of Output_Variables: an unqualified {@code --} entry takes the
     * VARIABLE prefix, and a dot-qualified entry splits — dataset half on the domain code, variable
     * half on the variable prefix. The AP family is where the two differ (APMH holds MHTERM), so a
     * mutant collapsing the split names a different dataset or column than the Check actually read.
     */
    @Test
    void wildcardOutputVariablesSplitDatasetAndVariablePrefixes()
    {
        IDataTable t = MockTable.of().name("APMH").col("MHTERM", "v").build();
        JoinLookup supp = new JoinLookup()
        {

            @Override
            public String lookup(IDataTable primaryTable, long row, String col)
            {
                return "QVAL".equals(col) ? "joined" : null;
            }


            @Override
            public String getDatasetName()
            {
                return "SUPPAPMH";
            }
        };
        EvaluationContext ctx = EvaluationContext.builder().table(t).variableWildcardPrefix("MH")
                .domainPrefix("APMH").joinedDatasets(Map.of("SUPPAPMH", supp)).build();

        Map<String, String> out = extract(t, ctx, List.of("--TERM", "SUPP--.QVAL"), 0);
        assertEquals("v", out.get("MHTERM"), "unqualified -- takes the variable prefix");
        assertEquals("joined", out.get("SUPPAPMH.QVAL"),
                "the dataset half of a dotted entry takes the domain code");
        assertEquals(2, out.size());
    }

    // -----------------------------------------------------------------------
    // readRowIdentity — column-index-zero boundaries
    // -----------------------------------------------------------------------


    /**
     * The identity columns are honoured at column index 0. All three index comparisons are
     * boundary-sensitive ({@code < 0} / {@code >= 0}); a mutant turns a first-column AESEQ or a
     * first-column USUBJID into "absent" and strips the identity off every finding of that dataset.
     */
    @Test
    void identityColumnsAtIndexZeroAreRead()
    {
        IDataTable seqFirst = MockTable.of().name("AE").col("AESEQ", "7").col("USUBJID", "S1")
                .build();
        RuleRunner.RowIdentity ri = RuleRunner.readRowIdentity(seqFirst, "AE", 0);
        assertEquals("S1", ri.usubjid());
        assertEquals("7", ri.seq(), "AESEQ at column 0 must be read, not re-probed as ASEQ");

        IDataTable seqOnlyFirst = MockTable.of().name("TS").col("TSSEQ", "3").col("TSPARMCD", "X")
                .build();
        RuleRunner.RowIdentity seqOnly = RuleRunner.readRowIdentity(seqOnlyFirst, "TS", 0);
        assertNull(seqOnly.usubjid());
        assertEquals("3", seqOnly.seq(), "a seq column at index 0 alone is still an identity");
    }

    // -----------------------------------------------------------------------
    // buildJoinedDatasets — result keying
    // -----------------------------------------------------------------------


    /**
     * A wildcard Match_Datasets name is registered under its RESOLVED name (SUPP-- on an AE primary
     * → SUPPAE) so the Check's dotted reads find it; a keyless entry builds nothing. The 4-arg
     * overload must delegate, not return empty — a mutant there silently drops every join of every
     * rule executed through it.
     */
    @Test
    void joinedDatasetsAreKeyedByResolvedWildcardName()
    {
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1").build();
        IDataTable suppae = MockTable.of().name("SUPPAE").col("USUBJID", "S1").col("QVAL", "yes")
                .build();
        MatchDataset md = new MatchDataset();
        md.setName("SUPP--");
        md.setKeys(List.of("USUBJID"));

        Map<String, JoinLookup> joins = RuleRunner.buildJoinedDatasets(List.of(md), ae,
                name -> "SUPPAE".equals(name) ? suppae : null, null);
        assertEquals(List.of("SUPPAE"), List.copyOf(joins.keySet()),
                "wildcard names register under the resolved dataset name");
        assertEquals("yes", joins.get("SUPPAE").lookup(ae, 0, "QVAL"));

        MatchDataset nonWildcard = new MatchDataset();
        nonWildcard.setName("SUPPAE");
        nonWildcard.setKeys(List.of("USUBJID"));
        Map<String, JoinLookup> plain = RuleRunner.buildJoinedDatasets(List.of(nonWildcard), ae,
                name -> "SUPPAE".equals(name) ? suppae : null, null);
        assertEquals(List.of("SUPPAE"), List.copyOf(plain.keySet()));

        MatchDataset keyless = new MatchDataset();
        keyless.setName("SUPPAE");
        Map<String, JoinLookup> none = RuleRunner.buildJoinedDatasets(List.of(keyless), ae,
                name -> "SUPPAE".equals(name) ? suppae : null, null);
        assertTrue(none.isEmpty(), "a keyless Match_Dataset has no usable join");
    }

    // -----------------------------------------------------------------------
    // collectCheckLeafColumns — any / not recursion
    // -----------------------------------------------------------------------


    /**
     * The Output_Variables inference must descend {@code any} and {@code not} branches, not just
     * {@code all} — a rule with no authored Output_Variables whose Check is a disjunction would
     * otherwise report findings with NO values at all.
     */
    @Test
    void inferenceDescendsAnyAndNotBranches()
    {
        var anyCheck = new net.cumba.cdisc.core.model.CheckConditionAny(
                List.of(net.cumba.cdisc.core.model.CheckConditionLeaf.builder().name("AESEV")
                        .operator("non_empty").build()));
        var meta = MockTable.of().name("AE").col("AESEV", "x").build().getMetaData();
        assertEquals(List.of("AESEV"),
                List.copyOf(RuleRunner.collectCheckLeafColumns(anyCheck, meta)));

        var notCheck = new net.cumba.cdisc.core.model.CheckConditionNot(
                net.cumba.cdisc.core.model.CheckConditionLeaf.builder().name("AESEV")
                        .operator("non_empty").build());
        assertEquals(List.of("AESEV"),
                List.copyOf(RuleRunner.collectCheckLeafColumns(notCheck, meta)));
    }

}
