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
 * (nothing is ever null) nor as {@code ""} (a present value naming nothing).
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
                "the two named groups, in document order, with the nameless one between them gone");
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
        assertEquals(List.of("M", "F"), terms,
                "the item without a CodedValue is skipped; the two coded items keep their order");
    }

}
