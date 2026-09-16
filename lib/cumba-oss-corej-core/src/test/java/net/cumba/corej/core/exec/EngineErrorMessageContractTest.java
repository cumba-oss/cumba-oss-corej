package net.cumba.corej.core.exec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Pins the exact wording of the engine's authored ERROR messages, and the number of sites that can
 * produce one.
 *
 * <p>
 * ⚠⚠ <b>This is one half of a CROSS-REPOSITORY pair, and the halves must change together.</b> The
 * other half is {@code ViolationNormaliser} in <b>cumba-oss-corej-rules</b>
 * ({@code src/test/java/net/cumba/corej/core/rulespec/}), which classifies a rule execution into a
 * closed {@code ErrorReason} vocabulary by matching these messages EXACTLY. That exact match is
 * deliberate — a reworded message degrades to {@code OTHER}, which is visible rather than
 * mis-classified — but "visible" only helps if something looks, and no corpus spec produces a Java
 * {@code ERROR} today. This test is that something.
 * </p>
 *
 * <p>
 * ⭐ <b>Why it lives here rather than beside the classifier it protects.</b> It reads the engine's
 * SOURCE TEXT: the messages are inline literals with no constant to import, and one assertion
 * counts source lines, which no compiled form preserves. In the coreJ monorepo the classifier's
 * module read {@code ../corej-core/src/main/java/...} as a sibling. The split put the engine in
 * this repository and the classifier in another, and cumba-oss-corej-rules' build never checks this
 * one out. The engine's sources jar is not a way round it: this repo attaches sources on the deploy
 * path only, by an explicit decision in the root pom, and a test convenience is not a reason to
 * reverse that.
 * </p>
 *
 * <p>
 * ⚠ So the three literals below are COPIES of {@code ViolationNormaliser}'s. Changing an engine
 * message means changing it in three places: the engine, this test, and that classifier. Changing
 * only the first two leaves the classifier silently degrading every affected ERROR to
 * {@code OTHER}.
 * </p>
 */
class EngineErrorMessageContractTest
{

    private static final Path SRC = Path.of(System.getProperty("projectBasedir"), "src", "main",
            "java", "net", "cumba", "corej", "core", "exec");

    /** Verbatim copy of {@code ViolationNormaliser.RELREC_KEY_EXPANSION_MESSAGE}. */
    private static final String RELREC_KEY_EXPANSION_MESSAGE = "forward RELREC join combined with a key-based Match_Datasets row "
            + "expansion is not supported on the same rule";

    /** Verbatim copy of {@code ViolationNormaliser.NO_NATIVE_CHECK_EXPR_MESSAGE}. */
    private static final String NO_NATIVE_CHECK_EXPR_MESSAGE = "Rule error — the Check has no native expression form; the "
            + "legacy evaluator has been retired";

    /** Verbatim copy of {@code ViolationNormaliser.INVALID_SPLIT_DOMAIN_MESSAGE_SUFFIX}. */
    private static final String INVALID_SPLIT_DOMAIN_MESSAGE_SUFFIX = "; the domain cannot be joined";

    private static String source(String aSimpleName) throws IOException
    {
        Path p = SRC.resolve(aSimpleName);
        Assertions.assertTrue(Files.isRegularFile(p),
                "engine source not found at " + p.toAbsolutePath()
                        + " — this guard cannot run, and a reworded message would silently "
                        + "reclassify every affected ERROR as OTHER in cumba-oss-corej-rules.");
        return Files.readString(p, StandardCharsets.UTF_8);
    }


    @Test
    void theEngineStillHasExactlySixErrorSites() throws IOException
    {
        // The ErrorReason vocabulary is exhaustive only as long as RuleRunner has no seventh way
        // to return ERROR. If this count changes, the classifier needs a new branch — otherwise
        // the new site silently lands in OTHER. Site 5 is Fix #358's InvalidJoinedDomainException
        // catch (split-domain union failure → INVALID_SPLIT_DOMAIN, matched by its fixed message
        // tail; it used to be mapped in CohortRunner as well, which this RuleRunner-only count
        // deliberately did not see — that second site is gone with the cohort runner,
        // PLAN-retire-cohort-runner.md). Site 6 is PLAN-column-type-conformance Phase 3's
        // ColumnTypeMismatchException catch — its messages all start with the fixed prefix
        // "column-type mismatch: " (pinned below), which is the classifier's match key, and
        // ViolationNormaliser in cumba-oss-corej-rules carries the matching COLUMN_TYPE_MISMATCH
        // branch.
        long sites = source("RuleRunner.java").lines()
                .filter(l -> l.contains("RuleExecutionStatus.ERROR")).count();
        Assertions.assertEquals(6, sites,
                "RuleRunner's ERROR-producing sites changed. Re-derive the ErrorReason vocabulary "
                        + "in cumba-oss-corej-rules' ViolationNormaliser before accepting this.");
    }


    @Test
    void theColumnTypeMismatchPrefixIsStillTheEngineSWording() throws IOException
    {
        // Phase 3's messages are built in ColumnTypeGate (expr/eval, not exec) and every one
        // starts with this prefix — the stable key cumba-oss-corej-rules' ViolationNormaliser
        // branch matches on. THREE builder sites, so the count closes the rewording-one-of-them
        // hole.
        Path gate = Path.of(System.getProperty("projectBasedir"), "src", "main", "java", "net",
                "cumba", "corej", "core", "expr", "eval", "ColumnTypeGate.java");
        Assertions.assertTrue(Files.isRegularFile(gate),
                "ColumnTypeGate source not found at " + gate.toAbsolutePath());
        String text = Files.readString(gate, StandardCharsets.UTF_8);
        long count = text.lines().filter(l -> l.contains("\"column-type mismatch: \"")).count();
        Assertions.assertEquals(3, count,
                "the \"column-type mismatch: \" message prefix no longer appears at exactly the "
                        + "three ColumnTypeGate builder sites — a reworded prefix would classify "
                        + "every such ERROR as OTHER in cumba-oss-corej-rules");
    }


    @Test
    void theInvalidSplitDomainSuffixIsStillTheEngineSWording() throws IOException
    {
        // The suffix lives in SplitDomainResolution (the resolution helper), not RuleRunner.
        Assertions.assertTrue(
                source("SplitDomainResolution.java").contains(INVALID_SPLIT_DOMAIN_MESSAGE_SUFFIX),
                "the split-domain union-failure message tail moved or was reworded — every such "
                        + "ERROR would classify as OTHER; update this constant AND "
                        + "ViolationNormaliser's in cumba-oss-corej-rules");
    }


    @Test
    void theRelrecMessageIsStillTheEngineSWording() throws IOException
    {
        assertSourceContains(RELREC_KEY_EXPANSION_MESSAGE, 1);
    }


    @Test
    void theNoNativeCheckExprMessageIsStillTheEngineSWording() throws IOException
    {
        // TWO occurrences — two sites emit the same text. Asserting the COUNT, not just presence,
        // closes the hole a bare `contains` leaves: rewording only one of the two would otherwise
        // keep this green while every ERROR from that site silently classified as OTHER.
        assertSourceContains(NO_NATIVE_CHECK_EXPR_MESSAGE, 2);
    }


    /**
     * Java's compiler concatenates a literal split across source lines, so the engine text is
     * searched with the {@code " + "} seams removed.
     */
    private static void assertSourceContains(String aMessage, int aExpectedOccurrences)
        throws IOException
    {
        String flattened = source("RuleRunner.java").replaceAll("\"\\s*\\+\\s*\"", "");
        int count = 0;
        for (int from = flattened.indexOf(aMessage); from >= 0; from = flattened.indexOf(aMessage,
                from + aMessage.length()))
        {
            count++;
        }
        Assertions.assertEquals(aExpectedOccurrences, count,
                "this engine message no longer appears exactly " + aExpectedOccurrences
                        + " time(s) in RuleRunner:\n  " + aMessage
                        + "\nEvery ERROR carrying a changed wording would classify as OTHER in "
                        + "cumba-oss-corej-rules, losing the label precision the errors channel exists "
                        + "to provide. Update this constant and ViolationNormaliser's — and if a "
                        + "SITE was added or removed, re-derive the ErrorReason vocabulary too.");
    }
}
