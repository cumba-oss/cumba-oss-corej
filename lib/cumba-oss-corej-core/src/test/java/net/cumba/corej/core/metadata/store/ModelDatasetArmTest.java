package net.cumba.corej.core.metadata.store;

import static net.cumba.corej.core.metadata.CustomDomainWalkFixture.dataset;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code PLAN-send-base-ig-view} {@code B-MD} — the LIBRARY variable read for a dataset the run's
 * IG lacks and whose class walk returns no row is answered from the run's model definition of that
 * dataset: {@code name}, {@code simpleDatatype}, {@code role} only ({@code LVC-D1} (a)). The reach
 * is the mechanism, not a name list ({@code B-MD-REACH} (b)); "no row" covers a dataset no class
 * places and one placed in a class with nothing to walk ({@code B-MD-WALK} (b)); {@code APRELSUB}
 * is looked up by its own name (the {@code AP--} ladder would strip it to {@code RELSUB}).
 */
class ModelDatasetArmTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    @TempDir
    private Path temp;

    private MetadataProvider provider;

    @BeforeEach
    void openStore() throws IOException
    {
        Path file = temp.resolve("store.zip");
        new MetadataStoreWriter().addProduct(ig()).addProduct(model())
                .publishedCtPackages(List.of()).productCatalogue(List.of()).write(file);
        provider = StoreMetadataProviderFactory.open(file).forSdtm("sdtmig", "3-4", List.of())
                .orElseThrow();
    }


    @Test
    void aDatasetNoClassPlacesReadsItsModelDefinition()
    {
        // DI: not in the IG, not in the curated class map, no sniffer topic.
        IDataTable di = dataset("DI", "DI", "STUDYID", "SPDEVID", "DIPARMCD", "DIVAL");
        assertEquals(Map.of("name", "DIPARMCD", "simpleDatatype", "Char", "role", "Topic"),
                read(di, "DIPARMCD"));
        assertEquals(Map.of("name", "DISEQ", "simpleDatatype", "Num", "role", "Identifier"),
                read(di, "diseq"));
    }


    @Test
    void aDatasetPlacedInAClassWithNothingToWalkReadsItsModelDefinition()
    {
        // SV: the curated map places it in SPECIAL PURPOSE, whose walk is empty (B-MD-WALK (b)).
        IDataTable sv = dataset("SV", "SV", "STUDYID", "USUBJID", "VISITNUM", "SVSTDTC");
        assertEquals(Map.of("name", "SVSTDTC", "simpleDatatype", "Char", "role", "Timing"),
                read(sv, "SVSTDTC"));
    }


    @Test
    void anAssociatedPersonsModelDatasetIsFoundByItsOwnName()
    {
        // APRELSUB: the AP-- ladder strips it to RELSUB. The own-name lookup matters where the IG
        // lacks RELSUB (SEND packages, SDTMIG 3.2): the canonical lookup would read the model's
        // RELSUB, which has no APID. At SDTMIG 3.3 / 3.4 tier 1's AP shim answers instead; this
        // fixture's RELSUB has no APID, so tiers 1-3 miss and the arm is reached.
        IDataTable aprelsub = dataset("APRELSUB", null, "STUDYID", "APID", "RSUBJID", "SREL");
        assertEquals(Map.of("name", "APID", "simpleDatatype", "Char", "role", "Identifier"),
                read(aprelsub, "APID"));
    }


    @Test
    void onlyNameTypeAndRoleAreServedAndAnAbsentRoleIsOmitted()
    {
        // The model row carries a label, core and ordinal: none is served (LVC-D1 (a)). TXVAL
        // carries no role (older models): name and type only.
        IDataTable tx = dataset("TX", "TX", "STUDYID", "SETCD", "TXVAL");
        assertEquals(Map.of("name", "TXVAL", "simpleDatatype", "Char"), read(tx, "TXVAL"));
    }


    @Test
    void aVariableTheModelDatasetDoesNotDefineReadsNothing()
    {
        IDataTable di = dataset("DI", "DI", "STUDYID", "DIPARMCD", "DIFOO");
        assertEquals(Map.of(), read(di, "DIFOO"));
    }


    @Test
    void anIgDefinedDatasetAndASupplementalDatasetAreNotAnsweredByTheModel()
    {
        // RELSUB is IG-defined: a variable the IG row lacks stays unserved, even though the
        // model's RELSUB defines it. SUPP-- stays with the SUPPQUAL tiers.
        IDataTable relsub = dataset("RELSUB", null, "STUDYID", "USUBJID", "RSUBJID", "POOLID");
        assertEquals(Map.of(), read(relsub, "POOLID"));
        IDataTable suppxx = dataset("SUPPXX", null, "STUDYID", "RDOMAIN", "QNAM", "QFOO");
        assertEquals(Map.of(), read(suppxx, "QFOO"));
    }


    @Test
    void aDatasetTheModelDoesNotDefineReadsNothing()
    {
        IDataTable zz = dataset("ZZ", "ZZ", "STUDYID", "ZZPARMCD");
        assertEquals(Map.of(), read(zz, "ZZPARMCD"));
    }


    private Map<String, String> read(IDataTable aTable, String aVariable)
    {
        return ExprCompiler.libraryVariableMetadata(provider, aTable, NO_RESOLVER,
                aTable.getMetaData().getName(), aVariable);
    }


    private static StoredVariable variable(String aName, String aType, String aRole)
    {
        return StoredVariable.builder().name(aName).label(aName + " label").ordinal("1").core("Req")
                .role(aRole).simpleDatatype(aType).build();
    }


    private static StoredDataset ds(String aName, StoredVariable... aVariables)
    {
        return new StoredDataset(aName, aName + " label", "1", null, List.of(aVariables), null,
                null);
    }


    private static StoredClass klass(String aName, List<StoredVariable> aClassVariables,
            StoredDataset... aDatasets)
    {
        return new StoredClass(aName, aName, "1", aClassVariables, List.of(aDatasets), null,
                List.of(), List.of(), List.of());
    }


    private static StoredProduct ig()
    {
        return StoredProduct.builder().key("standards/sdtmig/3-4").name("SDTMIG v3.4")
                .version("3.4").modelHref("/mdr/sdtm/2-0")
                .classes(List.of(
                        klass("Special-Purpose", List.of(),
                                ds("DM", variable("STUDYID", "Char", "Identifier"))),
                        klass("Relationship", List.of(),
                                ds("RELSUB", variable("STUDYID", "Char", "Identifier"),
                                        variable("RSUBJID", "Char", "Identifier")),
                                ds("SUPPQUAL", variable("QNAM", "Char", "Topic")))))
                .build();
    }


    private static StoredProduct model()
    {
        StoredDataset di = ds("DI", variable("STUDYID", "Char", "Identifier"),
                variable("DOMAIN", "Char", "Identifier"), variable("DISEQ", "Num", "Identifier"),
                variable("DIPARMCD", "Char", "Topic"),
                variable("DIVAL", "Char", "Result Qualifier"));
        StoredDataset sv = ds("SV", variable("STUDYID", "Char", "Identifier"),
                variable("SVSTDTC", "Char", "Timing"));
        StoredDataset tx = ds("TX", variable("TXVAL", "Char", null));
        StoredDataset relsub = ds("RELSUB", variable("STUDYID", "Char", "Identifier"),
                variable("POOLID", "Char", "Identifier"));
        StoredDataset aprelsub = ds("APRELSUB", variable("STUDYID", "Char", "Identifier"),
                variable("APID", "Char", "Identifier"));
        return StoredProduct.builder().key("models/sdtm/2-0").name("SDTM v2.0").version("2.0")
                .classes(List.of(
                        klass("General Observations",
                                List.of(variable("STUDYID", "Char", "Identifier"))),
                        klass("Special-Purpose", List.of(), sv),
                        klass("Trial Design", List.of(), tx),
                        klass("Study Reference", List.of(), di),
                        klass("Relationship", List.of(), relsub, aprelsub)))
                .datasets(List.of(sv, tx, di, relsub, aprelsub)).build();
    }
}
