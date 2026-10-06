package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.view.UnionDataTable;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-split-supp-resolution} C18: a split supplemental family — {@code SUPPAE1} +
 * {@code SUPPAE2} (both {@code RDOMAIN = AE}, no {@code SUPPAE}) and the SDTMIG-AP twin
 * {@code SQAPMH1} + {@code SQAPMH2} ({@code RDOMAIN = APMH}, no {@code SQAPMH}) — resolves under
 * its family name at <b>every</b> resolution site (Q1, Q3, Q7): one case per site of
 * {@code DatasetNameResolutionRosterTest}'s roster, run for both families, plus the D3 / D5 / D9 /
 * D10 shapes, the lower-case PhUSE members, an {@code Invalid} family, the bound (nothing else
 * walks) and the confirmation look's two-character cases.
 *
 * <p>
 * <b>Sensitivity arm.</b> {@link SplitDomainResolution#suppFamilyArmForTests} turns the family arm
 * off, which is the engine before this plan; every per-site case must then fail, and the count is
 * asserted — a case that stays green with the arm off would be testing nothing about the family.
 * </p>
 */
class SplitSuppFamilyResolutionTest
{

    @AfterEach
    void armOn()
    {
        SplitDomainResolution.suppFamilyArmForTests(true);
    }

    // ---------------------------------------------------------------- the two families

    /**
     * One supplemental family: the family name, the primary it qualifies, the qualifier every
     * member delivers, the subject key both sides carry, and fresh tables per call (the seam
     * memoises per resolver instance, so every case builds its own inventory).
     */
    record Family(String name, String primary, String qualifier, String key,
            Supplier<IDataTable> base, Supplier<IDataTable> member1, Supplier<IDataTable> member2)
    {

        DatasetResolver.WithInventory inventory(IDataTable... extra)
        {
            List<IDataTable> all = new ArrayList<>(
                    List.of(base.get(), member1.get(), member2.get()));
            all.addAll(List.of(extra));
            return RealTables.inventoryOf(all.toArray(IDataTable[]::new));
        }


        /** The two member names, upper case, in feeding order. */
        List<String> memberNames()
        {
            return List.of(name + "1", name + "2");
        }
    }

    static final Family SUPP = new Family("SUPPAE", "AE", "AETRTEM", "USUBJID",
            () -> RealTables.of("AE").str("STUDYID", "S1", "S1").str("DOMAIN", "AE", "AE")
                    .str("USUBJID", "001", "002").lng("AESEQ", 1L, 1L).str("AETERM", "A", "B")
                    .build(),
            () -> supp("SUPPAE1", "AE", "USUBJID", "001", "AESEQ", "AETRTEM", "Y"),
            () -> supp("SUPPAE2", "AE", "USUBJID", "002", "AESEQ", "AETRTEM", "N"));

    static final Family SQAP = new Family("SQAPMH", "APMH", "MHTRTEM", "APID",
            () -> RealTables.of("APMH").str("STUDYID", "S1", "S1").str("DOMAIN", "APMH", "APMH")
                    .str("APID", "A1", "A2").lng("MHSEQ", 1L, 1L).str("MHTERM", "A", "B").build(),
            () -> supp("SQAPMH1", "APMH", "APID", "A1", "MHSEQ", "MHTRTEM", "Y"),
            () -> supp("SQAPMH2", "APMH", "APID", "A2", "MHSEQ", "MHTRTEM", "N"));

    /** One supplemental row: STUDYID S1, the subject key, one QNAM / QVAL keyed by IDVAR = 1. */
    static IDataTable supp(String name, String rdomain, String key, String subject, String idvar,
            String qnam, String qval)
    {
        return RealTables.of(name).str("STUDYID", "S1").str("RDOMAIN", rdomain).str(key, subject)
                .str("IDVAR", idvar).str("IDVARVAL", "1").str("QNAM", qnam).str("QVAL", qval)
                .str("QNUM1", "1").build(); // QNUM1: `${*}` matches a digit run (the wildcard
                                            // sites)
    }

    // ---------------------------------------------------------------- rule helpers


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


    /** A rule with the given extra members (bindings, requirements, joins) and Check. */
    static Rule rule(String extra, String check)
    {
        return load("{\"Core\":{\"Id\":\"X-1\"}," + extra + "\"Check\":{\"expression\":\"" + check
                + "\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"STUDYID\"]}}");
    }


    static RuleExecutionResult run(Rule rule, IDataTable primary, DatasetResolver resolver)
    {
        return RuleRunnerCalls.execute(rule, primary, resolver,
                DatasetIdentity.domainPrefix(primary));
    }


    /** Runs {@code rule} on the family's primary over its inventory and asserts it executed. */
    static RuleExecutionResult executed(Family f, Rule rule, IDataTable... extra)
    {
        IDataTable base = f.base.get();
        List<IDataTable> others = new ArrayList<>(List.of(f.member1.get(), f.member2.get()));
        others.addAll(List.of(extra));
        others.add(base);
        RuleExecutionResult r = run(rule, base,
                RealTables.inventoryOf(others.toArray(IDataTable[]::new)));
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus(),
                () -> "must execute: " + r.getStatusMessage());
        return r;
    }


    static void noFinding(Family f, Rule rule)
    {
        RuleExecutionResult r = executed(f, rule);
        assertEquals(List.of(), r.getViolations(), "the family must have answered");
    }


    static String keyed(Family f, String name)
    {
        return "\"Match_Datasets\":[{\"Name\":\"" + name + "\",\"Keys\":[\"" + f.key
                + "\"],\"Join_Type\":\"left\"}],";
    }


    static ScopeVariableSource scope(Family f)
    {
        ScopeVariableSource src = ScopeVariableSource.of(f.inventory(), f.base.get(), true);
        assertNotNull(src);
        return src;
    }

    // ---------------------------------------------------------------- the per-site roster

    /** One resolution site and the assertion that holds only when the family resolves there. */
    record Site(String name, Consumer<Family> check)
    {
    }

    /**
     * One case per widening site of the roster ({@code DatasetNameResolutionRosterTest}), each
     * expressed through the surface a rule author reaches it by.
     */
    static final List<Site> SITES = List
            .of(new Site("SplitDomainResolution.resolve — the seam unions the family", f ->
            {
                IDataTable t = SplitDomainResolution.resolveTableOrThrow(f.inventory(), f.name,
                        "R");
                assertNotNull(t);
                assertEquals(2, t.getRowCount(), "both members' rows");
                assertInstanceOf(UnionDataTable.class, t);
                assertEquals(f.name, t.getMetaData().getName(),
                        "the union carries the family name");
            }), new Site("OperatorRegistry.existsAsDataset — ds_exists",
                    f -> noFinding(f, rule("", "not ds_exists(\\\"" + f.name + "\\\")"))),
                    new Site("GroupedAggregate.resolveTarget — record_count(domain=)",
                            f -> noFinding(f, rule(
                                    "\"Bindings\":[{\"name\":\"$n\",\"expression\":\"record_count(domain=\\\""
                                            + f.name + "\\\")\"}],",
                                    "$n != 2"))),
                    new Site("GroupedAggregate.resolveTarget — distinct(domain=)", f -> noFinding(f,
                            rule("\"Bindings\":[{\"name\":\"$q\",\"expression\":\"distinct(QNAM, domain=\\\""
                                    + f.name + "\\\")\"}],",
                                    "\\\"" + f.qualifier + "\\\" not in $q"))),
                    new Site("ReadValue — read_value(domain=)",
                            f -> noFinding(f, rule("", "read_value(QNAM, domain=\\\"" + f.name
                                    + "\\\", mode=\\\"FIRST\\\") != \\\"" + f.qualifier + "\\\""))),
                    new Site(
                            "OperatorRegistry.existsAsDottedDatasetColumn — var_exists(\"F.QNAM\")",
                            f -> noFinding(f,
                                    rule("", "not var_exists(\\\"" + f.name + ".QNAM\\\")"))),
                    new Site("OperatorRegistry.existsAsDottedFilter — var_exists(\"F.QNAM=Q\")",
                            f -> noFinding(f,
                                    rule("", "not var_exists(\\\"" + f.name + ".QNAM=" + f.qualifier
                                            + "\\\")"))),
                    new Site("OperatorRegistry.anyColumnMatches — var_exists(\"F.QNUM${*}\")",
                            f -> noFinding(f,
                                    rule("", "not var_exists(\\\"" + f.name + ".QNUM${*}\\\")"))),
                    new Site("ExprCompiler.readDataLevel — ds_name(\"F\", \"DATA\")",
                            f -> noFinding(f,
                                    rule("", "empty(ds_name(\\\"" + f.name
                                            + "\\\", \\\"DATA\\\"))"))),
                    new Site("ExprCompiler.readDataLevel — ds_domain(\"F\", \"DATA\")",
                            f -> noFinding(f,
                                    rule("", "empty(ds_domain(\\\"" + f.name
                                            + "\\\", \\\"DATA\\\"))"))),
                    new Site("RuleRunner.describeMissingRequiredDataset — Requirements.Datasets",
                            f -> executed(f,
                                    rule("\"Requirements\":{\"Datasets\":[\"" + f.name + "\"]},",
                                            "empty(STUDYID)"))),
                    new Site("ScopeVariableSource.resolveMetas — qualified Requirements.Variables",
                            f -> executed(f,
                                    rule("\"Requirements\":{\"Variables\":{\"All\":[\"" + f.name
                                            + ".QNAM\"]}},", "empty(STUDYID)"))),
                    new Site("ScopeVariableSource.localQualifier — bare Requirements.Variables",
                            f -> executed(f,
                                    rule("\"Requirements\":{\"Variables\":{\"All\":[\""
                                            + f.qualifier + "\"]}},", "empty(STUDYID)"))),
                    new Site("RuleRunner ${*} Output_Variables expansion", f ->
                    {
                        Rule r = load("{\"Core\":{\"Id\":\"X-1\"}," + keyed(f, f.name)
                                + "\"Check\":{\"expression\":\"not empty(STUDYID)\"},"
                                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"" + f.name
                                + ".QNUM${*}\"]}}");
                        RuleExecutionResult res = executed(f, r);
                        assertEquals(2, res.getViolations().size());
                        assertEquals("1",
                                res.getViolations().get(0).getValues().get(f.name + ".QNUM1"),
                                "the wildcard expands against the family union: "
                                        + res.getViolations().get(0).getValues());
                    }),
                    new Site("MergedEvaluation.keyedSpecs — a keyed Match_Datasets partner",
                            f -> noFinding(f,
                                    rule(keyed(f, f.name), "not " + f.name + "._matched_"))),
                    new Site(
                            "RuleRunner.foreignInventory — stage B binds the Filter against the family",
                            f ->
                            {
                                // stage B checks a Match_Datasets Filter's columns against the
                                // partner's columns
                                // through the foreign inventory: the family resolved, QNAM is a
                                // column of it
                                Rule r = rule("\"Match_Datasets\":[{\"Name\":\"" + f.name
                                        + "\",\"Keys\":[\"" + f.key
                                        + "\"],\"Join_Type\":\"left\",\"Filter\":\"QNAM == \\\""
                                        + f.qualifier + "\\\"\"}],",
                                        "not " + f.name + "._matched_");
                                noFinding(f, r);
                            }),
                    new Site("AbsentDatasetSkip — the family is a present dependency", f ->
                    {
                        Rule r = rule(keyed(f, f.name), "empty(" + f.name + ".QVAL)");
                        RuleExecutionResult res = RuleRunnerCalls.execute(r, f.base.get(),
                                f.inventory(), f.primary, null, null, null, Integer.MAX_VALUE, null,
                                null, null, Set.of(f.name));
                        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(),
                                res.getStatusMessage());
                    }),
                    new Site("ReferencedDatasetVariables.columnsOf — referenced_dataset_variables",
                            f ->
                            {
                                IDataTable dm = RealTables.of("DM").str("STUDYID", "S1", "S1")
                                        .str("REF", f.name, f.name).build();
                                Rule r = rule("",
                                        "\\\"QNAM\\\" not in referenced_dataset_variables(REF)");
                                RuleExecutionResult res = run(r, dm, RealTables.inventoryOf(dm,
                                        f.member1.get(), f.member2.get()));
                                assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(),
                                        res.getStatusMessage());
                                assertEquals(List.of(), res.getViolations());
                            }),
                    new Site("ScalarMetadataFunctions.crossDatasetVariableMetadata — domain=F",
                            f -> assertEquals("DOMAIN", crossMetadataFindings(f, f.name),
                                    "the first primary column the family's union lacks — never STUDYID, which the family labels")),
                    new Site("VariableMetadataResult.build — the var_*(dataset=) channel",
                            f -> assertEquals("QNAM",
                                    VariableMetadataResult
                                            .build(f.inventory(), f.name, "label", f.primary)
                                            .getForVariable("QNAM"))),
                    new Site("ScopeVariableSource.metasOf — the family's member metas",
                            f -> assertEquals(2, scope(f).metasOf(f.name).size())),
                    new Site(
                            "ScopeVariableSource.existsViaSuppQnam — the qualified SUPP-QNAM probe",
                            f -> assertTrue(scope(f).existsViaSuppQnam(f.primary, f.qualifier))),
                    new Site("ScopeVariableSource.localQualifier — the bare probe",
                            f -> assertTrue(scope(f).localQualifier(f.qualifier))),
                    new Site("ScopeVariableSource.suppQvalType — the typed probe",
                            f -> assertEquals(DataValueType.STRING,
                                    scope(f).suppQvalType(f.primary))),
                    new Site("GroupedAggregate.datasetName — domain=\"SUPP--\" (D6 / D10)",
                            f -> noFinding(f, rule(
                                    "\"Bindings\":[{\"name\":\"$n\",\"expression\":\"record_count(domain=\\\"SUPP--\\\")\"}],",
                                    "$n != 2"))),
                    new Site("GroupedAggregate.datasetName — domain=\"SQAP--\", the synonym (D10)",
                            f -> noFinding(f, rule(
                                    "\"Bindings\":[{\"name\":\"$n\",\"expression\":\"record_count(domain=\\\"SQAP--\\\")\"}],",
                                    "$n != 2"))),
                    new Site(
                            "RuleRunner.describeMissingRequiredDataset — Requirements.Datasets SUPP--",
                            f -> executed(f,
                                    rule("\"Requirements\":{\"Datasets\":[\"SUPP--\"]},",
                                            "empty(STUDYID)"))),
                    new Site(
                            "RuleSpecialiser.resolveMatchDataset — non-Child Match_Datasets SUPP--",
                            f ->
                            {
                                // a `--` dataset half has no native form in a Check; the join's
                                // column is read through Output_Variables instead
                                Rule r = load("{\"Core\":{\"Id\":\"X-1\"}," + keyed(f, "SUPP--")
                                        + "\"Check\":{\"expression\":\"not empty(STUDYID)\"},"
                                        + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\""
                                        + f.name + ".QVAL\"]}}");
                                RuleExecutionResult res = executed(f, r);
                                assertEquals("Y",
                                        res.getViolations().get(0).getValues()
                                                .get(f.name + ".QVAL"),
                                        "SUPP-- joined the family: "
                                                + res.getViolations().get(0).getValues());
                            }),
                    new Site("RuleRunner.resolveOutputVarName — Output_Variables SUPP--.QNAM", f ->
                    {
                        Rule r = load("{\"Core\":{\"Id\":\"X-1\"}," + keyed(f, "SUPP--")
                                + "\"Check\":{\"expression\":\"not empty(STUDYID)\"},"
                                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"SUPP--.QNAM\"]}}");
                        RuleExecutionResult res = executed(f, r);
                        assertEquals(f.qualifier,
                                res.getViolations().get(0).getValues().get(f.name + ".QNAM"),
                                "the finding names the family: "
                                        + res.getViolations().get(0).getValues());
                    }),
                    new Site(
                            "ScopeVariableSource.resolveQualifierName — a qualified SUPP--.QNAM entry",
                            f -> executed(f, rule(
                                    "\"Requirements\":{\"Variables\":{\"All\":[\"SUPP--.QNAM\"]}},",
                                    "empty(STUDYID)"))),
                    new Site(
                            "ScalarMetadataFunctions.crossDatasetVariableMetadata — domain=\"SUPP--\"",
                            f -> assertEquals("DOMAIN", crossMetadataFindings(f, "SUPP--"))));

    /**
     * The variable the one folded finding of {@code empty(cross_dataset_variable_metadata("label",
     * domain=D))} on the family's primary names (a Dataset-sensitivity rule folds to one finding
     * per dataset, naming the first variable with an empty answer): {@code DOMAIN} when the family
     * answered {@code STUDYID}'s label, {@code STUDYID} when nothing resolved.
     */
    static String crossMetadataFindings(Family f, String domain)
    {
        Rule r = load("{\"Core\":{\"Id\":\"X-1\"},\"Sensitivity\":\"Dataset\","
                + "\"Bindings\":[{\"name\":\"$l\",\"expression\":\"cross_dataset_variable_metadata(\\\"label\\\", domain=\\\""
                + domain + "\\\")\"}],\"Check\":{\"expression\":\"empty($l)\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"variable_name\"]}}");
        RuleExecutionResult res = executed(f, r);
        assertEquals(1, res.getViolations().size(), "one folded finding");
        return res.getViolations().get(0).getValues().get("variable_name");
    }


    /** The sites whose case fails for {@code f}, each with its failure. */
    private static List<String> failedSites(Family f)
    {
        List<String> failed = new ArrayList<>();
        for (Site site : SITES)
        {
            try
            {
                site.check.accept(f);
            }
            catch (AssertionError | RuntimeException e)
            {
                failed.add(site.name + " -> " + e);
            }
        }
        return failed;
    }


    @Test
    void everySiteResolvesTheSuppFamily()
    {
        assertEquals(List.of(), failedSites(SUPP), "SUPPAE: every site must resolve the family");
    }


    @Test
    void everySiteResolvesTheSqapFamily()
    {
        assertEquals(List.of(), failedSites(SQAP), "SQAPMH: every site must resolve the family");
    }


    /**
     * The sensitivity arm: with the family arm off every per-site case fails, for both families —
     * the roster's cases each depend on the widening, none is green for another reason.
     */
    @Test
    void familyArmOff_everySiteCaseReds()
    {
        SplitDomainResolution.suppFamilyArmForTests(false);
        List<String> stillGreen = new ArrayList<>();
        int reds = 0;
        for (Family f : List.of(SUPP, SQAP))
        {
            for (Site site : SITES)
            {
                try
                {
                    site.check.accept(f);
                    stillGreen.add(f.name + ": " + site.name);
                }
                catch (AssertionError | RuntimeException _)
                {
                    reds++;
                }
            }
        }
        assertEquals(List.of(), stillGreen, "a case green without the family arm tests nothing");
        assertEquals(2 * SITES.size(), reds);
    }

    // ---------------------------------------------------------------- membership (D3, D5, D9)


    @Test
    void anExactlyNamedZeroRowOrEmptyRdomainSuppIsItsOwnFamily()
    {
        // PG-H2: CDISC-SEND-0330's Requirements.Datasets: [SUPPPC] and CG0531's
        // record_count(domain="SUPPDM") keep answering on a metadata-only or blank-RDOMAIN table
        IDataTable zeroRows = RealTables.of("SUPPPC").str("RDOMAIN").str("QNAM").build();
        assertSame(zeroRows, SplitDomainResolution.resolveTableOrThrow(
                RealTables.inventoryOf(SUPP.base.get(), zeroRows), "SUPPPC", "R"));
        IDataTable blankRdomain = RealTables.of("SUPPDM").str("RDOMAIN", "").str("QNAM", "X")
                .build();
        assertSame(blankRdomain, SplitDomainResolution.resolveTableOrThrow(
                RealTables.inventoryOf(SUPP.base.get(), blankRdomain), "SUPPDM", "R"));
        assertTrue(SplitDomainResolution
                .isPresentAsDomain(RealTables.inventoryOf(SUPP.base.get(), zeroRows), "SUPPPC"));
    }


    @Test
    void anExactSupplbBesideSupplbchIsOneMemberAmongTwo()
    {
        // D3: the family arm runs BEFORE the exact lookup — SUPPLB is not the whole family
        IDataTable supplb = supp("SUPPLB", "LB", "USUBJID", "U1", "LBSEQ", "LBX", "1");
        IDataTable supplbch = supp("SUPPLBCH", "LB", "USUBJID", "U2", "LBSEQ", "LBX", "2");
        IDataTable t = SplitDomainResolution
                .resolveTableOrThrow(RealTables.inventoryOf(supplb, supplbch), "SUPPLB", "R");
        UnionDataTable union = assertInstanceOf(UnionDataTable.class, t);
        assertEquals("SUPPLB,SUPPLBCH",
                union.getMetaData().getMetaData(UnionDataTable.META_UNION_OF));
    }


    @Test
    void aNonExactMemberNeedsARowZeroRdomain()
    {
        // D5: a non-exact SUPP with no rows or a blank RDOMAIN is not a member; the name heuristic
        // never decides (SUPPAE1 would "look like" SUPPAE)
        IDataTable blank = supp("SUPPAE1", "", "USUBJID", "001", "AESEQ", "AETRTEM", "Y");
        IDataTable empty = RealTables.of("SUPPAE2").str("RDOMAIN").str("QNAM").build();
        IDataTable member = supp("SUPPAE3", "AE", "USUBJID", "003", "AESEQ", "AETRTEM", "Y");
        assertSame(member, SplitDomainResolution.resolveTableOrThrow(
                RealTables.inventoryOf(SUPP.base.get(), blank, empty, member), "SUPPAE", "R"),
                "the one member by RDOMAIN answers alone — no union over non-members");
        assertNull(DatasetIdentity.suppFamilyKey(blank));
        assertNull(DatasetIdentity.suppFamilyKey(empty));
        assertEquals("SUPPAE", DatasetIdentity.suppFamilyKey(member));
    }


    @Test
    void aNonIgNameJoinsTheIgFamilyByRdomain()
    {
        // D9: SUPPAPMH (the name SDTMIG v3.4 sec. 8.4.2 replaces by SQAPMH) with RDOMAIN = APMH
        // is a member of SQAPMH — and still resolves by its exact name as today
        IDataTable nonIg = supp("SUPPAPMH", "APMH", "APID", "A9", "MHSEQ", "MHTRTEM", "Y");
        DatasetResolver.WithInventory inv = SQAP.inventory(nonIg);
        assertEquals("SQAPMH", DatasetIdentity.suppFamilyKey(nonIg),
                "the key is RDOMAIN's, not the prefix's");
        IDataTable family = SplitDomainResolution.resolveTableOrThrow(inv, "SQAPMH", "R");
        assertEquals(3, family.getRowCount(), "SQAPMH1 + SQAPMH2 + SUPPAPMH");
        assertSame(nonIg, SplitDomainResolution.resolveTableOrThrow(inv, "SUPPAPMH", "R"));
        // the member is not a family name: it never walks
        assertFalse(SplitDomainResolution.isSuppFamilyName("SUPPAPMH"));
        assertFalse(SplitDomainResolution.isSuppFamilyName("SQAPMH1"));
        assertFalse(SplitDomainResolution.isSuppFamilyName("SQAPFAMH"));
        assertTrue(SplitDomainResolution.isSuppFamilyName("sqapmh"));
        assertTrue(SplitDomainResolution.isSuppFamilyName("SuppLB"));
    }


    @Test
    void lowerCaseMembersResolveUnderTheUpperCaseFamilyName()
    {
        // the PhUSE pilot's supplbch / supplbhe / supplbur beside lbch / lbhe / lbur, no supplb
        DatasetResolver.WithInventory inv = phuse();
        IDataTable family = SplitDomainResolution.resolveTableOrThrow(inv, "SUPPLB", "R");
        UnionDataTable union = assertInstanceOf(UnionDataTable.class, family);
        assertEquals("supplbch,supplbhe,supplbur",
                union.getMetaData().getMetaData(UnionDataTable.META_UNION_OF));
        assertTrue(SplitDomainResolution.isPresentAsDomain(inv, "supplb"), "the name as written");
        assertEquals("SUPPLB", DatasetIdentity.unsplitNameFromData(inv.resolve("supplbch")),
                "R4: the scope family key of a lower-case member");
    }


    /** The PhUSE shape: lower-case split LB and its three SUPPs, every SUPP keyed by LBSEQ. */
    static DatasetResolver.WithInventory phuse()
    {
        return RealTables.inventoryOf(lb("lbch", "U1", 1L), lb("lbhe", "U1", 2L),
                lb("lbur", "U2", 3L),
                supp("supplbch", "LB", "USUBJID", "U1", "LBSEQ", "LBCHX", "a"),
                supp("supplbhe", "LB", "USUBJID", "U1", "LBSEQ", "LBHEX", "b"),
                supp("supplbur", "LB", "USUBJID", "U2", "LBSEQ", "LBURX", "c"));
    }


    static IDataTable lb(String name, String subject, long seq)
    {
        return RealTables.of(name).str("STUDYID", "S1").str("DOMAIN", "LB").str("USUBJID", subject)
                .lng("LBSEQ", seq).str("LBORRES", "r").build();
    }


    @Test
    void suppWildcardOnASplitMemberIsItsDomainsFamily()
    {
        // Q6 / D6: SUPP-- on lbch is SUPPLB (the three LB SUPPs), not SUPPlbch
        DatasetResolver.WithInventory inv = phuse();
        IDataTable lbch = inv.resolve("lbch");
        assertNotNull(lbch);
        assertEquals("SUPPLB", DatasetIdentity.resolveDatasetWildcard("SUPP--", lbch));
        assertEquals("SUPPLB", DatasetIdentity.resolveDatasetWildcard("sqap--", lbch),
                "the synonym");
        RuleExecutionResult counted = run(rule(
                "\"Bindings\":[{\"name\":\"$n\",\"expression\":\"record_count(domain=\\\"SUPP--\\\")\"}],",
                "$n != 3"), lbch, inv);
        assertEquals(RuleExecutionStatus.EXECUTED, counted.getStatus(), counted.getStatusMessage());
        assertEquals(List.of(), counted.getViolations(), "every SUPP of the LB family is counted");
        RuleExecutionResult required = run(
                rule("\"Requirements\":{\"Datasets\":[\"SUPP--\"]},", "empty(STUDYID)"), lbch, inv);
        assertEquals(RuleExecutionStatus.EXECUTED, required.getStatus(),
                "no SUPPLB file, yet the family is present: " + required.getStatusMessage());
        // the gate's bare probe reads the chooser's members: on lbch exactly supplbch (D12)
        ScopeVariableSource src = ScopeVariableSource.of(inv, lbch, true);
        assertNotNull(src);
        assertTrue(src.localQualifier("LBCHX"), "its own SUPP's qualifier");
        assertFalse(src.localQualifier("LBHEX"),
                "a sibling's SUPP qualifies the sibling, not lbch");
        assertEquals("SUPPLB", src.resolvedQualifier("SUPP--"));
    }


    @Test
    void suppWildcardOnAnApPrimaryIsTheSqapFamily_andOnASuppPrimaryStaysFix33()
    {
        // D10: SUPP-- on APMH is SQAPMH (the IG's name), never SUPPAPMH; SQAP-- on AE is SUPPAE
        assertEquals("SQAPMH", DatasetIdentity.resolveDatasetWildcard("SUPP--", SQAP.base.get()));
        assertEquals("SQAPMH", DatasetIdentity.resolveDatasetWildcard("SQAP--", SQAP.base.get()));
        assertEquals("SUPPAE", DatasetIdentity.resolveDatasetWildcard("SQAP--", SUPP.base.get()));
        assertEquals("SUPPAE", DatasetIdentity.resolveDatasetWildcard("SUPP--", SUPP.base.get()));
        // Fix #33 untouched on a SUPP / SQAP primary: the parent prefix
        assertEquals("SUPPAE",
                DatasetIdentity.resolveDatasetWildcard("SUPP--", SUPP.member1.get()));
        assertEquals("SUPPMH", DatasetIdentity.resolveDatasetWildcard("SUPP--", SQAP.member1.get()),
                "SQAPMH1 -> Fix #33 strips the SQAP prefix -> parent MH -> today's SUPPMH, unchanged");
        // every other shape is resolveWildcard's
        assertEquals("AE1", DatasetIdentity.resolveDatasetWildcard("--1", SUPP.base.get()));
        assertEquals("DM", DatasetIdentity.resolveDatasetWildcard("DM", SUPP.base.get()));
        // the text policy's dataset half
        assertEquals("SQAPMH", DatasetIdentity.resolveDatasetHalf("SUPP--", "APMH"));
        assertEquals("SUPPAE", DatasetIdentity.resolveDatasetHalf("sqap--", "AE"));
        assertEquals("SUPPSUPPAE", DatasetIdentity.resolveDatasetHalf("SUPP--", "SUPPAE"),
                "a SUPP primary's code: today's answer, pinned by WildcardPrefixResolutionTest");
        assertEquals("AEX", DatasetIdentity.resolveDatasetHalf("--X", "AE"));
    }

    // ---------------------------------------------------------------- an Invalid family (D3)


    @Test
    void anInvalidFamilyIsPresent_aReadersError_andNoGateThrows()
    {
        IDataTable text = supp("SUPPAE1", "AE", "USUBJID", "001", "AESEQ", "AETRTEM", "Y");
        IDataTable numeric = RealTables.of("SUPPAE2").str("STUDYID", "S1").str("RDOMAIN", "AE")
                .str("USUBJID", "002").str("IDVAR", "AESEQ").str("IDVARVAL", "1")
                .str("QNAM", "AETRTEM").lng("QVAL", 1L).build();
        IDataTable ae = SUPP.base.get();
        DatasetResolver.WithInventory inv = RealTables.inventoryOf(ae, text, numeric);
        DomainResolution r = SplitDomainResolution.resolve(inv, "SUPPAE", "R");
        DomainResolution.Invalid invalid = assertInstanceOf(DomainResolution.Invalid.class, r);
        assertTrue(invalid.message().contains("QVAL"), invalid.message());
        assertTrue(invalid.message().contains("split supplemental family"), invalid.message());
        assertTrue(invalid.message().endsWith("; the domain cannot be joined"),
                "the classifier's tail: " + invalid.message());
        // present
        assertTrue(SplitDomainResolution.isPresentAsDomain(inv, "SUPPAE"));
        // Supp_Merge off: with it on, every rule on AE is MERGE_REFUSED (the view feeds the
        // family) — the ds_exists answer is shown on a rule whose view does not
        RuleExecutionResult presence = run(
                rule("\"Supp_Merge\":false,", "not ds_exists(\\\"SUPPAE\\\")"), ae, inv);
        assertEquals(RuleExecutionStatus.EXECUTED, presence.getStatus());
        assertEquals(List.of(), presence.getViolations(), "ds_exists answers true");
        RuleExecutionResult required = run(
                rule("\"Requirements\":{\"Datasets\":[\"SUPPAE\"]},\"Supp_Merge\":false,",
                        "empty(STUDYID)"),
                ae, inv);
        assertEquals(RuleExecutionStatus.EXECUTED, required.getStatus(),
                required.getStatusMessage());
        // a reader's ERROR
        assertThrows(InvalidJoinedDomainException.class,
                () -> SplitDomainResolution.resolveTableOrThrow(inv, "SUPPAE", "R"));
        RuleExecutionResult counted = run(rule(
                "\"Bindings\":[{\"name\":\"$n\",\"expression\":\"record_count(domain=\\\"SUPPAE\\\")\"}],\"Supp_Merge\":false,",
                "$n != 2"), ae, inv);
        assertEquals(RuleExecutionStatus.ERROR, counted.getStatus());
        // no gate throws: the members' metas and qualifiers answer
        ScopeVariableSource src = ScopeVariableSource.of(inv, ae, true);
        assertNotNull(src);
        assertEquals(2, src.metasOf("SUPPAE").size());
        assertTrue(src.localQualifier("AETRTEM"));
        assertTrue(src.existsViaSuppQnam("AE", "AETRTEM"));
        assertEquals(DataValueType.MISSING, src.suppQvalType("AE"),
                "the members disagree: unknown");
        // and the merge refuses it (D3): MERGE_REFUSED on a rule whose view feeds the family
        RuleExecutionResult merged = run(rule("", "empty(AETRTEM)"), ae, inv);
        assertEquals(RuleExecutionStatus.ERROR, merged.getStatus());
        assertTrue(
                merged.getStatusMessage().startsWith(MergedEvaluation.MERGE_REFUSED_MESSAGE_PREFIX),
                merged.getStatusMessage());
    }

    // ---------------------------------------------------------------- the bound (D2)

    /** An inventory that records every resolved name and counts its walks. */
    static final class RecordingInventory implements DatasetResolver.WithInventory
    {

        private final Map<String, IDataTable> byName = new LinkedHashMap<>();

        final List<String> resolved = new ArrayList<>();

        int walks;

        RecordingInventory(IDataTable... tables)
        {
            for (IDataTable t : tables)
            {
                byName.put(t.getMetaData().getName().toUpperCase(Locale.ROOT), t);
            }
        }


        @Override
        public @Nullable IDataTable resolve(String name)
        {
            resolved.add(name);
            return byName.get(name.toUpperCase(Locale.ROOT));
        }


        @Override
        public Set<String> availableDatasets()
        {
            walks++;
            return byName.keySet();
        }
    }

    @Test
    void nothingButTheThreeShapesWalks_andAFamilyWalkResolvesOnlySupplementals()
    {
        RecordingInventory inv = new RecordingInventory(SUPP.base.get(),
                RealTables.of("DM").str("DOMAIN", "DM").str("USUBJID", "001").build(),
                SUPP.member1.get(), SUPP.member2.get());
        for (String name : List.of("ADSL", "SQAPMH1", "SUPPAEX", "SUPPA", "SUPPQUAL", "SQAPFAMH"))
        {
            assertInstanceOf(DomainResolution.Absent.class,
                    SplitDomainResolution.resolve(inv, name, "R"), name);
        }
        assertEquals(0, inv.walks, "no walk for a name outside the three shapes");
        inv.resolved.clear();
        SplitDomainResolution.resolve(inv, "SUPPAE", "R");
        assertEquals(1, inv.walks, "one walk per family");
        for (String name : inv.resolved)
        {
            String upper = name.toUpperCase(Locale.ROOT);
            assertTrue(upper.startsWith("SUPP") || upper.startsWith("SQ"),
                    "the family walk resolved a non-supplemental dataset: " + name);
        }
        SplitDomainResolution.resolve(inv, "suppae", "R");
        assertEquals(1, inv.walks, "memoised under the upper-cased name");
    }

    // ---------------------------------------------------------------- confirmation look (a), (b)


    @Test
    void aThrowingUnrelatedDatasetDoesNotThrowThroughTheScopeGate()
    {
        // side (a): the two-character leg of resolveMetas walks fault-tolerantly
        IDataTable adlb = RealTables.of("ADLB").str("STUDYID", "S1").str("USUBJID", "U1").build();
        Map<String, IDataTable> tables = new LinkedHashMap<>();
        tables.put("ADLB", adlb);
        tables.put("LBCH", lb("lbch", "U1", 1L));
        tables.put("LBHE", lb("lbhe", "U1", 2L));
        DatasetResolver.WithInventory inv = new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                if ("BAD".equalsIgnoreCase(name))
                {
                    throw new IllegalStateException("corrupt file");
                }
                return tables.get(name.toUpperCase(Locale.ROOT));
            }


            @Override
            public Set<String> availableDatasets()
            {
                Set<String> all = new java.util.LinkedHashSet<>(tables.keySet());
                all.add("BAD");
                return all;
            }
        };
        ScopeVariableSource src = ScopeVariableSource.of(inv, adlb, true);
        assertNotNull(src);
        assertEquals(2, src.metasOf("LB").size(), "the members, the unreadable entry skipped");
        RuleExecutionResult res = run(
                rule("\"Requirements\":{\"Variables\":{\"All\":[\"LB.LBORRES\"]}},",
                        "empty(STUDYID)"),
                adlb, inv);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), res.getStatusMessage());
    }


    @Test
    void theMetadataChannelsWidenToASplitDomain_andATypeClashIsTheirError()
    {
        // side (b): var_*(dataset=) / cross_dataset_variable_metadata on split LB read the union,
        // as read_value(domain="LB") does (Fix #358 D7)
        DatasetResolver.WithInventory inv = phuse();
        assertEquals("LBORRES",
                VariableMetadataResult.build(inv, "LB", "label", "ADLB").getForVariable("LBORRES"));
        IDataTable adlb = RealTables.of("ADLB").str("STUDYID", "S1").str("LBORRES", "x")
                .str("ADLBX", "y").build();
        Rule cross = load("{\"Core\":{\"Id\":\"X-1\"},\"Sensitivity\":\"Dataset\","
                + "\"Bindings\":[{\"name\":\"$l\",\"expression\":\"cross_dataset_variable_metadata(\\\"label\\\", domain=\\\"LB\\\")\"}],"
                + "\"Check\":{\"expression\":\"empty($l)\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"variable_name\"]}}");
        RuleExecutionResult res = run(cross, adlb, inv);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), res.getStatusMessage());
        assertEquals(1, res.getViolations().size(), "only ADLBX is absent from the LB union");
        // a type-clashing split domain is the reader's ERROR in both channels
        DatasetResolver.WithInventory clash = RealTables.inventoryOf(
                RealTables.of("lbch").str("DOMAIN", "LB").lng("LBSTRESN", 1L).build(),
                RealTables.of("lbhe").str("DOMAIN", "LB").str("LBSTRESN", "high").build());
        assertThrows(InvalidJoinedDomainException.class,
                () -> VariableMetadataResult.build(clash, "LB", "label", "ADLB"));
        RuleExecutionResult error = run(cross, adlb, clash);
        assertEquals(RuleExecutionStatus.ERROR, error.getStatus());
    }


    @Test
    void theSensitivityHookIsOffForEveryOtherTest()
    {
        // the control of the control: the hook defaults to on, and @AfterEach restores it
        DatasetResolver.WithInventory inv = SUPP.inventory();
        assertNotNull(SplitDomainResolution.resolveTableOrThrow(inv, "SUPPAE", "R"));
        SplitDomainResolution.suppFamilyArmForTests(false);
        assertNull(SplitDomainResolution.resolveTableOrThrow(SUPP.inventory(), "SUPPAE", "R"),
                "arm off: the family is invisible, as before the plan");
        SplitDomainResolution.suppFamilyArmForTests(true);
        assertNotNull(SplitDomainResolution.resolveTableOrThrow(SUPP.inventory(), "SUPPAE", "R"));
    }
}
