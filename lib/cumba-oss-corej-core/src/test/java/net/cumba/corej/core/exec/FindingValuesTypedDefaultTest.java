package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-output-variable-null-key-provenance} §2.1 / §6.2 (owner, 2026-10-04: <i>"nothing can
 * be null … the variable based default absence value (empty string on char or MissingValue MIS on
 * num)"</i>; <i>"In Output_Variables an absent column should be omitted"</i>) — one projection
 * policy on every finding path, authored and derived alike:
 * <ol>
 * <li>an entry naming an <b>absent column</b> is omitted;</li>
 * <li>a <b>present</b> column's missing cell reports that cell's own {@link MissingValue};</li>
 * <li>any other entry that resolves to no value carries its typed default — {@code ""} for a char
 * entry, {@code .} ({@link MissingValue#MIS}) for a numeric, boolean or list one (H3).</li>
 * </ol>
 * Plus M3: a projection gap (a value that exists but the variable map did not reach —
 * {@code variable_size}, {@code variable_max_size}, …) is <em>resolved</em>, never defaulted.
 *
 * <p>
 * One test per birth site of the plan's §6.1: S1 {@code buildVariableViolation} (authored and
 * derived, char / numeric / boolean / list), S2 {@code buildDefineVariableViolation}, S3
 * {@code addVariableRowViolations}, S4 {@code buildVariableMetadata} (through S1), S5
 * {@code extractOutputValues} (a numeric binding on an empty table, an unresolved dataset virtual,
 * a missing binding). Each asserted on the {@link Violation#getValues()} map, which the writers
 * print verbatim.
 * </p>
 */
class FindingValuesTypedDefaultTest
{

    private static final String MIS = MissingValue.MIS.toString();

    private static Rule load(String body) throws Exception
    {
        Rule rule = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + body + "}}").getRules()
                .get("R1");
        assertNotNull(rule);
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    private static Map<String, String> single(RuleExecutionResult result)
    {
        assertNotNull(result);
        assertEquals(1, result.getViolationCount(), "status=" + result.getStatus() + " "
                + result.getStatusMessage() + " violations=" + result.getViolations());
        return result.getViolations().get(0).getValues();
    }


    private static void assertNullFree(Map<String, String> values)
    {
        values.forEach((k, v) -> assertNotNull(v, k + " carries a null value in " + values));
    }

    // ------------------------------------------------------------------ S1 / S4


    @Test
    void s1AuthoredAndDerivedCharAttributesWithNoValueBothReportTheEmptyString() throws Exception
    {
        // variable_format is read by the Check (derived); variable_label is authored only.
        String check = "{\"all\":[{\"expression\": \"ends_with(varname(), \\\"AETERM\\\")\"},"
                + "{\"expression\": \"empty(var_format(\\\"DATA\\\"))\"}]}";
        Rule authored = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\",\"Check\":"
                + check + ",\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":"
                + "[\"variable_name\",\"variable_label\",\"variable_format\"]}}");
        Rule derived = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\",\"Check\":"
                + check + ",\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":"
                + "[\"variable_name\",\"variable_label\"]}}");
        assertTrue(derived.getEffectiveOutputVariables().contains("variable_format"),
                "precondition: variable_format is derived: "
                        + derived.getEffectiveOutputVariables());
        IDataTable table = MockTable.of().name("AE").col("AETERM", "X", "Y").build();

        Map<String, String> a = single(RuleRunnerCalls.execute(authored, table));
        Map<String, String> d = single(RuleRunnerCalls.execute(derived, table));

        assertEquals("", a.get("variable_label"), a.toString());
        assertEquals("", a.get("variable_format"), "authored, no format: \"\" — not null");
        assertEquals("", d.get("variable_format"), "derived, no format: \"\" — not omitted");
        assertEquals(a, d, "authored and derived project identically (D1: no provenance split)");
        assertNullFree(a);
    }


    @Test
    void s1NumericBooleanAndListAttributesWithNoValueReportTheMissingMarker() throws Exception
    {
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"all":[{"expression": "not empty(var_role(\\"LIBRARY\\"))"},
                                 {"expression": "not empty(var_role(\\"DEFINE\\"))"}]},
                 "Outcome":{"Message":"m","Output_Variables":["variable_name",
                   "library_variable_length","library_variable_codelist_coded_values",
                   "define_variable_has_comment","define_variable_label","variable_length"]}}""");
        MetadataProvider library = new StubMetadataProvider().variable("DM",
                Map.of("name", "SEX", "role", "Identifier"));
        MetadataProvider define = new StubMetadataProvider().variable("DM",
                Map.of("name", "SEX", "role", "Qualifier"));
        IDataTable dm = MockTable.of().name("DM").col("SEX", "F").build();

        Map<String, String> v = single(
                RuleRunnerCalls.execute(rule, dm, _ -> null, "DM", library, null, define));

        assertEquals(MIS, v.get("library_variable_length"), "numeric: MIS — " + v);
        // A list accessor never answers a missing: no codelist IS the empty list, the value the
        // Check compared against — so it is reported, not defaulted (M3).
        assertEquals("[]", v.get("library_variable_codelist_coded_values"), "list: [] — " + v);
        assertEquals(MIS, v.get("define_variable_has_comment"), "boolean (D36): MIS — " + v);
        assertEquals("", v.get("define_variable_label"), "char: \"\" — " + v);
        // T2 MED-1: an unspecified length (the mock's 0) is no value — the Check reads it as
        // missing (var_length), so the finding prints the numeric default, never "0".
        assertEquals(MIS, v.get("variable_length"), "unspecified length: MIS — " + v);
        assertNullFree(v);
    }


    @Test
    void s1AColumnEntryOnAColumnLevelFindingIsOmittedWhetherAbsentOrPresent() throws Exception
    {
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Check":{"expression": "ends_with(varname(), \\"AETERM\\")"},
                         "Outcome":{"Message":"m","Output_Variables":["variable_name","DOMAIN","NOPE"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("DOMAIN", "AE").col("AETERM", "X").build();

        Map<String, String> v = single(RuleRunnerCalls.execute(rule, ae));

        assertFalse(v.containsKey("NOPE"), "an absent column is omitted (§2.1 case 1): " + v);
        // The deferred state: a PRESENT column on a finding without a row (no cell to read).
        // PLAN-finding-values-record-level-only (V8) gives it a name-only form; until then it is
        // OMITTED, never a null key (T2 coordinator decision under "nothing can be null"), and the
        // corpus roster nullkey/present-column-omitted.tsv lists every such entry for that plan.
        assertFalse(v.containsKey("DOMAIN"), "a present column with no cell is omitted: " + v);
        assertEquals("AETERM", v.get("variable_name"));
        assertNullFree(v);
    }


    @Test
    void m3TheLengthFactsAreProjectedNotDefaulted() throws Exception
    {
        // FDA-SD1082's shape: both facts are read by the Check and authored as Output_Variables,
        // yet the variable map carried neither — the finding printed two nulls (M3).
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Check":{"expression": "var_type(\\"DATA\\") == \\"Char\\" and var_length(\\"DATA\\") != max_value_length(variable_name)"},
                         "Outcome":{"Message":"m","Output_Variables":["variable_size","variable_max_size"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "ab", "abcd")
                .colMeta("AETERM", "Term", 10, null).build();

        Map<String, String> v = single(RuleRunnerCalls.execute(rule, ae));

        assertEquals("10", v.get("variable_size"),
                "declared length, as var_length(\"DATA\"): " + v);
        assertEquals("4", v.get("variable_max_size"),
                "max stored length, as max_value_length: " + v);
        assertNullFree(v);
    }


    @Test
    void m3AnUnspecifiedDeclaredLengthReportsTheNumericDefault() throws Exception
    {
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Check":{"expression": "var_type(\\"DATA\\") == \\"Char\\" and max_value_length(variable_name) > 2"},
                         "Outcome":{"Message":"m","Output_Variables":["variable_size","variable_max_size"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "ab", "abcd").build();

        Map<String, String> v = single(RuleRunnerCalls.execute(rule, ae));

        assertEquals(MIS, v.get("variable_size"), "no declared length: the numeric default — " + v);
        assertEquals("4", v.get("variable_max_size"), v.toString());
    }

    // ------------------------------------------------------------------ M3 on the row path

    /**
     * A {@link MetadataProvider} that forwards every call to {@code target} and counts the calls (a
     * small fake, no Mockito: a JDK proxy over the real stub); {@code unavailable} reports the
     * library as degraded ({@link MetadataProvider#isLibraryUnavailable}).
     */
    private static final class CountingProvider implements InvocationHandler
    {

        private final MetadataProvider target;

        private final boolean unavailable;

        private final AtomicInteger reads = new AtomicInteger();

        CountingProvider(MetadataProvider aTarget, boolean aUnavailable)
        {
            target = aTarget;
            unavailable = aUnavailable;
        }


        MetadataProvider proxy()
        {
            return (MetadataProvider) Proxy
                    .newProxyInstance(MetadataProvider.class.getClassLoader(), new Class<?>[]
                    {
                            MetadataProvider.class
                    }, this);
        }


        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
        {
            if (method.getDeclaringClass() == Object.class)
            {
                return method.invoke(this, args);
            }
            if ("isLibraryUnavailable".equals(method.getName()))
            {
                return unavailable;
            }
            reads.incrementAndGet();
            try
            {
                return method.invoke(target, args);
            }
            catch (InvocationTargetException e)
            {
                throw e.getCause();
            }
        }
    }

    private static Rule valueRule(String outputVariables) throws Exception
    {
        return load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"all":[{"expression": "ends_with(varname(), \\"DESC\\")"},
                                 {"expression": "len(value()) > 1"}]},
                 "Outcome":{"Message":"m","Output_Variables":[%s]}}""".formatted(outputVariables));
    }


    private static int libraryReads(Rule rule, IDataTable table, boolean unavailable,
            int expectedFindings)
    {
        CountingProvider counting = new CountingProvider(new StubMetadataProvider().variable("ADSL",
                Map.of("name", "DESC", "role", "Qualifier")), unavailable);
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table, _ -> null, "ADSL",
                counting.proxy(), null, null);
        assertEquals(expectedFindings, result.getViolationCount(), String.valueOf(result));
        result.getViolations().forEach(v -> assertNullFree(v.getValues()));
        return counting.reads.get();
    }


    @Test
    void m3TheRowPathEvaluatesAnOperandOncePerColumnNotOncePerFinding() throws Exception
    {
        // T2 MED-2: the per-(variable, row) path projects library_variable_role (the Check never
        // read it) through its accessor form. Three findings in one column must read the library
        // exactly as often as one finding does — the operand's Vector is evaluated once per
        // column and every finding reads its row from it.
        Rule rule = valueRule("\"variable_name\",\"variable_value\",\"library_variable_role\"");
        IDataTable threeFindings = MockTable.of().name("ADSL").col("DESC", "abc", "abd", "abe")
                .build();
        IDataTable oneFinding = MockTable.of().name("ADSL").col("DESC", "abc", "a", "b").build();

        int three = libraryReads(rule, threeFindings, false, 3);
        int one = libraryReads(rule, oneFinding, false, 1);

        assertTrue(one > 0, "precondition: the projection reads the library (" + one + ")");
        assertEquals(one, three, "library reads must not scale with the findings of a column");
        Map<String, String> v = single(RuleRunnerCalls.execute(rule, oneFinding, _ -> null, "ADSL",
                new StubMetadataProvider().variable("ADSL",
                        Map.of("name", "DESC", "role", "Qualifier")),
                null, null));
        assertEquals("Qualifier", v.get("library_variable_role"), "the value is projected: " + v);
    }


    @Test
    void m3TheRowPathHonoursTheLibraryAnswerabilityGate() throws Exception
    {
        // T2 LOW-1: Fix #369's gate on the row path too — a degraded library is never read for the
        // report; the entry carries its typed default and the provider is not consulted for it.
        Rule rule = valueRule("\"variable_name\",\"variable_value\",\"library_variable_role\"");
        Rule without = valueRule("\"variable_name\",\"variable_value\"");
        IDataTable table = MockTable.of().name("ADSL").col("DESC", "abc", "abd").build();

        int with = libraryReads(rule, table, true, 2);
        int baseline = libraryReads(without, table, true, 2);

        assertEquals(baseline, with, "a degraded library is not read for the projection");
        CountingProvider degraded = new CountingProvider(new StubMetadataProvider().variable("ADSL",
                Map.of("name", "DESC", "role", "Qualifier")), true);
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table, _ -> null, "ADSL",
                degraded.proxy(), null, null);
        for (Violation violation : result.getViolations())
        {
            assertEquals("", violation.getValues().get("library_variable_role"),
                    "the char default, never the degraded library's answer: "
                            + violation.getValues());
        }
    }


    @Test
    void s5TheReportedDefaultFallsBackToTheStaticTypeForAnUntypedCarrier()
    {
        // T2 LOW-5: one decision in reportedHandOver (the record path's empty-table arm that
        // shared it is gone with owner Q-C) — an evaluated type that is neither a string nor a
        // number / boolean (a missing, a carrier of no evaluated type) defers to the binding's
        // stage-A static type.
        CompiledBinding binding = new CompiledBinding("$b",
                new net.cumba.corej.core.expr.ast.Expr.Lit(
                        net.cumba.corej.core.expr.ast.Expr.LitKind.STRING, "x"),
                List.of(), null);
        BindingValue charBinding = new BindingValue(binding,
                net.cumba.corej.core.expr.typed.ExprType.Primitive.STRING);
        BindingValue numBinding = new BindingValue(binding,
                net.cumba.corej.core.expr.typed.ExprType.Primitive.NUMBER);
        BindingValue dateBinding = new BindingValue(binding,
                net.cumba.corej.core.expr.typed.ExprType.Primitive.DATE);

        assertEquals("", charBinding.reportedDefault(DataValueType.MISSING));
        assertEquals(MissingValue.MIS, numBinding.reportedDefault(DataValueType.MISSING));
        assertEquals("", dateBinding.reportedDefault(DataValueType.MISSING),
                "a date's carrier is its ISO-8601 text: the char default");
        assertEquals("", numBinding.reportedDefault(DataValueType.STRING),
                "an evaluated string wins over the static type");
        assertEquals(MissingValue.MIS, charBinding.reportedDefault(DataValueType.DOUBLE),
                "an evaluated number wins over the static type");
    }

    // ------------------------------------------------------------------ S2


    @Test
    void s2DefineDrivenProjectionReportsTypedDefaultsForBothProvenances() throws Exception
    {
        // library_variable_role is derived (the Check reads it) and the library knows no SEX;
        // library_variable_length is authored and numeric.
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Variable_Universe":"Define","Sensitivity":"Dataset",
                         "Check":{"all":[{"expression": "var_role(\\"DEFINE\\") != var_role(\\"LIBRARY\\")"}]},
                         "Outcome":{"Message":"m","Output_Variables":["define_variable_name","library_variable_length"]}}""");
        assertTrue(rule.getEffectiveOutputVariables().contains("library_variable_role"),
                "precondition: library_variable_role is derived: "
                        + rule.getEffectiveOutputVariables());
        MetadataProvider define = new StubMetadataProvider().variable("DM",
                Map.of("name", "SEX", "role", "Qualifier"));
        MetadataProvider library = new StubMetadataProvider().variable("DM",
                Map.of("name", "AGE", "role", "Identifier"));
        IDataTable dm = MockTable.of().name("DM").col("SEX", "F").build();

        Map<String, String> v = single(
                RuleRunnerCalls.execute(rule, dm, _ -> null, "DM", library, null, define));

        assertEquals("SEX", v.get("define_variable_name"));
        assertEquals("", v.get("library_variable_role"), "derived char, no value: \"\" — " + v);
        assertEquals(MIS, v.get("library_variable_length"), "authored numeric, no value — " + v);
        assertNullFree(v);
    }

    // ------------------------------------------------------------------ S3


    @Test
    void s3PerVariableRowProjectionReportsAnAbsentLabelAsTheEmptyString() throws Exception
    {
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Check":{"all":[{"expression": "ends_with(varname(), \\"DESC\\")"},
                                         {"expression": "len(value()) > 3"}]},
                         "Outcome":{"Message":"m","Output_Variables":["variable_name","variable_value","variable_label"]}}""");
        IDataTable table = MockTable.of().name("ADSL").col("USUBJID", "S1", "S2")
                .col("DESC", "abcdef", "ab").build();

        Map<String, String> v = single(
                RuleRunnerCalls.execute(rule, table, _ -> null, "ADSL", null, null, null));

        assertEquals("DESC", v.get("variable_name"));
        assertEquals("abcdef", v.get("variable_value"));
        assertEquals("", v.get("variable_label"), "no label: \"\" (it was null) — " + v);
        assertNullFree(v);
    }

    // ------------------------------------------------------------------ S5


    @Test
    void s5ARecordRuleReportsAMissingBindingByItsType() throws Exception
    {
        // $lbl reads a char attribute of a column the dataset lacks (a missing string);
        // $len a numeric one. Both print their typed default — never null, never an untyped "".
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Bindings":[{"name":"$lbl","expression":"var_label(\\"ZZZ\\", \\"DATA\\")"},
                             {"name":"$len","expression":"var_length(\\"ZZZ\\", \\"DATA\\")"}],
                 "Check":{"expression": "AETERM != \\"zz\\""},
                 "Outcome":{"Message":"m","Output_Variables":["AETERM","$lbl","$len"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "X").build();

        Map<String, String> v = single(RuleRunnerCalls.execute(rule, ae));

        assertEquals("X", v.get("AETERM"));
        assertEquals("", v.get("$lbl"), "a missing char binding: \"\" — " + v);
        assertEquals(MIS, v.get("$len"), "a missing numeric binding: MIS — " + v);
        assertNullFree(v);
    }


    @Test
    void s5AnUnresolvedDatasetVirtualCarriesItsDefaultOnTheRecordPath() throws Exception
    {
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"expression": "AETERM != \\"zz\\""},
                 "Outcome":{"Message":"m","Output_Variables":["AETERM","dataset_label"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "X").build();

        Map<String, String> v = single(RuleRunnerCalls.execute(rule, ae));

        assertTrue(v.containsKey("dataset_label"), "a resolvable virtual is never omitted: " + v);
        assertEquals("", v.get("dataset_label"), "no label: the char default — " + v);
        assertNullFree(v);
    }


    @Test
    void s5APerRowBindingOnAnEmptyTableIsListedByName() throws Exception
    {
        // M4 gave a per-row binding on an empty table its typed default (MIS for $n, "" for $s)
        // instead of an untyped "". Owner Q-C (2026-10-05) supersedes it: a per-row binding on a
        // non-record finding is listed by name — an empty primary has no row it could belong to
        // either. The PRESENT column is N20's: it printed "" until
        // PLAN-finding-values-record-level-only listed it by name (V8 (b)).
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Dataset",
                 "Bindings":[{"name":"$n","expression":"AENUM + 1"},
                             {"name":"$s","expression":"upper(AETERM)"}],
                 "Check":{"expression": "record_count() == 0"},
                 "Outcome":{"Message":"m","Output_Variables":["AETERM","$n","$s"]}}""");
        IDataTable empty = MockTable.of().name("AE").col("AETERM").colDouble("AENUM").build();

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, empty);
        Map<String, String> v = single(result);

        assertEquals(Map.of("record_count", "0"), v,
                "only the dataset fact the Check reads is valued — every other entry is a column"
                        + " or a per-row binding");
        // AENUM is derived from the binding ($n reads it), so it is a projected raw column too.
        assertEquals(List.of("AETERM", "$n", "$s", "AENUM"),
                result.getViolations().get(0).getNamedColumns());
        assertNullFree(v);
    }


    @Test
    void aBindingWithNoTypeSourceErrorsInsteadOfPrintingAnUntypedValue()
    {
        // P3 (owner OPEN-T (c), 2026-10-05): the untyped "" fallback is retired. A binding whose
        // type neither the evaluation nor stage A knows has no type source, and the finding
        // refuses to guess — the rule ERRORs (LibraryValidator.executeRule maps the throw).
        CompiledBinding binding = new CompiledBinding("$u",
                new net.cumba.corej.core.expr.ast.Expr.Lit(
                        net.cumba.corej.core.expr.ast.Expr.LitKind.STRING, "x"),
                List.of(), null);
        BindingValue untyped = new BindingValue(binding, null);
        BindingValue unknown = new BindingValue(binding,
                net.cumba.corej.core.expr.typed.ExprType.Unknown.UNKNOWN);

        IllegalStateException noStatic = assertThrows(IllegalStateException.class,
                () -> untyped.reportedDefault(DataValueType.MISSING));
        assertTrue(noStatic.getMessage().contains("$u")
                && noStatic.getMessage().contains("no type source"), noStatic.getMessage());
        assertThrows(IllegalStateException.class,
                () -> unknown.reportedDefault(DataValueType.MISSING));
    }


    @Test
    void aReportedUntypedNoValueBindingIsRefusedAtLoadNotErroredAtRunTime() throws Exception
    {
        // An extract_metadata key no reader documents is whatever the reader publishes: no
        // declared type, and a table without the key answers an untyped no-value. P3 ERRORed the
        // rule at run time — on some readers' data only; the P3 review (MED-1, decision (a))
        // moved the refusal to the load: the rule text decides
        // (ReportedBindingTypeSourceGateTest holds the gate's cases).
        Rule rule = RulePackageLoader.loadFromString("""
                {"rules":{"R1":{"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Bindings":[{"name":"$m","expression":"extract_metadata(\\"no_such_key\\")"}],
                 "Check":{"expression": "empty($m)"},
                 "Outcome":{"Message":"m","Output_Variables":["AETERM","$m"]}}}}""").getRules()
                .get("R1");

        assertNotNull(rule);
        assertNotNull(rule.getLoadError(), "a reported untyped binding is a load error");
        assertTrue(rule.getLoadError().contains("$m")
                && rule.getLoadError().contains("no type source"), rule.getLoadError());
    }


    @Test
    void aClassNameFunctionIsTypedAStringAndReportsTheCharDefault() throws Exception
    {
        // OPEN-T (c): dataset_class_from_library answers a class name — a string — so stage A
        // types the binding and an empty Library's no-answer reports "" by that type (it was the
        // untyped fallback's "" before; the value is the same, the type source is not).
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Bindings":[{"name":"$c","expression":"dataset_class_from_library()"}],
                 "Check":{"expression": "empty($c)"},
                 "Outcome":{"Message":"m","Output_Variables":["AETERM","$c"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "X").build();
        MetadataProvider library = new StubMetadataProvider();

        assertEquals(net.cumba.corej.core.expr.typed.ExprType.Primitive.STRING,
                rule.staticBindingTypes().get("$c"), "a class name is a string");
        Map<String, String> v = single(
                RuleRunnerCalls.execute(rule, ae, _ -> null, "AE", library, null, null));

        assertEquals("", v.get("$c"), "the char default — " + v);
        assertNullFree(v);
    }


    @Test
    void aNumericMetadataKeyReportsTheMissingMarkerByItsDeclaredType() throws Exception
    {
        // extract_metadata("dataset_size") is the byte count (a Long): a table without the key
        // reports MIS, where the untyped fallback printed "".
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Bindings":[{"name":"$size","expression":"extract_metadata(\\"dataset_size\\")"},
                             {"name":"$name","expression":"extract_metadata(\\"file_format\\")"}],
                 "Check":{"expression": "empty($size)"},
                 "Outcome":{"Message":"m","Output_Variables":["AETERM","$size","$name"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "X").build();

        assertEquals(net.cumba.corej.core.expr.typed.ExprType.Primitive.NUMBER,
                rule.staticBindingTypes().get("$size"));
        Map<String, String> v = single(RuleRunnerCalls.execute(rule, ae));

        assertEquals(MIS, v.get("$size"), "a numeric key with no value: MIS — " + v);
        assertEquals("", v.get("$name"), "a char key with no value: \"\" — " + v);
        assertNullFree(v);
    }


    @Test
    void aKeyedAttributeReportsItsTypedDefaultWhenTheSourceIsAbsent() throws Exception
    {
        // cross_dataset_variable_metadata("length", domain="ADSL") with no ADSL in the study
        // answers an untyped no-value (ConstVector.of(null)); "length" is a number, "label" a
        // string — typed by the key literal.
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Dataset",
                         "Bindings":[{"name":"$len","expression":"cross_dataset_variable_metadata(\\"length\\", domain=\\"ADSL\\")"},
                                     {"name":"$lbl","expression":"cross_dataset_variable_metadata(\\"label\\", domain=\\"ADSL\\")"}],
                         "Check":{"expression": "empty($len) and empty($lbl)"},
                         "Outcome":{"Message":"m","Output_Variables":["$len","$lbl"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "X").build();

        assertEquals(net.cumba.corej.core.expr.typed.ExprType.Primitive.NUMBER,
                rule.staticBindingTypes().get("$len"));
        assertEquals(net.cumba.corej.core.expr.typed.ExprType.Primitive.STRING,
                rule.staticBindingTypes().get("$lbl"));
        Map<String, String> v = single(RuleRunnerCalls.execute(rule, ae));

        assertEquals(MIS, v.get("$len"), "numeric attribute, no source: MIS — " + v);
        assertEquals("", v.get("$lbl"), "char attribute, no source: \"\" — " + v);
        assertNullFree(v);
    }


    @Test
    void everyValueFunctionWhoseResultItsSemanticsPinHasAResultType()
    {
        // P3 roster (OPEN-T (c)): every registered VALUE function that stage A types by name has
        // an ElementTable row, except the ones whose result is the type of the column they read
        // (a stage-B fact, typed by the evaluation's carrier) and the two keyed functions (typed
        // by their key literal). A new value function without a row reds here, so a binding over
        // it cannot silently become a binding with no type source.
        java.util.Set<String> byStageABranch = java.util.Set.of("colref", "value", "varname",
                "date", "time", "earliest_possible", "latest_possible");
        java.util.Set<String> unpinned = new java.util.TreeSet<>();
        for (net.cumba.corej.core.expr.eval.FunctionDescriptor d : net.cumba.corej.core.expr.eval.FunctionRegistry
                .all())
        {
            String name = d.name();
            if (d.kind() == net.cumba.corej.core.expr.eval.FunctionKind.VALUE
                    && net.cumba.corej.core.expr.eval.MetadataAttribute.fromFunction(name) == null
                    && !byStageABranch.contains(name)
                    && net.cumba.corej.core.expr.typed.ElementTable.resultType(
                            name) == net.cumba.corej.core.expr.typed.ExprType.Unknown.UNKNOWN)
            {
                unpinned.add(name);
            }
        }
        assertEquals(
                java.util.Set.of("coalesce", "max", "read_value", "row_max", "extract_metadata",
                        "cross_dataset_variable_metadata"),
                unpinned,
                "column-typed (coalesce, max, read_value, row_max) and keyed functions only");
        assertEquals(net.cumba.corej.core.expr.typed.ExprType.Primitive.NUMBER,
                net.cumba.corej.core.expr.typed.ElementTable.resultType("extract_metadata",
                        "dataset_size"));
        assertEquals(net.cumba.corej.core.expr.typed.ExprType.Unknown.UNKNOWN,
                net.cumba.corej.core.expr.typed.ElementTable.resultType("extract_metadata", "Rows"),
                "a reader-published key is the reader's, not pinned");
        assertEquals(net.cumba.corej.core.expr.typed.ExprType.Primitive.NUMBER,
                net.cumba.corej.core.expr.typed.ElementTable
                        .resultType("cross_dataset_variable_metadata", "length"));
    }


    @Test
    void theClassifierIsTheOneTypeSource()
    {
        assertEquals(OutputEntryKind.NAME, OutputEntryKind.of("variable_name"));
        assertEquals(OutputEntryKind.COLUMN, OutputEntryKind.of("variable_value"));
        assertEquals(OutputEntryKind.COLUMN, OutputEntryKind.of("AETERM"));
        assertEquals(OutputEntryKind.COLUMN, OutputEntryKind.of("DM.ARM"));
        assertEquals(OutputEntryKind.COLUMN, OutputEntryKind.of("--CAT"));
        assertEquals(OutputEntryKind.BINDING, OutputEntryKind.of("$x"));
        assertEquals(OutputEntryKind.CHAR, OutputEntryKind.of("variable_label"));
        assertEquals(OutputEntryKind.CHAR, OutputEntryKind.of("dataset_label"));
        assertEquals(OutputEntryKind.CHAR, OutputEntryKind.of("library_variable_label_template"));
        assertEquals(OutputEntryKind.NUM, OutputEntryKind.of("variable_length"));
        assertEquals(OutputEntryKind.NUM, OutputEntryKind.of("variable_max_size"));
        assertEquals(OutputEntryKind.NUM, OutputEntryKind.of("record_count"));
        assertEquals(OutputEntryKind.NUM, OutputEntryKind.of("define_vlm_length"));
        assertEquals(OutputEntryKind.BOOLEAN, OutputEntryKind.of("define_variable_has_comment"));
        assertEquals(OutputEntryKind.BOOLEAN,
                OutputEntryKind.of("library_variable_codelist_extensible"));
        assertEquals(OutputEntryKind.LIST,
                OutputEntryKind.of("library_variable_codelist_coded_values"));
        assertEquals(OutputEntryKind.LIST, OutputEntryKind.of("library_variable_label_values"));
        assertEquals("", OutputEntryKind.CHAR.defaultValue());
        for (OutputEntryKind k : List.of(OutputEntryKind.NUM, OutputEntryKind.BOOLEAN,
                OutputEntryKind.LIST))
        {
            assertEquals(MIS, k.defaultValue(), k.toString());
        }
    }
}
