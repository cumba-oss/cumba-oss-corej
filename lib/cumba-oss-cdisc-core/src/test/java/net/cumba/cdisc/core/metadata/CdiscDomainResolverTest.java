package net.cumba.cdisc.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.values.IDataValue;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Fix #59 {@link CdiscDomainResolver}.
 *
 * <p>
 * Resolution order under test:
 * </p>
 * <ol>
 * <li>{@code DOMAIN} column on row 0 wins.</li>
 * <li>Otherwise, {@code SplitDatasetUtil.unsplitName(memberName)} for digit/SUPP/AP splits.</li>
 * <li>Otherwise, raw member name.</li>
 * </ol>
 */
class CdiscDomainResolverTest
{

    private static IDataTable mockTable(String memberName, boolean hasDomainColumn,
            String domainColumnValue, long rowCount)
    {
        IDataTable table = mock(IDataTable.class);
        DataTableMeta meta = mock(DataTableMeta.class);
        lenient().when(meta.getName()).thenReturn(memberName);
        lenient().when(meta.getColumnIndex("DOMAIN")).thenReturn(hasDomainColumn ? 3 : -1);
        lenient().when(table.getMetaData()).thenReturn(meta);
        lenient().when(table.getRowCount()).thenReturn(rowCount);
        if (hasDomainColumn && rowCount > 0)
        {
            IDataValue dv = mock(IDataValue.class);
            lenient().when(dv.isMissingOrInvalid()).thenReturn(domainColumnValue == null);
            lenient().when(dv.getValueAsString()).thenReturn(domainColumnValue);
            IDataTableColumn col = mock(IDataTableColumn.class);
            lenient().when(col.getDataValue(anyLong())).thenReturn(dv);
            lenient().when(table.getColumn(3)).thenReturn(col);
        }
        return table;
    }


    @Test
    void cdiscDomainOf_domainColumnPresentWithValue_returnsColumnValue()
    {
        // Member name LBHE, DOMAIN column says LB → LB wins. This is the LBHE bug regression.
        IDataTable table = mockTable("LBHE", true, "LB", 1);
        assertEquals("LB", CdiscDomainResolver.cdiscDomainOf(table));
    }


    @Test
    void cdiscDomainOf_lbheStyleSplit_withoutDomainColumn_returnsMemberName()
    {
        // LBHE-style suffix isn't recognised by SplitDatasetUtil.unsplitName (it's not digit-
        // suffix and not SUPP/AP-prefix). Without a DOMAIN column, the resolver falls back to
        // the member name. This is acceptable — the engine has nothing better to go on.
        IDataTable table = mockTable("LBHE", false, null, 0);
        assertEquals("LBHE", CdiscDomainResolver.cdiscDomainOf(table));
    }


    @Test
    void cdiscDomainOf_digitSuffixSplit_withoutDomainColumn_unsplits()
    {
        // LB1 → LB via SplitDatasetUtil.unsplitName even when DOMAIN column is missing.
        IDataTable table = mockTable("LB1", false, null, 0);
        assertEquals("LB", CdiscDomainResolver.cdiscDomainOf(table));
    }


    @Test
    void cdiscDomainOf_suppLetterSplit_withoutDomainColumn_unsplits()
    {
        // SUPPLBHM → SUPPLBH (SUPP letter-suffix split rule).
        IDataTable table = mockTable("SUPPLBHM", false, null, 0);
        assertEquals("SUPPLBH", CdiscDomainResolver.cdiscDomainOf(table));
    }


    @Test
    void cdiscDomainOf_domainColumnPresentButRowCountZero_fallsBackToMemberName()
    {
        // DOMAIN column exists but the table has zero rows → cannot read row 0 → falls back.
        IDataTable table = mockTable("AE", true, "AE", 0);
        assertEquals("AE", CdiscDomainResolver.cdiscDomainOf(table));
    }


    @Test
    void cdiscDomainOf_domainColumnValueMissing_fallsBackToMemberName()
    {
        // Row 0 has DOMAIN as missing → falls back. Mirrors a malformed dataset.
        IDataTable table = mockTable("AE", true, null, 1);
        assertEquals("AE", CdiscDomainResolver.cdiscDomainOf(table));
    }


    @Test
    void cdiscDomainOf_domainColumnEmptyString_fallsBackToMemberName()
    {
        IDataTable table = mockTable("AE", true, "", 1);
        assertEquals("AE", CdiscDomainResolver.cdiscDomainOf(table));
    }


    @Test
    void cdiscDomainOf_nonSplitDatasetWithoutDomainColumn_returnsMemberName()
    {
        // Standard non-split AE without a DOMAIN column → member name as-is.
        IDataTable table = mockTable("AE", false, null, 0);
        assertEquals("AE", CdiscDomainResolver.cdiscDomainOf(table));
    }


    @Test
    void cdiscDomainOf_nullTable_returnsEmptyString()
    {
        assertEquals("", CdiscDomainResolver.cdiscDomainOf(null));
    }


    @Test
    void cdiscDomainOf_nullMemberName_returnsEmptyString()
    {
        IDataTable table = mock(IDataTable.class);
        DataTableMeta meta = mock(DataTableMeta.class);
        lenient().when(meta.getName()).thenReturn(null);
        lenient().when(meta.getColumnIndex("DOMAIN")).thenReturn(-1);
        lenient().when(table.getMetaData()).thenReturn(meta);
        lenient().when(table.getRowCount()).thenReturn(0L);
        assertEquals("", CdiscDomainResolver.cdiscDomainOf(table));
    }


    @Test
    void cdiscDomainOf_emptyMemberName_returnsEmptyString()
    {
        IDataTable table = mock(IDataTable.class);
        DataTableMeta meta = mock(DataTableMeta.class);
        lenient().when(meta.getName()).thenReturn("");
        lenient().when(meta.getColumnIndex("DOMAIN")).thenReturn(-1);
        lenient().when(table.getMetaData()).thenReturn(meta);
        lenient().when(table.getRowCount()).thenReturn(0L);
        assertEquals("", CdiscDomainResolver.cdiscDomainOf(table));
    }


    @Test
    void cdiscDomainOf_domainColumnValueDifferentFromMember_columnWins()
    {
        // Defensive: DOMAIN column says XX even though member name is LBHE. Column wins —
        // the resolver trusts the data.
        IDataTable table = mockTable("LBHE", true, "XX", 1);
        assertEquals("XX", CdiscDomainResolver.cdiscDomainOf(table));
    }

}
