package net.cumba.corej.core.metadata;

import static net.cumba.datatable.testkit.TestMetadataFixtures.column;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import net.cumba.datatable.metadata.IDataTableMetadata;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * Coverage for Fix #41's {@link CustomDomainClassDetector}.
 *
 * <p>
 * Each topic-variable pattern from Python's {@code _handle_custom_domains}
 * ({@code cdisc-rules-engine/cdisc_rules_engine/services/data_services/base_data_service.py:239-254})
 * has a positive case (pattern present → expected class returned) and a negative case (pattern
 * absent → null). The DOMAIN-vs-RDOMAIN switch is exercised explicitly so the SUPP/SQAP family path
 * stays correct (literal {@code QNAM} not {@code SUPPDMQNAM}).
 * </p>
 */
class CustomDomainClassDetectorTest
{

    private static IDataTableMetadata withColumns(String tableName, String... cols)
    {
        var b = table(tableName);
        int i = 0;
        for (String c : cols)
        {
            b.column(column(c, i++, DataValueType.STRING).build());
        }
        return b.build();
    }

    // ----- Topic-variable patterns (DOMAIN-prefixed branch) -----


    @Test
    void detectClass_eventsViaDomainPrefixedTERM()
    {
        IDataTableMetadata meta = withColumns("MYAE", "STUDYID", "USUBJID", "DOMAIN", "MYAETERM");
        assertEquals("EVENTS", CustomDomainClassDetector.detectClass(meta, "MYAE"));
    }


    @Test
    void detectClass_interventionsViaDomainPrefixedTRT()
    {
        IDataTableMetadata meta = withColumns("MYCM", "STUDYID", "USUBJID", "DOMAIN", "MYCMTRT");
        assertEquals("INTERVENTIONS", CustomDomainClassDetector.detectClass(meta, "MYCM"));
    }


    @Test
    void detectClass_relationshipViaDomainPrefixedQNAM()
    {
        // Theoretical: a regular SDTM domain with a QNAM column. Not common but the
        // heuristic still applies symmetrically.
        IDataTableMetadata meta = withColumns("XX", "STUDYID", "USUBJID", "DOMAIN", "XXQNAM");
        assertEquals("RELATIONSHIP", CustomDomainClassDetector.detectClass(meta, "XX"));
    }


    @Test
    void detectClass_findingsAboutViaTESTCDplusOBJ()
    {
        IDataTableMetadata meta = withColumns("MYFA", "STUDYID", "USUBJID", "DOMAIN", "MYFATESTCD",
                "MYFAOBJ");
        assertEquals("FINDINGS ABOUT", CustomDomainClassDetector.detectClass(meta, "MYFA"));
    }


    @Test
    void detectClass_findingsViaTESTCDwithoutOBJ()
    {
        IDataTableMetadata meta = withColumns("MYLB", "STUDYID", "USUBJID", "DOMAIN", "MYLBTESTCD");
        assertEquals("FINDINGS", CustomDomainClassDetector.detectClass(meta, "MYLB"));
    }


    @Test
    void detectClass_priority_TERMwinsOverTRT()
    {
        // Both TERM and TRT present — TERM (EVENTS) wins per Python's order.
        IDataTableMetadata meta = withColumns("XX", "STUDYID", "USUBJID", "DOMAIN", "XXTERM",
                "XXTRT");
        assertEquals("EVENTS", CustomDomainClassDetector.detectClass(meta, "XX"));
    }


    @Test
    void detectClass_priority_TRTwinsOverQNAM()
    {
        IDataTableMetadata meta = withColumns("XX", "STUDYID", "USUBJID", "DOMAIN", "XXTRT",
                "XXQNAM");
        assertEquals("INTERVENTIONS", CustomDomainClassDetector.detectClass(meta, "XX"));
    }


    @Test
    void detectClass_priority_QNAMwinsOverTESTCD()
    {
        IDataTableMetadata meta = withColumns("XX", "STUDYID", "USUBJID", "DOMAIN", "XXQNAM",
                "XXTESTCD");
        assertEquals("RELATIONSHIP", CustomDomainClassDetector.detectClass(meta, "XX"));
    }

    // ----- RDOMAIN (SUPP/SQAP) branch — literal column names, no domain prefix -----


    @Test
    void detectClass_rdomainBranch_relationshipViaLiteralQNAM()
    {
        // A SUPP-shaped dataset. RDOMAIN present, no DOMAIN — Python's _contains_topic_variable
        // looks for the literal column name (no prefix). QNAM exists → RELATIONSHIP.
        IDataTableMetadata meta = withColumns("SUPPDM", "STUDYID", "USUBJID", "RDOMAIN", "QNAM",
                "QVAL");
        assertEquals("RELATIONSHIP", CustomDomainClassDetector.detectClass(meta, "SUPPDM"));
    }


    @Test
    void detectClass_rdomainBranch_eventsViaLiteralTERM()
    {
        // A defensive case: dataset with RDOMAIN but a literal TERM column.
        IDataTableMetadata meta = withColumns("XYZ", "STUDYID", "USUBJID", "RDOMAIN", "TERM");
        assertEquals("EVENTS", CustomDomainClassDetector.detectClass(meta, "XYZ"));
    }


    @Test
    void detectClass_rdomainBranch_findingsAboutViaLiteralTESTCDplusOBJ()
    {
        IDataTableMetadata meta = withColumns("XYZ", "STUDYID", "USUBJID", "RDOMAIN", "TESTCD",
                "OBJ");
        assertEquals("FINDINGS ABOUT", CustomDomainClassDetector.detectClass(meta, "XYZ"));
    }

    // ----- Negative cases -----


    @Test
    void detectClass_neitherDomainNorRdomain_returnsNull()
    {
        // No DOMAIN and no RDOMAIN — Python returns False from _contains_topic_variable's first
        // check, so no class can be inferred.
        IDataTableMetadata meta = withColumns("XX", "STUDYID", "USUBJID", "XXTERM");
        assertNull(CustomDomainClassDetector.detectClass(meta, "XX"));
    }


    @Test
    void detectClass_noTopicVariable_returnsNull()
    {
        IDataTableMetadata meta = withColumns("XX", "STUDYID", "USUBJID", "DOMAIN", "XXFOO");
        assertNull(CustomDomainClassDetector.detectClass(meta, "XX"));
    }


    @Test
    void detectClass_nullDomain_returnsNull()
    {
        IDataTableMetadata meta = withColumns("XX", "STUDYID", "USUBJID", "DOMAIN", "XXTERM");
        assertNull(CustomDomainClassDetector.detectClass(meta, null));
    }


    @Test
    void detectClass_nullMeta_returnsNull()
    {
        assertNull(CustomDomainClassDetector.detectClass((IDataTableMetadata) null, "XX"));
    }

    // ----- Set<String> column-name overload (Part B: sniff the actual dataset) -----


    @Test
    void detectClass_setOverload_supRelationshipViaLiteralQnam()
    {
        // SUPPAE-shaped columns: RDOMAIN present (no DOMAIN) ⇒ literal QNAM ⇒ RELATIONSHIP.
        Set<String> cols = Set.of("STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "QNAM",
                "QLABEL", "QVAL");
        assertEquals("RELATIONSHIP", CustomDomainClassDetector.detectClass(cols, "SUPPAE"));
    }


    @Test
    void detectClass_setOverload_caseInsensitiveColumnNames()
    {
        Set<String> cols = Set.of("rdomain", "qnam", "qval");
        assertEquals("RELATIONSHIP", CustomDomainClassDetector.detectClass(cols, "suppae"));
    }


    @Test
    void detectClass_setOverload_findingsViaDomainPrefixedTestcd()
    {
        Set<String> cols = Set.of("STUDYID", "DOMAIN", "USUBJID", "MYLBTESTCD");
        assertEquals("FINDINGS", CustomDomainClassDetector.detectClass(cols, "MYLB"));
    }


    @Test
    void detectClass_setOverload_findingsAboutWhenObjPresent()
    {
        Set<String> cols = Set.of("DOMAIN", "MYFATESTCD", "MYFAOBJ");
        assertEquals("FINDINGS ABOUT", CustomDomainClassDetector.detectClass(cols, "MYFA"));
    }


    @Test
    void detectClass_setOverload_nullEmptyOrNoDomainColumn_returnsNull()
    {
        assertNull(CustomDomainClassDetector.detectClass((Set<String>) null, "XX"));
        assertNull(CustomDomainClassDetector.detectClass(Set.of(), "XX"));
        // Neither DOMAIN nor RDOMAIN present ⇒ not classifiable.
        assertNull(CustomDomainClassDetector.detectClass(Set.of("STUDYID", "USUBJID"), "XX"));
        assertNull(CustomDomainClassDetector.detectClass(Set.of("DOMAIN", "XXTERM"), ""));
    }


    @Test
    void detectClass_emptyDomain_returnsNull()
    {
        IDataTableMetadata meta = withColumns("XX", "STUDYID", "USUBJID", "DOMAIN", "XXTERM");
        assertNull(CustomDomainClassDetector.detectClass(meta, ""));
    }


    @Test
    void detectClass_caseInsensitiveColumnLookup()
    {
        // CDISC convention is uppercase but data may carry mixed case. Detector is
        // case-insensitive on column names, mirroring tolerance elsewhere in the engine.
        IDataTableMetadata meta = withColumns("xx", "studyid", "usubjid", "domain", "xxterm");
        assertEquals("EVENTS", CustomDomainClassDetector.detectClass(meta, "xx"));
    }

}
