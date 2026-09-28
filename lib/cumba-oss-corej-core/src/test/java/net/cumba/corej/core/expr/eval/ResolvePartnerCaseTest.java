package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.testkit.SyntheticDataTable;
import org.junit.jupiter.api.Test;

/**
 * Fix #123's decode-partner resolution ({@code ExprCompiler.resolvePartner}) matches column names
 * ignoring letter case (owner ruling 2026-09-28, {@code PLAN-case-insensitive-templates}, register
 * {@code CIT §1}) and names the partner in the dataset's own spelling. Mockito-free: the column set
 * is a real {@link SyntheticDataTable}'s metadata.
 */
class ResolvePartnerCaseTest
{

    private static DataTableMeta columns(String... names)
    {
        return new SyntheticDataTable("ADSL", List.of(names), new String[]
        {
                "x"
        }, 1).getMetaData();
    }


    /**
     * The suffix step on a lowercase cursor: {@code trtpn} proposes {@code trtp}. Compared
     * case-sensitively no suffix matched and the metadata-less fallback found nothing.
     */
    @Test
    void theSuffixStepProposesAPartnerForALowercaseCodeVariable()
    {
        assertEquals("trtp", ExprCompiler.resolvePartner("trtpn", columns("trtp", "trtpn", "trta"),
                _ -> ExprCompiler.PartnerVerdict.UNKNOWN));
    }


    /** An upper-case cursor over lowercase data resolves to the dataset's spelling. */
    @Test
    void thePartnerIsNamedInTheDatasetsSpelling()
    {
        assertEquals("trtp", ExprCompiler.resolvePartner("TRTPN", columns("trtp", "trtpn"),
                _ -> ExprCompiler.PartnerVerdict.UNKNOWN));
    }


    /**
     * The unique-match fallback excludes the cursor itself in any case: with {@code etcd} excluded,
     * {@code element} is the one confirmed candidate. Compared case-sensitively {@code etcd} was a
     * second candidate for the cursor {@code ETCD} and the ambiguity answered {@code null}.
     */
    @Test
    void theFallbackExcludesTheCursorIgnoringCase()
    {
        assertEquals("element", ExprCompiler.resolvePartner("ETCD", columns("etcd", "element"),
                _ -> ExprCompiler.PartnerVerdict.CONFIRMED));
    }
}
