package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * EC-36: {@code OperationExecutor.variableWildcardPrefix} and {@code OperationExecutor.apSuffixOf}
 * — the {@code --} replacement for a <em>variable name</em>, mirroring Python's
 * {@code SDTMDatasetMetadata.wildcard_replacement}.
 *
 * <p>
 * Each case also states what {@link OperationExecutor#domainPrefix(IDataTable)} returns, because
 * the whole point of EC-36 is that the two answer different questions. Where they differ, the
 * {@code domainPrefix} value is the one the engine used before EC-36 — so these tests double as
 * regression pins against reverting to it.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class VariableWildcardPrefixTest
{

    // -----------------------------------------------------------------------
    // Ordinary domains — the two helpers agree
    // -----------------------------------------------------------------------

    @Test
    void ordinaryDomain_usesDomainValue()
    {
        IDataTable lb = MockTable.of().col("DOMAIN", "LB").col("LBTESTCD", "ALB").name("LB")
                .build();

        assertEquals("LB", OperationExecutor.variableWildcardPrefix(lb, "LB"));
        assertEquals("LB", OperationExecutor.domainPrefix(lb), "unchanged for ordinary domains");
    }


    @Test
    void splitMember_usesDomainValueNotName()
    {
        IDataTable lb1 = MockTable.of().col("DOMAIN", "LB").col("LBTESTCD", "ALB").name("LB1")
                .build();

        assertEquals("LB", OperationExecutor.variableWildcardPrefix(lb1, "LB"));
    }

    // -----------------------------------------------------------------------
    // AP — the defect EC-36 fixes
    // -----------------------------------------------------------------------


    @Test
    void apDataset_usesTwoCharParentSuffix()
    {
        // An AP dataset carries PARENT-prefixed variables: APMH holds MHTERM, not APMHTERM.
        // Python: is_ap (APID present) -> ap_suffix "MH". Pre-EC-36 Java resolved --TERM against
        // "APMH" and looked for a column that cannot exist, so the rule never fired.
        IDataTable apmh = MockTable.of().col("DOMAIN", "APMH").col("APID", "A1")
                .col("MHTERM", "HEADACHE").name("APMH").build();

        assertEquals("MH", OperationExecutor.apSuffixOf(apmh, "APMH"));
        assertEquals("MH", OperationExecutor.variableWildcardPrefix(apmh, "APMH"));
        assertEquals("APMH", OperationExecutor.domainPrefix(apmh), "domainPrefix is unchanged");
    }


    @Test
    void apNamedButNoApidColumn_isNotAnApDataset()
    {
        // Python is_ap is `"APID" in first_record` — without it there is no AP suffix, so the
        // DOMAIN value stands and both helpers agree.
        IDataTable apmh = MockTable.of().col("DOMAIN", "APMH").col("MHTERM", "X").name("APMH")
                .build();

        assertEquals("", OperationExecutor.apSuffixOf(apmh, "APMH"));
        assertEquals("APMH", OperationExecutor.variableWildcardPrefix(apmh, "APMH"));
    }


    @Test
    void apDomainShorterThanFourChars_hasNoSuffix()
    {
        // Python's `len(domain) >= 4` gate: substring(2) of a 3-char domain is not a domain code.
        IDataTable apm = MockTable.of().col("DOMAIN", "APM").col("APID", "A1").name("APM").build();

        assertEquals("", OperationExecutor.apSuffixOf(apm, "APM"));
        assertEquals("APM", OperationExecutor.variableWildcardPrefix(apm, "APM"));
    }


    @Test
    void apDatasetWithoutDomainValue_stillUsesTheCallerDomainCode()
    {
        // The AP suffix comes from the caller-supplied DOMAIN CODE, not from the table's row-0
        // DOMAIN cell — that is the whole point of the redesign. A missing DOMAIN column therefore
        // does not disable AP handling; only a missing APID column does.
        IDataTable ap = MockTable.of().col("APID", "A1").col("MHTERM", "X").name("APMH").build();

        assertEquals("MH", OperationExecutor.apSuffixOf(ap, "APMH"));
        assertEquals("MH", OperationExecutor.variableWildcardPrefix(ap, "APMH"));
    }

    // -----------------------------------------------------------------------
    // SUPP / SQ — empty prefix, so --QNAM resolves to the column that exists
    // -----------------------------------------------------------------------


    @Test
    void suppDataset_resolvesToEmptyPrefix()
    {
        // --QNAM must become QNAM. Pre-EC-36 domainPrefix gave "SUPPAE" (=> SUPPAEQNAM) while
        // resolvePrefixes' Fix #33 branch gave "AE" (=> AEQNAM) — Java disagreed with itself.
        IDataTable suppae = MockTable.of().col("RDOMAIN", "AE").col("QNAM", "X").name("SUPPAE")
                .build();

        assertEquals("", OperationExecutor.apSuffixOf(suppae, "SUPPAE"));
        assertEquals("", OperationExecutor.variableWildcardPrefix(suppae, "SUPPAE"));
        assertEquals("SUPPAE", OperationExecutor.domainPrefix(suppae), "domainPrefix is unchanged");
    }


    @Test
    void sqDataset_resolvesToEmptyPrefix()
    {
        IDataTable sqapae = MockTable.of().col("RDOMAIN", "APAE").col("QNAM", "X").name("SQAPAE")
                .build();

        assertEquals("", OperationExecutor.variableWildcardPrefix(sqapae, "SQAPAE"));
    }


    @Test
    void suppDatasetWithApidColumn_isStillSuppNotAp()
    {
        // Python's ap_suffix returns "" for supp datasets unconditionally, before the APID test.
        IDataTable suppap = MockTable.of().col("RDOMAIN", "APAE").col("APID", "A1").col("QNAM", "X")
                .name("SUPPAPAE").build();

        assertEquals("", OperationExecutor.apSuffixOf(suppap, "SUPPAPAE"));
        assertEquals("", OperationExecutor.variableWildcardPrefix(suppap, "SUPPAPAE"));
    }

    // -----------------------------------------------------------------------
    // Everything else: the caller's domain code is returned unchanged
    // -----------------------------------------------------------------------


    @Test
    void zeroRowDataset_resolvesFromItsName()
    {
        // A metadata-only dataset has no row-0 DOMAIN cell, but the caller still supplies a domain
        // code — so resolution is unaffected. (The redesign reads no cell at all; an earlier
        // revision derived the prefix from row 0 and needed a "name tier" to cover this case.)
        IDataTable emptyLb = MockTable.of().col("DOMAIN").col("LBTESTCD").name("LB").build();

        assertEquals(0L, emptyLb.getRowCount());
        assertEquals("LB", OperationExecutor.variableWildcardPrefix(emptyLb, "LB"));
    }


    @Test
    void zeroRowSplitMember_resolvesToItsUnsplitBase()
    {
        // A split member is no different: the caller passes the family's domain code.
        IDataTable emptyLb1 = MockTable.of().col("DOMAIN").col("LBTESTCD").name("LB1").build();

        assertEquals("LB", OperationExecutor.variableWildcardPrefix(emptyLb1, "LB"));
    }


    @Test
    void datasetWithBlankDomainCell_fallsBackToName()
    {
        // A blank row-0 DOMAIN cell is irrelevant — the helper never reads it.
        IDataTable lb = MockTable.of().col("DOMAIN", "").col("LBTESTCD", "ALB").name("LB").build();

        assertEquals("LB", OperationExecutor.variableWildcardPrefix(lb, "LB"));
    }


    @Test
    void relrec_keepsTheCallerDomainCode()
    {
        // RELREC is neither AP nor SUPP, so the prefix is the caller's domain code — IDENTICAL to
        // pre-EC-36 behaviour. An earlier revision returned null here and skipped the rule (D2'),
        // which was withdrawn: its detector suppressed genuine findings. See plan section 10.4.
        IDataTable relrec = MockTable.of().col("RDOMAIN", "AE").col("RELID", "1").name("RELREC")
                .build();

        assertEquals("RELREC", OperationExecutor.variableWildcardPrefix(relrec, "RELREC"));
        assertEquals("RELREC", OperationExecutor.domainPrefix(relrec), "domainPrefix is unchanged");
    }


    @Test
    void adamDataset_keepsTheCallerDomainCode()
    {
        // ADaM datasets carry no DOMAIN column, but the caller still supplies a domain code, so
        // resolution is unchanged from pre-EC-36. (No ADaM rule uses `--` anyway — it is an SDTM
        // convention — measured as 0 scenarios in Phase 0.)
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("AGE", "42").name("ADSL").build();

        assertEquals("ADSL", OperationExecutor.variableWildcardPrefix(adsl, "ADSL"));
    }


    @Test
    void namelessDatasetWithoutDomain_isUnresolvable()
    {
        IDataTable anon = MockTable.of().col("X", "a").build();

        assertNull(OperationExecutor.variableWildcardPrefix(anon, null));
    }


    @Test
    void datasetNameIsIrrelevant_onlyTheCallerDomainCodeMatters()
    {
        // The redesign removed every name-shape heuristic (former decision D5): the caller's domain
        // code is the source, so a lowercase or unusual table name changes nothing.
        IDataTable lb = MockTable.of().col("X", "a").name("lb").build();

        assertEquals("LB", OperationExecutor.variableWildcardPrefix(lb, "LB"));
    }


    @Test
    void nullDomainCode_isTheOnlyUnresolvableCase()
    {
        // Degraded / synthetic contexts with no domain at all: callers substitute nothing, exactly
        // as they did before EC-36.
        IDataTable lb = MockTable.of().col("DOMAIN", "LB").col("LBTESTCD", "ALB").name("LB")
                .build();

        assertNull(OperationExecutor.variableWildcardPrefix(lb, null));
    }


    @Test
    void corruptDomainCell_doesNotHijackResolution()
    {
        // THE regression that sank the first attempt (SdtmAllRuleTest.CORE_000544_invalid): an AE
        // dataset whose row-0 DOMAIN says "GRP1". Re-deriving the prefix from the cell yielded
        // GRP1SEQ and the rule silently no-fired. Anchored to the caller's code, it stays AE.
        IDataTable ae = MockTable.of().col("DOMAIN", "GRP1", "GRP1").col("AESEQ", "1", "2")
                .name("AE").build();

        assertEquals("AE", OperationExecutor.variableWildcardPrefix(ae, "AE"));
    }


    @Test
    void nullTable_isUnresolvable()
    {
        assertNull(OperationExecutor.variableWildcardPrefix(null, null));
    }
}
