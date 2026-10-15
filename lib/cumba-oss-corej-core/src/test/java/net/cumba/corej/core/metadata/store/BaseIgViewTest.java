package net.cumba.corej.core.metadata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link BaseIgView#compose} over two small products shaped like the real ones: an AR-like IG
 * (datasets 1–4; class 7 is Trial Design, no Relationship class) and a SENDIG-like base (datasets
 * 1–7; class 7 is Relationship) — the ordinal collision of {@code B-GRAIN}.
 */
class BaseIgViewTest
{

    private static final String IG_KEY = "standards/sendig/ar-1-0";

    private static final String BASE_KEY = "standards/sendig/3-1";

    @Test
    void addsEveryBaseDatasetTheIgLacksIncludingSuppqualInBaseOrder()
    {
        BaseIgView.Composition view = BaseIgView.compose(arLikeIg(), baseLike());

        // DM is the IG's own; every other base dataset comes in, SUPPQUAL included (B-SUPP).
        assertEquals(List.of("CO", "EX", "LB", "TS", "RELREC", "SUPPQUAL"), view.addedDatasets());
        assertEquals(BASE_KEY, view.baseKey());
        assertEquals(List.of("DM", "CO", "CM", "EX", "MB", "LB", "AC", "TS", "RELREC", "SUPPQUAL"),
                datasetNames(view.product()));
    }


    @Test
    void aDomainTheIgPrintsKeepsTheIgsOwnSpecification()
    {
        StoredProduct ig = arLikeIg();
        BaseIgView.Composition view = BaseIgView.compose(ig, baseLike());

        // B-GRAIN: domain-level precedence, never a per-variable merge.
        assertSame(dataset(ig, "DM"), dataset(view.product(), "DM"));
        assertEquals(List.of("STUDYID", "ARDMVAR"), dataset(view.product(), "DM").variables()
                .stream().map(StoredVariable::name).toList());
    }


    @Test
    void addedDatasetsCarryTheBaseContentUnderRenumberedOrdinals()
    {
        BaseIgView.Composition view = BaseIgView.compose(arLikeIg(), baseLike());

        StoredDataset lb = dataset(view.product(), "LB");
        StoredDataset baseLb = dataset(baseLike(), "LB");
        assertEquals(baseLb.label(), lb.label());
        assertEquals(baseLb.variables(), lb.variables());
        assertEquals(baseLb.datasetStructure(), lb.datasetStructure());
        // The IG's datasets are 1-4; the six added ones follow in base order.
        assertEquals(
                Map.of("CO", "5", "EX", "6", "LB", "7", "TS", "8", "RELREC", "9", "SUPPQUAL", "10"),
                addedOrdinals(view));
    }


    @Test
    void ordinalsAreUniqueAcrossTheComposedProduct()
    {
        StoredProduct product = BaseIgView.compose(arLikeIg(), baseLike()).product();

        List<String> datasetOrdinals = new ArrayList<>();
        List<String> classOrdinals = new ArrayList<>();
        for (StoredClass klass : product.classes())
        {
            classOrdinals.add(klass.ordinal());
            for (StoredDataset ds : klass.datasets())
            {
                datasetOrdinals.add(ds.ordinal());
            }
        }
        assertEquals(datasetOrdinals.size(), new HashSet<>(datasetOrdinals).size(),
                "dataset ordinals " + datasetOrdinals);
        assertEquals(classOrdinals.size(), new HashSet<>(classOrdinals).size(),
                "class ordinals " + classOrdinals);
    }


    @Test
    void datasetsJoinTheIgClassOfTheSameNameAndAMissingClassIsAddedAfterTheIgs()
    {
        StoredProduct product = BaseIgView.compose(arLikeIg(), baseLike()).product();

        assertEquals(List.of("DM", "CO"), datasetsOf(product, "Special-Purpose"));
        assertEquals(List.of("AC", "TS"), datasetsOf(product, "Trial Design"));
        assertEquals(List.of("CM", "EX"), datasetsOf(product, "Interventions"));
        // AR has no Relationship class: it is added, numbered after the IG's highest class (7).
        StoredClass relationship = product.classes().get(product.classes().size() - 1);
        assertEquals("Relationship", relationship.name());
        assertEquals("8", relationship.ordinal());
        assertEquals(List.of("RELREC", "SUPPQUAL"),
                relationship.datasets().stream().map(StoredDataset::name).toList());
        // The IG's own classes keep their ordinals.
        assertEquals("7", classNamed(product, "Trial Design").ordinal());
    }


    @Test
    void theComposedProductKeepsTheIgsIdentityAndModel()
    {
        StoredProduct ig = arLikeIg();
        StoredProduct product = BaseIgView.compose(ig, baseLike()).product();

        assertEquals(ig.key(), product.key());
        assertEquals(ig.name(), product.name());
        assertEquals(ig.version(), product.version());
        assertEquals(ig.modelHref(), product.modelHref());
    }


    @Test
    void aBaseThatAddsNothingLeavesTheIgUntouched()
    {
        StoredProduct ig = arLikeIg();
        StoredProduct base = product(BASE_KEY, "/mdr/sdtm/1-5",
                List.of(klass("Special-Purpose", "2", dataset("DM", "1", "STUDYID"))));

        BaseIgView.Composition view = BaseIgView.compose(ig, base);

        assertSame(ig, view.product());
        assertTrue(view.addedDatasets().isEmpty());
    }


    @Test
    void scopeIsExactlyTheThreeProductsEachWithTheBaseItsDocumentNames()
    {
        // B-SCOPE + B-BASE (owner 2026-10-15): AR 1.0 and DART 1.1 name SENDIG v3.1, DART 1.2
        // names SENDIG v3.1.1.
        assertEquals(Map.of("standards/sendig/ar-1-0", "standards/sendig/3-1",
                "standards/sendig/dart-1-1", "standards/sendig/3-1", "standards/sendig/dart-1-2",
                "standards/sendig/3-1-1"), BaseIgView.BASE_IG);
        assertNull(BaseIgView.baseKeyFor("standards/sendig/3-1"));
        assertNull(BaseIgView.baseKeyFor("standards/sendig/genetox-1-0"));
        assertNull(BaseIgView.baseKeyFor("standards/sdtmig/3-4"));
        assertNull(BaseIgView.baseKeyFor(null));
    }

    // ------------------------------------------------------------------ fixtures


    /** AR-like: DM (Special-Purpose), CM (Interventions), MB (Findings), AC (Trial Design, 7). */
    static StoredProduct arLikeIg()
    {
        return product(IG_KEY, "/mdr/sdtm/1-8",
                List.of(klass("General Observations", "1"),
                        klass("Special-Purpose", "2", dataset("DM", "1", "STUDYID", "ARDMVAR")),
                        klass("Interventions", "3", dataset("CM", "2", "CMTRT")),
                        klass("Findings", "5", dataset("MB", "3", "MBTESTCD")),
                        klass("Trial Design", "7", dataset("AC", "4", "ACPARMCD"))));
    }


    /** SENDIG-like: Relationship is class 7; JSON class order is not ordinal order (3-1-1). */
    static StoredProduct baseLike()
    {
        return product(BASE_KEY, "/mdr/sdtm/1-5", List.of(klass("General Observations", "1"),
                klass("Interventions", "3", dataset("EX", "3", "EXTRT")),
                klass("Special-Purpose", "2", dataset("DM", "1", "STUDYID", "BASEDMVAR"),
                        dataset("CO", "2", "COVAL")),
                klass("Findings", "5", dataset("LB", "4", "LBTESTCD", "LBORRES")),
                klass("Trial Design", "6", dataset("TS", "5", "TSPARMCD")), klass("Relationship",
                        "7", dataset("RELREC", "6", "RELID"), dataset("SUPPQUAL", "7", "QNAM"))));
    }


    static StoredProduct product(String aKey, String aModelHref, List<StoredClass> aClasses)
    {
        return StoredProduct.builder().key(aKey).name(aKey)
                .version(aKey.substring(aKey.lastIndexOf('/') + 1)).modelHref(aModelHref)
                .classes(aClasses).build();
    }


    static StoredClass klass(String aName, String aOrdinal, StoredDataset... aDatasets)
    {
        return new StoredClass(aName, aName, aOrdinal, List.of(), List.of(aDatasets), null,
                List.of(), List.of(), List.of());
    }


    static StoredDataset dataset(String aName, String aOrdinal, String... aVariables)
    {
        List<StoredVariable> vars = new ArrayList<>();
        int ordinal = 1;
        for (String name : aVariables)
        {
            vars.add(StoredVariable.builder().name(name).label(aName + " " + name)
                    .ordinal(Integer.toString(ordinal++)).core("Req").role("Identifier")
                    .simpleDatatype("Char").build());
        }
        return new StoredDataset(aName, aName + " label", aOrdinal, "One record per " + aName, vars,
                null, null);
    }


    private static List<String> datasetNames(StoredProduct aProduct)
    {
        return aProduct.classes().stream().flatMap(c -> c.datasets().stream())
                .map(StoredDataset::name).toList();
    }


    private static StoredDataset dataset(StoredProduct aProduct, String aName)
    {
        return aProduct.classes().stream().flatMap(c -> c.datasets().stream())
                .filter(d -> aName.equals(d.name())).findFirst().orElseThrow();
    }


    private static StoredClass classNamed(StoredProduct aProduct, String aName)
    {
        return aProduct.classes().stream().filter(c -> aName.equals(c.name())).findFirst()
                .orElseThrow();
    }


    private static List<String> datasetsOf(StoredProduct aProduct, String aClass)
    {
        return classNamed(aProduct, aClass).datasets().stream().map(StoredDataset::name).toList();
    }


    private static Map<String, String> addedOrdinals(BaseIgView.Composition aView)
    {
        Set<String> added = Set.copyOf(aView.addedDatasets());
        Map<String, String> out = new HashMap<>();
        aView.product().classes().stream().flatMap(c -> c.datasets().stream())
                .filter(d -> added.contains(d.name())).forEach(d -> out.put(d.name(), d.ordinal()));
        return out;
    }
}
