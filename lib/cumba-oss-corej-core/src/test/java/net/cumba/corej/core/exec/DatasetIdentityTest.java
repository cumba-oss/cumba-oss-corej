package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * The dataset-identity statics ({@link DatasetIdentity}): the {@code --} dataset-name wildcard (Fix
 * #33's SUPP/SQAP parent-prefix substitution), the domain prefix ({@code DOMAIN} column over the
 * unsplit table name) and the per-dataset variable template. Moved with the statics from the
 * retired operation executor's tests in runbook W8 ({@code PLAN-retire-operation-surface}); the
 * claims are unchanged.
 */
class DatasetIdentityTest
{

    @Test
    void testResolveWildcard_nullDomain()
    {
        IDataTable table = MockTable.of().col("X", "1").name("AE").build();
        assertNull(DatasetIdentity.resolveWildcard(null, table));
    }


    @Test
    void testResolveWildcard_noDashDash()
    {
        IDataTable table = MockTable.of().col("X", "1").name("AE").build();
        assertEquals("DM", DatasetIdentity.resolveWildcard("DM", table));
    }


    @Test
    void testResolveWildcard_withDashDash()
    {
        IDataTable table = MockTable.of().col("X", "1").name("AE").build();
        assertEquals("SUPPAE", DatasetIdentity.resolveWildcard("SUPP--", table));
    }


    @Test
    void testResolveWildcard_nullTableName()
    {
        IDataTable table = MockTable.of().col("X", "1").build(); // no name set
        assertEquals("SUPP--", DatasetIdentity.resolveWildcard("SUPP--", table));
    }


    @Test
    void resolveWildcard_suppPrimaryTable_substitutesParentPrefix()
    {
        // Table is named "SUPPAE" (a SUPP primary). The "SUPP--" template must resolve to
        // "SUPPAE" using the parent-prefix "AE" — not "SUPPSUPPAE".
        IDataTable suppae = MockTable.of().col("X", "1").name("SUPPAE").build();
        assertEquals("SUPPAE", DatasetIdentity.resolveWildcard("SUPP--", suppae));
    }


    @Test
    void resolveWildcard_sqapPrimary_substitutesParentPrefix()
    {
        IDataTable sqap = MockTable.of().col("X", "1").name("SQAPAP").build();
        assertEquals("SQAPAP", DatasetIdentity.resolveWildcard("SQAP--", sqap));
    }


    @Test
    void resolveWildcard_suppNoSuffix_passedThrough()
    {
        // Length 4 means tableName is just "SUPP" with nothing after — no parent prefix to
        // extract, so the literal-substitution branch runs.
        IDataTable supp = MockTable.of().col("X", "1").name("SUPP").build();
        assertEquals("SUPPSUPP", DatasetIdentity.resolveWildcard("SUPP--", supp));
    }


    @Test
    void domainPrefix_fromDomainColumn()
    {
        IDataTable t = MockTable.of().col("DOMAIN", "AE", "AE").name("AE1").build();
        assertEquals("AE", DatasetIdentity.domainPrefix(t));
    }


    @Test
    void domainPrefix_fallback_unsplitTableName()
    {
        // No DOMAIN column → falls back to SplitDatasetUtil.unsplitName("LB1") → "LB".
        IDataTable t = MockTable.of().col("USUBJID", "S").name("LB1").build();
        assertEquals("LB", DatasetIdentity.domainPrefix(t));
    }


    @Test
    void domainPrefix_nullTable_returnsEmpty()
    {
        assertEquals("", DatasetIdentity.domainPrefix(null));
    }


    @Test
    void domainPrefix_emptyName_returnsEmpty()
    {
        // No DOMAIN column, no name on the table → returns "".
        IDataTable t = MockTable.of().col("X", "1").build();
        assertEquals("", DatasetIdentity.domainPrefix(t));
    }


    @Test
    void resolveTemplate_nullTemplate()
    {
        IDataTable t = MockTable.of().col("X", "1").name("AE").build();
        assertNull(DatasetIdentity.resolveTemplate(null, t));
    }


    @Test
    void resolveTemplate_noWildcard_passedThrough()
    {
        IDataTable t = MockTable.of().col("X", "1").name("AE").build();
        assertEquals("AESEQ", DatasetIdentity.resolveTemplate("AESEQ", t));
    }


    @Test
    void resolveTemplate_substitutesFromDomainColumn()
    {
        IDataTable t = MockTable.of().col("DOMAIN", "AE").name("AE1").build();
        assertEquals("AESEQ", DatasetIdentity.resolveTemplate("--SEQ", t));
    }

}
