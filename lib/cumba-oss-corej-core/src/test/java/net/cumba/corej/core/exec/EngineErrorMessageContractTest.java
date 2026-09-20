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
 * other half is {@code ViolationNormaliser} in <b>the rules repository</b>
 * ({@code src/test/java/net/cumba/corej/core/rulespec/} there), which classifies a rule execution
 * into a closed {@code ErrorReason} vocabulary by matching these messages EXACTLY. That exact match
 * is deliberate — a reworded message degrades to {@code OTHER}, which is visible rather than
 * mis-classified — but "visible" only helps if something looks, and no corpus spec produces a Java
 * {@code ERROR} today. This test is that something.
 * </p>
 *
 * <p>
 * ⭐ <b>Why it lives here rather than beside the classifier it protects.</b> It reads the engine's
 * SOURCE TEXT: the messages are inline literals with no constant to import, and one assertion
 * counts source lines, which no compiled form preserves. In the coreJ monorepo the classifier's
 * module read {@code ../corej-core/src/main/java/...} as a sibling. The split put the engine in
 * this repository and the classifier in another, and the rules repository's build never checks this
 * one out. The engine's sources jar is not a way round it: this repo attaches sources on the deploy
 * path only, by an explicit decision in the root pom, and a test convenience is not a reason to
 * reverse that.
 * </p>
 *
 * <p>
 * ⚠ So the {@code ViolationNormaliser} literals below are COPIES of that classifier's. Changing an
 * engine message means changing it in three places: the engine, this test, and that classifier.
 * Changing only the first two leaves the classifier silently degrading every affected ERROR to
 * {@code OTHER}.
 * </p>
 *
 * <p>
 * ⭐ <b>The classifier is no longer the only downstream.</b>
 * {@link #JOIN_LOOKUP_REQUIRED_MESSAGE_FRAGMENT} is a copy of a different literal in the rules
 * repository — the {@code messageSubstring} of that repo's {@code NativeCorpusFullCoverageTest}
 * allowlist — and its message is a thrown {@code SubstitutionException} from {@code ValueResolver},
 * not one of {@code RuleRunner}'s ERROR sites. Read that constant's own javadoc; the mechanism is
 * different even though the exposure (engine prose matched from another repository) is the same.
 * This class is the home for BOTH kinds: whenever a test in the rules repository matches on engine
 * prose, the pin belongs here, next to the words being changed.
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

    /**
     * ⚠⚠ <b>A SECOND cross-repository coupling, and a different one from the four above.</b> Those
     * protect {@code ViolationNormaliser}'s ERROR classification; this one protects a test
     * ALLOWLIST. Verbatim copy of the {@code messageSubstring} of the single
     * {@code KNOWN_UNDISPATCHABLE} entry in <b>the rules repository</b>'s
     * {@code NativeCorpusFullCoverageTest} ({@code src/test/java/net/cumba/corej/core/expr/eval/}
     * there).
     *
     * <p>
     * That gate asserts every shipped corpus rule reaches native dispatch. Three shipped copies of
     * {@code CDISC-AD0720} (packages {@code rules-cdisc-adamig-1-1}, {@code -1-2}, {@code -1-3})
     * legitimately cannot, so the gate allow-lists them by rule id <em>plus</em> this substring —
     * the substring being what stops the same rule throwing for a DIFFERENT reason from passing
     * silently. It is matched there with a plain {@code String.contains} against the thrown
     * message: {@code attempt.detail().contains(known.messageSubstring())}.
     * </p>
     *
     * <p>
     * ⛔ <b>So a harmless reword of the engine text below reds a gate in ANOTHER repository</b>, one
     * whose build never checks this repo out and whose failure names nothing over here. This test
     * is the warning that repo structurally cannot give you. Rewording means changing BOTH — this
     * constant and that {@code KNOWN_UNDISPATCHABLE} entry — in the same wave.
     * </p>
     *
     * <p>
     * ⚑ Deliberately NOT the whole message. The engine builds {@code "wildcard operand requires a
     * JoinLookup for foreign dataset `" + foreign + "` but none was provided (Match_Datasets
     * missing?)"}; only the run of words below is what the downstream {@code contains} sees, so
     * only that is pinned. Pinning the {@code "wildcard operand "} prefix or the
     * {@code "but none was provided"} tail as well would red this test for rewordings that break
     * nothing downstream — and a pin that cries wolf gets deleted in irritation.
     * </p>
     */
    private static final String JOIN_LOOKUP_REQUIRED_MESSAGE_FRAGMENT = "requires a JoinLookup for foreign dataset";

    private static String source(String aSimpleName) throws IOException
    {
        return sourceAt(SRC.resolve(aSimpleName));
    }


    /**
     * Reads an engine source file with its comments blanked out — see
     * {@link #stripComments(String)} for why that matters and what it does not handle.
     */
    private static String sourceAt(Path aPath) throws IOException
    {
        Assertions.assertTrue(Files.isRegularFile(aPath),
                "engine source not found at " + aPath.toAbsolutePath()
                        + " — this guard cannot run, so a reworded message would reach "
                        + "the rules repository unannounced (silently reclassified as OTHER by "
                        + "ViolationNormaliser, or redding NativeCorpusFullCoverageTest).");
        return stripComments(Files.readString(aPath, StandardCharsets.UTF_8));
    }


    /**
     * Replaces every {@code //…} and {@code /*…}{@code *}{@code /} comment with spaces, keeping
     * newlines so that line numbers, line counts and every line-based pin in this class are
     * unaffected.
     *
     * <p>
     * ⚠⚠ <b>Without this, every pin below is a LATENT DISARM.</b> They search the engine's source
     * TEXT, so a pin that asserts "this fragment appears exactly once" was equally satisfied by an
     * occurrence in a comment. Measured: rewording {@code ValueResolver}'s missing-JoinLookup
     * message <em>and</em> leaving a {@code //} comment quoting the old fragment kept
     * {@link #theJoinLookupRequiredFragmentIsStillTheEngineSWording} GREEN while the rules
     * repository's gate would have gone red. That comment is exactly the cross-reference the pins'
     * own javadoc asks a future editor to write, so the hole was one helpful edit away from being
     * live.
     * </p>
     *
     * <p>
     * ⚑ <b>A scanner, not a regex, and deliberately so.</b> A naive {@code replaceAll} would eat a
     * {@code //} or {@code /*} appearing INSIDE a string literal — a URL, a regex, a path — and
     * silently change the text being searched, trading a latent disarm for a live false red. This
     * walks the file tracking string and char literals (with backslash escapes) so literal content
     * is never touched. Measured 2026-09-18: no literal in any scanned file ({@code RuleRunner},
     * {@code SplitDomainResolution}, {@code ValueResolver}, {@code ColumnTypeGate}) contains either
     * sequence today — the scanner is what keeps that from having to stay true.
     * </p>
     *
     * <p>
     * ⚠ <b>Limits, stated rather than papered over.</b> It is not a full Java lexer: it does not
     * understand text blocks ({@code """}) or unicode escapes that spell a quote or a slash
     * ({@code \}{@code u0022}, {@code \}{@code u002f}). Text blocks are ASSERTED absent below
     * rather than merely assumed, so a future one reds here loudly instead of corrupting a pin;
     * unicode-escaped delimiters are neither used nor checked for — do not introduce one in a
     * scanned file.
     * </p>
     */
    private static String stripComments(String aSource)
    {
        StringBuilder out = new StringBuilder(aSource.length());
        int n = aSource.length();
        int i = 0;
        while (i < n)
        {
            char c = aSource.charAt(i);
            if (c == '"' || c == '\'')
            {
                Assertions.assertFalse(aSource.startsWith("\"\"\"", i),
                        "this engine source uses a text block, which this class's comment "
                                + "stripper does not lex — teach it text blocks before relying "
                                + "on the pins below.");
                out.append(c);
                i++;
                while (i < n)
                {
                    char d = aSource.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\\' && i < n)
                    {
                        out.append(aSource.charAt(i));
                        i++;
                    }
                    else if (d == c)
                    {
                        break;
                    }
                }
            }
            else if (c == '/' && i + 1 < n && aSource.charAt(i + 1) == '/')
            {
                while (i < n && aSource.charAt(i) != '\n')
                {
                    out.append(' ');
                    i++;
                }
            }
            else if (c == '/' && i + 1 < n && aSource.charAt(i + 1) == '*')
            {
                out.append(' ');
                out.append(' ');
                i += 2;
                while (i < n
                        && !(aSource.charAt(i) == '*' && i + 1 < n && aSource.charAt(i + 1) == '/'))
                {
                    out.append(aSource.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n)
                {
                    out.append(' ');
                    out.append(' ');
                    i += 2;
                }
            }
            else
            {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }


    @Test
    void theEngineStillHasExactlySevenErrorSites() throws IOException
    {
        // The ErrorReason vocabulary is exhaustive only as long as RuleRunner has no seventh way
        // to return ERROR. If this count changes, the classifier needs a new branch — otherwise
        // the new site silently lands in OTHER. Site 5 is Fix #358's
        // InvalidJoinedDomainException catch (split-domain union failure → INVALID_SPLIT_DOMAIN,
        // matched by its fixed message tail; it used to be mapped in CohortRunner as well, which
        // this RuleRunner-only count deliberately did not see — that second site is gone with the
        // cohort runner, PLAN-retire-cohort-runner.md). Site 6 is PLAN-column-type-conformance
        // Phase 3's
        // ColumnTypeMismatchException catch — its messages all start with the fixed prefix
        // "column-type mismatch: " (pinned below), which is the classifier's match key.
        // ⚠ ViolationNormaliser in the rules repository still needs the matching
        // COLUMN_TYPE_MISMATCH branch — that repo is Phase 6's lane, not this one's. (Since
        // delivered: the classifier carries COLUMN_TYPE_MISMATCH_MESSAGE_PREFIX.) Site 7 is
        // PLAN-typed-expression-engine phase 4's stage-B bind gate (RuleRunner.mapStageBReport) —
        // its messages all start with the fixed prefix "stage B: " (pinned below), which is the
        // classifier's match key. ⭐ SINCE DELIVERED (2026-09-17, D104e): ViolationNormaliser
        // carries STAGE_B and STAGE_B_MESSAGE_PREFIX, and StageBErrorReasonReachabilityTest in the
        // rules repository proves the branch reachable by driving RuleRunner.stageBGate and
        // classifying the message the ENGINE built — so a reworded prefix reds there instead of
        // degrading every stage-B ERROR to OTHER in silence. Until it landed such an ERROR did
        // classify as OTHER, which was tolerable only because every armed stage-B kind is measured
        // at ZERO population.
        long sites = source("RuleRunner.java").lines()
                .filter(l -> l.contains("RuleExecutionStatus.ERROR")).count();
        Assertions.assertEquals(7, sites,
                "RuleRunner's ERROR-producing sites changed. Re-derive the ErrorReason vocabulary "
                        + "in the rules repository's ViolationNormaliser before accepting this.");
    }


    @Test
    void theStageBPrefixIsStillTheEngineSWording() throws IOException
    {
        // Phase 4's stage-B bind errors are built at exactly ONE site (RuleRunner.mapStageBReport)
        // and every message starts with this prefix — the stable key for the owed
        // rules-repository classifier branch. Counting the builder site closes the
        // rewording hole the same way the column-type prefix pin does.
        long count = source("RuleRunner.java").lines().filter(l -> l.contains("\"stage B: \""))
                .count();
        Assertions.assertEquals(1, count,
                "the \"stage B: \" message prefix no longer appears at exactly the one "
                        + "stage-B gate builder site — a reworded prefix would strand the "
                        + "classifier branch the rules repository is owed for it");
    }


    @Test
    void theColumnTypeMismatchPrefixIsStillTheEngineSWording() throws IOException
    {
        // Phase 3's messages are built in ColumnTypeGate (expr/eval, not exec) and every one
        // starts with this prefix — the stable key a rules-repository classifier branch can
        // match on. THREE builder sites, so the count closes the rewording-one-of-them hole.
        Path gate = Path.of(System.getProperty("projectBasedir"), "src", "main", "java", "net",
                "cumba", "corej", "core", "expr", "eval", "ColumnTypeGate.java");
        String text = sourceAt(gate);
        long count = text.lines().filter(l -> l.contains("\"column-type mismatch: \"")).count();
        Assertions.assertEquals(3, count,
                "the \"column-type mismatch: \" message prefix no longer appears at exactly the "
                        + "three ColumnTypeGate builder sites — a reworded prefix would classify "
                        + "every such ERROR as OTHER in the rules repository");
    }


    @Test
    void theInvalidSplitDomainSuffixIsStillTheEngineSWording() throws IOException
    {
        // The suffix lives in SplitDomainResolution (the resolution helper), not RuleRunner.
        Assertions.assertTrue(
                source("SplitDomainResolution.java").contains(INVALID_SPLIT_DOMAIN_MESSAGE_SUFFIX),
                "the split-domain union-failure message tail moved or was reworded — every such "
                        + "ERROR would classify as OTHER; update this constant AND "
                        + "ViolationNormaliser's in the rules repository");
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


    @Test
    void theJoinLookupRequiredFragmentIsStillTheEngineSWording() throws IOException
    {
        // The fragment is built at exactly ONE site (ValueResolver's missing-JoinLookup guard).
        // Asserting the COUNT rather than mere presence closes the hole a bare `contains` leaves
        // if a second site is ever added and only one of the two is reworded.
        int count = countFlattenedOccurrences("ValueResolver.java",
                JOIN_LOOKUP_REQUIRED_MESSAGE_FRAGMENT);
        Assertions.assertEquals(1, count,
                "this engine message fragment no longer appears exactly once in ValueResolver:\n  "
                        + JOIN_LOOKUP_REQUIRED_MESSAGE_FRAGMENT
                        + "\nThe rules repository's NativeCorpusFullCoverageTest allow-lists the "
                        + "three shipped CDISC-AD0720 rules as legitimately undispatchable by "
                        + "rule id AND by a String.contains match on exactly this fragment "
                        + "(KNOWN_UNDISPATCHABLE). Reworded here, that gate reds over there — an "
                        + "accepted non-dispatch reported as an unexplained thrower — and its "
                        + "failure names nothing in this repository. Update this constant AND that "
                        + "entry's messageSubstring in the same wave. If instead a SECOND site now "
                        + "emits the fragment, that is harmless downstream: raise the expected "
                        + "count here rather than weakening the match.");
    }


    /**
     * Java's compiler concatenates a literal split across source lines, so the engine text is
     * searched with the {@code " + "} seams removed.
     */
    private static void assertSourceContains(String aMessage, int aExpectedOccurrences)
        throws IOException
    {
        int count = countFlattenedOccurrences("RuleRunner.java", aMessage);
        Assertions.assertEquals(aExpectedOccurrences, count,
                "this engine message no longer appears exactly " + aExpectedOccurrences
                        + " time(s) in RuleRunner:\n  " + aMessage
                        + "\nEvery ERROR carrying a changed wording would classify as OTHER in "
                        + "the rules repository, losing the label precision the errors channel "
                        + "exists to provide. Update this constant and ViolationNormaliser's — "
                        + "and if a SITE was added or removed, re-derive the ErrorReason "
                        + "vocabulary too.");
    }


    /**
     * Counts occurrences of {@code aNeedle} in an engine source file, with the {@code " + "}
     * concatenation seams removed first, and its comments blanked out by {@link #source(String)}.
     * Removing the seams matters in BOTH directions: re-wrapping a literal across source lines
     * changes nothing at runtime, so it must not red these pins either. Blanking the comments is
     * what stops a comment QUOTING the needle from standing in for the message itself.
     */
    private static int countFlattenedOccurrences(String aSimpleName, String aNeedle)
        throws IOException
    {
        String flattened = source(aSimpleName).replaceAll("\"\\s*\\+\\s*\"", "");
        int count = 0;
        for (int from = flattened.indexOf(aNeedle); from >= 0; from = flattened.indexOf(aNeedle,
                from + aNeedle.length()))
        {
            count++;
        }
        return count;
    }
}
