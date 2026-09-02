package net.cumba.cdisc.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * CX-2 probe — {@code CDISC-SEND-0272} / {@code FDA-SD0062} promoted from filename-extension
 * proxies to checks of the authoritative {@code file_format} metadata key the host providers stamp
 * ({@code DataTableMetaSupport.setFileFormat}: the parser that actually read the source declares
 * its format). A table without the key (older host) is excluded by the {@code non_empty} guard
 * rather than false-firing.
 */
class Cx2FileFormatRulesProbeTest
{

    private static final YAMLMapper MAPPER = (YAMLMapper) new YAMLMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static Rule load(String path) throws Exception
    {
        Rule rule = MAPPER.readValue(Files.readString(Path.of(path)), Rule.class);
        // Form-B operations (PLAN-retire-corpus-transforms phase 8) carry no operator
        // until normalized — the same pass the loader and RuleScaffold run.
        RulePackageLoader.normalizeOperations(rule);
        net.cumba.cdisc.core.RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    private static int violations(Rule rule, IDataTable table)
    {
        RuleExecutionResult result = RuleRunner.execute(rule, table, _ -> null);
        return result.getViolationCount();
    }


    private static IDataTable withFormat(String format)
    {
        MockTable t = MockTable.of().col("USUBJID", "S1").name("AE");
        if (format != null)
        {
            t = t.metaValue("file_format", format);
        }
        return t.build();
    }


    @Test
    void send272_firesOnNonXportSourceFormat() throws Exception
    {
        Rule rule = load("src/test/resources/fixtures/rules/checks/CDISC/CDISC-SEND-0272.yaml");
        assertEquals(1, violations(rule, withFormat("DATASET-JSON")),
                "a SEND dataset loaded from Dataset-JSON must fire");
        assertEquals(1, violations(rule, withFormat("CSV")), "CSV source must fire");
        assertEquals(0, violations(rule, withFormat("XPORT")),
                "an XPORT-parsed source must not fire");
        assertEquals(0, violations(rule, withFormat(null)),
                "no file_format key (older host) is excluded by the guard, not fired");
    }


    @Test
    void sd0062_acceptsXportAndCsv() throws Exception
    {
        Rule rule = load("src/test/resources/fixtures/rules/checks/FDA/FDA-SD0062.yaml");
        assertEquals(0, violations(rule, withFormat("XPORT")), "XPORT is a valid source");
        assertEquals(0, violations(rule, withFormat("CSV")),
                "text-delimited (CSV) is a valid source per the sheet");
        assertEquals(1, violations(rule, withFormat("XLSX")), "XLSX source must fire");
        assertEquals(1, violations(rule, withFormat("SAS7BDAT")),
                "a sas7bdat source is not a submission format and must fire");
        assertEquals(0, violations(rule, withFormat(null)),
                "no file_format key (older host) is excluded by the guard, not fired");
    }
}
