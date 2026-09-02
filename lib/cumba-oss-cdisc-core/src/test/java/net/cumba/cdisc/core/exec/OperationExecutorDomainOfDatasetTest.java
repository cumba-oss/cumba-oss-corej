package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * J7 — pins {@link OperationExecutor#domainOfDataset}, the data-driven SDTM domain of a dataset
 * member (Python's {@code SDTMDatasetMetadata.domain}).
 *
 * <p>
 * The method had <b>no direct test at all</b>: mutation testing reported all 16 of its mutants
 * surviving, so every branch below — the no-domain prefixes, the {@code AP--} 4-character rule, the
 * {@code DOMAIN}-cell lookup and the two-character fallback — could be inverted or deleted without
 * a single red test. That matters because the branches encode a measured CDISC fact:
 * {@code SUPP--}/{@code SQ--}/{@code RELREC} carry {@code RDOMAIN}, never {@code DOMAIN}, so they
 * must answer {@code null} rather than fall through to the name-prefix fallback.
 * </p>
 */
class OperationExecutorDomainOfDatasetTest
{

    /** A resolver that knows nothing — every member is unresolvable. */
    private static final DatasetResolver NONE = _ -> null;

    private static IDataTable tableWithDomain(String memberName, String domainCell)
    {
        return MockTable.of().name(memberName).col("DOMAIN", domainCell).build();
    }


    /** Resolves exactly one member name, verbatim (case-sensitive). */
    private static DatasetResolver only(String memberName, IDataTable table)
    {
        return name -> memberName.equals(name) ? table : null;
    }


    @Test
    void nullOrEmptyMemberHasNoDomain()
    {
        assertNull(OperationExecutor.domainOfDataset(null, NONE));
        assertNull(OperationExecutor.domainOfDataset("", NONE));
    }


    @Test
    void suppSqAndRelrecCarryRdomainSoHaveNoDomain()
    {
        // These three ship an RDOMAIN column and no DOMAIN column; answering the name prefix
        // ("SU", "SQ", "RE") would be a fabricated domain.
        assertNull(OperationExecutor.domainOfDataset("SUPPAE", NONE));
        assertNull(OperationExecutor.domainOfDataset("SQAPSC", NONE));
        assertNull(OperationExecutor.domainOfDataset("RELREC", NONE));
    }


    @Test
    void theNoDomainPrefixesAreMatchedCaseInsensitively()
    {
        assertNull(OperationExecutor.domainOfDataset("suppae", NONE));
        assertNull(OperationExecutor.domainOfDataset("sqapsc", NONE));
        assertNull(OperationExecutor.domainOfDataset("relrec", NONE));
    }


    @Test
    void aDomainMerelyStartingWithSIsNotSuppressed()
    {
        // Guards the prefix tests against being widened to "S" / "R".
        assertEquals("SE", OperationExecutor.domainOfDataset("SE", NONE));
        assertEquals("RS", OperationExecutor.domainOfDataset("RS", NONE));
    }


    @Test
    void associatedPersonsMemberIsItsOwnFourCharacterDomain()
    {
        assertEquals("APQS", OperationExecutor.domainOfDataset("APQS", NONE));
        assertEquals("APQS", OperationExecutor.domainOfDataset("apqs", NONE));
        // Longer AP-- members truncate to exactly four characters.
        assertEquals("APQS", OperationExecutor.domainOfDataset("APQSX", NONE));
    }


    @Test
    void anApNameShorterThanFourCharactersFallsThroughToTheNormalPath()
    {
        // Boundary guard on `n.length() >= 4`: "APQ" is 3 chars, so it takes the two-character
        // fallback ("AP"), not the AP-- rule.
        assertEquals("AP", OperationExecutor.domainOfDataset("APQ", NONE));
    }


    @Test
    void anUnresolvableMemberFallsBackToItsFirstTwoCharacters()
    {
        assertEquals("LB", OperationExecutor.domainOfDataset("LBCH", NONE));
        assertEquals("LB", OperationExecutor.domainOfDataset("lbch", NONE));
    }


    @Test
    void aResolvedMemberAnswersItsDomainCellNotItsName()
    {
        // The split member lbch carries DOMAIN=LB — the whole point of J7.
        IDataTable lbch = tableWithDomain("LBCH", "LB");
        assertEquals("LB", OperationExecutor.domainOfDataset("LBCH", only("LBCH", lbch)));
    }


    @Test
    void theResolverIsCalledWithTheOriginalNameNotTheUpperCasedOne()
    {
        // `n` is upper-cased for the prefix tests only; the resolve() call must use the member
        // name as given, or a lower-case study inventory resolves nothing.
        IDataTable lbch = tableWithDomain("LBCH", "LB");
        assertEquals("LB", OperationExecutor.domainOfDataset("lbch", only("lbch", lbch)));
    }


    @Test
    void aTwoCharacterMemberStillConsultsTheResolver()
    {
        // Boundary guard on `n.length() >= 2`: a 2-char member must reach the DOMAIN cell, so a
        // table whose DOMAIN disagrees with its name proves the lookup happened.
        IDataTable xx = tableWithDomain("XX", "ZZ");
        assertEquals("ZZ", OperationExecutor.domainOfDataset("XX", only("XX", xx)));
    }


    @Test
    void aSingleCharacterMemberIsItsOwnUpperCasedDomain()
    {
        assertEquals("A", OperationExecutor.domainOfDataset("A", NONE));
        assertEquals("A", OperationExecutor.domainOfDataset("a", NONE));
    }

}
