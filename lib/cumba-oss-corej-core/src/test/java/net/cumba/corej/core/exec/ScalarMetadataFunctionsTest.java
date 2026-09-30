package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.expr.eval.NativeExprEvaluator;
import net.cumba.corej.core.expr.eval.ProviderNeed;
import net.cumba.corej.core.expr.eval.UnusableProviderAnswerException;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Wave 4b ({@code PLAN-scalar-metadata-functions} §4.2): the six dataset-level scalar / metadata
 * functions — their descriptors, every answer and empty / unusable arm carried from the retired
 * operations (§2.2), the per-variable shape of {@code cross_dataset_variable_metadata} end to end
 * (D-W4b-1), the loud non-inventory arm of {@code variable_count} (D-W4b-5) and the compile seam's
 * load errors (D-W4b-4 / D-W4b-6). Real tables and small fakes throughout — no Mockito (owner,
 * 2026-09-28).
 */
class ScalarMetadataFunctionsTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DatasetResolver NO_INVENTORY = _ -> null;

    @AfterEach
    void clearFallbackProperty()
    {
        System.clearProperty(LibraryAnswerability.DEGRADED_DEFINE_FALLBACK_PROPERTY);
    }

    // ============================================================ the descriptors


    @Test
    void everyScalarFunctionIsARegisteredAggregateWithItsCapability()
    {
        for (String name : ScalarMetadataFunctions.FUNCTION_NAMES)
        {
            FunctionDescriptor d = descriptor(name);
            assertTrue(d.aggregate(), name + ": one value for the dataset");
            boolean library = ScalarMetadataFunctions.DOMAIN_IS_CUSTOM.equals(name)
                    || ScalarMetadataFunctions.DATASET_CLASS_FROM_LIBRARY.equals(name);
            assertEquals(library ? ProviderNeed.LIBRARY : null, d.provider(), name);
        }
        assertEquals(6, ScalarMetadataFunctions.FUNCTION_NAMES.size());
    }

    // ============================================================ domain_is_custom


    @Test
    void domainIsCustomAnswersTheLibraryPerDomainCode()
    {
        Library lib = new Library();
        lib.customDomains = List.of("XX");
        assertEquals(true, value(ScalarMetadataFunctions
                .domainIsCustom(run(table("XX", "DOMAIN", "XX"), lib), List.of())));
        assertEquals(false, value(ScalarMetadataFunctions
                .domainIsCustom(run(table("LB", "DOMAIN", "LB"), lib), List.of())));
        // the domain CODE, not the member name: a split XXA carrying DOMAIN=XX is custom too
        assertEquals(true, value(ScalarMetadataFunctions
                .domainIsCustom(run(table("XXA", "DOMAIN", "XX"), lib), List.of())));
    }


    @Test
    void domainIsCustomIsUnusableWithoutALibraryAndWhenDegradedEvenUnderTheOptIn()
    {
        IDataTable xx = table("XX", "DOMAIN", "XX");
        assertUnusable(ScalarMetadataFunctions.DOMAIN_IS_CUSTOM,
                () -> ScalarMetadataFunctions.domainIsCustom(run(xx, null), List.of()));
        Library degraded = new Library();
        degraded.unavailable = true;
        assertUnusable(ScalarMetadataFunctions.DOMAIN_IS_CUSTOM,
                () -> ScalarMetadataFunctions.domainIsCustom(run(xx, degraded), List.of()));
        // NEVER (D-W4b-8): opted in AND the fallback is a Define-XML — still unusable, because
        // `false` ("not custom") is the value that fires and a define cannot tell custom.
        System.setProperty(LibraryAnswerability.DEGRADED_DEFINE_FALLBACK_PROPERTY, "true");
        degraded.defineVersion = "2.1";
        degraded.customDomains = List.of("XX");
        assertTrue(LibraryAnswerability.libraryAnswerable(degraded), "the opt-in is answerable");
        assertUnusable(ScalarMetadataFunctions.DOMAIN_IS_CUSTOM,
                () -> ScalarMetadataFunctions.domainIsCustom(run(xx, degraded), List.of()));
    }


    @Test
    void aLibraryFunctionBindingSkipsTheRuleWithoutALibrary() throws Exception
    {
        // The eager no-provider SKIP of a library OPERATION (RuleRunner.eagerProviderSkip) went
        // with W4b; the function's LIBRARY capability is what SKIPs now — never a FIRE on the
        // missing class (AD0497's `empty($dataset_class)` would fire on every AD dataset).
        Rule rule = rule("empty($c)", List.of("$c"), "$c", "dataset_class_from_library()");
        assertNull(rule.getLoadError(), rule.getLoadError());
        IDataTable adlbc = table("ADLBC", "STUDYID", "S");
        RuleExecutionResult skipped = RuleRunnerCalls.execute(rule, adlbc, NO_INVENTORY, null,
                (MetadataProvider) null);
        assertEquals(RuleExecutionStatus.SKIPPED, skipped.getStatus());
        Library lib = new Library();
        RuleExecutionResult fires = RuleRunnerCalls.execute(rule, adlbc, NO_INVENTORY, null, lib);
        assertEquals(RuleExecutionStatus.EXECUTED, fires.getStatus(), fires.getStatusMessage());
        assertEquals(1, fires.getViolations().size(), "the Library holds no class ⇒ fires");
    }

    // ============================================================ dataset_class_from_library


    @Test
    void datasetClassFromLibraryAnswersTheClassNameOrMissing()
    {
        Library lib = new Library();
        lib.datasetMetadata.put("ADLBC", Map.of("className", "BASIC DATA STRUCTURE"));
        assertEquals("BASIC DATA STRUCTURE", value(ScalarMetadataFunctions
                .datasetClassFromLibrary(run(table("ADLBC", "STUDYID", "S"), lib), List.of())));
        Vector none = ScalarMetadataFunctions
                .datasetClassFromLibrary(run(table("ADXX", "STUDYID", "S"), lib), List.of());
        assertNull(value(none), "no class ⇒ the missing value");
        assertTrue(none.value(0).cell().isMissingOrInvalid(), "missing, never a Java null leak");
    }


    @Test
    void datasetClassFromLibraryTextArmUnderTheDegradedOptIn()
    {
        IDataTable adlbc = table("ADLBC", "STUDYID", "S");
        assertUnusable(ScalarMetadataFunctions.DATASET_CLASS_FROM_LIBRARY,
                () -> ScalarMetadataFunctions.datasetClassFromLibrary(run(adlbc, null), List.of()));
        Library degraded = new Library();
        degraded.unavailable = true;
        assertUnusable(ScalarMetadataFunctions.DATASET_CLASS_FROM_LIBRARY,
                () -> ScalarMetadataFunctions.datasetClassFromLibrary(run(adlbc, degraded),
                        List.of()));
        System.setProperty(LibraryAnswerability.DEGRADED_DEFINE_FALLBACK_PROPERTY, "true");
        degraded.defineVersion = "2.1";
        // TEXT: a blank answer from the define is unusable …
        assertUnusable(ScalarMetadataFunctions.DATASET_CLASS_FROM_LIBRARY,
                () -> ScalarMetadataFunctions.datasetClassFromLibrary(run(adlbc, degraded),
                        List.of()));
        // … a non-blank one answers.
        degraded.datasetMetadata.put("ADLBC", Map.of("className", "BASIC DATA STRUCTURE"));
        assertEquals("BASIC DATA STRUCTURE", value(
                ScalarMetadataFunctions.datasetClassFromLibrary(run(adlbc, degraded), List.of())));
    }

    // ============================================================ extract_metadata


    @Test
    void extractMetadataReadsEachKeyOfTheTable()
    {
        OverlayDataTable bw = OverlayDataTable.empty("BW", "Body Weight", 1);
        bw.addColumn("STUDYID", DataValueType.STRING, "Study Identifier");
        bw.setValue(0, 0, "S");
        bw.setTableURI(URI.create("file:///study/BW%20dir/bw.xpt"));
        bw.setTableMetaData("dataset_size", 6_000_000_000L);
        bw.setTableMetaData("file_format", "XPORT");
        EvalRun run = run(bw, null);
        assertEquals("BW", extract(run, "dataset_name"));
        assertEquals("Body Weight", extract(run, "dataset_label"));
        assertEquals(6_000_000_000L, extract(run, "dataset_size"));
        assertEquals("bw.xpt", extract(run, "dataset_location"), "the URI basename");
        assertEquals("bw.xpt", extract(run, "filename"), "the alias");
        assertEquals("XPORT", extract(run, "file_format"), "any provider metadata key");
        assertNull(extract(run, "transport_version"), "an absent key ⇒ missing");
    }


    @Test
    void extractMetadataLocationIsMissingWithoutASourceUri()
    {
        assertNull(extract(run(table("BW", "STUDYID", "S"), null), "dataset_location"));
    }

    // ============================================================ cross_dataset_variable_metadata


    @Test
    void crossDatasetVariableMetadataIsOnePerVariableMapForTheDataset()
    {
        IDataTable adlbc = labelled("ADLBC", "AGE", "Age");
        IDataTable adsl = labelled("ADSL", "AGE", "Age in Years");
        Vector v = ScalarMetadataFunctions.crossDatasetVariableMetadata(
                run(adlbc, null, RealTables.inventoryOf(adlbc, adsl)), args("label", "ADSL"));
        assertInstanceOf(ConstVector.class, v, "one value for the dataset (D-W4b-1)");
        VariableMetadataResult vmr = assertInstanceOf(VariableMetadataResult.class, value(v));
        assertEquals("Age in Years", vmr.getForVariable("AGE"));
    }


    @Test
    void crossDatasetVariableMetadataOfAnAbsentDatasetIsMissing()
    {
        IDataTable adlbc = labelled("ADLBC", "AGE", "Age");
        assertNull(value(ScalarMetadataFunctions.crossDatasetVariableMetadata(
                run(adlbc, null, RealTables.inventoryOf(adlbc)), args("label", "ADSL"))));
    }


    @Test
    void crossDatasetVariableMetadataStarScansShortestNameFirstAndExcludesThePrimaryByName()
    {
        OverlayDataTable adae = OverlayDataTable.empty("ADAE", "ADAE", 1);
        adae.addColumn("DOMAIN", DataValueType.STRING, "Domain");
        adae.addColumn("SHARED", DataValueType.STRING, "Shared ADAE");
        adae.addColumn("ONLYADAE", DataValueType.STRING, "Only here");
        adae.setValue(0, 0, "AE");
        IDataTable ae = labelled("AE", "SHARED", "Shared AE");
        IDataTable adsl = labelled("ADSL", "SHARED", "Shared ADSL");
        VariableMetadataResult vmr = (VariableMetadataResult) value(
                ScalarMetadataFunctions.crossDatasetVariableMetadata(
                        run(adae, null, RealTables.inventoryOf(adae, ae, adsl)),
                        args("label", "*")));
        assertNotNull(vmr);
        assertEquals("Shared AE", vmr.getForVariable("SHARED"), "AE (shortest) wins over ADSL");
        assertNull(vmr.getForVariable("ONLYADAE"), "the primary is excluded — even though its"
                + " domain code is AE (DOMAIN column), it is excluded by its NAME, ADAE");
    }


    @Test
    void crossDatasetVariableMetadataFiresPerVariableThroughTheRunner() throws Exception
    {
        Rule rule = rule("not empty($adsl_label) and var_label(\"DATA\") != $adsl_label",
                List.of("variable_name", "$adsl_label"), "$adsl_label",
                "cross_dataset_variable_metadata(\"label\", domain=\"ADSL\")");
        assertNull(rule.getLoadError(), rule.getLoadError());
        OverlayDataTable adlbc = OverlayDataTable.empty("ADLBC", "ADLBC", 1);
        adlbc.addColumn("AGE", DataValueType.STRING, "Age");
        adlbc.addColumn("SEX", DataValueType.STRING, "Sex");
        adlbc.setValue(0, 0, "63");
        adlbc.setValue(0, 1, "F");
        OverlayDataTable adsl = OverlayDataTable.empty("ADSL", "ADSL", 1);
        adsl.addColumn("AGE", DataValueType.STRING, "Age in Years");
        adsl.addColumn("SEX", DataValueType.STRING, "Sex");
        adsl.setValue(0, 0, "63");
        adsl.setValue(0, 1, "F");
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, adlbc,
                RealTables.inventoryOf(adlbc, adsl));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size(), "AGE's label differs; SEX's does not");
        assertEquals("AGE", result.getViolations().get(0).getValues().get("variable_name"));
        assertEquals("Age in Years", result.getViolations().get(0).getValues().get("$adsl_label"));
        // no ADSL: the missing value — nothing to compare, nothing fires
        RuleExecutionResult noAdsl = RuleRunnerCalls.execute(rule, adlbc,
                RealTables.inventoryOf(adlbc));
        assertEquals(0, noAdsl.getViolations().size());
    }

    // ============================================================ variable_count


    @Test
    void variableCountByPatternCountsMatchingColumnsIgnoringCase()
    {
        IDataTable t = realTable("ADSL", "SAFFL", "ittfl", "AGE");
        assertEquals(2L, value(ScalarMetadataFunctions.variableCount(run(t, null),
                Arrays.asList(null, ConstVector.of("^.+FL$")))));
    }


    @Test
    void variableCountWithNoArgumentIsTheColumnCount()
    {
        IDataTable t = realTable("DM", "A", "B");
        assertEquals(2L, value(
                ScalarMetadataFunctions.variableCount(run(t, null), Arrays.asList(null, null))));
    }


    @Test
    void variableCountTemplateCountsTheStudyOncePerSplitFamily()
    {
        IDataTable ae = realTable("AE", "AELNKGRP");
        IDataTable cm = realTable("CM", "CMLNKGRP");
        IDataTable dm = realTable("DM", "USUBJID");
        Map<String, IDataTable> byName = new LinkedHashMap<>();
        byName.put("AE", ae);
        byName.put("CM", cm);
        byName.put("DM", dm);
        byName.put("GONE", null);
        assertEquals(2L,
                value(ScalarMetadataFunctions.variableCount(
                        run(ae, null, RealTables.inventory(byName)), args("--LNKGRP", null))),
                "AELNKGRP + CMLNKGRP, DM has none, an unresolvable name is skipped");
        IDataTable ae1 = RealTables.of("AE1").str("DOMAIN", "AE").str("AELNKID", "1").build();
        IDataTable ae2 = RealTables.of("AE2").str("DOMAIN", "AE").str("AELNKID", "1").build();
        assertEquals(1L,
                value(ScalarMetadataFunctions.variableCount(
                        run(ae1, null, RealTables.inventoryOf(ae1, ae2)), args("--LNKID", null))),
                "AE1 / AE2 share DOMAIN=AE — one family, counted once");
    }


    @Test
    void variableCountTemplateIsLoudWithoutAnInventory()
    {
        IDataTable ae = realTable("AE", "AELNKGRP");
        IllegalStateException loud = assertThrows(IllegalStateException.class,
                () -> ScalarMetadataFunctions.variableCount(run(ae, null, NO_INVENTORY),
                        args("--LNKGRP", null)));
        assertTrue(loud.getMessage().startsWith("variable_count needs a dataset inventory"),
                loud.getMessage());
    }

    // ============================================================ column_series_metadata


    @Test
    void columnSeriesMetadataGapShortMemberAndTooFewMembers()
    {
        assertEquals(true, series(realTable("CO", "coval", "COVAL2"), null),
                "a lowercase base (0) and member 2: the gap at 1 fires");
        assertEquals(true, series(realTable("CO", "coval1", "Coval3"), null),
                "members are selected in any letter case (CIT §1): 1, 3 — the gap at 2 fires");
        assertEquals(false, series(realTable("CO", "COVAL", "COVAL1", "coval2"), null),
                "0, 1, 2 contiguous ⇒ complete");
        assertEquals(false, series(realTable("CO", "COVAL"), 200),
                "fewer than two members ⇒ no series");
        OverlayDataTable co = OverlayDataTable.empty("CO", "CO", 1);
        co.addColumn("COVAL", DataValueType.STRING, "Comment");
        co.addColumn("COVAL1", DataValueType.STRING, "Comment 1");
        co.setColumnLength("COVAL", 100);
        co.setColumnLength("COVAL1", 200);
        assertEquals(true, series(co, 200), "a non-terminal member shorter than min_length fires");
        assertEquals(false, series(co, null), "without min_length only gaps count");
    }

    // ============================================================ the compile seam (load errors)


    @Test
    void theStaticArgumentsAreLoadErrorsWhenMisspelt() throws Exception
    {
        assertLoadError("variable_count(\"--LNKGRP\", name_pattern=\"^.+FL$\")", "never both");
        assertLoadError("variable_count(--LNKGRP)", "static string literal", "--LNKGRP");
        assertLoadError("variable_count(name_pattern=\"[unbalanced\")",
                "not a valid regular expression");
        assertLoadError("column_series_metadata(\"COVAL\", name_pattern=\"^COVAL\\\\d+$\","
                + " min_length=20.5)", "non-negative integer literal");
        assertLoadError("column_series_metadata(\"COVAL\", name_pattern=\"^COVAL\\\\d+$\","
                + " min_length=COVAL)", "non-negative integer literal");
        assertLoadError("column_series_metadata(\"COVAL\")", "name_pattern");
        assertLoadError("column_series_metadata(COVAL, name_pattern=\"^COVAL\\\\d+$\")",
                "static string literal", "COVAL");
        assertLoadError("cross_dataset_variable_metadata(\"lable\", domain=\"ADSL\")", "lable",
                "label");
        assertLoadError("cross_dataset_variable_metadata(\"label\", domain=ADSL)",
                "static string literal", "ADSL");
        assertLoadError("extract_metadata(DOMAIN)", "static string literal");
        assertLoadError("domain_is_custom(DOMAIN)", "domain_is_custom");
    }


    @Test
    void theCorpusSpellingsLoad() throws Exception
    {
        for (String binding : List.of("domain_is_custom()", "dataset_class_from_library()",
                "extract_metadata(\"file_format\")", "extract_metadata(\"dataset_size\")",
                "cross_dataset_variable_metadata(\"label\", domain=\"ADSL\")",
                "cross_dataset_variable_metadata(\"data_type\", domain=\"*\")",
                "variable_count(\"--LNKGRP\")", "variable_count(name_pattern=\"^.+FL$\")",
                "column_series_metadata(\"COVAL\", name_pattern=\"^COVAL\\\\d+$\","
                        + " min_length=200)"))
        {
            Rule rule = rule("not empty($v)", List.of("$v"), "$v", binding);
            assertNull(rule.getLoadError(), binding + " → " + rule.getLoadError());
        }
    }


    /**
     * D-W4b-1, re-pinned by the combined review of runbook W2–W8 (W8 M3): its only test went with
     * {@code InlineOperationFunctionTest} in W8. {@code cross_dataset_variable_metadata} answers
     * one value per variable and is read only through a binding, so an inline call in a Check is a
     * load error ({@code RulePackageLoader.rejectInlinePerVariableCalls}); its twin — a
     * dataset-level list function inline — compiles.
     */
    @Test
    void anInlineCrossDatasetVariableMetadataIsALoadErrorWhereAnInlineListFunctionCompiles()
        throws Exception
    {
        assertTrue(
                NativeExprEvaluator
                        .isSupported(CheckExpressionParser.parse("required_variables() == AAA")),
                "a dataset-level list function compiles inline");
        String json = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"W4b-INLINE\"},"
                + "\"Check\":{\"expression\":"
                + "\"cross_dataset_variable_metadata(\\\"label\\\", domain=\\\"ADSL\\\") == AAA\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}}}";
        Rule rule = Objects
                .requireNonNull(RulePackageLoader.loadFromString(json).getRules().get("x"));
        String error = rule.getLoadError();
        assertNotNull(error, "an inline per-variable call must not load");
        assertTrue(
                error.contains(
                        "answers one value per variable and is read only through a" + " binding"),
                error);
    }


    /**
     * Combined review of runbook W2–W8, W3/W4b M4 (F-W4b-2): the inline {@code var_*(…,
     * dataset="*")} read excludes the dataset under evaluation by its NAME, as
     * {@code cross_dataset_variable_metadata} does. It passed the context's DOMAIN, so a split
     * {@code QSCG} (domain {@code QS}) excluded a dataset called {@code QS} instead of itself and
     * read its own label. Pre-fix: SHARED reads "Own QSCG label"; post-fix: "QS label".
     */
    @Test
    void anInlineStarDatasetReadExcludesTheEvaluatedDatasetByNameNotByDomain()
    {
        IDataTable qscg = labelled("QSCG", "SHARED", "Own QSCG label");
        IDataTable qs = labelled("QS", "SHARED", "QS label");
        EvaluationContext ctx = EvaluationContext.builder().table(qscg).domainName("QS")
                .ruleId("W4b-SELF").datasetResolver(RealTables.inventoryOf(qscg, qs)).build();
        java.util.BitSet row0 = new java.util.BitSet();
        row0.set(0);
        assertEquals(row0,
                NativeExprEvaluator.evaluate(
                        CheckExpressionParser.parse(
                                "var_label(\"SHARED\", \"DATA\", dataset=\"*\") == \"QS label\""),
                        ctx),
                "the other dataset's label is read — QS is not the dataset under evaluation");
        assertEquals(new java.util.BitSet(),
                NativeExprEvaluator.evaluate(CheckExpressionParser.parse(
                        "var_label(\"SHARED\", \"DATA\", dataset=\"*\") == \"Own QSCG label\""),
                        ctx),
                "the evaluated dataset QSCG never answers its own label");
    }

    // ============================================================ helpers


    private static FunctionDescriptor descriptor(String name)
    {
        return Objects.requireNonNull(FunctionRegistry.descriptor(name), name);
    }


    private static IDataTable table(String name, String column, String value)
    {
        return RealTables.of(name).str(column, value).build();
    }


    private static IDataTable realTable(String name, String... columns)
    {
        RealTables t = RealTables.of(name);
        for (String c : columns)
        {
            t.str(c, "x");
        }
        return t.build();
    }


    private static IDataTable labelled(String name, String column, String label)
    {
        OverlayDataTable t = OverlayDataTable.empty(name, name, 1);
        t.addColumn(column, DataValueType.STRING, label);
        t.setValue(0, 0, "1");
        return t;
    }


    private static EvalRun run(IDataTable table, @Nullable MetadataProvider library)
    {
        return run(table, library, NO_INVENTORY);
    }


    private static EvalRun run(IDataTable table, @Nullable MetadataProvider library,
            DatasetResolver resolver)
    {
        return EvalRun.fullRange(EvaluationContext.builder().table(table).ruleId("W4b-FN")
                .libraryProvider(library).datasetResolver(resolver).build());
    }


    private static List<@Nullable Vector> args(@Nullable Object... statics)
    {
        List<@Nullable Vector> out = new ArrayList<>();
        for (Object s : statics)
        {
            out.add(s == null ? null : ConstVector.of(s));
        }
        return out;
    }


    private static @Nullable Object value(Vector v)
    {
        assertInstanceOf(ConstVector.class, v, "a dataset-level value is one broadcast constant");
        return v.value(0).resolved();
    }


    private static @Nullable Object extract(EvalRun run, String key)
    {
        return value(ScalarMetadataFunctions.extractMetadata(run, args(key)));
    }


    private static @Nullable Object series(IDataTable table, @Nullable Integer minLength)
    {
        return value(ScalarMetadataFunctions.columnSeriesMetadata(run(table, null),
                args("COVAL", "^COVAL\\d+$", minLength == null ? null : minLength.doubleValue())));
    }


    private static void assertUnusable(String function, Runnable body)
    {
        UnusableProviderAnswerException ex = assertThrows(UnusableProviderAnswerException.class,
                body::run, function);
        assertEquals(function, ex.function());
        assertEquals(ProviderNeed.Kind.LIBRARY, ex.kind());
    }


    private static Rule rule(String check, List<String> outputs, String... bindings)
        throws Exception
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "W4b-SCALAR"));
        List<Map<String, String>> declared = new ArrayList<>();
        for (int i = 0; i < bindings.length; i += 2)
        {
            declared.add(Map.of("name", bindings[i], "expression", bindings[i + 1]));
        }
        rule.put("Bindings", declared);
        rule.put("Check", Map.of("expression", check));
        rule.put("Outcome", Map.of("Message", "m", "Output_Variables", outputs));
        String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
        return Objects.requireNonNull(RulePackageLoader.loadFromString(json).getRules().get("x"));
    }


    private static void assertLoadError(String binding, String... fragments) throws Exception
    {
        Rule rule = rule("not empty($v)", List.of("$v"), "$v", binding);
        String error = rule.getLoadError();
        assertNotNull(error, binding + " must be a load error");
        for (String fragment : fragments)
        {
            assertTrue(error.contains(fragment),
                    binding + " → " + error + " // missing " + fragment);
        }
    }

    // ------------------------------------------------------------ the fake

    /** A Library answering from fields; every unset field is the interface's empty default. */
    private static final class Library implements MetadataProvider
    {

        List<String> customDomains = List.of();

        final Map<String, Map<String, String>> datasetMetadata = new LinkedHashMap<>();

        boolean unavailable;

        @Nullable
        String defineVersion;

        @Override
        public boolean isLibraryUnavailable()
        {
            return unavailable;
        }


        @Override
        public @Nullable String getDefineVersion()
        {
            return defineVersion;
        }


        @Override
        public boolean isDomainCustom(String domain)
        {
            return customDomains.contains(domain);
        }


        @Override
        public Map<String, String> getDatasetMetadata(String domain)
        {
            return datasetMetadata.getOrDefault(domain, Map.of());
        }


        @Override
        public List<String> getRequiredVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getExpectedVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getColumnOrder(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getCodelistTerms(String codelistCode)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getVariableMetadata(String domain, String variable)
        {
            return Map.of();
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String domain)
        {
            return List.of();
        }


        @Override
        public Optional<Boolean> isCodelistExtensible(String codelistName)
        {
            return Optional.empty();
        }


        @Override
        public String getStandard()
        {
            return "sdtmig";
        }
    }

    // ------------------------------------------- VAR-cursor bindings (combined review W3+W4b)

    /** ADLBC (AGE "Age", SEX "Sex") beside ADSL (AGE "Age in Years", SEX "Sex"), one row each. */
    private static IDataTable[] adlbcBesideAdsl()
    {
        OverlayDataTable adlbc = OverlayDataTable.empty("ADLBC", "ADLBC", 1);
        adlbc.addColumn("AGE", DataValueType.STRING, "Age");
        adlbc.addColumn("SEX", DataValueType.STRING, "Sex");
        adlbc.setValue(0, 0, "63");
        adlbc.setValue(0, 1, "F");
        OverlayDataTable adsl = OverlayDataTable.empty("ADSL", "ADSL", 1);
        adsl.addColumn("AGE", DataValueType.STRING, "Age in Years");
        adsl.addColumn("SEX", DataValueType.STRING, "Sex");
        adsl.setValue(0, 0, "63");
        adsl.setValue(0, 1, "F");
        return new IDataTable[]
        {
                adlbc, adsl
        };
    }


    /**
     * Combined review of runbook W2–W8, W3+W4b H1 (F-W4b-1): a binding that reads the VARIABLE
     * CURSOR — {@code var_label("DATA", dataset="ADSL")} reads {@code variable_name} — was handed
     * over against the outer context, where no cursor is set, and answered {@code null} for every
     * column: {@code not empty($adsl_label) and …} PASSED silently. Handed over per column it is
     * ADSL's label of the column under the cursor.
     */
    @Test
    void aVariableCursorBindingIsHandedOverAgainstTheColumn() throws Exception
    {
        Rule rule = rule("not empty($adsl_label) and var_label(\"DATA\") != $adsl_label",
                List.of("variable_name", "$adsl_label"), "$adsl_label",
                "var_label(\"DATA\", dataset=\"ADSL\")");
        assertNull(rule.getLoadError(), rule.getLoadError());
        IDataTable[] study = adlbcBesideAdsl();
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, study[0],
                RealTables.inventoryOf(study));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size(),
                "AGE's ADSL label differs; SEX's does not — " + result.getViolations());
        assertEquals("AGE", result.getViolations().get(0).getValues().get("variable_name"));
        assertEquals("Age in Years", result.getViolations().get(0).getValues().get("$adsl_label"));
    }


    /**
     * Combined review of runbook W2–W8, W3+W4b M1 / M2: on the {VAR,ROW} path a per-variable map
     * {@code $}-ref on a comparison's RIGHT-hand side was not projected (the legacy "guard
     * position" gate), so the label compared against the whole map and fired on every (variable,
     * row) (M1), and the finding reported {@code VariableMetadataResult[…]} (M2). The map is
     * projected per column whatever the position, and the finding reports the column's value.
     */
    @Test
    void aPerVariableMapOnAComparisonRightHandSideIsProjectedPerColumnAndRow() throws Exception
    {
        Rule rule = rule("not empty(value()) and var_label(\"DATA\") != $adsl_label",
                List.of("variable_name", "$adsl_label"), "$adsl_label",
                "cross_dataset_variable_metadata(\"label\", domain=\"ADSL\")");
        assertNull(rule.getLoadError(), rule.getLoadError());
        IDataTable[] study = adlbcBesideAdsl();
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, study[0],
                RealTables.inventoryOf(study));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size(),
                "AGE's label differs in row 0; SEX's does not — " + result.getViolations());
        assertEquals("AGE", result.getViolations().get(0).getValues().get("variable_name"));
        assertEquals("Age in Years", result.getViolations().get(0).getValues().get("$adsl_label"),
                "the column's projected value, not the map's toString");
    }


    /**
     * Combined review of runbook W2–W8, round 2 H1 — a regression pin. The M1 fix projects every
     * binding per column on the {VAR,ROW} path, and the projection stored a ROW-cursor binding's
     * hand-over (its {@link Vector}) as a PLAIN value: the compiler then found no compiled binding
     * under {@code $flag} and read the Vector as one constant — its {@code toString} on every row —
     * so {@code $flag == "Y"} was false everywhere and the rule passed silently. Green before the
     * M1 fix (the variables were copied unchanged), red after it, green once a row-cursor binding
     * is kept as the binding itself.
     */
    @Test
    void aRowCursorBindingStaysABindingOnTheVariableRowPath() throws Exception
    {
        Rule rule = rule("var_type(\"DATA\") == \"Num\" and $flag == \"Y\"",
                List.of("variable_name", "$flag"), "$flag", "AEYN");
        assertNull(rule.getLoadError(), rule.getLoadError());
        IDataTable ae = RealTables.of("AE").dbl("AESEQ", 1.0, 2.0).str("AEYN", "Y", "N").build();
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, ae, RealTables.inventoryOf(ae));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(1, result.getViolations().size(),
                "the Num column AESEQ on the row whose AEYN is Y — " + result.getViolations());
        assertEquals("AESEQ", result.getViolations().get(0).getValues().get("variable_name"));
        assertEquals("Y", result.getViolations().get(0).getValues().get("$flag"));
    }
}
