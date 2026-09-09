package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.cumba.corej.core.model.Operation;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⭐ The runtime half of the {@code key_name} diagnostic — the half that would have caught
 * {@code FDA-SD1078} automatically.
 *
 * <p>
 * The load-time guard ({@code OperationExpressionParser.validateKeyName}) rejects a key <em>no</em>
 * level can serve. It deliberately cannot reject {@code core} on
 * {@code get_model_filtered_variables}, the shape FDA-SD1078 got wrong, because the Model walk
 * <em>does</em> publish {@code core} for SUPP--/SQ-- datasets and for every ADaM dataset. So the
 * distinction — "the filter matched nothing" versus "this level cannot serve this key at all" — is
 * drawn where the rows are, at runtime.
 * </p>
 *
 * <p>
 * ⚠ Deliberately a WARNING and not a SKIP: the verdict is unchanged, so the diagnostic moves no
 * findings. {@link #theVerdictIsUnchanged} pins that.
 * </p>
 */
class UnservedKeyNameDiagnosticTest
{

    private static final DatasetResolver NO_RESOLVER = d -> null;

    /** A standard SDTM domain, as FDA-SD1078 was run against. */
    private static IDataTable ae()
    {
        return MockTable.of().name("AE").col("DOMAIN", "AE").col("AESEV", "MILD").build();
    }


    /**
     * The pure-Model row shape: {@code substituteAndResolve} publishes role / simpleDatatype /
     * label / ordinal for a standard domain, and <b>no {@code core}</b> — the Model product does
     * not carry it.
     */
    private static List<Map<String, String>> modelRowsWithoutCore()
    {
        return List.of(Map.of("name", "AESEV", "role", "Record Qualifier", "ordinal", "12"),
                Map.of("name", "AETERM", "role", "Topic", "ordinal", "5"));
    }


    /**
     * The SUPP/SQ and ADaM row shape: the same accessor, but these rows DO carry {@code core}. The
     * counter-case that makes a load-time reject of {@code core} wrong.
     */
    private static List<Map<String, String>> modelRowsWithCore()
    {
        return List.of(Map.of("name", "AESEV", "role", "Record Qualifier", "core", "Perm"),
                Map.of("name", "AETERM", "role", "Topic", "core", "Req"));
    }


    private static Operation filterOp(String keyName, String keyValue)
    {
        Operation op = new Operation();
        op.setId("$t");
        op.setOperator("get_model_filtered_variables");
        op.setKeyName(keyName);
        op.setKeyValue(keyValue);
        return op;
    }


    private static Map<String, Object> run(List<Map<String, String>> rows, Operation op)
    {
        return OperationExecutor.execute(List.of(op), ae(), NO_RESOLVER,
                new ModelRowProvider(rows));
    }


    @Test
    @DisplayName("the FDA-SD1078 shape: core on a Model walk that publishes none")
    void anUnservedKeyIsReported()
    {
        List<String> lines = capture(() -> run(modelRowsWithoutCore(), filterOp("core", "Perm")));
        assertEquals(1, lines.size(), lines.toString());
        String line = lines.get(0);
        // The message must name the operation (hence the level), the key, the dataset, and what
        // this level DOES publish — everything a rule author needs to move to the other operation.
        assertTrue(line.contains("get_model_filtered_variables"), line);
        assertTrue(line.contains("key_name `core`"), line);
        assertTrue(line.contains("AE"), line);
        assertTrue(line.contains("can never match"), line);
        assertTrue(line.contains("role"), line);
        assertTrue(line.contains("ordinal"), line);
        assertTrue(!line.contains("core`]") && !line.contains("[core"), line);
    }


    @Test
    @DisplayName("⛔ the same declaration on rows that DO carry core is silent")
    void aServedKeyIsNotReported()
    {
        // This is the discriminator. If the diagnostic fired on "the filter matched nothing"
        // rather than "the key is absent from every row", it would also fire here — and a
        // load-time reject of (model, core) would have refused this rule outright.
        assertEquals(List.of(), capture(() -> run(modelRowsWithCore(), filterOp("core", "Perm"))));
        // …and it really did filter: only the Perm variable comes back.
        assertEquals(List.of("AESEV"),
                run(modelRowsWithCore(), filterOp("core", "Perm")).get("$t"));
    }


    @Test
    @DisplayName("a served key that simply matches nothing is silent too")
    void aServedKeyMatchingNothingIsNotReported()
    {
        assertEquals(List.of(),
                capture(() -> run(modelRowsWithoutCore(), filterOp("role", "Timing"))));
        assertEquals(List.of(), run(modelRowsWithoutCore(), filterOp("role", "Timing")).get("$t"));
    }


    @Test
    void noFilterMeansNoDiagnostic()
    {
        Operation unfiltered = filterOp(null, null);
        assertEquals(List.of(), capture(() -> run(modelRowsWithoutCore(), unfiltered)));
        assertEquals(List.of("AESEV", "AETERM"), run(modelRowsWithoutCore(), unfiltered).get("$t"));
    }


    @Test
    @DisplayName("a WARNING, not a SKIP — the diagnostic moves no findings")
    void theVerdictIsUnchanged()
    {
        // The under-reporting result is still the under-reporting result: landing the diagnostic
        // is behaviour-neutral, and escalating it is a separate, owner-visible decision.
        assertEquals(List.of(), run(modelRowsWithoutCore(), filterOp("core", "Perm")).get("$t"));
    }


    /** Runs {@code body} with a handler attached to {@link OperationExecutor}'s class logger. */
    private static List<String> capture(Runnable body)
    {
        CapturingHandler handler = new CapturingHandler();
        handler.setLevel(Level.ALL);
        Logger juli = Logger.getLogger(OperationExecutor.class.getName());
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

    /** A provider whose only real answer is the Model-level detailed row list under test. */
    private record ModelRowProvider(List<Map<String, String>> rows) implements MetadataProvider
    {

        @Override
        public List<Map<String, String>> getStandardModelVariablesDetailed(IDataTable aTable,
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
        public @Nullable String getVersion()
        {
            return "3-4";
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
        public List<String> getModelColumnOrder(String aDomain)
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


        @Override
        public Map<String, String> getCodelistTermMappings(String aCodelistName)
        {
            return Map.of();
        }
    }


    /**
     * Collects the {@link LogRecord}s emitted by {@link OperationExecutor}'s class logger. Lombok's
     * {@code @CustomLog} yields a {@link System.Logger}, which the JDK routes through
     * {@code java.util.logging}.
     */
    private static final class CapturingHandler extends Handler
    {

        private final List<LogRecord> records = new ArrayList<>();

        /** The captured records with their {@code {0}} placeholders substituted. */
        List<String> formatted()
        {
            return records.stream()
                    .map(r -> MessageFormat.format(r.getMessage(), r.getParameters())).toList();
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
