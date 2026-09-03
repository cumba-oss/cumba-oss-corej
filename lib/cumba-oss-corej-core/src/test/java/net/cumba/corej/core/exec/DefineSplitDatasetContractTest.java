package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.define.DefineSupport;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.cdisc.define.ODM;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.metadata.DefineXmlMetadataProvider;
import net.cumba.corej.core.metadata.MetadataLibraryProvider;
import net.cumba.corej.core.metadata.OdmDefineXMLProvider;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.DomainScope;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Scope;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.provider.define.metadata.DefineMetadataLibrary;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * <b>The per-part Define contract for a split dataset</b> —
 * {@code plans/done/PLAN-define-split-dataset-contract.md} §4, settled by decision <b>D6 option
 * (a)</b> (2026-08-07). A split dataset's parts are separate datasets for every purpose the
 * {@code Dataset Metadata Check against Define XML} family cares about: each submitted file has its
 * own {@code ItemGroupDef}, each part is evaluated against <em>its own</em> {@code ItemGroupDef},
 * and a finding is attributed to <em>the part</em>. The logical name stays what <em>scope
 * matching</em> resolves, but it is never an evaluation unit and never a finding's dataset.
 *
 * <p>
 * coreJ already conforms to all four clauses; this class exists so it cannot stop conforming
 * silently. The four clauses map onto the tests as:
 * </p>
 * <ul>
 * <li><b>§4.1 Frame</b> &rarr; {@link #frameIsThePartsOwnRows_neverASiblings()} — coreJ has no
 * split concatenation anywhere, so a part's row set is its own file's rows even when a sibling part
 * is resolvable.</li>
 * <li><b>§4.2 Define lookup</b> &rarr; {@link #defineLookupIsKeyedByThePart_notByTheBaseDomain()}
 * and {@link #theUnsplitBaseIsNotAValidDefineKey()} — the key is the physical table's name
 * ({@code EvaluationContext.domainName}, see the note below), never the caller-supplied domain and
 * never the unsplit base.</li>
 * <li><b>§4.3 Attribution</b> &rarr;
 * {@link #theEvaluationUnitIdentityIsThePart_notTheLogicalDomain()} — the dataset identity the
 * evaluation carries is the part. (The report-side {@code DatasetResult.domain} is built from the
 * same library-member name in {@code LibraryValidator}; that layer is not exercised here.)</li>
 * <li><b>§4.4 Scope untouched</b> &rarr; {@link #scopeMatchingStillResolvesTheUnsplitBase()} — a
 * rule scoped to the logical domain must keep running on every part.</li>
 * </ul>
 *
 * <p>
 * <b>⚠ Why the fixture is a real Define-XML and not a hand-built one.</b> Two independent filters
 * reject the same input on this path — {@code RuleRunner} SKIPs a {@code define_*} rule outright
 * when no Define provider is present, and all six shipped rules of the family additionally guard on
 * {@code non_empty(define_dataset_*)}. A fixture that gets either wrong yields a green that pins
 * nothing. The artefact used here is the <b>CDISC MSG v2.0 Define-XML v2.1 sample</b> (vendored
 * into this module at {@code src/test/resources/convert/define-v21-sdtm.xml},
 * {@code Originator="CDISC MSG Team"}), which declares the {@code QS} domain as two submitted files
 * — {@code IG.QSPH} / {@code IG.QSSL}, both {@code Domain="QS"}, each with its own
 * {@code def:leaf href} and, decisively for this test, its <b>own label</b>. The provider is
 * composed exactly as {@code StudyValidationService} composes it in production: the ODM-direct
 * {@link OdmDefineXMLProvider} over the datatable-backed {@link DefineMetadataLibrary} fallback,
 * which is the chain that actually serves {@code define_dataset_*}.
 * </p>
 *
 * <p>
 * <b>⚑⚑ Where the per-part key actually lives — read this before "fixing" a citation.</b> A
 * native-expression rule never reads the {@code define_dataset_*} variables that
 * {@code RuleRunner.injectDatasetLevel} puts into the variable map: {@code MetadataOperandMapping}
 * lowers the whole {@code define_dataset_} prefix to the {@code ds_*(…, "DEFINE")} accessors, which
 * resolve in {@code ExprCompiler.readProviderLevel} against
 * {@code EvaluationContext.getDomainName()} — set at <b>{@code RuleRunner.java:778}</b> from
 * {@code evalTable.getMetaData().getName()}. {@code injectDatasetLevel}
 * ({@code RuleRunner.java:734}) keys by the same part name and serves the legacy non-native leaf
 * path. <b>Both are per-part; only the first is exercised by a shipped rule.</b>
 * </p>
 *
 * <p>
 * <b>Neuter-and-watch evidence (2026-08-07, wave 13 lane G).</b> Two edits, each reverted, each
 * watched failing:
 * </p>
 * <ol>
 * <li>{@code RuleRunner.java:778} {@code .domainName(evalTable.getMetaData().getName())} &rarr;
 * {@code .domainName(domainPrefix)} — i.e. keying the Define lookup by the CDISC domain {@code QS}
 * instead of the part, the most plausible wrong change and the one every other library-facing site
 * in the engine already makes. &rArr; {@link #defineLookupIsKeyedByThePart_notByTheBaseDomain()}
 * <b>RED</b> (<i>"QSPH must be evaluated against QSPH's own ItemGroupDef"</i>), the other five
 * green.</li>
 * <li>{@code ExprCompiler} {@code case DS_NAME -> meta.getName()} &rarr;
 * {@code ctx.getDomainPrefix()}. &rArr;
 * {@link #theEvaluationUnitIdentityIsThePart_notTheLogicalDomain()} <b>RED</b>, the other five
 * green.</li>
 * </ol>
 * <p>
 * ⚠ Neutering {@code RuleRunner.java:734} alone leaves all six <b>green</b> — that site is not on
 * the live path, and a guard aimed only at it would have been vacuous. §4.1 and §4.4 are pinned by
 * mutually exclusive assertion pairs instead (see each test), because there is no single line whose
 * mutation expresses "the engine started concatenating split parts".
 * </p>
 */
class DefineSplitDatasetContractTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The logical domain both parts belong to — a valid SCOPE key and an invalid DEFINE key. */
    private static final String BASE = "QS";

    private static final String PART_A = "QSPH";

    private static final String PART_B = "QSSL";

    private static final String LABEL_A = "Questionnaires (PHQ-9)";

    private static final String LABEL_B = "Questionnaires (SQLS)";

    /** The production composition: ODM-direct over the datatable-backed define library. */
    private static MetadataProvider defineProvider;

    /** The no-fallback composition ({@code StudyValidationService} tests / embedding path). */
    private static MetadataProvider odmOnlyProvider;

    /** The parsed MSG sample, kept so the raw {@code ItemGroupDef} shape can be asserted. */
    private static ODM odm;

    @BeforeAll
    static void parseTheRealDefineXml() throws IOException
    {
        // Module-local copy: the Define-XML model lives in a separate repository here, so this
        // fixture is vendored into this module rather than reached for across the reactor.
        Path xml = Path.of(System.getProperty("projectBasedir"), "src", "test", "resources",
                "convert", "define-v21-sdtm.xml").normalize();
        assertTrue(Files.isRegularFile(xml), "MSG Define-XML 2.1 sample not found at " + xml
                + " — this guard is worthless without the real artefact");
        try (InputStream in = Files.newInputStream(xml))
        {
            odm = new DefineXmlParser().parse(in);
        }
        DefineSupport support = new DefineSupport(xml.toUri(), odm);
        MetadataProvider datatableDefine = MetadataLibraryProvider
                .forDefine(DefineMetadataLibrary.from(support));
        defineProvider = new DefineXmlMetadataProvider(new OdmDefineXMLProvider(odm),
                datatableDefine);
        odmOnlyProvider = new DefineXmlMetadataProvider(new OdmDefineXMLProvider(odm));
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------


    /** {@code QSPH} — two rows, the PHQ-9 questionnaire file. */
    private static IDataTable partA()
    {
        return MockTable.of().name(PART_A).col("DOMAIN", BASE, BASE)
                .col("QSTESTCD", "PHQ0101", "PHQ0102").build();
    }


    /** {@code QSSL} — three rows, the SQLS questionnaire file. */
    private static IDataTable partB()
    {
        return MockTable.of().name(PART_B).col("DOMAIN", BASE, BASE, BASE)
                .col("QSTESTCD", "SQLS01", "SQLS02", "SQLS03").build();
    }


    /**
     * What the frame would look like if coreJ ever concatenated split parts the way the fork's
     * {@code concat_split_datasets} does: five rows under one part's name. Never produced by the
     * engine — built here only so {@link #frameIsThePartsOwnRows_neverASiblings()} can show its
     * row-count assertions are capable of seeing five.
     */
    private static IDataTable concatenatedAsTheForkWould()
    {
        return MockTable.of().name(PART_A).col("DOMAIN", BASE, BASE, BASE, BASE, BASE)
                .col("QSTESTCD", "PHQ0101", "PHQ0102", "SQLS01", "SQLS02", "SQLS03").build();
    }


    private static Rule datasetMetadataRule(String operand, String operator, JsonNode value)
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name(operand).operator(operator)
                .value(value).valueIsLiteral(Boolean.TRUE).build();
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-SPLIT-DEFINE");
        rule.setCore(core);
        rule.setCheck(new CheckConditionAll(List.of(leaf)));
        Outcome outcome = new Outcome();
        outcome.setMessage("per-part Define contract");
        rule.setOutcome(outcome);
        RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    private static Rule equalsLiteral(String operand, String literal)
    {
        return datasetMetadataRule(operand, "equal_to", MAPPER.valueToTree(literal));
    }


    private static Rule countEquals(int expected)
    {
        return datasetMetadataRule("record_count", "equal_to",
                MAPPER.valueToTree(Integer.valueOf(expected)));
    }


    /**
     * Runs the rule with the caller-supplied domain deliberately set to the <b>unsplit base</b>
     * ({@code "QS"}) while the table is a part. Every per-part assertion below therefore also
     * proves the engine keys off the physical table, not off what the caller called the domain.
     */
    private static RuleExecutionResult run(Rule rule, IDataTable table)
    {
        return run(rule, table, _ -> null);
    }


    private static RuleExecutionResult run(Rule rule, IDataTable table, DatasetResolver resolver)
    {
        return RuleRunner.execute(rule, table, resolver, BASE, null, null, defineProvider);
    }

    // -----------------------------------------------------------------------
    // The artefact itself — a real split declaration, one ItemGroupDef per file
    // -----------------------------------------------------------------------


    @Test
    void theArtefactReallyDeclaresOneItemGroupDefPerSubmittedFile()
    {
        Map<String, String> a = defineProvider.getDatasetMetadata(PART_A);
        Map<String, String> b = defineProvider.getDatasetMetadata(PART_B);

        assertEquals(PART_A, a.get("name"), "ItemGroupDef Name is the PART name");
        assertEquals(PART_B, b.get("name"), "ItemGroupDef Name is the PART name");
        // The two parts carry DIFFERENT labels — that is what makes every per-part assertion in
        // this class capable of failing. Without it the guard would be vacuous by construction.
        assertEquals(LABEL_A, a.get("label"));
        assertEquals(LABEL_B, b.get("label"));

        // Both declare the SAME logical domain: two files, one dataset.
        OdmDefineXMLProvider raw = new OdmDefineXMLProvider(odm);
        assertEquals(BASE, raw.getDatasetMetadata(PART_A).get("domain"));
        assertEquals(BASE, raw.getDatasetMetadata(PART_B).get("domain"));
    }

    // -----------------------------------------------------------------------
    // §4.2 — the Define lookup is keyed by the part's submitted file name
    // -----------------------------------------------------------------------


    @Test
    void defineLookupIsKeyedByThePart_notByTheBaseDomain()
    {
        Rule wantsA = equalsLiteral("define_dataset_label", LABEL_A);
        assertTrue(run(wantsA, partA()).hasViolations(),
                "QSPH must be evaluated against QSPH's own ItemGroupDef");
        assertFalse(run(wantsA, partB()).hasViolations(),
                "QSSL must NOT see its sibling QSPH's ItemGroupDef");

        Rule wantsB = equalsLiteral("define_dataset_label", LABEL_B);
        assertFalse(run(wantsB, partA()).hasViolations(),
                "QSPH must NOT see its sibling QSSL's ItemGroupDef");
        assertTrue(run(wantsB, partB()).hasViolations(),
                "QSSL must be evaluated against QSSL's own ItemGroupDef");

        // The same statement made on the name rather than the label.
        assertTrue(run(equalsLiteral("define_dataset_name", PART_A), partA()).hasViolations());
        assertTrue(run(equalsLiteral("define_dataset_name", PART_B), partB()).hasViolations());
        assertFalse(run(equalsLiteral("define_dataset_name", BASE), partA()).hasViolations(),
                "the injected define_dataset_name is never the logical domain");
    }


    /**
     * The unsplit base is not a Define key at all. On the production chain
     * ({@code DefineMetadataLibrary.getDataTable} matches on {@code ItemGroupDef/@Name} only) it
     * resolves to <b>nothing</b>: a Define that does not follow the per-part convention leaves
     * {@code define_dataset_*} uninjected, and the family's {@code non_empty(define_dataset_*)}
     * guards then silence it on every part — strict per-part, with no base fallback.
     *
     * <p>
     * The ODM-only composition (no datatable fallback) behaves differently and worse:
     * {@code OdmDefineXMLProvider.itemGroup} matches {@code @Name} <em>or</em> {@code @Domain} and
     * returns the <b>first</b> hit, so a base key silently yields whichever part is declared first
     * — exactly the shape §4.2 forbids. No production dataset-level caller keys by base today; this
     * pins the latent behaviour so that a future base-keyed caller is a visible change, not a
     * silent mis-attribution.
     * </p>
     */
    @Test
    void theUnsplitBaseIsNotAValidDefineKey()
    {
        assertTrue(defineProvider.getDatasetMetadata(BASE).isEmpty(),
                "the production define chain resolves NOTHING for the unsplit base");
        assertEquals(PART_A, odmOnlyProvider.getDatasetMetadata(BASE).get("name"),
                "ODM-only: a base key returns the FIRST declared part — first-part-wins, latent");
    }

    // -----------------------------------------------------------------------
    // §4.1 — the frame evaluated for a part is that part's rows only
    // -----------------------------------------------------------------------


    @Test
    void frameIsThePartsOwnRows_neverASiblings()
    {
        // A resolver that CAN hand out the sibling part, by its own name and by the logical
        // domain. coreJ has no split concatenation, so it never asks — and the frame stays 2.
        DatasetResolver siblingAvailable = name -> PART_B.equalsIgnoreCase(name)
                || BASE.equalsIgnoreCase(name) ? partB() : null;

        assertTrue(run(countEquals(2), partA(), siblingAvailable).hasViolations(),
                "QSPH's frame is QSPH's two rows");
        assertFalse(run(countEquals(5), partA(), siblingAvailable).hasViolations(),
                "QSPH's frame must NOT be QSPH+QSSL concatenated");

        // Control: the record_count check is capable of seeing five rows, so the assertion above
        // fails if the frame ever becomes a concatenation. Without this the pair is untestable —
        // "does not fire" would be indistinguishable from "cannot fire".
        assertTrue(run(countEquals(5), concatenatedAsTheForkWould()).hasViolations(),
                "control: a five-row frame DOES satisfy record_count == 5");
    }

    // -----------------------------------------------------------------------
    // §4.3 — the evaluation unit, and therefore the finding, is the part
    // -----------------------------------------------------------------------


    @Test
    void theEvaluationUnitIdentityIsThePart_notTheLogicalDomain()
    {
        assertTrue(run(equalsLiteral("dataset_name", PART_A), partA()).hasViolations(),
                "the evaluated dataset identity is QSPH even though the caller said QS");
        assertTrue(run(equalsLiteral("dataset_name", PART_B), partB()).hasViolations());
        assertFalse(run(equalsLiteral("dataset_name", BASE), partA()).hasViolations(),
                "the logical name is never the evaluation unit");
    }

    // -----------------------------------------------------------------------
    // §4.4 — scope matching is untouched: it still resolves the unsplit base
    // -----------------------------------------------------------------------


    @Test
    void scopeMatchingStillResolvesTheUnsplitBase()
    {
        Rule scopedToLogicalDomain = scopedTo(List.of(BASE), null);
        assertNull(ScopeMatcher.describeDomainMismatch(scopedToLogicalDomain, PART_A, BASE),
                "a rule scoped to QS must keep running on QSPH");
        assertNull(ScopeMatcher.describeDomainMismatch(scopedToLogicalDomain, PART_B, BASE),
                "a rule scoped to QS must keep running on QSSL");

        // ⚠ The one scope key that can remove a split part outright (CDISC-CG0333's only user):
        // include_split_datasets is a CONJUNCTIVE gate, so `false` drops both parts.
        Rule nonSplitsOnly = scopedTo(List.of(BASE), Boolean.FALSE);
        assertTrue(ScopeMatcher.describeDomainMismatch(nonSplitsOnly, PART_A, BASE) != null,
                "include_split_datasets: false removes a split part from scope");
    }


    private static Rule scopedTo(List<String> include, Boolean includeSplitDatasets)
    {
        Rule r = new Rule();
        Scope s = new Scope();
        DomainScope d = new DomainScope();
        d.setInclude(include);
        if (includeSplitDatasets != null)
        {
            d.setIncludeSplitDatasets(includeSplitDatasets);
        }
        s.setDomains(d);
        r.setScope(s);
        return r;
    }
}
