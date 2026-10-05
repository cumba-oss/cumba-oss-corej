package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.merge.MergeKind;
import net.cumba.datatable.merge.MergedPart;
import net.cumba.datatable.merge.MergedTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The W2 shadow of {@code PLAN-merged-evaluation-table} ({@link MergedTableShadow}): off by default
 * and inert when off; when on, a keyed and a SUPP read that agree report nothing, the §3.5.3 SUPP
 * case-table movements are seen, a sabotaged row map is seen, and no finding moves either way.
 */
class MergedTableShadowTest
{

    /** Collects every report. */
    private static final class Collector implements MergedTableShadow.Observer
    {

        private final List<MergedTableShadow.Report> reports = new ArrayList<>();

        private final @Nullable MergeKind plant;

        private Collector(@Nullable MergeKind aPlant)
        {
            plant = aPlant;
        }


        @Override
        public synchronized void onExecution(MergedTableShadow.Report report)
        {
            reports.add(report);
        }


        @Override
        public IDataTable valueView(MergedTable merged)
        {
            MergeKind k = plant;
            return k == null ? merged : shifted(merged, k);
        }


        MergedTableShadow.Report only()
        {
            assertEquals(1, reports.size(), () -> "reports: " + reports);
            return reports.get(0);
        }
    }

    @AfterEach
    void off()
    {
        MergedTableShadow.install(null);
    }


    private static Rule load(String ruleJson)
    {
        try
        {
            RulePackage pkg = RulePackageLoader
                    .loadFromString("{\"rules\":{\"X-1\":" + ruleJson + "}}");
            Rule rule = pkg.getRules().get("X-1");
            assertNull(rule.getLoadError(), "fixture must load: " + rule.getLoadError());
            return rule;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + ruleJson, e);
        }
    }


    /** An inventory-capable resolver, so the qualified key requirements can be decided. */
    private static DatasetResolver resolver(IDataTable... tables)
    {
        return new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                for (IDataTable t : tables)
                {
                    if (t.getMetaData().getName().equalsIgnoreCase(name))
                    {
                        return t;
                    }
                }
                return null;
            }


            @Override
            public java.util.Set<String> availableDatasets()
            {
                java.util.Set<String> out = new java.util.LinkedHashSet<>();
                for (IDataTable t : tables)
                {
                    out.add(t.getMetaData().getName());
                }
                return out;
            }
        };
    }


    private static IDataTable ae()
    {
        return RealTableFixture.of("AE").str("STUDYID", "S1", "S1", "S1")
                .str("DOMAIN", "AE", "AE", "AE").str("USUBJID", "01", "01", "02")
                .lng("AESEQ", 1L, 2L, 1L).str("AETERM", "A", "B", "C").build();
    }


    private static IDataTable dm()
    {
        return RealTableFixture.of("DM").str("STUDYID", "S1", "S1", "S1")
                .str("USUBJID", "01", "02", "03").lng("AGE", 54L, 61L, 70L)
                .str("SEX", "F", "M", "F").build();
    }


    private static Rule keyedRule()
    {
        return load("{\"Core\":{\"Id\":\"X-1\"},\"Requirements\":{\"Variables\":{"
                + "\"All\":[\"STUDYID\",\"USUBJID\"],\"All_Or_None\":[[\"STUDYID\",\"DM.STUDYID\"],"
                + "[\"USUBJID\",\"DM.USUBJID\"]]}},\"Match_Datasets\":[{\"Name\":\"DM\","
                + "\"Keys\":[\"STUDYID\",\"USUBJID\"],\"Join_Type\":\"left\"}],"
                + "\"Check\":{\"expression\":\"DM.AGE > 60 or DM.SEX == \\\"F\\\"\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"DM.AGE\",\"DM.SEX\"]}}");
    }


    private static Rule suppRule()
    {
        return load(
                "{\"Core\":{\"Id\":\"X-1\"}," + "\"Check\":{\"expression\":\"not empty(AETRTEM)\"},"
                        + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AETRTEM\"]}}");
    }


    private static IDataTable suppAe(String... rows)
    {
        // rows: STUDYID|USUBJID|IDVARVAL|QVAL, all on QNAM AETRTEM, IDVAR AESEQ
        int n = rows.length;
        String[] studyid = new String[n];
        String[] rdomain = new String[n];
        String[] usubjid = new String[n];
        String[] idvar = new String[n];
        String[] idvarval = new String[n];
        String[] qnam = new String[n];
        String[] qval = new String[n];
        for (int i = 0; i < n; i++)
        {
            String[] f = rows[i].split("\\|", -1);
            studyid[i] = f[0];
            rdomain[i] = "AE";
            usubjid[i] = f[1];
            idvar[i] = "AESEQ";
            idvarval[i] = f[2];
            qnam[i] = "AETRTEM";
            qval[i] = f[3];
        }
        return RealTableFixture.of("SUPPAE").str("STUDYID", studyid).str("RDOMAIN", rdomain)
                .str("USUBJID", usubjid).str("IDVAR", idvar).str("IDVARVAL", idvarval)
                .str("QNAM", qnam).str("QVAL", qval).build();
    }


    @Test
    void offByDefaultTheShadowIsNeverOpened()
    {
        IDataTable ae = ae();
        assertNull(MergedTableShadow.open(keyedRule(), ae, ae, null, false, Map.of(),
                resolver(ae, dm()), "X-1", "AE"));
    }


    @Test
    void agreeingKeyedReadsReportNothingAndMoveNoFinding()
    {
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, dm());
        RuleExecutionResult off = RuleRunnerCalls.execute(keyedRule(), ae, r);
        Collector c = new Collector(null);
        MergedTableShadow.install(c);
        RuleExecutionResult on = RuleRunnerCalls.execute(keyedRule(), ae, r);
        MergedTableShadow.Report report = c.only();
        assertNull(report.notShadowed());
        assertTrue(report.keyedReads() > 0, "the shadow compared no keyed read");
        assertEquals(0, report.divergences(), () -> "divergences: " + report.examples());
        assertEquals(off.getViolations(), on.getViolations(), "the shadow moved a finding");
        assertEquals(3, on.getViolations().size());
    }


    @Test
    void agreeingSuppReadsReportNothing()
    {
        IDataTable ae = ae();
        Collector c = new Collector(null);
        MergedTableShadow.install(c);
        RuleExecutionResult on = RuleRunnerCalls.execute(suppRule(), ae,
                resolver(ae, suppAe("S1|01|1|Y", "S1|02|1|N")));
        MergedTableShadow.Report report = c.only();
        assertTrue(report.suppReads() > 0, "the shadow compared no SUPP read");
        assertEquals(0, report.divergences(), () -> "divergences: " + report.examples());
        assertEquals(2, on.getViolations().size());
    }


    @Test
    void studyidInTheSuppKeyIsSeenAsAMovement()
    {
        // §3.5.1 "STUDYID in the key": today's pivot ignores STUDYID and matches; the merged
        // view's IG key does not.
        IDataTable ae = ae();
        Collector c = new Collector(null);
        MergedTableShadow.install(c);
        RuleRunnerCalls.execute(suppRule(), ae, resolver(ae, suppAe("S2|01|1|Y")));
        MergedTableShadow.Report report = c.only();
        assertEquals(java.util.Set.of(MergedTableShadow.Kind.SUPP_VALUE), report.counts().keySet(),
                () -> "examples: " + report.examples());
        for (MergedTableShadow.Divergence d : report.examples())
        {
            assertEquals(0L, d.realRow(), d::toString);
            assertTrue(d.today().endsWith(":Y") && d.merged().endsWith(":"), d::toString);
        }
    }


    @Test
    void aDuplicateQualifierIsSeenAsTheCaseTableError()
    {
        // §3.5.3 case 2: two rows for one record — today first wins, the merged view ERRORs.
        IDataTable ae = ae();
        Collector c = new Collector(null);
        MergedTableShadow.install(c);
        RuleRunnerCalls.execute(suppRule(), ae, resolver(ae, suppAe("S1|01|1|Y", "S1|01|1|N")));
        MergedTableShadow.Report report = c.only();
        assertEquals(1L, report.counts().get(MergedTableShadow.Kind.SUPP_MERGE_ERROR),
                () -> "examples: " + report.examples());
    }


    @Test
    void aSabotagedKeyedRowMapIsSeen()
    {
        IDataTable ae = ae();
        Collector c = new Collector(MergeKind.KEYED);
        MergedTableShadow.install(c);
        RuleRunnerCalls.execute(keyedRule(), ae, resolver(ae, dm()));
        Long values = c.only().counts().get(MergedTableShadow.Kind.KEYED_VALUE);
        assertNotNull(values, () -> "the sabotage went unseen: " + c.only());
        assertTrue(values > 0);
    }


    @Test
    void aSabotagedSuppRowMapIsSeen()
    {
        IDataTable ae = ae();
        Collector c = new Collector(MergeKind.SUPP);
        MergedTableShadow.install(c);
        RuleRunnerCalls.execute(suppRule(), ae, resolver(ae, suppAe("S1|01|1|Y", "S1|02|1|N")));
        Long values = c.only().counts().get(MergedTableShadow.Kind.SUPP_VALUE);
        assertNotNull(values, () -> "the sabotage went unseen: " + c.only());
    }


    @Test
    void aForwardRelrecIsDeclined()
    {
        IDataTable ae = ae();
        Collector c = new Collector(null);
        MergedTableShadow.install(c);
        Rule rule = load("{\"Core\":{\"Id\":\"X-1\"},\"Match_Datasets\":[{\"Name\":\"RELREC\","
                + "\"Keys\":[]}],\"Check\":{\"expression\":\"AETERM == \\\"A\\\"\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}");
        RuleRunnerCalls.execute(rule, ae, resolver(ae));
        assertTrue(c.reports.stream().allMatch(rep -> rep.notShadowed() != null),
                () -> "reports: " + c.reports);
    }


    /** The merged view with every part of {@code kind} read one merged row on. */
    static IDataTable shifted(MergedTable merged, MergeKind kind)
    {
        boolean[] planted = new boolean[merged.getColumnCount()];
        for (MergedPart p : merged.parts())
        {
            if (p.kind() == kind)
            {
                for (int i = 0; i < p.columnCount(); i++)
                {
                    planted[p.firstColumn() + i] = true;
                }
            }
        }
        long rows = merged.getRowCount();
        return (IDataTable) Proxy.newProxyInstance(IDataTable.class.getClassLoader(), new Class<?>[]
        {
                IDataTable.class
        }, (proxy, method, args) ->
        {
            Object[] a = args;
            String n = method.getName();
            if (a != null && a.length == 2 && a[0] instanceof Long row
                    && a[1] instanceof Integer col && planted[col]
                    && ("getDataValue".equals(n) || "getValue".equals(n) || "isSupplied".equals(n)))
            {
                a = new Object[]
                {
                        (row + 1) % rows, col
                };
            }
            try
            {
                return method.invoke(merged, a);
            }
            catch (InvocationTargetException e)
            {
                throw e.getCause();
            }
        });
    }
}
