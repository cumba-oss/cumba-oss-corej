package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.cdisc.define.ODM;
import net.cumba.corej.core.exec.OperationExecutor;
import net.cumba.corej.core.model.Operation;
import net.cumba.datatable.testkit.SyntheticDataTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Register {@code NNL §1} ({@code PLAN-no-null-list-elements} row 13): a Define-XML entry with
 * <b>no member</b> — an {@code ItemGroupDef} without a {@code Name}, a {@code CodeListItem} without
 * a {@code CodedValue} — is <b>skipped</b> by its producer. It is neither served as {@code null}
 * (nothing is ever null) nor as {@code ""} (a present value naming nothing). A <b>blank</b>
 * {@code Name=""} / {@code CodedValue=""} names nothing either and is skipped the same way (review
 * round 1, S-1) — by every reader of the entry, not only the first one fixed: the column order and
 * the key variables skip a nameless or blank-named {@code ItemDef}, and the coded / extended values
 * skip a blank {@code CodedValue} (review round 2, MEDIUM-1: a blank {@code ItemDef} served as
 * {@code ""} made FDA/PMDA-SD0054 report {@code $missing_define_variables = [""]}).
 *
 * <p>
 * Real {@code OdmDefineXMLProvider} over a real parsed document, Mockito-free. Red-before on HEAD:
 * {@code getDatasetNames()} held a {@code null} for the nameless group, which
 * {@code define_dataset_names()} carried into FDA/PMDA-SD0061 as an empty dataset name.
 * </p>
 */
class NamelessDefineEntriesTest
{

    private static OdmDefineXMLProvider odm;

    private static DefineXmlMetadataProvider define;

    @BeforeAll
    static void load() throws IOException
    {
        try (InputStream in = NamelessDefineEntriesTest.class
                .getResourceAsStream("/define/define-nameless-entries.xml"))
        {
            ODM parsed = new DefineXmlParser().parse(in);
            odm = new OdmDefineXMLProvider(parsed);
        }
        define = new DefineXmlMetadataProvider(odm, null);
    }


    @Test
    void getDatasetNamesSkipsTheNamelessItemGroupDefAndKeepsTheNamedOnesInOrder()
    {
        List<String> names = odm.getDatasetNames();

        assertTrue(names.stream().noneMatch(Objects::isNull), "no null element (NNL §1)");
        assertFalse(names.contains(""), "and no empty name either — a nameless group is skipped");
        assertEquals(List.of("AE", "DM"), names,
                "the two named groups, in document order, with the nameless and the blank-named one"
                        + " between them gone");
        assertEquals(names, define.getDatasetNames(),
                "DefineXmlMetadataProvider passes the ODM list through unchanged");
    }


    @Test
    void defineDatasetNamesOperationIsExactlyTheNamedGroups()
    {
        Operation op = new Operation();
        op.setId("$define_datasets");
        op.setOperator("define_dataset_names");
        SyntheticDataTable dm = new SyntheticDataTable("DM", List.of("STUDYID"), new String[]
        {
                "S1"
        }, 1);

        Object result = OperationExecutor.executeOne(op, dm, _ -> null, null, Map.of(), "T-NNL",
                null, define);

        assertEquals(List.of("AE", "DM"), result,
                "define_dataset_names() is exactly the named groups — the nameless entry neither"
                        + " appears as null nor as \"\"");
    }


    @Test
    void getCodelistTermsSkipsTheItemWithoutACodedValue()
    {
        List<String> terms = define.getCodelistTerms("CL.SEX");

        assertTrue(terms.stream().noneMatch(Objects::isNull), "no null element (NNL §1)");
        assertFalse(terms.contains(""), "and no blank term — CodedValue=\"\" names nothing");
        assertEquals(List.of("M", "F"), terms,
                "the items without a CodedValue and with a blank one are skipped; the two coded items"
                        + " keep their order");
    }


    @Test
    void getColumnOrderSkipsTheNamelessAndTheBlankNamedItemDef()
    {
        List<String> order = define.getColumnOrder("DM");

        assertTrue(order.stream().noneMatch(Objects::isNull), "no null element (NNL §1)");
        assertFalse(order.contains(""), "and no blank name — Name=\"\" names no column");
        assertEquals(List.of("USUBJID", "SEX"), order,
                "the two named ItemDefs in OrderNumber order; the nameless and the blank-named one"
                        + " between them are gone");
    }


    @Test
    void defineVariableNamesOperationIsExactlyTheNamedItemDefs()
    {
        Operation op = new Operation();
        op.setId("$define_variables");
        op.setOperator("define_variable_names");
        SyntheticDataTable dm = new SyntheticDataTable("DM", List.of("STUDYID"), new String[]
        {
                "S1"
        }, 1);

        Object result = OperationExecutor.executeOne(op, dm, _ -> null, null, Map.of(), "T-NNL",
                null, define);

        assertEquals(List.of("USUBJID", "SEX"), result,
                "define_variable_names() — what FDA/PMDA-SD0054 compares against the dataset's"
                        + " columns — carries no \"\" for the blank-named ItemDef");
    }


    @Test
    void getKeyVariablesSkipsTheNamelessAndTheBlankNamedItemDef()
    {
        assertEquals(List.of("USUBJID"), odm.getKeyVariables("DM"),
                "KeySequence 2 (Name=\"\") and 3 (no Name) name no key variable; only KeySequence"
                        + " 1 is served");
        assertEquals(List.of("USUBJID"), define.getKeyVariables("DM"),
                "DefineXmlMetadataProvider passes the ODM key list through unchanged");
    }


    @Test
    void codedAndExtendedValuesSkipTheBlankCodedValue()
    {
        Map<String, String> sex = define.getVariableMetadata("DM", "SEX");

        assertEquals(List.of("M", "F"),
                DefineMetadataListCodec.decode(sex.get("codelist_coded_values")),
                "var_codelist_coded_values(\"DEFINE\"): the item without a CodedValue and the blank"
                        + " one are skipped");
        assertEquals(List.of("F"),
                DefineMetadataListCodec.decode(sex.get("codelist_extended_values")),
                "var_codelist_extended_values(\"DEFINE\"): the blank CodedValue is flagged"
                        + " def:ExtendedValue=\"Yes\" and is skipped all the same — it names no term");
    }

}
