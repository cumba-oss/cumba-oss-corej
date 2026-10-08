package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.RealTables;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Runbook W2 AP (§3 table B; W2-D4): an Associated Persons dataset inherits its class from the
 * parent domain the AP suffix names, and the parent is resolved the way every other dataset NAME is
 * — a split parent ({@code LB1} + {@code LB2}, each {@code DOMAIN = LB}) is its union, a lower-case
 * member ({@code lb}) answers for {@code LB} — instead of the exact {@code resolver.resolve} that
 * saw neither. A split parent whose members cannot be unioned leaves the class undetermined and
 * never throws (W2-D4).
 */
class ScopeClassLadderTest
{

    private static final MetadataProvider PROVIDER = CustomDomainWalkFixture.igViewProvider();

    private static IDataTable aplb()
    {
        return RealTables.of("APLB").str("STUDYID", "S1").str("DOMAIN", "APLB").str("USUBJID", "U1")
                .str("APID", "P1").build();
    }


    private static String classOfAplb(DatasetResolver.WithInventory study)
    {
        IDataTable ap = aplb();
        return ScopeClassLadder.classOf(PROVIDER, "APLB", "APLB", ap, study);
    }


    @Test
    void anApDatasetInheritsTheClassOfAnUnsplitParent()
    {
        // the baseline the other cases are measured against
        IDataTable lb = RealTables.of("LB").str("STUDYID", "S1").str("DOMAIN", "LB")
                .str("USUBJID", "U1").str("LBTESTCD", "GLUC").build();
        assertEquals("Findings", classOfAplb(RealTables.inventoryOf(aplb(), lb)));
    }


    @Test
    void anApDatasetInheritsTheClassOfASplitParent()
    {
        IDataTable lb1 = RealTables.of("LB1").str("STUDYID", "S1").str("DOMAIN", "LB")
                .str("USUBJID", "U1").str("LBTESTCD", "GLUC").build();
        IDataTable lb2 = RealTables.of("LB2").str("STUDYID", "S1").str("DOMAIN", "LB")
                .str("USUBJID", "U2").str("LBTESTCD", "ALB").build();
        assertEquals("Findings", classOfAplb(RealTables.inventoryOf(aplb(), lb1, lb2)),
                "the parent LB is the union of LB1 and LB2");
    }


    @Test
    void anApDatasetInheritsTheClassOfALowerCaseParent()
    {
        // a lower-case DOMAIN cell ("aplb") names the parent "lb"; the resolver registers the
        // product's upper-case names and looks them up exactly, as production's does
        IDataTable ap = RealTables.of("APLB").str("STUDYID", "S1").str("DOMAIN", "aplb")
                .str("USUBJID", "U1").str("APID", "P1").build();
        IDataTable lb = RealTables.of("LB").str("STUDYID", "S1").str("DOMAIN", "LB")
                .str("USUBJID", "U1").str("LBTESTCD", "GLUC").build();
        Map<String, IDataTable> byName = new LinkedHashMap<>();
        byName.put("APLB", ap);
        byName.put("LB", lb);
        DatasetResolver.WithInventory exact = new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String aName)
            {
                return byName.get(aName);
            }


            @Override
            public Set<String> availableDatasets()
            {
                return byName.keySet();
            }
        };
        assertEquals("Findings", ScopeClassLadder.classOf(PROVIDER, "APLB", "aplb", ap, exact),
                "dataset names match ignoring case (owner 2026-09-28)");
    }


    @Test
    void aSplitParentThatCannotBeUnionedLeavesTheClassUndeterminedWithoutThrowing()
    {
        // LBSEQ is a number in LB1 and text in LB2: the union is Invalid (ruling 1)
        IDataTable lb1 = RealTables.of("LB1").str("STUDYID", "S1").str("DOMAIN", "LB")
                .str("USUBJID", "U1").str("LBTESTCD", "GLUC").lng("LBSEQ", 1L).build();
        IDataTable lb2 = RealTables.of("LB2").str("STUDYID", "S1").str("DOMAIN", "LB")
                .str("USUBJID", "U2").str("LBTESTCD", "ALB").str("LBSEQ", "x").build();
        DatasetResolver.WithInventory study = RealTables.inventoryOf(aplb(), lb1, lb2);
        String className = assertDoesNotThrow(() -> classOfAplb(study),
                "an un-unionable parent must not abort the dataset (W2-D4)");
        assertNull(className, "class undetermined: class-scoped rules stay SKIPPED");
    }
}
