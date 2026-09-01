package net.cumba.cdisc.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.cumba.cdisc.core.exec.ScopeMatcher;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.Scope;
import net.cumba.cdisc.core.model.SubclassScope;
import org.junit.jupiter.api.Test;

/**
 * <b>Phase 4 of {@code plans/PLAN-metadata-product-selection.md} — ruling 5, the supertype chain on
 * the subclass axis</b>, plus the declared-tier structure check that had to land with it.
 *
 * <h2>What is being pinned, and why each half matters</h2>
 *
 * <p>
 * <b>The expansion</b> makes {@code MEDICAL DEVICE TIME-TO-EVENT} carry {@code TIME-TO-EVENT}
 * behind it, exactly as Fix #179 made {@code MEDICAL DEVICE BASIC DATA STRUCTURE} carry
 * {@code BASIC DATA STRUCTURE}. The load-bearing test is not that the pair comes back — it is that
 * the <b>declared</b> tier produces the same pair the heuristic does. Expanding only the heuristic
 * would leave a Define-XML {@code def:SubClass} covering fewer rules than the identical dataset
 * with no declaration at all, and nothing would say so.
 * </p>
 *
 * <p>
 * <b>The declared-tier structure check</b> closes a gap that was harmless until Phase 3 and is not
 * any more. {@code knownDeclaredTokens} used to accept a declared subclass on token membership
 * alone, and under the default {@code corej.defineFirst=true} it beats the heuristic — so a
 * define.xml declaring {@code ADVERSE EVENT} on a BDS dataset now <em>selects an occurrence
 * structure's variable list</em> for it.
 * </p>
 */
class AdamSubclassSupertypeTest
{

    private static final String MD_TTE = AdamSubclassDetector.MEDICAL_DEVICE_TIME_TO_EVENT;

    private static final String TTE = AdamSubclassDetector.TIME_TO_EVENT;

    private static final String NCA = AdamSubclassDetector.NON_COMPARTMENTAL_ANALYSIS;

    private static final String AE = AdamSubclassDetector.ADVERSE_EVENT;

    /**
     * A medical-device BDS dataset's structure set, exactly as Fix #179's {@code detectAll} builds
     * it.
     */
    private static final List<String> DEVICE_BDS_SET = List
            .of(AdamDataStructureDetector.MEDICAL_DEVICE_BDS, AdamDataStructureDetector.BDS);

    private static final List<String> BDS_SET = List.of(AdamDataStructureDetector.BDS);

    private static final List<String> OCCDS_SET = List.of(AdamDataStructureDetector.OCCDS);

    /** Columns no subclass heuristic recognises, so a tier under test answers alone. */
    private static final List<String> SILENT_COLUMNS = List.of("STUDYID", "USUBJID", "PARAMCD");

    private static Rule subclassRule(List<String> include)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        SubclassScope sc = new SubclassScope();
        sc.setInclude(include);
        scope.setSubclasses(sc);
        rule.setScope(scope);
        return rule;
    }

    // ------------------------------------------------------------------
    // subclassSet — the relation itself
    // ------------------------------------------------------------------


    @Test
    void subclassSetIsTheSubclassAxisAnalogueOfStructureSet()
    {
        assertEquals(List.of(MD_TTE, TTE), AdamSubclassDetector.subclassSet(MD_TTE),
                "most-specific first, exactly as AdamDataStructureDetector.structureSet");
        assertEquals(List.of(TTE), AdamSubclassDetector.subclassSet(TTE),
                "a root token yields itself alone");
        assertEquals(List.of(AE), AdamSubclassDetector.subclassSet(AE),
                "⚠ ADVERSE EVENT has NO subclass supertype — its fallback is a STRUCTURE (the base "
                        + "OCCDS behind the AE specialisation), which is the other hierarchy");
        assertEquals(List.of("NOT A TOKEN"), AdamSubclassDetector.subclassSet("NOT A TOKEN"),
                "an unrecognised token yields itself alone (closed-vocabulary behaviour)");
    }


    @Test
    void everySupertypeIsItselfARootAndAKnownToken()
    {
        // The walk in subclassSet is one level deep; a two-level chain would silently truncate.
        for (String token : AdamSubclassDetector.SUBCLASS_TOKENS)
        {
            List<String> set = AdamSubclassDetector.subclassSet(token);
            assertEquals(token, set.get(0), token);
            for (String supertype : set.subList(1, set.size()))
            {
                assertTrue(AdamSubclassDetector.SUBCLASS_TOKENS.contains(supertype),
                        supertype + " is not a subclass token");
                assertEquals(List.of(supertype), AdamSubclassDetector.subclassSet(supertype),
                        supertype + " is not a root — subclassSet would truncate the chain");
            }
        }
    }

    // ------------------------------------------------------------------
    // The expansion, per tier
    // ------------------------------------------------------------------


    @Test
    void theHeuristicTierCarriesTheSupertype()
    {
        assertEquals(List.of(MD_TTE, TTE), AdamSubclassDetector.resolve("ADMDTTE", DEVICE_BDS_SET,
                List.of("PARAMCD", "AVAL", "CNSR", AdamDataStructureDetector.DEVICE_IDENTIFIER),
                List.of(), true));
        assertEquals(List.of(TTE),
                AdamSubclassDetector.resolve("ADTTE", BDS_SET, List.of("PARAMCD", "AVAL", "CNSR"),
                        List.of(), true),
                "the converse must NOT hold: a plain TTE dataset is not a device one");
    }


    @Test
    void theDeclaredTierExpandsExactlyAsTheHeuristicDoes()
    {
        // ⚠⚠ The asymmetry this phase exists to prevent. The dataset carries no device column at
        // all, so only the declaration can put it in scope — and it must land on the same pair the
        // column heuristic produces for the device-shaped dataset above.
        List<String> declared = AdamSubclassDetector.resolve("ADXX01", DEVICE_BDS_SET,
                SILENT_COLUMNS, List.of(MD_TTE), true);
        List<String> heuristic = AdamSubclassDetector.resolve("ADMDTTE", DEVICE_BDS_SET,
                List.of("PARAMCD", "AVAL", "CNSR", AdamDataStructureDetector.DEVICE_IDENTIFIER),
                List.of(), true);

        assertEquals(List.of(MD_TTE, TTE), declared);
        assertEquals(heuristic, declared,
                "a declared def:SubClass must cover exactly the rules the heuristic covers — "
                        + "expanding only the heuristic tier is the Fix #179 asymmetry, on the "
                        + "other axis");
    }


    @Test
    void theDeclaredTierExpandsUnderDefineFirstFalseToo()
    {
        // -Dcorej.defineFirst=false routes through the THIRD tier of resolve, a different return
        // statement. The exit-point expansion is what makes them agree.
        assertEquals(List.of(MD_TTE, TTE), AdamSubclassDetector.resolve("ADXX02", DEVICE_BDS_SET,
                SILENT_COLUMNS, List.of(MD_TTE), false));
    }


    @Test
    void theNameTierGoesThroughTheSameExit()
    {
        // No name-tier token has a supertype today, so this cannot be observed as a longer list.
        // Asserting the identity with subclassSet is what still catches a refactor that returns
        // from the name tier without passing the exit.
        List<String> resolved = AdamSubclassDetector.resolve("ADNCA01", BDS_SET, SILENT_COLUMNS,
                List.of(), true);
        assertEquals(AdamSubclassDetector.subclassSet(NCA), resolved);
    }

    // ------------------------------------------------------------------
    // Scope coverage — what the expansion buys
    // ------------------------------------------------------------------


    @Test
    void aTimeToEventScopedRuleNowCoversADeviceTteDataset()
    {
        List<String> deviceTte = AdamSubclassDetector.resolve("ADMDTTE", DEVICE_BDS_SET,
                List.of("PARAMCD", "AVAL", "CNSR", AdamDataStructureDetector.DEVICE_IDENTIFIER),
                List.of(), true);

        assertNull(ScopeMatcher.describeSubclassMismatch(subclassRule(List.of(TTE)), deviceTte),
                "a device time-to-event dataset IS a time-to-event dataset");
        assertNull(ScopeMatcher.describeSubclassMismatch(subclassRule(List.of(MD_TTE)), deviceTte));
    }


    @Test
    void aDeviceScopedRuleStillDoesNotCoverAPlainTteDataset()
    {
        List<String> plainTte = AdamSubclassDetector.resolve("ADTTE", BDS_SET,
                List.of("PARAMCD", "AVAL", "CNSR"), List.of(), true);

        assertNotNull(
                ScopeMatcher.describeSubclassMismatch(subclassRule(List.of(MD_TTE)), plainTte),
                "is-a runs one way only; widening it both ways would silently grow every "
                        + "device-scoped rule's population");
    }

    // ------------------------------------------------------------------
    // The declared-tier structure check
    // ------------------------------------------------------------------


    @Test
    void aDeclaredSubclassThatCannotApplyToTheStructureIsDroppedWithAWarning()
    {
        List<String> logged = captureWarnings(() -> assertEquals(List.of(),
                AdamSubclassDetector.resolve("ADBDS01", BDS_SET, SILENT_COLUMNS, List.of(AE), true),
                "ADVERSE EVENT applies to an occurrence dataset; on a BDS dataset the declaration "
                        + "would select the AE structure's variable list"));

        assertTrue(
                logged.stream()
                        .anyMatch(m -> m.contains("ADBDS01") && m.contains(AE)
                                && m.contains(AdamDataStructureDetector.OCCDS)),
                "the drop must be explained; got " + logged);
    }


    @Test
    void aDeclaredSubclassThatFitsTheStructureIsKept()
    {
        // The control for the test above: same declaration, a structure it can apply to.
        assertEquals(List.of(AE), AdamSubclassDetector.resolve("ADOCC01", OCCDS_SET, SILENT_COLUMNS,
                List.of(AE), true));
    }


    @Test
    void aDroppedDeclarationFallsThroughToTheColumnsRatherThanSilencingTheDataset()
    {
        // Dropping is a fall-through, not a verdict — the heuristic still gets its turn.
        assertEquals(List.of(TTE), AdamSubclassDetector.resolve("ADBDS02", BDS_SET,
                List.of("PARAMCD", "AVAL", "CNSR"), List.of(AE), true));
    }


    @Test
    void aDeclaredBdsSubclassOnAnOccurrenceDatasetIsDroppedToo()
    {
        assertEquals(List.of(), AdamSubclassDetector.resolve("ADOCC02", OCCDS_SET, SILENT_COLUMNS,
                List.of(NCA), true));
    }


    @Test
    void aValidDeclarationAmongInvalidOnesSurvivesAlone()
    {
        assertEquals(List.of(AE), AdamSubclassDetector.resolve("ADOCC03", OCCDS_SET, SILENT_COLUMNS,
                java.util.Arrays.asList(NCA, AE, null), true));
    }


    @Test
    void theWarningFiresOncePerDatasetAndToken()
    {
        // ⚠ resolve runs once per RULE per dataset (RuleRunner), so an un-deduplicated warning
        // would fire dozens of times for one dataset — the reason the class javadoc gives for
        // rejecting a runtime ambiguity warning outright.
        List<String> logged = captureWarnings(() ->
        {
            for (int i = 0; i < 5; i++)
            {
                AdamSubclassDetector.resolve("ADBDS03", BDS_SET, SILENT_COLUMNS, List.of(AE), true);
            }
        });
        assertEquals(1, logged.size(), "expected exactly one warning, got " + logged);
    }


    /**
     * Runs {@code aBody} with the detector's JUL output captured, and returns the WARNING lines.
     */
    private static List<String> captureWarnings(Runnable aBody)
    {
        Logger logger = Logger.getLogger(AdamSubclassDetector.class.getName());
        CapturingHandler handler = new CapturingHandler();
        handler.setLevel(Level.ALL);
        Level previous = logger.getLevel();
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        try
        {
            aBody.run();
        }
        finally
        {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
        return handler.formatted();
    }

    /** Collects the records the detector's {@link System.Logger} routes through JUL. */
    private static final class CapturingHandler extends Handler
    {

        private final List<LogRecord> records = new ArrayList<>();

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
            // nothing buffered
        }


        @Override
        public void close()
        {
            // nothing to release
        }


        @Override
        public boolean isLoggable(LogRecord logRecord)
        {
            return logRecord.getLevel().intValue() >= Level.WARNING.intValue();
        }
    }
}
