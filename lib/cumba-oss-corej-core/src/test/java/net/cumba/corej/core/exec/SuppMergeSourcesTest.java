package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.merge.MergeKind;
import net.cumba.datatable.merge.MergedPart;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-split-supp-resolution} C19: the one chooser of SUPP sources
 * ({@link SplitDomainResolution#suppMergeSources}, D4 (b)) at every choosing site — the merged
 * view's primary and keyed partners, the SUPP-qualifier key refusal, the merged-schema QNAM set and
 * the scope gate's probes — under Q5 (b), D11 and D12:
 *
 * <ul>
 * <li>a split member is fed its own SUPP once ({@code LBCH} ⇐ {@code supplbch}, never the sibling's
 * — no case-2 duplicate on the PhUSE pilot);</li>
 * <li>an unsplit base is fed the whole family ({@code AE} ⇐ {@code SUPPAE1} ∪ {@code SUPPAE2},
 * {@code APMH} ⇐ {@code SQAPMH1} ∪ {@code SQAPMH2}); a size-split base its own member only;</li>
 * <li>an exact {@code SUPPAE} beside {@code SUPPAE1} is fed once each (D11: the callback answers a
 * chosen name from the engine's map, never the seam's union);</li>
 * <li>a keyed partner resolving as a split union contains every member and takes the whole family
 * (D12); a keyed partner that is one member takes its own SUPP;</li>
 * <li>what an existence surface reports is what the view delivers: the QNAM set equals the view's
 * SUPP columns, {@code var_exists("LBCH.X")} with X only in {@code SUPPLBHE} is false (PG-H3);</li>
 * <li>no member is fed twice (the guard); an {@code Invalid} family is MERGE_REFUSED (D3); the
 * confirmation look's F1 (a colliding keyed partner is refused), F2 (subject-level rows on a union
 * partner) and F3 (one member, two bases, two QNAM sets).</li>
 * </ul>
 */
class SuppMergeSourcesTest
{

    // ---------------------------------------------------------------- fixtures

    static IDataTable lb(String name, String[] subjects, long[] seqs)
    {
        Long[] boxed = java.util.Arrays.stream(seqs).boxed().toArray(Long[]::new);
        String[] domain = new String[seqs.length];
        java.util.Arrays.fill(domain, "LB");
        String[] study = new String[seqs.length];
        java.util.Arrays.fill(study, "S1");
        return RealTables.of(name).str("STUDYID", study).str("DOMAIN", domain)
                .str("USUBJID", subjects).lng("LBSEQ", boxed).build();
    }


    /** One record-level SUPP row keyed by {@code <idvar> = <idvarval>}. */
    static IDataTable supp(String name, String rdomain, String keyColumn, String subject,
            String idvar, String idvarval, String qnam, String qval)
    {
        return RealTables.of(name).str("STUDYID", "S1").str("RDOMAIN", rdomain)
                .str(keyColumn, subject).str("IDVAR", idvar).str("IDVARVAL", idvarval)
                .str("QNAM", qnam).str("QVAL", qval).build();
    }


    /** The PhUSE shape: lower-case split LB, each member with its own SUPP, no SUPPLB. */
    static List<IDataTable> phuse()
    {
        return List.of(lb("lbch", new String[]
        {
                "U1", "U2"
        }, new long[]
        {
                1, 2
        }), lb("lbhe", new String[]
        {
                "U1"
        }, new long[]
        {
                3
        }), lb("lbur", new String[]
        {
                "U2"
        }, new long[]
        {
                4
        }), supp("supplbch", "LB", "USUBJID", "U1", "LBSEQ", "1", "LBCHX", "a"),
                supp("supplbhe", "LB", "USUBJID", "U1", "LBSEQ", "3", "LBHEX", "b"),
                supp("supplbur", "LB", "USUBJID", "U2", "LBSEQ", "4", "LBURX", "c"));
    }


    static DatasetResolver.WithInventory inventory(List<IDataTable> tables, IDataTable... more)
    {
        List<IDataTable> all = new ArrayList<>(tables);
        all.addAll(List.of(more));
        return RealTables.inventoryOf(all.toArray(IDataTable[]::new));
    }


    static IDataTable ae()
    {
        return RealTables.of("AE").str("STUDYID", "S1", "S1").str("DOMAIN", "AE", "AE")
                .str("USUBJID", "001", "002").lng("AESEQ", 1L, 1L).str("AETERM", "A", "B").build();
    }


    static IDataTable suppae1()
    {
        return supp("SUPPAE1", "AE", "USUBJID", "001", "AESEQ", "1", "AETRTEM", "Y");
    }


    static IDataTable suppae2()
    {
        return supp("SUPPAE2", "AE", "USUBJID", "002", "AESEQ", "1", "AETRTEM", "N");
    }


    static Rule load(String ruleJson)
    {
        try
        {
            RulePackage pkg = RulePackageLoader
                    .loadFromString(net.cumba.corej.core.KeyedJoinFixtures
                            .declared("{\"rules\":{\"X-1\":" + ruleJson + "}}"));
            Rule rule = pkg.getRules().get("X-1");
            assertNull(rule.getLoadError(), "fixture must load: " + rule.getLoadError());
            return rule;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + ruleJson, e);
        }
    }


    static Rule rule(String extra, String check, String... outputVariables)
    {
        StringBuilder ov = new StringBuilder();
        for (String v : outputVariables)
        {
            ov.append(ov.length() == 0 ? "" : ",").append('"').append(v).append('"');
        }
        return load("{\"Core\":{\"Id\":\"X-1\"}," + extra + "\"Check\":{\"expression\":\"" + check
                + "\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[" + ov + "]}}");
    }


    static RuleExecutionResult run(Rule rule, IDataTable primary, DatasetResolver inv)
    {
        return RuleRunnerCalls.execute(rule, primary, inv, DatasetIdentity.domainPrefix(primary));
    }


    static List<String> names(List<IDataTable> tables)
    {
        List<String> out = new ArrayList<>();
        for (IDataTable t : tables)
        {
            out.add(t.getMetaData().getName());
        }
        return out;
    }


    /** The chooser's answer for a base, as member names. */
    static List<String> chosen(DatasetResolver inv, IDataTable base)
    {
        String name = base.getMetaData().getName();
        String domain = DatasetIdentity.domainOfDataset(name, inv);
        assertNotNull(domain);
        return names(SplitDomainResolution.suppMergeSources(inv, base, name, domain, "R"));
    }


    /**
     * The view of {@code rule} on {@code primary}, with the guard: every SUPP part's sources are
     * distinct, and the primary's QNAM set equals the existence surface's answer.
     */
    static MergedEvaluation.Built view(Rule rule, IDataTable primary, DatasetResolver inv)
    {
        JoinCache.SharedIndexCache shared = new JoinCache.SharedIndexCache();
        MergedEvaluation.Built built = MergedEvaluation.build(rule, primary, primary, inv,
                new JoinCache(shared), "R", DatasetIdentity.domainPrefix(primary));
        assertNotNull(built, "a view is built");
        for (MergedPart part : built.view().parts())
        {
            if (part.kind() == MergeKind.SUPP)
            {
                List<String> sources = List.of(part.source().split("\\+"));
                assertEquals(new LinkedHashSet<>(sources).size(), sources.size(),
                        "a member fed twice: " + part.source());
            }
        }
        assertEquals(built.suppQnams(), SuppQualifiers.of(primary, inv, shared, "R"),
                "the existence surface's QNAM set must be the view's SUPP columns");
        return built;
    }


    /**
     * The sources of the SUPP part of {@code base} ({@code "A+B"} — the datatable names a stacked
     * part by its sources, joined with {@code +}), or {@code null} for none; the primary's part is
     * qualified by the primary's own name, a keyed partner's by the partner's.
     */
    static String suppSourcesOf(MergedEvaluation.Built built, String base)
    {
        for (MergedPart part : built.view().parts())
        {
            if (part.kind() == MergeKind.SUPP && base.equalsIgnoreCase(part.qualifier()))
            {
                return part.source();
            }
        }
        return null;
    }


    static String primarySuppSources(MergedEvaluation.Built built)
    {
        return suppSourcesOf(built, built.table().getMetaData().getName());
    }


    static String partnerSuppSources(MergedEvaluation.Built built, String partner)
    {
        return suppSourcesOf(built, partner);
    }

    // ---------------------------------------------------------------- the chooser (Q5, D12)


    @Test
    void aSplitMemberIsFedItsOwnSuppOnce_neverASiblings()
    {
        // the PhUSE pilot: zero movement — LBCH keeps exactly SUPPLBCH
        DatasetResolver.WithInventory inv = inventory(phuse());
        IDataTable lbch = inv.resolve("LBCH");
        assertEquals(List.of("supplbch"), chosen(inv, lbch));
        MergedEvaluation.Built built = view(rule("", "empty(LBCHX)"), lbch, inv);
        assertEquals("supplbch", primarySuppSources(built));
        assertEquals(Set.of("LBCHX"), built.suppQnams());
        RuleExecutionResult res = run(rule("", "empty(LBCHX)", "LBCHX"), lbch, inv);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), res.getStatusMessage());
        assertEquals(1, res.getViolations().size(), "U2's record carries no qualifier");
        // the gate's probes agree with the view
        ScopeVariableSource src = ScopeVariableSource.of(inv, lbch, true);
        assertNotNull(src);
        assertTrue(src.localQualifier("LBCHX"));
        assertFalse(src.localQualifier("LBHEX"), "a sibling's qualifier is not LBCH's");
        assertEquals(DataValueType.STRING, src.suppQvalType("LBCH"));
    }


    @Test
    void anUnsplitBaseIsFedTheWholeFamily()
    {
        DatasetResolver.WithInventory inv = RealTables.inventoryOf(ae(), suppae1(), suppae2());
        IDataTable ae = inv.resolve("AE");
        assertEquals(List.of("SUPPAE1", "SUPPAE2"), chosen(inv, ae));
        MergedEvaluation.Built built = view(rule("", "empty(AETRTEM)"), ae, inv);
        assertEquals("SUPPAE1+SUPPAE2", primarySuppSources(built));
        RuleExecutionResult res = run(rule("", "AETRTEM == \\\"N\\\"", "AETRTEM"), ae, inv);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), res.getStatusMessage());
        assertEquals(1, res.getViolations().size(), "subject 002's flag comes from SUPPAE2");
        assertEquals(2L, res.getViolations().get(0).getRowNumber(), "the second row, 1-based");
        // the AP twin
        IDataTable apmh = RealTables.of("APMH").str("STUDYID", "S1", "S1")
                .str("DOMAIN", "APMH", "APMH").str("APID", "A1", "A2").lng("MHSEQ", 1L, 1L)
                .str("MHTERM", "x", "y").build();
        DatasetResolver.WithInventory ap = RealTables.inventoryOf(apmh,
                supp("SQAPMH1", "APMH", "APID", "A1", "MHSEQ", "1", "MHTRTEM", "Y"),
                supp("SQAPMH2", "APMH", "APID", "A2", "MHSEQ", "1", "MHTRTEM", "N"));
        assertEquals(List.of("SQAPMH1", "SQAPMH2"), chosen(ap, apmh));
        assertEquals("SQAPMH1+SQAPMH2",
                primarySuppSources(view(rule("", "empty(MHTRTEM)"), apmh, ap)));
    }


    @Test
    void aSizeSplitBaseIsFedItsOwnMemberOnly()
    {
        IDataTable ae1 = RealTables.of("AE1").str("STUDYID", "S1").str("DOMAIN", "AE")
                .str("USUBJID", "001").lng("AESEQ", 1L).build();
        IDataTable ae2 = RealTables.of("AE2").str("STUDYID", "S1").str("DOMAIN", "AE")
                .str("USUBJID", "002").lng("AESEQ", 1L).build();
        DatasetResolver.WithInventory inv = RealTables.inventoryOf(ae1, ae2, suppae1(), suppae2());
        assertEquals(List.of("SUPPAE1"), chosen(inv, ae1), "Q5's own example");
        assertEquals(List.of("SUPPAE2"), chosen(inv, ae2));
        assertEquals("SUPPAE1", primarySuppSources(view(rule("", "empty(AETRTEM)"), ae1, inv)));
    }


    @Test
    void anExactSuppBesideASplitMemberIsFedOnceEach()
    {
        // D11: the seam answers SUPPAE with the union of both — fed as member names, each once
        IDataTable exact = supp("SUPPAE", "AE", "USUBJID", "001", "AESEQ", "1", "AEX", "e");
        DatasetResolver.WithInventory inv = RealTables.inventoryOf(ae(), exact, suppae2());
        IDataTable ae = inv.resolve("AE");
        assertEquals(List.of("SUPPAE", "SUPPAE2"), chosen(inv, ae));
        MergedEvaluation.Built built = view(rule("", "empty(AETRTEM)"), ae, inv);
        assertEquals("SUPPAE+SUPPAE2", primarySuppSources(built));
        assertEquals(Set.of("AEX", "AETRTEM"), built.suppQnams());
        RuleExecutionResult res = run(rule("", "empty(AEX) and empty(AETRTEM)"), ae, inv);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), res.getStatusMessage());
        assertEquals(List.of(), res.getViolations(), "each record has one qualifier, no duplicate");
    }

    // ---------------------------------------------------------------- keyed partners (MQ5, D12)


    static IDataTable adlb()
    {
        return RealTables.of("ADLB").str("STUDYID", "S1", "S1", "S1")
                .str("USUBJID", "U1", "U1", "U2").lng("LBSEQ", 1L, 3L, 4L).build();
    }


    static String keyed(String name)
    {
        return "\"Match_Datasets\":[{\"Name\":\"" + name
                + "\",\"Keys\":[\"USUBJID\",\"LBSEQ\"],\"Join_Type\":\"left\"}],";
    }


    @Test
    void aKeyedPartnerThatIsOneMemberTakesItsOwnSupp_once()
    {
        // PG-H3: a keyed partner exactly LBCH is fed SUPPLBCH once — no MERGE_ERROR on LBCH.X
        DatasetResolver.WithInventory inv = inventory(phuse(), adlb());
        MergedEvaluation.Built built = view(rule(keyed("LBCH"), "empty(LBCH.LBCHX)"),
                inv.resolve("ADLB"), inv);
        assertEquals("supplbch", partnerSuppSources(built, "LBCH"));
        RuleExecutionResult res = run(rule(keyed("LBCH"), "not empty(STUDYID)", "LBCH.LBCHX"),
                inv.resolve("ADLB"), inv);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), res.getStatusMessage());
        assertEquals("a", res.getViolations().get(0).getValues().get("LBCH.LBCHX"));
    }


    @Test
    void aKeyedPartnerResolvingAsTheSplitUnionTakesTheWholeFamily()
    {
        // D12: LB on lbch / lbhe / lbur contains every member — every SUPP of the family
        DatasetResolver.WithInventory inv = inventory(phuse(), adlb());
        MergedEvaluation.Built built = view(rule(keyed("LB"), "empty(LB.LBCHX)"),
                inv.resolve("ADLB"), inv);
        assertEquals("supplbch+supplbhe+supplbur", partnerSuppSources(built, "LB"));
        RuleExecutionResult res = run(
                rule(keyed("LB"), "not empty(STUDYID)", "LB.LBCHX", "LB.LBHEX", "LB.LBURX"),
                inv.resolve("ADLB"), inv);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), res.getStatusMessage());
        assertEquals(3, res.getViolations().size());
        assertEquals("a", res.getViolations().get(0).getValues().get("LB.LBCHX"),
                "U1 / LBSEQ 1 ← lbch");
        assertEquals("b", res.getViolations().get(1).getValues().get("LB.LBHEX"),
                "U1 / LBSEQ 3 ← lbhe");
        assertEquals("c", res.getViolations().get(2).getValues().get("LB.LBURX"),
                "U2 / LBSEQ 4 ← lbur");
    }


    @Test
    void dottedVarExistsOnAMemberSeesOnlyItsOwnSupp()
    {
        // PG-H3: var_exists("LBCH.LBHEX") — LBHEX qualifies lbhe, never lbch
        DatasetResolver.WithInventory inv = inventory(phuse(),
                RealTables.of("DM").str("STUDYID", "S1").str("USUBJID", "U1").build());
        IDataTable dm = inv.resolve("DM");
        RuleExecutionResult sibling = run(rule("", "var_exists(\\\"LBCH.LBHEX\\\")"), dm, inv);
        assertEquals(RuleExecutionStatus.EXECUTED, sibling.getStatus(), sibling.getStatusMessage());
        assertEquals(List.of(), sibling.getViolations(), "the view of LBCH has no LBHEX column");
        RuleExecutionResult own = run(rule("", "var_exists(\\\"LBCH.LBCHX\\\")"), dm, inv);
        assertEquals(1, own.getViolations().size(), "control: its own qualifier is there");
        RuleExecutionResult family = run(rule("", "var_exists(\\\"LB.LBHEX\\\")"), dm, inv);
        assertEquals(1, family.getViolations().size(),
                "the family's union base LB delivers every member's qualifier");
    }


    @Test
    void theSuppQualifierKeyRefusalSeesExactlyTheFedMembers()
    {
        DatasetResolver.WithInventory inv = inventory(phuse(), RealTables.of("DM")
                .str("STUDYID", "S1").str("USUBJID", "U1").str("LBCHX", "a").build());
        IDataTable lbch = inv.resolve("LBCH");
        String join = "\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"%s\"],\"Join_Type\":\"left\"}],";
        RuleExecutionResult refused = run(rule(join.formatted("LBCHX"), "empty(DM.USUBJID)"), lbch,
                inv);
        assertEquals(RuleExecutionStatus.ERROR, refused.getStatus());
        assertTrue(refused.getStatusMessage().contains("qualifier (QNAM) of supplbch"),
                refused.getStatusMessage());
        RuleExecutionResult notFed = run(rule(join.formatted("LBHEX"), "empty(DM.USUBJID)"), lbch,
                inv);
        assertFalse(String.valueOf(notFed.getStatusMessage()).contains("supplbhe"),
                "a sibling's SUPP is not a source of LBCH: " + notFed.getStatusMessage());
    }

    // ---------------------------------------------------------------- D3, F1, F2, F3


    @Test
    void anInvalidFamilyIsMergeRefused()
    {
        IDataTable numeric = RealTables.of("SUPPAE2").str("STUDYID", "S1").str("RDOMAIN", "AE")
                .str("USUBJID", "002").str("IDVAR", "AESEQ").str("IDVARVAL", "1")
                .str("QNAM", "AETRTEM").lng("QVAL", 1L).build();
        DatasetResolver.WithInventory inv = RealTables.inventoryOf(ae(), suppae1(), numeric);
        IDataTable ae = inv.resolve("AE");
        assertEquals(List.of("SUPPAE1", "SUPPAE2"), chosen(inv, ae), "the chooser reads members");
        RuleExecutionResult res = run(rule("", "empty(AETERM)"), ae, inv);
        assertEquals(RuleExecutionStatus.ERROR, res.getStatus());
        assertTrue(res.getStatusMessage().startsWith(MergedEvaluation.MERGE_REFUSED_MESSAGE_PREFIX),
                res.getStatusMessage());
        assertTrue(res.getStatusMessage().contains("QVAL"), res.getStatusMessage());
    }


    @Test
    void aKeyedPartnerNamedLikeAChosenMemberIsRefused_neverASilentDrop()
    {
        // F1: exact SUPPAE + SUPPAE1, a non-Child keyed SUPPAE on AE — the key gates checked the
        // family union, the callback would hand the exact member: refused loudly
        IDataTable exact = supp("SUPPAE", "AE", "USUBJID", "001", "AESEQ", "1", "AEX", "e");
        DatasetResolver.WithInventory inv = RealTables.inventoryOf(ae(), exact, suppae2());
        IDataTable ae = inv.resolve("AE");
        String join = "\"Match_Datasets\":[{\"Name\":\"SUPPAE\",\"Keys\":[\"USUBJID\"],\"Join_Type\":\"left\"}],";
        RuleExecutionResult res = run(rule(join, "empty(SUPPAE.QVAL)"), ae, inv);
        assertEquals(RuleExecutionStatus.ERROR, res.getStatus(), res.getStatusMessage());
        assertTrue(res.getStatusMessage().startsWith(MergedEvaluation.MERGE_REFUSED_MESSAGE_PREFIX),
                res.getStatusMessage());
        assertTrue(res.getStatusMessage().contains("SUPPAE"), res.getStatusMessage());
        // the same join with Supp_Merge off: no chosen member, the family union is the partner
        RuleExecutionResult off = run(rule(join + "\"Supp_Merge\":false,", "empty(SUPPAE.QVAL)"),
                ae, inv);
        assertEquals(RuleExecutionStatus.EXECUTED, off.getStatus(), off.getStatusMessage());
        assertEquals(List.of(), off.getViolations(), "both members' rows reach the join");
    }


    @Test
    void aSubjectLevelQualifierOfOneMemberQualifiesEveryMembersRowsOnAUnionPartner()
    {
        // F2: a subject-level row (empty IDVAR) of supplbch carries RDOMAIN = LB and qualifies U1's
        // rows from lbch AND lbhe on the union partner LB (IG-conform: the qualifier is the
        // domain's)
        IDataTable subjectLevel = RealTables.of("supplbch").str("STUDYID", "S1")
                .str("RDOMAIN", "LB").str("USUBJID", "U1").str("IDVAR", "").str("IDVARVAL", "")
                .str("QNAM", "SUBJX").str("QVAL", "s").build();
        List<IDataTable> tables = new ArrayList<>(phuse().subList(0, 3));
        tables.add(subjectLevel);
        DatasetResolver.WithInventory inv = inventory(tables, adlb());
        RuleExecutionResult res = run(rule(keyed("LB"), "not empty(STUDYID)", "LB.SUBJX"),
                inv.resolve("ADLB"), inv);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), res.getStatusMessage());
        assertEquals("s", res.getViolations().get(0).getValues().get("LB.SUBJX"), "U1 ← lbch");
        assertEquals("s", res.getViolations().get(1).getValues().get("LB.SUBJX"), "U1 ← lbhe");
        // the same subject-level QNAM in two members: a case-2 duplicate, a loud MERGE_ERROR
        IDataTable twin = RealTables.of("supplbhe").str("STUDYID", "S1").str("RDOMAIN", "LB")
                .str("USUBJID", "U1").str("IDVAR", "").str("IDVARVAL", "").str("QNAM", "SUBJX")
                .str("QVAL", "t").build();
        tables.add(twin);
        DatasetResolver.WithInventory dup = inventory(tables, adlb());
        RuleExecutionResult error = run(rule(keyed("LB"), "not empty(STUDYID)", "LB.SUBJX"),
                dup.resolve("ADLB"), dup);
        assertEquals(RuleExecutionStatus.ERROR, error.getStatus(), error.getStatusMessage());
        assertTrue(error.getStatusMessage().contains("LB.SUBJX"), error.getStatusMessage());
    }


    @Test
    void oneMemberPairedWithTwoBasesAnswersOneQnamSet_builtOnce()
    {
        // F3 of the confirmation look, measured FALSE at cumba-datatable 1d5c6d3: the merged
        // schema's QNAM set is every QNAM the member carries "whether or not it became a column"
        // (MergedTable.suppQnams) — AE2 owns AETRTEM as a column (P-10 skips the qualifier column)
        // and still lists it, so the set does not depend on the base and the memo stays keyed by
        // the member: one build serves both bases. The dotted existence of AE2.AETRTEM is its own
        // column's anyway, probed before the pivot.
        IDataTable ae1 = RealTables.of("AE1").str("STUDYID", "S1").str("DOMAIN", "AE")
                .str("USUBJID", "001").build();
        IDataTable ae2 = RealTables.of("AE2").str("STUDYID", "S1").str("DOMAIN", "AE")
                .str("USUBJID", "002").str("AETRTEM", "own").build();
        IDataTable shared = RealTables.of("SUPPAE").str("STUDYID", "S1", "S1")
                .str("RDOMAIN", "AE", "AE").str("USUBJID", "001", "002").str("IDVAR", "", "")
                .str("IDVARVAL", "", "").str("QNAM", "AETRTEM", "AETRTEM").str("QVAL", "Y", "N")
                .build();
        DatasetResolver.WithInventory inv = RealTables.inventoryOf(ae1, ae2, shared);
        JoinCache.SharedIndexCache cache = new JoinCache.SharedIndexCache();
        assertEquals(List.of("SUPPAE"), chosen(inv, ae1),
                "the exact member of the family, fed to both");
        assertEquals(List.of("SUPPAE"), chosen(inv, ae2));
        assertEquals(Set.of("AETRTEM"), SuppQualifiers.of(ae1, inv, cache, "R"));
        assertEquals(Set.of("AETRTEM"), SuppQualifiers.of(ae2, inv, cache, "R"),
                "the existence set lists the QNAM AE2 owns as a column too (P-10 skips the column)");
        assertEquals(1, cache.suppQnamsBuildCount(), "one build per member on the shared cache");
        MergedEvaluation.Built built = view(rule("", "empty(AETRTEM)"), ae2, inv);
        assertEquals("own", built.view().getDataValue(0, built.view().getColumnIndex("AETRTEM"))
                .getValueAsString(), "P-10: the base's own column wins in the view");
    }

}
