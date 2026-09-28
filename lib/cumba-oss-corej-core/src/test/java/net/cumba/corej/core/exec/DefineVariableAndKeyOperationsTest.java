package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.cdisc.define.ODM;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.metadata.DefineXmlMetadataProvider;
import net.cumba.corej.core.metadata.OdmDefineXMLProvider;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * T2-residual — the two Define-XML set operations, {@code define_variable_names()} and
 * {@code define_key_variables()}, bound by hand-written rules and run end to end against a real
 * define.xml parsed to ODM ({@link OdmDefineXMLProvider} &rarr; {@link DefineXmlMetadataProvider}).
 *
 * <ul>
 * <li>{@code define_variable_names()} answers the variables the Define declares for the current
 * domain, in ItemRef order.</li>
 * <li>{@code define_key_variables()} answers the key variables in {@code KeySequence} order.</li>
 * <li>An <b>empty</b> Define key set (M4) SKIPS the rule instead of answering {@code []}: an empty
 * key would collapse a uniqueness check to a constant and flag every record as a duplicate.</li>
 * <li>With no Define-XML at all both operations SKIP the rule — never PASS/FAIL.</li>
 * </ul>
 *
 * <p>
 * Each rule projects the bound value, so the assertions read what the operation answered, not
 * merely that something fired.
 * </p>
 */
class DefineVariableAndKeyOperationsTest
{

    /** Fires once per dataset and projects the Define's declared variable names. */
    private static final String VARIABLE_NAMES_RULE = """
            {"rules":{"V1":{"Core":{"Id":"T-DEFINE-VARS"},"Sensitivity":"Dataset",
             "Scope":{"Domains":{"Include":["ALL"]}},
             "Bindings":[{"name":"$declared","expression":"define_variable_names()"}],
             "Check":{"expression":"not empty($declared)"},
             "Outcome":{"Message":"m","Output_Variables":["$declared"]}}}}""";

    /** Fires once per dataset and projects the Define's key variables. */
    private static final String KEY_VARIABLES_RULE = """
            {"rules":{"K1":{"Core":{"Id":"T-DEFINE-KEYS"},"Sensitivity":"Dataset",
             "Scope":{"Domains":{"Include":["ALL"]}},
             "Bindings":[{"name":"$keys","expression":"define_key_variables()"}],
             "Check":{"expression":"not empty($keys)"},
             "Outcome":{"Message":"m","Output_Variables":["$keys"]}}}}""";

    private static MetadataProvider dmDefine;

    private static MetadataProvider lbDefine;

    @BeforeAll
    static void load() throws IOException
    {
        dmDefine = new DefineXmlMetadataProvider(
                new OdmDefineXMLProvider(parse("/define/define-itemmeta-e2e.xml")), null);
        lbDefine = new DefineXmlMetadataProvider(
                new OdmDefineXMLProvider(parse("/define/define-keys-e2e.xml")), null);
    }


    private static ODM parse(String resource) throws IOException
    {
        try (InputStream in = DefineVariableAndKeyOperationsTest.class
                .getResourceAsStream(resource))
        {
            return new DefineXmlParser().parse(in);
        }
    }


    private static Rule rule(String json, String key) throws IOException
    {
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get(key);
        assertNull(rule.getLoadError(), "the hand-written rule must load: " + rule.getLoadError());
        return rule;
    }


    /**
     * DM declares AGE then SEX; the data carries only AGE — the answer is the Define's, not the
     * data's.
     */
    @Test
    void defineVariableNames_answersTheDeclaredVariablesInItemRefOrder() throws IOException
    {
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").build();

        RuleExecutionResult r = RuleRunnerCalls.execute(rule(VARIABLE_NAMES_RULE, "V1"), dm,
                _ -> null, "DM", null, null, dmDefine);
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus());
        assertEquals(1, r.getViolationCount());
        assertEquals("[AGE, SEX]", r.getViolations().get(0).getValues().get("$declared"));
    }


    @Test
    void defineVariableNames_skipsWithoutADefine() throws IOException
    {
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").build();

        RuleExecutionResult r = RuleRunnerCalls.execute(rule(VARIABLE_NAMES_RULE, "V1"), dm,
                _ -> null, "DM", null, null, null);
        assertTrue(r.isSkipped(), "no Define-XML -> rule SKIPPED");
        assertFalse(r.hasViolations(), "a SKIPPED rule reports no violations");
    }


    /**
     * LB declares USUBJID (KeySequence 1), LBDTC (2) and LBTESTCD (3); STUDYID and LBORRES are not
     * keys. LBDTC is the LAST ItemRef, so ItemRef order would answer
     * {@code [USUBJID, LBTESTCD, LBDTC]} — only a KeySequence sort answers the expected order.
     */
    @Test
    void defineKeyVariables_answersTheKeysInKeySequenceOrder() throws IOException
    {
        IDataTable lb = MockTable.of().name("LB").col("STUDYID", "S1").col("USUBJID", "S1-001")
                .col("LBTESTCD", "ALB").col("LBORRES", "40").col("LBDTC", "2026-01-01").build();

        RuleExecutionResult r = RuleRunnerCalls.execute(rule(KEY_VARIABLES_RULE, "K1"), lb,
                _ -> null, "LB", null, null, lbDefine);
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus());
        assertEquals(1, r.getViolationCount());
        assertEquals("[USUBJID, LBDTC, LBTESTCD]",
                r.getViolations().get(0).getValues().get("$keys"));
    }


    /**
     * M4: a Define that declares ZERO key variables for the dataset SKIPS the rule — the empty set
     * is reported as "the Define cannot answer", never handed to the Check as {@code []}.
     */
    @Test
    void defineKeyVariables_skipsWhenTheDefineDeclaresNoKeys() throws IOException
    {
        IDataTable lb = MockTable.of().name("LB").col("STUDYID", "S1").col("USUBJID", "S1-001")
                .build();

        // StubMetadataProvider serves the MetadataProvider default: no key variables anywhere.
        RuleExecutionResult r = RuleRunnerCalls.execute(rule(KEY_VARIABLES_RULE, "K1"), lb,
                _ -> null, "LB", null, null, new StubMetadataProvider());
        assertTrue(r.isSkipped(), "empty Define key set -> rule SKIPPED");
        assertTrue(String.valueOf(r.getStatusMessage()).contains("declares no key variables"),
                r.getStatusMessage());
        assertFalse(r.hasViolations(), "a SKIPPED rule reports no violations");
    }


    @Test
    void defineKeyVariables_skipsWithoutADefine() throws IOException
    {
        IDataTable lb = MockTable.of().name("LB").col("STUDYID", "S1").build();

        RuleExecutionResult r = RuleRunnerCalls.execute(rule(KEY_VARIABLES_RULE, "K1"), lb,
                _ -> null, "LB", null, null, null);
        assertTrue(r.isSkipped(), "no Define-XML -> rule SKIPPED");
        assertFalse(r.hasViolations(), "a SKIPPED rule reports no violations");
    }
}
