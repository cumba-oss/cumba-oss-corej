package net.cumba.corej.ruletest.cdt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import net.cumba.corej.ruletest.cdt.CdtLoader.CdtParseException;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link CdtLoader}.
 */
class CdtLoaderTest
{

    @Test
    void parse_minimal_charColumns()
    {
        String content = """
                dataset ADLBC
                col USUBJID
                col PARAMCD
                ---
                01-001 | ALB
                01-002 | GLUC
                """;
        OverlayDataTable table = CdtLoader.parse(content, "test");
        DataTableMeta meta = table.getMetaData();
        assertEquals("ADLBC", meta.getName());
        assertEquals(2, table.getRowCount());
        assertEquals(2, meta.getColumnCount());
        assertEquals("USUBJID", meta.getColumn(0).getName());
        assertEquals(DataValueType.STRING, meta.getColumn(0).getType());
        assertEquals("01-001", table.getValue(0, 0));
        assertEquals("ALB", table.getValue(0, 1));
        assertEquals("01-002", table.getValue(1, 0));
        assertEquals("GLUC", table.getValue(1, 1));
    }


    @Test
    void parse_numericColumn_parsesDouble()
    {
        String content = """
                dataset ADLBC
                col USUBJID type=Char
                col AVAL    type=Num
                ---
                01-001 | 3.5
                01-002 | 95.2
                """;
        OverlayDataTable table = CdtLoader.parse(content, "test");
        assertEquals(DataValueType.DOUBLE, table.getMetaData().getColumn(1).getType());
        assertEquals(3.5, (double) table.getValue(0, 1), 0.0001);
        assertEquals(95.2, (double) table.getValue(1, 1), 0.0001);
    }


    @Test
    void parse_nullValues_emptyField_and_dot()
    {
        String content = """
                dataset ADLBC
                col USUBJID type=Char
                col PARAMCD type=Char
                col AVAL    type=Num
                ---
                01-001 |      | 3.5
                01-002 | GLUC | .
                """;
        OverlayDataTable table = CdtLoader.parse(content, "test");
        // Empty char field → null
        assertNull(table.getValue(0, 1));
        // Dot in numeric field → null
        assertNull(table.getValue(1, 2));
        // Other values present
        assertEquals("01-001", table.getValue(0, 0));
        assertEquals("GLUC", table.getValue(1, 1));
    }


    @Test
    void parse_quotedLabel_withSpaces()
    {
        String content = """
                dataset ADLBC label="Lab Chemistry"
                col USUBJID type=Char label="Unique Subject Identifier"
                ---
                01-001
                """;
        OverlayDataTable table = CdtLoader.parse(content, "test");
        assertEquals("Lab Chemistry", table.getMetaData().getLabel());
        assertEquals("Unique Subject Identifier", table.getMetaData().getColumn(0).getLabel());
    }


    @Test
    void parse_classShortcut_expanded()
    {
        String content = """
                dataset ADLBC class=BDS
                col USUBJID
                ---
                01-001
                """;
        OverlayDataTable table = CdtLoader.parse(content, "test");
        Object classVal = table.getMetaData().getMetaData("dataset_class");
        assertEquals("BASIC DATA STRUCTURE", classVal);
    }


    /**
     * The {@code uri=} dataset attribute becomes the table's source URI — the input
     * {@code extract_metadata("dataset_location")} reads — and is ALSO kept in the table-metadata
     * map so the attribute survives a CdtWriter round-trip.
     */
    @Test
    void parse_uriAttribute_setsTableUriAndKeepsMetadataKey()
    {
        String content = """
                dataset BW uri=file:///study/send/BW.xpt
                col USUBJID
                ---
                01-001
                """;
        OverlayDataTable table = CdtLoader.parse(content, "test");
        assertEquals(java.net.URI.create("file:///study/send/BW.xpt"),
                table.getMetaData().getTableURI());
        assertEquals("file:///study/send/BW.xpt", table.getMetaData().getMetaData("uri"));
    }


    /** No {@code uri=} means no URI: the accessor must see null, not a fabricated location. */
    @Test
    void parse_withoutUriAttribute_leavesTableUriNull()
    {
        String content = """
                dataset BW
                col USUBJID
                ---
                01-001
                """;
        assertNull(CdtLoader.parse(content, "test").getMetaData().getTableURI());
    }


    /** A malformed URI is an authoring error naming the dataset, never a silently dropped attr. */
    @Test
    void parse_malformedUri_isRejected()
    {
        String content = """
                dataset BW uri="file:// bad uri"
                col USUBJID
                ---
                01-001
                """;
        CdtParseException ex = assertThrows(CdtParseException.class,
                () -> CdtLoader.parse(content, "test"));
        assertTrue(ex.getMessage().contains("dataset BW"), ex.getMessage());
        assertTrue(ex.getMessage().contains("invalid uri="), ex.getMessage());
    }


    @Test
    void parse_classFullName_passthrough()
    {
        String content = """
                dataset ADAE class="OCCURRENCE DATA STRUCTURE"
                col USUBJID
                ---
                01-001
                """;
        OverlayDataTable table = CdtLoader.parse(content, "test");
        assertEquals("OCCURRENCE DATA STRUCTURE", table.getMetaData().getMetaData("dataset_class"));
    }


    @Test
    void parse_dateColumn_isoStringToSasDays()
    {
        // 2020-01-01 is 21915 days after SAS epoch 1960-01-01
        String content = """
                dataset ADLBC
                col ASTDT type=Date
                ---
                2020-01-01
                """;
        OverlayDataTable table = CdtLoader.parse(content, "test");
        double value = (double) table.getValue(0, 0);
        assertEquals(21915.0, value, 0.0001);
        // Auto-derived format
        assertEquals("DATE9.", table.getMetaData().getColumn(0).getDisplayFormat());
    }


    @Test
    void parse_timeColumn_isoStringToSeconds()
    {
        String content = """
                dataset ADLBC
                col ASTTM type=Time
                ---
                14:30:00
                """;
        OverlayDataTable table = CdtLoader.parse(content, "test");
        double value = (double) table.getValue(0, 0);
        assertEquals(14 * 3600 + 30 * 60, value, 0.0001);
        assertEquals("TIME5.", table.getMetaData().getColumn(0).getDisplayFormat());
    }


    @Test
    void parse_datetimeColumn_isoStringToSasSeconds()
    {
        // 2020-01-01T00:00:00 = 21915 days * 86400 sec
        String content = """
                dataset ADLBC
                col ASTDTM type=DateTime
                ---
                2020-01-01T00:00:00
                """;
        OverlayDataTable table = CdtLoader.parse(content, "test");
        double value = (double) table.getValue(0, 0);
        assertEquals(21915.0 * 86400, value, 0.0001);
        assertEquals("DATETIME20.", table.getMetaData().getColumn(0).getDisplayFormat());
    }


    @Test
    void parse_explicitFormat_overridesAutoDerivation()
    {
        String content = """
                dataset ADLBC
                col ASTDT type=Date format=YYMMDD10.
                ---
                2020-01-01
                """;
        OverlayDataTable table = CdtLoader.parse(content, "test");
        assertEquals("YYMMDD10.", table.getMetaData().getColumn(0).getDisplayFormat());
    }


    @Test
    void parse_lengthAttribute()
    {
        String content = """
                dataset ADLBC
                col USUBJID type=Char length=20
                ---
                01-001
                """;
        OverlayDataTable table = CdtLoader.parse(content, "test");
        assertEquals(20, table.getMetaData().getColumn(0).getLength());
    }


    @Test
    void parse_codelistAttribute_storedAsMetadata()
    {
        String content = """
                dataset ADSL
                col RACE type=Char codelist=C74457
                ---
                WHITE
                """;
        OverlayDataTable table = CdtLoader.parse(content, "test");
        assertEquals("C74457", table.getMetaData().getMetaData("RACE.codelist"));
    }


    @Test
    void parse_comments_and_blankLines_ignored()
    {
        String content = """
                # This is a comment
                # Another comment
                dataset ADLBC

                # Comment between dataset and columns
                col USUBJID type=Char

                col PARAMCD type=Char
                # Comment before separator
                ---
                # Comment in data section
                01-001 | ALB

                01-002 | GLUC
                """;
        OverlayDataTable table = CdtLoader.parse(content, "test");
        assertEquals(2, table.getRowCount());
    }


    @Test
    void parse_unknownAttribute_silentlyIgnored()
    {
        String content = """
                dataset ADLBC version=1.3
                col USUBJID type=Char origin=Assigned mandatory=true
                ---
                01-001
                """;
        OverlayDataTable table = CdtLoader.parse(content, "test");
        // Parses without error. Unknown dataset attrs stored as table metadata.
        assertNotNull(table.getMetaData());
        assertEquals("01-001", table.getValue(0, 0));
    }


    @Test
    void parse_missingSeparator_throwsError()
    {
        String content = """
                dataset ADLBC
                col USUBJID
                01-001
                """;
        CdtParseException ex = assertThrows(CdtParseException.class,
                () -> CdtLoader.parse(content, "test"));
        // The provider parser reports the expected-col-or-fence error; look for either
        // substring. The original wording included "---" but the refactor in commit
        // 7cd8995d switched to "fence line".
        assertTrue(ex.getMessage().contains("fence") || ex.getMessage().contains("expected 'col"),
                "unexpected error message: " + ex.getMessage());
    }


    private static Stream<Arguments> parseErrorScenarios()
    {
        return Stream.of(Arguments.of("missingDatasetLine", """
                col USUBJID
                ---
                01-001
                """, "expected 'dataset'"), Arguments.of("wrongFieldCount", """
                dataset ADLBC
                col USUBJID type=Char
                col PARAMCD type=Char
                ---
                01-001
                """, "1 field"), Arguments.of("invalidNumericValue", """
                dataset ADLBC
                col AVAL type=Num
                ---
                not-a-number
                """, "invalid NUM"), Arguments.of("unknownType", """
                dataset ADLBC
                col USUBJID type=Boolean
                ---
                true
                """, "unknown column type"), Arguments.of("unterminatedQuote", """
                dataset ADLBC label="Unterminated
                col USUBJID
                ---
                01-001
                """, "unterminated quoted string"));
    }


    @ParameterizedTest(name = "parse_{0}_throwsError")
    @MethodSource("parseErrorScenarios")
    void parse_throwsError(String aScenario, String aContent, String aExpectedSubstring)
    {
        CdtParseException ex = assertThrows(CdtParseException.class,
                () -> CdtLoader.parse(aContent, "test"));
        assertTrue(ex.getMessage().contains(aExpectedSubstring),
                "scenario=" + aScenario + " message=" + ex.getMessage());
    }


    @Test
    void parse_realisticCdiscAd0117_invalid()
    {
        // Real-world example: uniqueness violation within PARAMCD
        String content = """
                # CDISC-AD0117 invalid: same ATPTN=1 maps to two different ATPTs within HEIGHT
                dataset ADLBC class=BDS label="Lab Chemistry"
                col PARAMCD type=Char label="Parameter Code"
                col ATPTN   type=Char label="Planned Time Point Number"
                col ATPT    type=Char label="Planned Time Point Name"
                ---
                HEIGHT | 1 | Baseline
                HEIGHT | 1 | Different Label
                HEIGHT | 2 | Week 2
                """;
        OverlayDataTable table = CdtLoader.parse(content, "test");
        assertEquals("ADLBC", table.getMetaData().getName());
        assertEquals("Lab Chemistry", table.getMetaData().getLabel());
        assertEquals(3, table.getRowCount());
        assertEquals("BASIC DATA STRUCTURE", table.getMetaData().getMetaData("dataset_class"));
        assertEquals("HEIGHT", table.getValue(0, 0));
        assertEquals("1", table.getValue(0, 1));
        assertEquals("Baseline", table.getValue(0, 2));
        assertEquals("Different Label", table.getValue(1, 2));
    }
}
