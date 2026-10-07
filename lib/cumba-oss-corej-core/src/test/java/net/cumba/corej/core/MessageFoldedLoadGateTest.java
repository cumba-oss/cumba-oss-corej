package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-present-empty-column-fold} §4.3 load-time checks, each with a planted fixture that
 * must red it (pre-go finding 7): R2 (a) no foldable leaf — widened by
 * {@code PLAN-message-folded-corpus-alignment} P2 to "can never fold OR collapse" —, (b) a
 * non-Record Sensitivity ({@code Dataset} / {@code Group} / {@code Study}), (c) a variable-cursor
 * domain; an unread {@code Keep_Row_Findings} entry; and D10's load error with a hint for the
 * retired {@code var_is_null}. A clean opted-in rule is the positive control.
 */
class MessageFoldedLoadGateTest
{

    private static Rule load(String members) throws IOException
    {
        String json = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"T-MF\"}," + members + "}}}";
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(rule, "the fixture must bind");
        return rule;
    }


    private static String record(String check, String outcomeExtra)
    {
        return "\"Sensitivity\":\"Record\",\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Check\":{\"expression\":\"" + check + "\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AEBDSYCD\"]"
                + outcomeExtra + "}";
    }

    private static final String FOLDED = ",\"Message_Folded\":\"folded\"";

    @Test
    void anOptedInRecordRuleWithAPlainEmptyLeafLoadsClean() throws IOException
    {
        Rule rule = load(record("not empty(AEBODSYS) and empty(--BDSYCD)",
                FOLDED + ",\"Keep_Row_Findings\":[\"--BDSYCD\"]"));
        assertNull(rule.getLoadError(), rule.getLoadError());
        assertNull(load(record("empty(AEBDSYCD)", "")).getLoadError(),
                "no key, no gate (positive control)");
        assertNull(
                load(record("empty(AEBDSYCD)", ",\"Keep_Row_Findings\":[\"AEBDSYCD\"]"))
                        .getLoadError(),
                "Keep_Row_Findings without Message_Folded is inert, not an error");
    }


    @Test
    void r2aWidenedToP2ARuleThatCanNeverFoldOrCollapseIsALoadError() throws IOException
    {
        // PLAN-message-folded-corpus-alignment P2 (V5): an absent-only rule whose read column is
        // ungated CAN fold on an absent column — accepted (C7's R2 (a) refused it).
        assertNull(load(record("AEBDSYCD != \\\"X\\\"", FOLDED)).getLoadError(),
                "ungated AEBDSYCD may be absent: the absent fold can decide the rule");
        // Gated everywhere and no collapsible leaf: nothing can fold or collapse.
        String error = load(
                "\"Sensitivity\":\"Record\",\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                        + "\"Requirements\":{\"Variables\":{\"All\":[\"AEBDSYCD\"]}},"
                        + "\"Check\":{\"expression\":\"AEBDSYCD != \\\"X\\\"\"},"
                        + "\"Outcome\":{\"Message\":\"m\"" + FOLDED + "}").getLoadError();
        assertNotNull(error);
        assertTrue(error.contains("[T-MF]") && error.contains("Message_Folded")
                && error.contains("can never fold or collapse")
                && error.contains("no empty-family leaf"), error);
        String excluded = load(
                "\"Sensitivity\":\"Record\",\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                        + "\"Requirements\":{\"Variables\":{\"All\":[\"AEBDSYCD\"]}},"
                        + "\"Check\":{\"expression\":\"empty(AEBDSYCD)\"},"
                        + "\"Outcome\":{\"Message\":\"m\"" + FOLDED
                        + ",\"Keep_Row_Findings\":[\"AEBDSYCD\"]}").getLoadError();
        assertNotNull(excluded);
        assertTrue(excluded.contains("outside Keep_Row_Findings"), excluded);
        assertNull(
                load(record("empty(AEBDSYCD)", FOLDED + ",\"Keep_Row_Findings\":[\"AEBDSYCD\"]"))
                        .getLoadError(),
                "the same rule ungated folds on an absent AEBDSYCD: accepted");
    }


    @Test
    void r2bNonRecordSensitivityIsALoadError() throws IOException
    {
        String dataset = load(
                "\"Sensitivity\":\"Dataset\"," + "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                        + "\"Check\":{\"expression\":\"empty(AEBDSYCD)\"},"
                        + "\"Outcome\":{\"Message\":\"m\"" + FOLDED + "}").getLoadError();
        assertNotNull(dataset);
        assertTrue(dataset.contains("RECORD-level") && dataset.contains("Dataset"), dataset);

        String group = load("\"Sensitivity\":\"Group\",\"Grouping_Variables\":[\"USUBJID\"],"
                + "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Check\":{\"expression\":\"empty(AEBDSYCD)\"},"
                + "\"Outcome\":{\"Message\":\"m\"" + FOLDED + "}").getLoadError();
        assertNotNull(group);
        assertTrue(group.contains("RECORD-level") && group.contains("Group"), group);

        String study = load(
                "\"Sensitivity\":\"Study\"," + "\"Scope\":{\"Domains\":{\"Include\":[\"ALL\"]}},"
                        + "\"Check\":{\"expression\":\"empty(AEBDSYCD)\"},"
                        + "\"Outcome\":{\"Message\":\"m\"" + FOLDED + "}").getLoadError();
        assertNotNull(study);
        assertTrue(study.contains("RECORD-level") && study.contains("Study"), study);
    }


    @Test
    void r2cAVariableCursorCheckIsALoadError() throws IOException
    {
        String error = load(record("empty(AEBDSYCD) and varname() == \\\"AEBDSYCD\\\"", FOLDED))
                .getLoadError();
        assertNotNull(error);
        assertTrue(error.contains("variable cursor"), error);
    }


    @Test
    void anUnreadKeepRowFindingsEntryIsALoadError() throws IOException
    {
        String error = load(
                record("empty(AEBDSYCD)", FOLDED + ",\"Keep_Row_Findings\":[\"AETERM\"]"))
                        .getLoadError();
        assertNotNull(error);
        assertTrue(error.contains("Keep_Row_Findings") && error.contains("'AETERM'")
                && error.contains("does not read"), error);
    }


    @Test
    void theRetiredVarIsNullIsALoadErrorWithAHint() throws IOException
    {
        String error = load(record("var_is_null(AEBDSYCD)", "")).getLoadError();
        assertNotNull(error, "D10: no alias");
        assertTrue(error.contains("var_is_null was renamed to var_is_empty"), error);
        assertNull(load(record("var_is_empty(AEBDSYCD)", "")).getLoadError(),
                "the new name loads (control)");
    }
}
