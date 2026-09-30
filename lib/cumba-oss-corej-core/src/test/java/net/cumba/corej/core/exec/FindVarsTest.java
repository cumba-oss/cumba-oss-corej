package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.KeyedJoinFixtures;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-dynamic-column-functions} phase 3, end to end through the loader and the runner:
 * {@code find_vars} (§2.4), {@code colref} over a list (§2.7), {@code var_exists} over a computed
 * name (§2.5) and the {@code Output_Variables} pattern step (§2.6). Every expected list is the
 * dataset's own column order.
 */
class FindVarsTest
{

    /** The primary: two subjects; TRTA the actual arm, a bare template pair, a driver. */
    private static IDataTable adae()
    {
        return RealTables.of("ADAE").str("USUBJID", "S1", "S1", "S2").lng("APERIOD", 1L, 2L, null)
                .str("TRTA", "A", "B", "Z").str("TRT01P", "X", "X", "X")
                .str("TRT02P", "Y", "Y", "Y").str("NAMECOL", "", null, "TRT01P")
                .str("PROBE", "2", "0.3", "9").build();
    }


    /**
     * ADSL: S1 and S2. Two IG-width arm columns, one non-IG width, two numeric template columns
     * (one a noisy double), and a single period date.
     */
    private static IDataTable adsl()
    {
        return RealTables.of("ADSL").str("USUBJID", "S1", "S2").str("TRT01A", "A", "Q")
                .str("TRT02A", null, "Z").str("TRT1A", "W", "W").dbl("TRT01N", 2.0, 5.0)
                .dbl("TRT02N", 0.1 + 0.2, MissingValue.MIS_A.asDouble()).str("TRT01P", "X", "X")
                .build();
    }


    private static String esc(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    private static String json(String check, List<String> outputs, String... bindings)
    {
        StringBuilder b = new StringBuilder(
                "{\"Core\":{\"Id\":\"T-FV\"},\"Sensitivity\":\"Record\"")
                        .append(",\"Match_Datasets\":[{\"Name\":\"ADSL\",\"Keys\":[\"USUBJID\"],")
                        .append("\"Join_Type\":\"left\"}]");
        if (bindings.length > 0)
        {
            b.append(",\"Bindings\":[");
            for (int i = 0; i < bindings.length; i += 2)
            {
                b.append(i == 0 ? "" : ",").append("{\"name\":\"").append(bindings[i])
                        .append("\",\"expression\":\"").append(esc(bindings[i + 1])).append("\"}");
            }
            b.append(']');
        }
        b.append(",\"Check\":{\"expression\":\"").append(esc(check))
                .append("\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[");
        for (int i = 0; i < outputs.size(); i++)
        {
            b.append(i == 0 ? "" : ",").append('"').append(esc(outputs.get(i))).append('"');
        }
        return b.append("]}}").toString();
    }


    private static Rule load(String ruleJson)
    {
        try
        {
            Rule r = RulePackageLoader
                    .loadFromString(
                            KeyedJoinFixtures.declared("{\"rules\":{\"R\":" + ruleJson + "}}"))
                    .getRules().get("R");
            assertNotNull(r);
            return r;
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }


    private static RuleExecutionResult run(IDataTable primary, String ruleJson,
            IDataTable... others)
    {
        Rule r = load(ruleJson);
        assertNull(r.getLoadError(), r.getLoadError());
        IDataTable[] all = new IDataTable[others.length + 1];
        all[0] = primary;
        System.arraycopy(others, 0, all, 1, others.length);
        return RuleRunnerCalls.execute(r, primary, RealTables.inventoryOf(all),
                primary.getMetaData().getName(), null);
    }


    /** The fired rows (1-based) of a row-guarded {@code check}. */
    private static List<Long> fired(String check, String... bindings)
    {
        RuleExecutionResult result = run(adae(),
                json("not empty(USUBJID) and (" + check + ")", List.of("USUBJID"), bindings),
                adsl());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> check + " → " + result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    /** What {@code find_vars(entry)} answers on row 1, as the report renders the binding. */
    private static String namesOf(String entry)
    {
        RuleExecutionResult result = run(adae(),
                json("not empty(USUBJID)", List.of("$fv"), "$fv", "find_vars(\"" + entry + "\")"),
                adsl());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        return result.getViolations().get(0).getValues().get("$fv");
    }


    private static String loadError(String check)
    {
        String error = load(json(check, List.of("USUBJID"))).getLoadError();
        assertNotNull(error, () -> check + " must be a load error");
        return error;
    }

    // ------------------------------------------------------------------ find_vars (§2.4)


    @Test
    void everyEntryFormSelectsTheNamesInColumnOrder()
    {
        assertEquals("[ADSL.TRT01A, ADSL.TRT02A]", namesOf("ADSL.TRTxxA"),
                "a template: xx is exactly two digits, so TRT1A is not selected (Q3's narrowing)");
        assertEquals("[ADSL.TRT01A, ADSL.TRT02A, ADSL.TRT1A]", namesOf("ADSL.TRT*A"));
        assertEquals("[ADSL.TRT01A, ADSL.TRT02A, ADSL.TRT1A]", namesOf("ADSL./TRT[0-9]+A/"),
                "the qualifier outside the slashes, the regex over the variable name only");
        assertEquals("[ADSL.TRT01A]", namesOf("ADSL.TRT01A"));
        assertEquals("[ADSL.TRT01A]", namesOf("ADSL.trt01a"), "case-insensitive; dataset's case");
        assertEquals("[]", namesOf("ADSL.TRT09A"), "a literal is an existence test");
        assertEquals("[TRT01P, TRT02P]", namesOf("TRTxxP"), "unqualified: the evaluation table");
        assertEquals("[]", namesOf("DM.TRTxxP"), "no DM join: no column colref could read");
    }


    @Test
    void aMalformedLiteralEntryIsALoadError()
    {
        assertTrue(loadError("count(find_vars(\"/ADSL\\\\.TRT01A/\")) == 0").contains("ADSL./"));
        assertTrue(loadError("count(find_vars(\"/ADSL.TRT01A/\")) == 0")
                .contains("\"ADSL./TRT01A/\""));
        assertTrue(loadError("count(find_vars(\"TRTxxP:N\")) == 0").contains("type suffix"));
        assertTrue(loadError("count(find_vars(\"ADSL.--SEQ\")) == 0").contains("--"));
        assertTrue(loadError("count(find_vars(\"/TRT[/\")) == 0").contains("regular expression"));
        assertNull(load(json("count(find_vars(\"DM.X\")) == 0", List.of("USUBJID"))).getLoadError(),
                "an unknown qualifier is NOT a load error (observe-only DOTTED_REF_UNDECLARED)");
    }


    @Test
    void aComputedRegexEntryIsARuleError()
    {
        Rule r = load(json("count(find_vars(concat(\"/TRT\", \".*/\"))) > 0", List.of("USUBJID")));
        assertNull(r.getLoadError(), r.getLoadError());
        // Loud, like the ${*} collector's SubstitutionException: the exception leaves the runner
        // and the validator (LibraryValidator.executeRule) records the (rule, dataset) as ERROR.
        RuleDefinitionException error = assertThrows(RuleDefinitionException.class,
                () -> RuleRunnerCalls.execute(r, adae(), RealTables.inventoryOf(adae(), adsl()),
                        "ADAE", null),
                "a /regex/ entry must be a literal: a computed one is written wrong (D35)");
        assertTrue(error.getMessage().contains("must be a string literal"), error.getMessage());
    }


    @Test
    void literalEntriesAreSpecialisedButARegexEntryIsNot()
    {
        IDataTable ae = RealTables.of("AE").str("USUBJID", "S1").str("AESEQ", "1").build();
        RuleExecutionResult template = run(ae,
                json("not empty(USUBJID)", List.of("$fv"), "$fv", "find_vars(\"--SEQ\")"), adsl());
        assertEquals("[AESEQ]", template.getViolations().get(0).getValues().get("$fv"));
        RuleExecutionResult regex = run(ae,
                json("not empty(USUBJID)", List.of("$fv"), "$fv", "find_vars(\"/^--.*SEQ$/\")"),
                adsl());
        assertEquals("[]", regex.getViolations().get(0).getValues().get("$fv"),
                "a regex entry is exempt from specialisation, like name_pattern= (D92b)");
    }


    @Test
    void aMissingOrEmptyEntrySelectsNothing()
    {
        assertEquals(List.of(1L, 2L), fired("count(find_vars(NAMECOL)) == 0"),
                "\"\" names no column; a missing entry is missing, and count(missing) is 0");
        assertEquals(List.of(3L), fired("not empty(find_vars(NAMECOL))"),
                "empty(missing) holds, so a missing entry does not fire not empty(…)");
    }

    // ------------------------------------------------------------------ colref(list) (§2.7)


    /** ⭐ Q4 / §2.7 — the function spelling of `v in DS.X${*}Y`, inline and through a binding. */
    @Test
    void listMembershipAnswersAsTheWildcardCollector()
    {
        // TRT${*}A also matches TRT1A (\d+); the template does not (Q3) — so compare over a
        // pattern both select identically: TRT0${*}A ≡ TRT0wA.
        List<Long> collector = fired("TRTA in ADSL.TRT0${*}A");
        assertEquals(List.of(1L, 3L), collector, "S1: A ∈ {A, <missing>}; S2: Z ∈ {Q, Z}");
        assertEquals(collector, fired("TRTA in colref(find_vars(\"ADSL.TRT0wA\"))"), "inline");
        assertEquals(collector, fired("TRTA in $m", "$m", "colref(find_vars(\"ADSL.TRT0wA\"))"),
                "through a binding");
        assertEquals(fired("TRTA not in ADSL.TRT0${*}A"),
                fired("TRTA not in colref(find_vars(\"ADSL.TRT0wA\"))"),
                "a missing member never equals a present probe: not in answers alike");
    }


    /** §2.7 point 2 — typed members build the collector's text members (IDataValue cells). */
    @Test
    void typedMembersMatchAsTheCollectorsText()
    {
        List<Long> collector = fired("PROBE in ADSL.TRT0${*}N");
        assertEquals(collector, fired("PROBE in colref(find_vars(\"ADSL.TRT0wN\"))"),
                "a char probe against numeric members, a noisy double (D84) among them");
        assertEquals(List.of(1L, 2L), collector, "\"2\" ∈ {2, 0.3}; \"0.3\" ∈ {2, 0.3}");
    }

    // ------------------------------------------------------------------ var_exists (§2.5)


    @Test
    void varExistsOverAComputedNameAnswersAsTheLiteralForm()
    {
        String computed = "var_exists(concat(\"ADSL.TRT\", printf(\"%02d\", APERIOD), \"P\"))";
        assertEquals(List.of(1L), fired(computed),
                "APERIOD 1 → ADSL.TRT01P exists (DatasetResolver); 2 → TRT02P does not; a"
                        + " missing APERIOD fires for neither polarity");
        assertEquals(List.of(2L), fired("not " + computed + " and not empty(APERIOD)"));
        assertEquals(List.of(2L),
                fired("var_not_exists(concat(\"ADSL.TRT\", printf(\"%02d\", APERIOD), \"P\"))"),
                "var_not_exists: the missing driver row stays unset too");
        assertEquals(fired("var_exists(\"ADSL.TRT01P\") and APERIOD == 1"),
                fired(computed + " and APERIOD == 1"), "identical to the literal form");
    }


    @Test
    void varExistsOverAComputedBareNameSeesTheSuppPivot()
    {
        IDataTable ae = RealTables.of("AE").str("STUDYID", "S").str("USUBJID", "U1")
                .str("AESEQ", "1").build();
        IDataTable suppae = RealTables.of("SUPPAE").str("STUDYID", "S").str("RDOMAIN", "AE")
                .str("USUBJID", "U1").str("IDVAR", "").str("IDVARVAL", "").str("QNAM", "AESOSP")
                .str("QVAL", "v").build();
        String rule = "{\"Core\":{\"Id\":\"T-VE\"},\"Sensitivity\":\"Record\",\"Check\":"
                + "{\"expression\":\"var_exists(concat(\\\"AE\\\", \\\"SOSP\\\")) and"
                + " var_exists(\\\"AESOSP\\\")\"},\"Outcome\":{\"Message\":\"m\"}}";
        RuleExecutionResult result = run(ae, rule, suppae);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size(),
                "the computed and the literal name both reach the SUPP-QNAM pivot");
    }

    // ------------------------------------------------------------------ Output_Variables (§2.6)


    /** ⭐ Q6 (a): a pattern entry expands to every EXISTING matching column (JVA 2c). */
    @Test
    void anOutputVariablePatternEntryReportsEveryExistingMatch()
    {
        RuleExecutionResult result = run(
                adae(), json(
                        "not empty(USUBJID) and $b == 1", List.of("USUBJID", "ADSL.TRTxxA",
                                "TRTxxP", "ADSL./TRT0[12]N/", "ADSL.TRT*P", "ADSL.ZZxx", "$b"),
                        "$b", "1"),
                adsl());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        Map<String, String> row1 = result.getViolations().get(0).getValues();
        assertEquals(
                List.of("USUBJID", "ADSL.TRT01A", "ADSL.TRT02A", "TRT01P", "TRT02P", "ADSL.TRT01N",
                        "ADSL.TRT02N", "ADSL.TRT01P", "$b"),
                List.copyOf(row1.keySet()),
                "each pattern in place, its matches in column order; ADSL.ZZxx matches nothing"
                        + " and is omitted; $b untouched");
        assertEquals("A", row1.get("ADSL.TRT01A"));
    }


    /** A qualified entry over a SPLIT domain enumerates the union's columns (Fix #358's union). */
    @Test
    void aQualifiedEntryOverASplitDomainSeesTheUnion()
    {
        IDataTable dm = RealTables.of("DM").str("USUBJID", "U1").build();
        IDataTable lbch = RealTables.of("lbch").str("DOMAIN", "LB").str("USUBJID", "U1")
                .str("LBXA", "a").build();
        IDataTable lbhe = RealTables.of("lbhe").str("DOMAIN", "LB").str("USUBJID", "U1")
                .str("LBXB", "b").build();
        String rule = "{\"Core\":{\"Id\":\"T-FV-SPLIT\"},\"Sensitivity\":\"Record\","
                + "\"Match_Datasets\":[{\"Name\":\"LB\",\"Keys\":[\"USUBJID\"],"
                + "\"Join_Type\":\"left\"}],\"Bindings\":[{\"name\":\"$fv\",\"expression\":"
                + "\"find_vars(\\\"LB.LBX*\\\")\"}],\"Check\":{\"expression\":"
                + "\"not empty(USUBJID)\"},\"Outcome\":{\"Message\":\"m\","
                + "\"Output_Variables\":[\"$fv\"]}}";
        RuleExecutionResult result = run(dm, rule, lbch, lbhe);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        String names = result.getViolations().get(0).getValues().get("$fv");
        assertTrue("[LB.LBXA, LB.LBXB]".equals(names) || "[LB.LBXB, LB.LBXA]".equals(names),
                "both members' columns, through the union: " + names);
    }


    /**
     * ⭐ Q4, the one semantic difference from the collector, asserted as a difference: a MISSING
     * probe is a member of a list that keeps the same missing ({@code D81}, {@code D34 #5-2}); the
     * {@code ${*}} collector drops a blank member, so it answers not-a-member.
     */
    @Test
    void aMissingProbeFindsTheMissingMemberTheCollectorDrops()
    {
        IDataTable primary = RealTables.of("ADAE").str("USUBJID", "S1").str("TRTA", (String) null)
                .build();
        String dynamic = json("not empty(USUBJID) and TRTA in colref(find_vars(\"ADSL.TRT0wA\"))",
                List.of("USUBJID"));
        String wildcard = json("not empty(USUBJID) and TRTA in ADSL.TRT0${*}A", List.of("USUBJID"));
        assertEquals(1, run(primary, dynamic, adsl()).getViolations().size(),
                "S1's TRT02A is missing: kept as a member, it equals the missing probe");
        assertEquals(0, run(primary, wildcard, adsl()).getViolations().size(),
                "the collector drops the missing member (ruled 2026-09-18/-21), unchanged (Q8)");
    }


    /**
     * {@code CDISC-AD0581}'s shape stays a DATASET-sensitive rule: a literal {@code find_vars}
     * entry is a dataset-level usage for the classifier, never a per-record column named
     * {@code TRTxxP} (measured: the routing census moved AD0581 Dataset → Record before this).
     */
    @Test
    void aLiteralFindVarsLeafIsADatasetLevelFact()
    {
        Rule rule = load("{\"Core\":{\"Id\":\"T-FV-SENS\"},\"Check\":{\"expression\":"
                + "\"not var_exists(\\\"TRTP\\\") and count(find_vars(\\\"TRTxxP\\\")) == 0\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}");
        assertEquals(net.cumba.corej.core.model.Sensitivity.DATASET,
                RuleClassifier.deriveSensitivity(rule).value(),
                RuleClassifier.deriveSensitivity(rule).toString());
    }

    // ------------------------------------------------------------------ review round 1


    /**
     * Lane A M2: a {@code $}-binding holding a {@code find_vars} list, dereferenced by
     * {@code colref($vars)} on the right of {@code in}, answers as the inline spelling (before the
     * fix: a run-time ERROR — the colref arm admitted only a literal or a list-valued call).
     */
    @Test
    void colrefOverAListBindingIsAMembershipSet()
    {
        List<Long> inline = fired("TRTA in colref(find_vars(\"ADSL.TRT0wA\"))");
        assertEquals(List.of(1L, 3L), inline);
        assertEquals(inline, fired("TRTA in colref($vars)", "$vars", "find_vars(\"ADSL.TRT0wA\")"));
        assertEquals(fired("TRTA not in colref(find_vars(\"ADSL.TRT0wA\"))"),
                fired("TRTA not in colref($vars)", "$vars", "find_vars(\"ADSL.TRT0wA\")"));
    }


    /**
     * Lane A L1: {@code colref(<list>)} on the LEFT of {@code in} compares element-wise — any
     * member in the set — like a list-valued accessor on the left (D81d), never the list as one
     * value.
     */
    @Test
    void aColrefListOnTheLeftComparesElementWise()
    {
        assertEquals(List.of(3L), fired("colref(find_vars(\"ADSL.TRT0wA\")) in [\"Z\"]"),
                "S2's TRT02A is Z");
        assertEquals(List.of(1L, 2L), fired("colref(find_vars(\"ADSL.TRT0wA\")) not in [\"Z\"]"));
        assertEquals(List.of(3L),
                fired("colref($vars) in [\"Z\"]", "$vars", "find_vars(\"ADSL.TRT0wA\")"),
                "through a binding too");
        assertEquals(List.of(1L, 2L), fired("colref(\"TRTA\") in [\"A\", \"B\"]"),
                "a scalar colref on the left stays a scalar probe");
    }


    /**
     * Lane A M1: a DATA-derived entry or name starting with {@code --} names no column — computed
     * {@code find_vars} answers {@code []} and computed {@code var_exists} answers as for an absent
     * name, never the D77b specialiser assertion (a rule ERROR on shipped data).
     */
    @Test
    void aDataDerivedDashNameSelectsNothing()
    {
        IDataTable primary = RealTables.of("ADAE").str("USUBJID", "S1").str("NAMECOL", "--SEQ")
                .str("AESEQ", "1").build();
        String rule = json("not empty(USUBJID) and count(find_vars(NAMECOL)) == 0"
                + " and not var_exists(concat(\"\", NAMECOL))", List.of("USUBJID"));
        RuleExecutionResult result = run(primary, rule, adsl());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size());
    }


    /**
     * Lane C F9: a {@code var_exists} over a COMPUTED name varies per row — a record-level fact for
     * the classifier (the mirror of the literal {@code find_vars} arm, which is dataset level).
     */
    @Test
    void aComputedVarExistsIsARecordLevelFact()
    {
        Rule computed = load("{\"Core\":{\"Id\":\"T-VE-SENS\"},\"Match_Datasets\":[{\"Name\":"
                + "\"ADSL\",\"Keys\":[\"USUBJID\"],\"Join_Type\":\"left\"}],\"Check\":{"
                + "\"expression\":\"not var_exists(concat(\\\"ADSL.TRT\\\", printf(\\\"%02d\\\","
                + " APERIOD), \\\"P\\\"))\"},\"Outcome\":{\"Message\":\"m\"}}");
        assertEquals(net.cumba.corej.core.model.Sensitivity.RECORD,
                RuleClassifier.deriveSensitivity(computed).value(),
                RuleClassifier.deriveSensitivity(computed).toString());
        Rule literal = load("{\"Core\":{\"Id\":\"T-VE-SENS2\"},\"Check\":{\"expression\":"
                + "\"not var_exists(\\\"TRTP\\\")\"},\"Outcome\":{\"Message\":\"m\"}}");
        assertEquals(net.cumba.corej.core.model.Sensitivity.DATASET,
                RuleClassifier.deriveSensitivity(literal).value(),
                RuleClassifier.deriveSensitivity(literal).toString());
    }


    /**
     * Lane A L7: {@code colref(find_vars(<literal>))} reads per-row CELLS — record level, even
     * though its entry is a dataset-level list of names.
     */
    @Test
    void aColrefOverALiteralFindVarsIsARecordLevelRead()
    {
        for (String check : List.of("\\\"Z\\\" in colref(find_vars(\\\"ADSL.TRT0wA\\\"))",
                "colref(find_vars(\\\"TRTxxP\\\")) in [\\\"X\\\"]"))
        {
            Rule rule = load("{\"Core\":{\"Id\":\"T-CR-SENS\"},\"Match_Datasets\":[{\"Name\":"
                    + "\"ADSL\",\"Keys\":[\"USUBJID\"],\"Join_Type\":\"left\"}],\"Check\":{"
                    + "\"expression\":\"" + check + "\"},\"Outcome\":{\"Message\":\"m\"}}");
            assertEquals(net.cumba.corej.core.model.Sensitivity.RECORD,
                    RuleClassifier.deriveSensitivity(rule).value(),
                    check + " → " + RuleClassifier.deriveSensitivity(rule));
        }
    }


    /**
     * ⭐ The cross-surface assertion for QUALIFIED entries (review round 1, lane B M3), end to end
     * on a joined ADSL: {@code find_vars(e)} is empty exactly when a rule requiring {@code e}
     * ({@code Requirements.Variables.All: [e]} — a one-entry {@code Any} is refused at load) is
     * SKIPPED. One parser, one matcher, so the two surfaces answer alike for every form.
     */
    @Test
    void aQualifiedEntrySelectsWhatTheRequirementsEntryRequires()
    {
        for (String entry : List.of("ADSL.TRTxxA", "ADSL.TRT*A", "ADSL./TRT[0-9]+A/", "ADSL.TRT01A",
                "ADSL.TRT09A", "ADSL.TRTxxZ"))
        {
            boolean none = "[]".equals(namesOf(entry));
            assertEquals(none, requirementSkips(adae(), entry, adsl()), entry);
        }
        assertEquals("[]", namesOf("ADSL.TRT09A"));
        assertEquals("[ADSL.TRT01A, ADSL.TRT02A]", namesOf("ADSL.TRTxxA"));
    }


    /**
     * The SPLIT-domain case of the cross-surface assertion: both surfaces see the union of the
     * members' columns.
     */
    @Test
    void aQualifiedEntryOverASplitDomainAgreesWithRequirements()
    {
        IDataTable dm = RealTables.of("DM").str("USUBJID", "U1").build();
        IDataTable lbch = RealTables.of("lbch").str("DOMAIN", "LB").str("USUBJID", "U1")
                .str("LBXA", "a").build();
        IDataTable lbhe = RealTables.of("lbhe").str("DOMAIN", "LB").str("USUBJID", "U1")
                .str("LBXB", "b").build();
        for (String entry : List.of("LB.LBX*", "LB.LBXB", "LB.LBXC"))
        {
            boolean none = "[]".equals(namesOn(dm, "LB", entry, lbch, lbhe));
            assertEquals(none, requirementSkipsOn(dm, "LB", entry, lbch, lbhe), entry);
        }
    }


    /**
     * ⭐ Q12, asserted as a DIVERGENCE: {@code Requirements.Variables} counts a SUPP-QNAM-pivoted
     * name as present, {@code find_vars} returns only REAL columns (owner Q12: a pivoted name is
     * not a column {@code colref} can read).
     */
    @Test
    void theSuppPivotIsTheOneDivergenceFromRequirements()
    {
        IDataTable ae = RealTables.of("AE").str("STUDYID", "S").str("USUBJID", "U1")
                .str("AESEQ", "1").build();
        IDataTable suppae = RealTables.of("SUPPAE").str("STUDYID", "S").str("RDOMAIN", "AE")
                .str("USUBJID", "U1").str("IDVAR", "").str("IDVARVAL", "").str("QNAM", "AESOSP")
                .str("QVAL", "v").build();
        assertEquals("[]", namesOn(ae, null, "AESOSP", suppae), "find_vars: real columns only");
        assertEquals(false, requirementSkipsOn(ae, null, "AESOSP", suppae),
                "Requirements.Variables: the pivoted QNAM counts as present");
    }


    private static boolean requirementSkips(IDataTable primary, String entry, IDataTable... others)
    {
        return requirementSkipsOn(primary, "ADSL", entry, others);
    }


    private static String match(@org.jspecify.annotations.Nullable String joined)
    {
        return joined == null ? ""
                : ",\"Match_Datasets\":[{\"Name\":\"" + joined + "\",\"Keys\":[\"USUBJID\"],"
                        + "\"Join_Type\":\"left\"}]";
    }


    /** Whether a rule requiring {@code entry} is SKIPPED on {@code primary}. */
    private static boolean requirementSkipsOn(IDataTable primary,
            @org.jspecify.annotations.Nullable String joined, String entry, IDataTable... others)
    {
        String rule = "{\"Core\":{\"Id\":\"T-FV-REQ\"},\"Sensitivity\":\"Record\"" + match(joined)
                + ",\"Requirements\":{\"Variables\":{\"All\":[\"" + esc(entry) + "\"]}},"
                + "\"Check\":{\"expression\":\"not empty(USUBJID)\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}";
        RuleExecutionStatus status = run(primary, rule, others).getStatus();
        assertTrue(status == RuleExecutionStatus.SKIPPED || status == RuleExecutionStatus.EXECUTED,
                entry + " → " + status);
        return status == RuleExecutionStatus.SKIPPED;
    }


    /** What {@code find_vars(entry)} answers on {@code primary}'s first row. */
    private static String namesOn(IDataTable primary,
            @org.jspecify.annotations.Nullable String joined, String entry, IDataTable... others)
    {
        String rule = "{\"Core\":{\"Id\":\"T-FV-ON\"},\"Sensitivity\":\"Record\"" + match(joined)
                + ",\"Bindings\":[{\"name\":\"$fv\",\"expression\":\"find_vars(\\\"" + esc(entry)
                + "\\\")\"}],\"Check\":{\"expression\":\"not empty(USUBJID)\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"$fv\"]}}";
        RuleExecutionResult result = run(primary, rule, others);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        return result.getViolations().get(0).getValues().get("$fv");
    }

    // ------------------------------------------------------------------ review round 2


    /**
     * Engine 1: an ABSENT member (and a not-supplied one — an undeclared qualifier) is the present
     * {@code ""}, keyed by its text on both sides of {@code in}; before, only a joined cell was
     * re-carried, so it keyed as the quoted {@code "\"\""} and matched no blank.
     */
    @Test
    void anAbsentOrNotSuppliedMemberIsTheBlankOnBothSides()
    {
        IDataTable primary = RealTables.of("ADAE").str("USUBJID", "S1", "S2").str("TRTA", "", "X")
                .build();
        for (String name : List.of("ZZ", "DM.ZZ"))
        {
            String member = "colref([\"" + name + "\"])";
            assertEquals(List.of(1L), firedOn(primary, "TRTA in " + member), name);
            assertEquals(List.of(2L), firedOn(primary, "TRTA not in " + member), name);
            assertEquals(List.of(1L, 2L), firedOn(primary, member + " in [\"\"]"), name);
        }
    }


    /**
     * Engine 2: {@code colref($x)} over a SCALAR binding is a scalar probe on every arm — a numeric
     * list, a temporal list, a {@code ${*}} set — answering as the authored name does; only a row
     * whose binding holds a list compares element-wise.
     */
    @Test
    void aScalarBindingProbeTakesTheScalarArms()
    {
        IDataTable primary = RealTables.of("ADAE").str("USUBJID", "S1", "S1", "S2")
                .dbl("N", 100.0, 200.0, 300.0).str("DTC", "2020-01-01T10:00", "2021-05", "")
                .str("TRTA", "A", "B", "Z").build();
        assertEquals(firedOn(primary, "N in [100.0, 300]"),
                firedOn(primary, "colref($x) in [100.0, 300]", "$x", "\"N\""),
                "a numeric list runs numerically (100 is 100.0)");
        assertEquals(List.of(1L, 3L),
                firedOn(primary, "colref($x) in [100.0, 300]", "$x", "\"N\""));
        assertEquals(firedOn(primary, "DTC in [date(\"2020-01-01\")]"),
                firedOn(primary, "colref($x) in [date(\"2020-01-01\")]", "$x", "\"DTC\""),
                "a temporal list compares as dates");
        assertEquals(firedOn(primary, "TRTA in ADSL.TRT0${*}A"),
                firedOn(primary, "colref($x) in ADSL.TRT0${*}A", "$x", "\"TRTA\""), "a ${*} set");
        assertEquals(List.of(1L, 3L),
                firedOn(primary, "colref($x) in ADSL.TRT0${*}A", "$x", "\"TRTA\""));
    }


    /** Engine 2 / Q3: the mixed numeric/string list stays a load error behind a binding probe. */
    @Test
    void aMixedListIsStillALoadErrorForABindingProbe()
    {
        assertNotNull(load(json("not empty(USUBJID) and TRTA in [1, \"A\"]", List.of("USUBJID")))
                .getLoadError(), "authored");
        assertNotNull(load(json("not empty(USUBJID) and colref($x) in [1, \"A\"]",
                List.of("USUBJID"), "$x", "\"TRTA\"")).getLoadError(), "through colref($x)");
    }


    /** The qualifier matches IGNORING case (owner 2026-09-28, CIT §1): colref and find_vars. */
    @Test
    void aLowerCaseQualifierNamesTheDeclaredDataset()
    {
        assertEquals("[adsl.TRT01A, adsl.TRT02A]", namesOf("adsl.TRTxxA"),
                "the qualifier as written, the column in the dataset's case");
        assertEquals(fired("TRTA == colref(\"ADSL.TRT01A\")"),
                fired("TRTA == colref(\"adsl.trt01a\")"));
        assertEquals(List.of(1L), fired("TRTA == colref(\"adsl.trt01a\")"));
    }


    /** A lower-case qualified template in Output_Variables expands like its upper-case twin. */
    @Test
    void aLowerCaseOutputVariablePatternExpands()
    {
        RuleExecutionResult result = run(adae(),
                json("not empty(USUBJID)", List.of("USUBJID", "adsl.TRTxxA")), adsl());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(List.of("USUBJID", "ADSL.TRT01A", "ADSL.TRT02A"),
                List.copyOf(result.getViolations().get(0).getValues().keySet()),
                "reported under the rule's declared spelling, which the report reads through");
        assertEquals("A", result.getViolations().get(0).getValues().get("ADSL.TRT01A"));
    }


    /** The fired rows (1-based) of a row-guarded {@code check} on {@code primary}. */
    private static List<Long> firedOn(IDataTable primary, String check, String... bindings)
    {
        RuleExecutionResult result = run(primary,
                json("not empty(USUBJID) and (" + check + ")", List.of("USUBJID"), bindings),
                adsl());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> check + " → " + result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }
}
