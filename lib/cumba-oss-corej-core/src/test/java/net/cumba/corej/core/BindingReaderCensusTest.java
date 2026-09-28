package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ⛔ The <b>reader census</b> of wave 0 ({@code PLAN-binding-expressions} §5.0 / §5.1), as a
 * ratchet: every main-source file of the engine that reads a rule's bindings — through
 * {@code getOperations()}, {@code getCompiledBindings()}, {@code bindingOrder()},
 * {@code compiledBinding(…)} or the authored {@code getBindings()} — is pinned here with the
 * treatment it gives a <b>compiled</b> binding.
 *
 * <p>
 * Why a ratchet and not only the sealed view. {@code Rule.operations} kept its meaning (the
 * operation bindings the executor reads, so none of the 892 shipped bindings can move), which means
 * a new reader can iterate it alone and silently miss every compiled binding — the failure §5.0
 * exists to prevent. The sealed {@code BoundBinding} switch forces a reader that uses
 * {@code bindingOrder()} to handle both kinds; this census forces a reader that does not to be
 * <em>seen</em>: a file that starts reading bindings reds here until a row says what it does with a
 * compiled one. Comments are stripped before scanning, so a javadoc mention is not a reader.
 * </p>
 */
class BindingReaderCensusTest
{

    private static final Pattern READER = Pattern.compile(
            "getOperations\\(\\)|getCompiledBindings\\(\\)|bindingOrder\\(\\)|getBindings\\(\\)"
                    + "|compiledBinding\\(");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    /** Every binding reader, by path under {@code src/main/java/net/cumba/corej/}. */
    private static final Map<String, String> CENSUS = new TreeMap<>(Map.ofEntries(
            Map.entry("core/RulePackageLoader.java",
                    "R1 routing + duplicate names, R2 polarity through bindings, R4/R5 inliner"
                            + " eligibility, R6 dangling refs over both kinds, R7/R8 nested"
                            + " calls, installCompiledBindings (R24/R9)"),
            Map.entry("core/exec/AbsentDatasetSkip.java",
                    "R12/R13 dotted + domain= reads of compiled bindings, R14 read-through"),
            Map.entry("core/exec/OutputVariableDeriver.java",
                    "R15 list-valued compiled binding is bulk, R16 D4a id + D4b target columns"),
            Map.entry("core/exec/ProviderNeeds.java",
                    "the one provider-needs reader: ops by OperationType, compiled bindings by"
                            + " their calls' capability"),
            Map.entry("core/exec/ProviderRequirements.java",
                    "R20 surface 1 via ProviderNeeds.ofBindings, surface 2b over compiled"
                            + " expressions"),
            Map.entry("core/exec/RuleClassifier.java",
                    "R19 reads compiled bindings through (BindingInliner); ops by id"),
            Map.entry("core/exec/RuleRunner.java",
                    "R10 BindingValue holders in authored order, context-first eager arms,"
                            + " I2 operand gate over compiled expressions"),
            Map.entry("core/exec/RuleSpecialiser.java",
                    "R11 resolves -- in compiled expressions and SETS them on the copy"),
            Map.entry("core/exec/StudyRuleClassifier.java",
                    "R27 walks a compiled binding's expression, never assumes the worst"),
            Map.entry("core/expr/convert/BindingInliner.java",
                    "the read-through helper static readers share"),
            Map.entry("core/expr/convert/RulePackageExpressionJson.java",
                    "R23 renders both kinds from bindingOrder()"),
            Map.entry("core/expr/eval/OperationKinds.java",
                    "R9 compiled binding kind/domain from its derived domain, authored order"),
            Map.entry("core/expr/typed/StageAChecker.java",
                    "R24 types compiled bindings with the Check's checker, R25 order over both"
                            + " kinds + operation-reads-cursor-binding, R26 dotted refs"),
            Map.entry("core/expr/typed/StageBChecker.java",
                    "I3 compiled expressions are stage-B roots (R18 via I3)"),
            Map.entry("core/gen/TokenExpander.java",
                    "R21 copies compiled bindings with the token substitution"),
            Map.entry("core/gen/WildcardExpander.java",
                    "R22 markers in compiled bindings make a template; renamed like the Check"),
            Map.entry("core/model/Rule.java", "the carrier: bindingOrder() / compiledBinding()"),
            Map.entry("ruletest/cdt/ruletest/ScenarioCapture.java",
                    "R28 compiled bindings' domain= / inventory / presence reads are captured")));

    private static final Path CORE = Path.of("src", "main", "java", "net", "cumba", "corej");

    @Test
    void everyBindingReaderIsInTheCensus() throws IOException
    {
        TreeSet<String> found = new TreeSet<>();
        scan(CORE, found);
        scan(ruletestRoot(), found);
        assertTrue(found.size() >= 15, "the scan reached too few files (" + found.size()
                + ") — it ran over the wrong directory, which would make the pin vacuous");
        assertEquals(CENSUS.keySet(), found,
                "a main-source file started (or stopped) reading a rule's bindings: give it a row"
                        + " saying what it does with a COMPILED binding (PLAN-binding-expressions"
                        + " §5.1) — iterating getOperations() alone silently misses every one");
    }


    /**
     * The sibling ruletest module's source root — {@code cumba-corej-ruletest} here,
     * {@code cumba-oss-corej-ruletest} in the OSS twin, so it is found by suffix and this file
     * stays byte-identical across the twins.
     */
    private static Path ruletestRoot() throws IOException
    {
        try (Stream<Path> siblings = Files.list(Path.of("..")))
        {
            List<Path> found = siblings
                    .filter(p -> p.getFileName().toString().endsWith("corej-ruletest")).toList();
            assertEquals(1, found.size(), "exactly one sibling ruletest module: " + found);
            return found.get(0).resolve(Path.of("src", "main", "java", "net", "cumba", "corej"));
        }
    }


    private static void scan(Path root, java.util.Set<String> found) throws IOException
    {
        assertTrue(Files.isDirectory(root), "missing source root " + root.toAbsolutePath());
        try (Stream<Path> files = Files.walk(root))
        {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList())
            {
                String code = LINE_COMMENT
                        .matcher(BLOCK_COMMENT.matcher(Files.readString(file)).replaceAll(""))
                        .replaceAll("");
                if (READER.matcher(code).find())
                {
                    found.add(root.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
    }

}
