package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.ProviderNeed;
import net.cumba.corej.core.expr.eval.UnusableProviderAnswerException;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⭐ The runtime half of the {@code key_name} check — CDW-F1 (a): an unserved {@code key_name} SKIPs
 * the rule, never a silent pass.
 *
 * <p>
 * The load-time guard (the compile seam {@code ExprCompiler.rejectNonLiteralListArguments}) rejects
 * a key <em>no</em> level can serve. It deliberately cannot reject {@code core} on
 * {@code get_model_filtered_variables}, nor on {@code get_dataset_filtered_variables} for a domain
 * the run's IG does not define (whose rows are the SDTM Model's), because the same walks
 * <em>do</em> publish {@code core} for SUPP--/SQ-- datasets, for every ADaM dataset and for every
 * IG-defined domain. So the distinction — "the filter matched nothing" versus "this level cannot
 * serve this key at all" — is drawn where the rows are, at runtime, for both functions.
 * </p>
 *
 * <p>
 * U-DEF: only where the filter can never match. Without a {@code key_value} an unserved key matches
 * every row ({@code Objects.equals(null, null)}) and keeps that answer; an empty resolution keeps
 * each function's own answer. The report carries {@code RuleRunner}'s generic SKIP text; the key,
 * the dataset and the published keys go to the INFO line and the exception (U-HOW).
 * </p>
 */
class UnservedKeyNameDiagnosticTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = "get_model_filtered_variables";

    private static final String DATASET = "get_dataset_filtered_variables";

    /** A standard SDTM domain carrying both library variables as columns. */
    private static IDataTable ae()
    {
        return MockTable.of().name("AE").col("DOMAIN", "AE").col("AETERM", "HEADACHE")
                .col("AESEV", "MILD").build();
    }


    /**
     * The pure-Model row shape: {@code substituteAndResolve} publishes role / simpleDatatype /
     * label / ordinal for a standard domain, and <b>no {@code core}</b> — the Model product does
     * not carry it. It is also what the dataset-level walk answers for a domain the run's IG does
     * not define.
     */
    private static List<Map<String, String>> rowsWithoutCore()
    {
        return List.of(Map.of("name", "AESEV", "role", "Record Qualifier", "ordinal", "12"),
                Map.of("name", "AETERM", "role", "Topic", "ordinal", "5"));
    }


    /** The IG / SUPP / ADaM row shape: the same accessors, but these rows DO carry {@code core}. */
    private static List<Map<String, String>> rowsWithCore()
    {
        return List.of(Map.of("name", "AESEV", "role", "Record Qualifier", "core", "Perm"),
                Map.of("name", "AETERM", "role", "Topic", "core", "Req"));
    }


    /** Some rows carry {@code core}, one does not: the key is served. */
    private static List<Map<String, String>> rowsPartlyWithCore()
    {
        return List.of(Map.of("name", "AESEV", "role", "Record Qualifier", "core", "Perm"),
                Map.of("name", "AETERM", "role", "Topic"));
    }


    /** {@code get_model_filtered_variables(key_name, key_value)} over {@code rows}. */
    private static List<?> model(List<Map<String, String>> rows, @Nullable String keyName,
            @Nullable String keyValue)
    {
        return call(LibraryLists::modelFilteredVariables, rows, keyName, keyValue, true);
    }


    /** {@code get_dataset_filtered_variables(key_name, key_value)} over {@code rows}. */
    private static List<?> dataset(List<Map<String, String>> rows, @Nullable String keyName,
            @Nullable String keyValue)
    {
        return call(LibraryLists::datasetFilteredVariables, rows, keyName, keyValue, false);
    }


    private static List<?> call(BiFunction<EvalRun, List<Vector>, Vector> function,
            List<Map<String, String>> rows, @Nullable String keyName, @Nullable String keyValue,
            boolean withModelClass)
    {
        Vector name = keyName == null ? null : ConstVector.of(keyName);
        Vector value = keyValue == null ? null : ConstVector.of(keyValue);
        // A null argument is an absent optional parameter.
        List<Vector> args = withModelClass ? Arrays.asList(name, value, null)
                : Arrays.asList(name, value);
        Object answer = function
                .apply(EvalRun.fullRange(EvaluationContext.builder().table(ae())
                        .libraryProvider(new RowProvider(rows)).ruleId("R-KEY").build()), args)
                .value(0).resolved();
        return (List<?>) Objects.requireNonNull(answer);
    }


    private static void assertSkipSignal(UnusableProviderAnswerException e, String function)
    {
        assertEquals(function, e.function());
        assertEquals(ProviderNeed.Kind.LIBRARY, e.kind());
        String message = e.getMessage();
        assertNotNull(message);
        // The message names the function (hence the level), the key, the dataset, and what this
        // level DOES publish — everything a rule author needs.
        assertTrue(message.startsWith(function + ": "), message);
        assertTrue(message.contains("key_name `core`"), message);
        assertTrue(message.contains("none of the 2 variables"), message);
        assertTrue(message.contains("dataset AE"), message);
        assertTrue(message.contains("this level publishes [name, ordinal, role]"), message);
    }


    @Test
    @DisplayName("the FDA-SD1078 shape on the model walk: an unserved key with a value SKIPs")
    void anUnservedKeyWithAValueSkipsTheModelLevel()
    {
        List<String> lines = capture(() ->
        {
            UnusableProviderAnswerException e = assertThrows(UnusableProviderAnswerException.class,
                    () -> model(rowsWithoutCore(), "core", "Perm"));
            assertSkipSignal(e, MODEL);
        });
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).startsWith("INFO [R-KEY] " + MODEL + ": key_name `core` is carried"
                + " by none of the 2 variables resolved for dataset AE — rule will be skipped."),
                lines.get(0));
    }


    @Test
    @DisplayName("the FDA-SD1078 shape on the dataset walk: an unserved key with a value SKIPs")
    void anUnservedKeyWithAValueSkipsTheDatasetLevel()
    {
        List<String> lines = capture(() ->
        {
            UnusableProviderAnswerException e = assertThrows(UnusableProviderAnswerException.class,
                    () -> dataset(rowsWithoutCore(), "core", "Perm"));
            assertSkipSignal(e, DATASET);
        });
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).startsWith("INFO [R-KEY] " + DATASET), lines.get(0));
    }


    @Test
    @DisplayName("U-DEF: without a key_value an unserved key matches every row — no SKIP")
    void anUnservedKeyWithoutAValueMatchesEveryRow()
    {
        assertEquals(List.of(), capture(() ->
        {
            assertEquals(List.of("AESEV", "AETERM"), model(rowsWithoutCore(), "core", null));
            assertEquals(List.of("AESEV", "AETERM"), dataset(rowsWithoutCore(), "core", null));
        }));
    }


    @Test
    @DisplayName("⛔ the same declaration on rows that DO carry core is answered, not skipped")
    void aServedKeyIsAnswered()
    {
        // The discriminator: the check fires on "the key is absent from every row", never on
        // "the filter matched nothing".
        assertEquals(List.of(), capture(() ->
        {
            assertEquals(List.of("AESEV"), model(rowsWithCore(), "core", "Perm"));
            assertEquals(List.of("AESEV"), dataset(rowsWithCore(), "core", "Perm"));
            // Served by one row of two: the row without it simply does not match.
            assertEquals(List.of("AESEV"), model(rowsPartlyWithCore(), "core", "Perm"));
            assertEquals(List.of(), dataset(rowsPartlyWithCore(), "core", "Req"));
        }));
    }


    @Test
    @DisplayName("a served key that simply matches nothing is an empty answer")
    void aServedKeyMatchingNothingIsAnEmptyAnswer()
    {
        assertEquals(List.of(), capture(() ->
        {
            assertEquals(List.of(), model(rowsWithoutCore(), "role", "Timing"));
            assertEquals(List.of(), dataset(rowsWithoutCore(), "role", "Timing"));
        }));
    }


    @Test
    void noFilterMeansEveryRow()
    {
        assertEquals(List.of(), capture(() ->
        {
            assertEquals(List.of("AESEV", "AETERM"), model(rowsWithoutCore(), null, null));
            assertEquals(List.of("AESEV", "AETERM"), dataset(rowsWithoutCore(), null, null));
        }));
    }


    @Test
    @DisplayName("U-DEF: an empty resolution keeps each function's own answer — the empty set")
    void anEmptyResolutionKeepsItsAnswer()
    {
        assertEquals(List.of(), capture(() ->
        {
            assertEquals(List.of(), model(List.of(), "core", "Perm"));
            assertEquals(List.of(), dataset(List.of(), "core", "Perm"));
        }));
    }


    @Test
    @DisplayName("through RuleRunner: SKIPPED with the generic library text, for both functions")
    void theRuleIsSkippedWithTheGenericStatusMessage() throws Exception
    {
        for (String function : List.of(DATASET, MODEL))
        {
            Rule rule = rule("not empty($v)", "$v",
                    function + "(key_name=\"core\", key_value=\"Perm\")");
            RuleExecutionResult skipped = RuleRunnerCalls.execute(rule, ae(), _ -> null, null,
                    new RowProvider(rowsWithoutCore()));
            assertEquals(RuleExecutionStatus.SKIPPED, skipped.getStatus(), function);
            assertEquals("Rule skipped — library returned no data for " + function,
                    skipped.getStatusMessage());
            // The control: the same rule over rows that serve the key executes.
            RuleExecutionResult executed = RuleRunnerCalls.execute(rule, ae(), _ -> null, null,
                    new RowProvider(rowsWithCore()));
            assertEquals(RuleExecutionStatus.EXECUTED, executed.getStatus(),
                    function + ": " + executed.getStatusMessage());
            assertFalse(executed.getViolations().isEmpty(), function + ": $v = [AESEV]");
            // U-DEF: no key_value on the unserved rows executes too.
            Rule noValue = rule("not empty($v)", "$v", function + "(key_name=\"core\")");
            RuleExecutionResult matchesAll = RuleRunnerCalls.execute(noValue, ae(), _ -> null, null,
                    new RowProvider(rowsWithoutCore()));
            assertEquals(RuleExecutionStatus.EXECUTED, matchesAll.getStatus(),
                    function + ": " + matchesAll.getStatusMessage());
        }
    }


    private static Rule rule(String check, String binding, String expression) throws Exception
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "R-KEY"));
        rule.put("Bindings", List.of(Map.of("name", binding, "expression", expression)));
        rule.put("Check", Map.of("expression", check));
        rule.put("Outcome", Map.of("Message", "m", "Output_Variables", List.of(binding)));
        String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
        Rule loaded = Objects
                .requireNonNull(RulePackageLoader.loadFromString(json).getRules().get("x"));
        assertNull(loaded.getLoadError(), expression);
        return loaded;
    }


    /** Runs {@code body} with a handler attached to {@link LibraryLists}' class logger. */
    private static List<String> capture(Runnable body)
    {
        CapturingHandler handler = new CapturingHandler();
        handler.setLevel(Level.ALL);
        Logger juli = Logger.getLogger(LibraryLists.class.getName());
        Level previous = juli.getLevel();
        juli.addHandler(handler);
        juli.setLevel(Level.ALL);
        try
        {
            body.run();
        }
        finally
        {
            juli.removeHandler(handler);
            juli.setLevel(previous);
        }
        return handler.formatted().stream().filter(l -> l.contains("key_name")).toList();
    }

    /** A provider whose only real answers are the detailed row lists of both walks. */
    private record RowProvider(List<Map<String, String>> rows) implements MetadataProvider
    {

        @Override
        public List<Map<String, String>> getStandardModelVariablesDetailed(IDataTable aTable,
                DatasetResolver aResolver)
        {
            return rows;
        }


        @Override
        public List<Map<String, String>> getStandardVariablesDetailed(IDataTable aTable,
                DatasetResolver aResolver)
        {
            return rows;
        }

        // ---- the interface's abstract members; none is reached by these tests ----


        @Override
        public @Nullable String getStandard()
        {
            return "sdtmig";
        }


        @Override
        public List<String> getRequiredVariables(String aDomain)
        {
            return List.of();
        }


        @Override
        public List<String> getExpectedVariables(String aDomain)
        {
            return List.of();
        }


        @Override
        public List<String> getColumnOrder(String aDomain)
        {
            return List.of();
        }


        @Override
        public boolean isDomainCustom(String aDomain)
        {
            return false;
        }


        @Override
        public List<String> getCodelistTerms(String aCodelistCode)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getVariableMetadata(String aDomain, String aVariable)
        {
            return Map.of();
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String aDomain)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getDatasetMetadata(String aDomain)
        {
            return Map.of();
        }


        @Override
        public Optional<Boolean> isCodelistExtensible(String aCodelistName)
        {
            return Optional.empty();
        }

    }


    /**
     * Collects the {@link LogRecord}s emitted by {@link LibraryLists}' class logger, as
     * {@code "<LEVEL> <message>"}. Lombok's {@code @CustomLog} yields a {@link System.Logger},
     * which the JDK routes through {@code java.util.logging}.
     */
    private static final class CapturingHandler extends Handler
    {

        private final List<LogRecord> records = new ArrayList<>();

        /** The captured records with their {@code {0}} placeholders substituted. */
        List<String> formatted()
        {
            return records.stream().map(r -> r.getLevel() + " "
                    + MessageFormat.format(r.getMessage(), r.getParameters())).toList();
        }


        @Override
        public void publish(LogRecord logRecord)
        {
            records.add(logRecord);
        }


        @Override
        public void flush()
        {
            // no-op
        }


        @Override
        public void close()
        {
            // no-op
        }
    }
}
