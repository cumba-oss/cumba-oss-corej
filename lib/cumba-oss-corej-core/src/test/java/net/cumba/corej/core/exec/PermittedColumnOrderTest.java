package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import net.cumba.corej.core.exec.PermittedColumnOrder.RoleRow;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code get_permitted_column_order()} ({@code PLAN-permitted-addition-position} C6): the §2.3
 * placement over plain inputs ({@link PermittedColumnOrder#place}), the provider composition
 * ({@link PermittedColumnOrder#resolve}: the class gate, the SKIP and the answer-{@code O}
 * fallbacks), and P4 as a property through the rule's own verdict
 * ({@link GroupSemantics#isNotOrderedSubsetVerdict}). Model lists are the store's (SDTMIG 3.3 /
 * 3.4, phase 0), abridged where the case does not need the rest.
 */
class PermittedColumnOrderTest
{

    // ------------------------------------------------------------------ fixtures

    private static List<RoleRow> rows(String... nameRole)
    {
        List<RoleRow> out = new ArrayList<>();
        for (String s : nameRole)
        {
            String[] p = s.split(":", -1);
            out.add(new RoleRow(p[0], p.length > 1 && !p[1].isEmpty() ? p[1] : null));
        }
        return out;
    }


    private static Map<String, String> roles(List<RoleRow> aRows)
    {
        Map<String, String> m = new HashMap<>();
        for (RoleRow r : aRows)
        {
            if (r.role() != null)
            {
                m.put(r.name().toUpperCase(java.util.Locale.ROOT), r.role());
            }
        }
        return m;
    }


    private static List<String> names(List<RoleRow> aRows)
    {
        return aRows.stream().map(RoleRow::name).toList();
    }

    /** The GENERAL OBSERVATIONS identifiers of the store's Findings walk (abridged). */
    private static final String[] GO_IDS =
    {
            "STUDYID:Identifier", "DOMAIN:Identifier", "USUBJID:Identifier", "POOLID:Identifier",
            "SPDEVID:Identifier", "--SEQ:Identifier", "--GRPID:Identifier", "--REFID:Identifier",
            "--SPID:Identifier"
    };

    /** The Findings topic and qualifiers (abridged). */
    private static final String[] FINDINGS_BODY =
    {
            "--TESTCD:Topic", "--TEST:Synonym Qualifier", "--CAT:Grouping Qualifier",
            "--ORRES:Result Qualifier", "--STRESC:Result Qualifier", "--XFN:Record Qualifier"
    };

    /** The SDTM 2.0 (SDTMIG 3.4) Findings timing: no --STDTC / --STDY. */
    private static final String[] TIMING_34_FINDINGS =
    {
            "VISITNUM:Timing", "VISIT:Timing", "VISITDY:Timing", "TAETORD:Timing", "EPOCH:Timing",
            "--DTC:Timing", "--ENDTC:Timing", "--DY:Timing", "--ENDY:Timing"
    };

    /** The same block as the Events walk serves it: --STDTC / --STDY present. */
    private static final String[] TIMING_EVENTS =
    {
            "VISITNUM:Timing", "VISIT:Timing", "VISITDY:Timing", "TAETORD:Timing", "EPOCH:Timing",
            "--DTC:Timing", "--STDTC:Timing", "--ENDTC:Timing", "--DY:Timing", "--STDY:Timing",
            "--ENDY:Timing"
    };

    private static List<RoleRow> walk(String aPrefix, String[]... aParts)
    {
        List<RoleRow> out = new ArrayList<>();
        for (String[] part : aParts)
        {
            for (RoleRow r : rows(part))
            {
                out.add(new RoleRow(r.name().replace("--", aPrefix), r.role()));
            }
        }
        return out;
    }

    /** SV as SDTMIG 3.3 prints it, IG roles. */
    private static final List<RoleRow> SV = rows("STUDYID:Identifier", "DOMAIN:Identifier",
            "USUBJID:Identifier", "VISITNUM:Topic", "VISIT:Synonym Qualifier", "VISITDY:Timing",
            "SVSTDTC:Timing", "SVENDTC:Timing", "SVSTDY:Timing", "SVENDY:Timing",
            "SVUPDES:Synonym Qualifier");

    private static List<String> place(List<RoleRow> aOwn, List<List<RoleRow>> aWalks,
            String... aColumns)
    {
        return PermittedColumnOrder.place(names(aOwn), roles(aOwn), aWalks, List.of(aColumns));
    }


    private static List<List<RoleRow>> sv33()
    {
        List<RoleRow> t = walk("SV", GO_IDS, FINDINGS_BODY, TIMING_EVENTS);
        return List.of(t, walk("SV", GO_IDS, TIMING_EVENTS), walk("SV", GO_IDS, TIMING_EVENTS));
    }


    private static List<String> list(String aSpaced)
    {
        return List.of(aSpaced.split(" ", -1));
    }

    // ------------------------------------------------------------------ the placement


    @Test
    void epochInSvGoesBeforeTheFirstTimingAnchorWhoseOrdinalExceedsIt()
    {
        // EPOCH (Timing): VISITNUM is Topic and VISIT a qualifier in the SV IG — not anchors
        assertEquals(
                list("STUDYID DOMAIN USUBJID VISITNUM VISIT VISITDY EPOCH SVSTDTC SVENDTC"
                        + " SVSTDY SVENDY SVUPDES"),
                place(SV, sv33(), "STUDYID", "DOMAIN", "USUBJID", "VISITNUM", "VISIT", "VISITDY",
                        "EPOCH", "SVSTDTC", "SVENDTC"));
    }


    @Test
    void anIdentifierAdditionFollowsTheLastIdentifierAnchor()
    {
        assertEquals(list("STUDYID DOMAIN USUBJID SVSEQ VISITNUM VISIT VISITDY SVSTDTC SVENDTC"
                + " SVSTDY SVENDY SVUPDES"), place(SV, sv33(), "SVSEQ", "STUDYID"));
    }


    @Test
    void anUnanchoredQualifierFollowsTheLastOwnVariableOfRankAtMostItsOwn()
    {
        // no own SV qualifier is in the Findings qualifier table: after the last rank <= 2 =
        // SVUPDES
        assertEquals(list("STUDYID DOMAIN USUBJID VISITNUM VISIT VISITDY SVSTDTC SVENDTC SVSTDY"
                + " SVENDY SVUPDES SVSTRESC"), place(SV, sv33(), "SVSTRESC"));
    }


    @Test
    void twoAdditionsInOneGapNeverReorderEachOther()
    {
        // M2: SESTDY and SEENDY both after the last anchor SEENDTC, sorted by table ordinal
        List<RoleRow> se = rows("STUDYID:Identifier", "DOMAIN:Identifier", "USUBJID:Identifier",
                "SESEQ:Identifier", "ETCD:Topic", "ELEMENT:Synonym Qualifier", "TAETORD:Timing",
                "EPOCH:Timing", "SESTDTC:Timing", "SEENDTC:Timing", "SEUPDES:Synonym Qualifier");
        List<List<RoleRow>> walks = List.of(walk("SE", GO_IDS, FINDINGS_BODY, TIMING_EVENTS));
        String expected = "STUDYID DOMAIN USUBJID SESEQ ETCD ELEMENT TAETORD EPOCH SESTDTC SEENDTC"
                + " SESTDY SEENDY SEUPDES";
        assertEquals(list(expected), place(se, walks, "SEENDY", "SESTDY"));
        assertEquals(list(expected), place(se, walks, "SESTDY", "SEENDY"));
    }


    @Test
    void dmAdditionsShareTheGapBeforeTheFirstTimingAnchorSortedByRank()
    {
        List<RoleRow> dm = rows("STUDYID:Identifier", "DOMAIN:Identifier", "USUBJID:Identifier",
                "SUBJID:Topic", "ACTARM:Synonym Qualifier", "COUNTRY:Record Qualifier",
                "DMDTC:Timing", "DMDY:Timing");
        List<List<RoleRow>> walks = List.of(walk("DM", GO_IDS, FINDINGS_BODY, TIMING_EVENTS));
        assertEquals(
                list("STUDYID DOMAIN USUBJID SUBJID ACTARM COUNTRY DMXFN VISITNUM VISIT"
                        + " VISITDY DMDTC DMDY"),
                place(dm, walks, "VISITDY", "VISIT", "DMXFN", "VISITNUM"));
    }


    @Test
    void coIsNotInRoleOrderAndEachAdditionStillFindsItsGap()
    {
        // M1: RDOMAIN (a qualifier) before USUBJID, COVAL (Topic) after qualifiers
        List<RoleRow> co = rows("STUDYID:Identifier", "DOMAIN:Identifier",
                "RDOMAIN:Record Qualifier", "USUBJID:Identifier", "COSEQ:Identifier",
                "IDVAR:Record Qualifier", "IDVARVAL:Record Qualifier", "COREF:Record Qualifier",
                "COVAL:Topic", "COEVAL:Record Qualifier", "CODTC:Timing");
        List<List<RoleRow>> walks = List.of(walk("CO", GO_IDS, FINDINGS_BODY, TIMING_EVENTS));
        assertEquals(
                list("STUDYID DOMAIN RDOMAIN USUBJID COSEQ COGRPID IDVAR IDVARVAL COREF COVAL"
                        + " COEVAL VISITNUM CODTC CODY"),
                place(co, walks, "CODY", "VISITNUM", "COGRPID"));
    }


    /**
     * P2 refined at phase 0: at SDTMIG 3.4 the Findings walk omits {@code --STDTC}, so only the
     * union with the Events walk makes {@code SVSTDTC} a Timing anchor. The control (Findings walk
     * alone) shows the defect the union removes: {@code EPOCH} judged after {@code SVSTDTC}.
     */
    @Test
    void identifierAndTimingOrdinalsComeFromTheUnionOfTheThreeWalks()
    {
        List<RoleRow> findings = walk("SV", GO_IDS, FINDINGS_BODY, TIMING_34_FINDINGS);
        List<RoleRow> events = walk("SV", GO_IDS, TIMING_EVENTS);
        String[] columns =
        {
                "STUDYID", "DOMAIN", "USUBJID", "VISITNUM", "VISIT", "VISITDY", "EPOCH", "SVSTDTC"
        };
        assertEquals(
                list("STUDYID DOMAIN USUBJID VISITNUM VISIT VISITDY EPOCH SVSTDTC SVENDTC"
                        + " SVSTDY SVENDY SVUPDES"),
                place(SV, List.of(findings, events, events), columns));
        assertEquals(
                list("STUDYID DOMAIN USUBJID VISITNUM VISIT VISITDY SVSTDTC EPOCH SVENDTC"
                        + " SVSTDY SVENDY SVUPDES"),
                place(SV, List.of(findings), columns),
                "the control: without the union SVSTDTC anchors nothing");
        // the union never adds an addition: SVSTDY is no Findings variable at 3.4, so not placed
        assertEquals(names(SV).stream().map(String::toUpperCase).toList(),
                place(rows("STUDYID:Identifier", "DOMAIN:Identifier", "USUBJID:Identifier",
                        "VISITNUM:Topic", "VISIT:Synonym Qualifier", "VISITDY:Timing",
                        "SVSTDTC:Timing", "SVENDTC:Timing", "SVSTDY:Timing", "SVENDY:Timing",
                        "SVUPDES:Synonym Qualifier"), List.of(findings, events), "SVSTDY"));
    }


    @Test
    void anOwnVariableOutsideTheFourRanksIsNeitherAnchorNorBoundary()
    {
        // L2: XX has a role of no rank; the unanchored qualifier skips over it to SVUPDES' gap
        List<RoleRow> own = rows("STUDYID:Identifier", "VISITNUM:Topic", "XX:Rule",
                "SVUPDES:Synonym Qualifier", "YY:Rule");
        assertEquals(list("STUDYID VISITNUM XX SVUPDES SVSTRESC YY"),
                place(own, sv33(), "SVSTRESC"));
    }


    @Test
    void anUnanchoredAdditionBelowEveryOwnRankGoesFirst()
    {
        List<RoleRow> own = rows("VISITDY:Timing", "SVSTDTC:Timing");
        assertEquals(list("SVSEQ VISITDY SVSTDTC"), place(own, sv33(), "SVSEQ"));
    }


    @Test
    void sponsorColumnsCaseAndDuplicatesAndRolelessAdditions()
    {
        List<List<RoleRow>> walks = List.of(new ArrayList<>(sv33().getFirst()));
        walks.getFirst().add(new RoleRow("SVODD", "Rule"));
        assertEquals(
                list("STUDYID DOMAIN USUBJID VISITNUM VISIT VISITDY EPOCH SVSTDTC SVENDTC"
                        + " SVSTDY SVENDY SVUPDES"),
                place(SV, walks, "studyid", "Epoch", "EPOCH", "SVSPONSR", "SVODD", "svstdtc"));
    }


    /**
     * P1 extended (phase 0 step 0.5 (c)): SENDIG 3.0 SV's model rows carry no role for its
     * identifiers; they take the walk's, so an added {@code SVSEQ} follows {@code USUBJID} instead
     * of landing before {@code STUDYID}.
     */
    @Test
    void anOwnVariableWithoutAnyRoleTakesTheWalksRole()
    {
        List<RoleRow> own = rows("STUDYID:", "DOMAIN:", "USUBJID:", "VISITNUM:Topic",
                "VISIT:Timing", "VISITDY:Timing", "SVSTDTC:Timing");
        assertEquals(list("STUDYID DOMAIN USUBJID SVSEQ VISITNUM VISIT VISITDY SVSTDTC"),
                place(own, sv33(), "SVSEQ"));
    }

    // ------------------------------------------------------------------ P4 as a property


    @Test
    void p4NoFailingVerdictPassesAgainstTheReferenceAndTheReferenceRestrictedToOIsO()
    {
        Random random = new Random(20261007L);
        List<RoleRow> pool = walk("XX", GO_IDS, FINDINGS_BODY, TIMING_EVENTS);
        String[] roleNames =
        {
                "Identifier", "Topic", "Record Qualifier", "Timing", "Rule", null
        };
        int failing = 0;
        for (int round = 0; round < 3000; round++)
        {
            List<RoleRow> shuffled = new ArrayList<>(pool);
            java.util.Collections.shuffle(shuffled, random);
            List<RoleRow> own = new ArrayList<>(shuffled.subList(0, 2 + random.nextInt(10)));
            for (int i = 0; i < own.size(); i++)
            {
                if (random.nextInt(4) == 0)
                {
                    own.set(i, new RoleRow(own.get(i).name(),
                            roleNames[random.nextInt(roleNames.length)]));
                }
            }
            List<List<RoleRow>> walks = switch (random.nextInt(4))
            {
            case 0 -> List.of(); // T unavailable
            case 1 -> List.of(pool);
            case 2 -> List.of(pool.subList(0, random.nextInt(pool.size())), pool);
            default -> List.of(shuffled, pool, pool);
            };
            List<String> columns = new ArrayList<>(
                    pool.stream().map(RoleRow::name).filter(n -> random.nextBoolean()).toList());
            columns.add("SPONSOR");
            java.util.Collections.shuffle(columns, random);
            Map<String, String> ownRoles = random.nextInt(5) == 0 ? Map.of() : roles(own); // IG
            List<String> o = names(own);
            List<String> reference = PermittedColumnOrder.place(o, ownRoles, walks, columns);
            Set<String> oSet = Set.copyOf(o);
            assertEquals(o, reference.stream().filter(oSet::contains).toList(),
                    "P4: restricted to O the reference is O");
            List<String> knownOld = columns.stream().filter(oSet::contains).toList();
            boolean failsToday = GroupSemantics.isNotOrderedSubsetVerdict(knownOld, o, 1).get(0);
            Set<String> refSet = Set.copyOf(reference);
            List<String> knownNew = columns.stream().filter(refSet::contains).toList();
            boolean failsNow = GroupSemantics.isNotOrderedSubsetVerdict(knownNew, reference, 1)
                    .get(0);
            if (failsToday)
            {
                failing++;
                assertTrue(failsNow, "P4: a failing verdict stays failing: " + o + " / " + columns);
            }
        }
        assertTrue(failing > 100, "the property must exercise failing verdicts: " + failing);
    }

    // ------------------------------------------------------------------ the composition

    /** A provider serving the seams {@link PermittedColumnOrder#resolve} reads. */
    private static final class Provider implements MetadataProvider
    {

        @Nullable
        List<String> modelOrder;

        @Nullable
        String productClass;

        @Nullable
        String defineClass;

        final Map<String, List<Map<String, String>>> classWalks = new LinkedHashMap<>();

        final Map<String, List<Map<String, String>>> legacyClassWalks = new LinkedHashMap<>();

        @Nullable
        List<Map<String, String>> ig;

        List<Map<String, String>> legacyIg = List.of();

        @Nullable
        List<Map<String, String>> modelRows;

        @Override
        public @Nullable List<Map<String, String>> getStandardModelVariablesDetailed(
                IDataTable aTable, DatasetResolver aResolver)
        {
            return modelRows;
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
        public String getStandard()
        {
            return "sdtmig";
        }


        @Override
        public @Nullable List<String> getStandardModelVariables(IDataTable aTable,
                DatasetResolver aResolver)
        {
            return modelOrder;
        }


        @Override
        public @Nullable String getDatasetClass(@Nullable String aMember, String aDomain)
        {
            return aMember == null ? productClass : defineClass;
        }


        @Override
        public @Nullable List<Map<String, String>> getStandardModelVariablesForClass(
                IDataTable aTable, DatasetResolver aResolver, String aModelClass)
        {
            return classWalks.get(aModelClass);
        }


        @Override
        public List<Map<String, String>> getModelVariablesForClass(String aModelClass)
        {
            return legacyClassWalks.getOrDefault(aModelClass, List.of());
        }


        @Override
        public @Nullable List<Map<String, String>> getStandardVariablesDetailed(IDataTable aTable,
                DatasetResolver aResolver)
        {
            return ig;
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String aDomain)
        {
            return legacyIg;
        }
    }

    private static List<Map<String, String>> maps(List<RoleRow> aRows)
    {
        List<Map<String, String>> out = new ArrayList<>();
        for (RoleRow r : aRows)
        {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", r.name());
            if (r.role() != null)
            {
                m.put("role", r.role());
            }
            out.add(m);
        }
        return out;
    }


    private static IDataTable sv(String... aColumns)
    {
        MockTable t = MockTable.of().name("SV");
        for (String c : aColumns)
        {
            t.col(c, c.equals("DOMAIN") ? "SV" : "x");
        }
        return t.build();
    }


    private static Provider svProvider()
    {
        Provider p = new Provider();
        p.modelOrder = names(SV);
        p.productClass = "Special-Purpose";
        p.classWalks.put("FINDINGS", maps(sv33().getFirst()));
        p.classWalks.put("EVENTS", maps(sv33().get(1)));
        p.ig = maps(SV);
        return p;
    }

    private static final IDataTable MISPLACED = sv("STUDYID", "DOMAIN", "USUBJID", "VISITNUM",
            "VISIT", "VISITDY", "SVSTDTC", "EPOCH");

    private static final List<String> PLACED = list("STUDYID DOMAIN USUBJID VISITNUM VISIT"
            + " VISITDY EPOCH SVSTDTC SVENDTC SVSTDY SVENDY SVUPDES");

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    @Test
    void aSpecialPurposeDatasetGetsItsAdditionsPlaced()
    {
        assertEquals(PLACED, PermittedColumnOrder.resolve(svProvider(), MISPLACED, NO_RESOLVER));
    }


    @Test
    void bothNormalisedSpellingsOpenTheGateAndNothingElseDoes()
    {
        for (String open : List.of("Special-Purpose", "Special-Purpose Datasets",
                "SPECIAL PURPOSE"))
        {
            Provider p = svProvider();
            p.productClass = open;
            assertEquals(PLACED, PermittedColumnOrder.resolve(p, MISPLACED, NO_RESOLVER), open);
        }
        for (String closed : Arrays.asList("Events", "Findings", "Trial Design", null))
        {
            Provider p = svProvider();
            p.productClass = closed;
            assertSame(p.modelOrder, PermittedColumnOrder.resolve(p, MISPLACED, NO_RESOLVER),
                    String.valueOf(closed));
        }
    }


    /** H2 / P8: the gate reads the product class (null member), never the define's. */
    @Test
    void theDefineClassCannotOpenOrCloseTheGate()
    {
        Provider p = svProvider();
        p.productClass = "Events";
        p.defineClass = "SPECIAL PURPOSE";
        assertSame(p.modelOrder, PermittedColumnOrder.resolve(p, MISPLACED, NO_RESOLVER));
        p.productClass = "Special-Purpose";
        p.defineClass = "EVENTS";
        assertEquals(PLACED, PermittedColumnOrder.resolve(p, MISPLACED, NO_RESOLVER));
    }


    @Test
    void onlyTheModelOrderSkips()
    {
        Provider p = svProvider();
        p.modelOrder = null;
        assertNull(PermittedColumnOrder.resolve(p, MISPLACED, NO_RESOLVER));
        p.modelOrder = List.of();
        assertNull(PermittedColumnOrder.resolve(p, MISPLACED, NO_RESOLVER));
    }


    @Test
    void anUnavailableFindingsWalkAnswersTheModelOrderNeverASkip()
    {
        Provider p = svProvider();
        p.classWalks.clear();
        assertSame(p.modelOrder, PermittedColumnOrder.resolve(p, MISPLACED, NO_RESOLVER));
        p.classWalks.put("FINDINGS", List.of());
        p.classWalks.put("EVENTS", maps(sv33().get(1)));
        assertSame(p.modelOrder, PermittedColumnOrder.resolve(p, MISPLACED, NO_RESOLVER),
                "an empty Findings walk: no addition is role-known");
    }


    @Test
    void theLegacyClassWalkIsReadWithTheDatasetPrefixSubstituted()
    {
        Provider p = svProvider();
        p.classWalks.clear();
        p.legacyClassWalks.put("FINDINGS", maps(walk("--", GO_IDS, FINDINGS_BODY, TIMING_EVENTS)));
        assertEquals(PLACED, PermittedColumnOrder.resolve(p, MISPLACED, NO_RESOLVER));
    }


    /** With no IG row an own variable takes the model dataset's role (P1), never a SKIP. */
    @Test
    void anUnreadableIgFallsBackToTheModelRolesNeverASkip()
    {
        Provider p = svProvider();
        p.ig = null;
        p.legacyIg = List.of();
        List<RoleRow> model = new ArrayList<>(SV);
        model.set(6, new RoleRow("SVSTDTC", "Record Qualifier")); // planted: no Timing anchor
        p.modelRows = maps(model);
        assertEquals(
                list("STUDYID DOMAIN USUBJID VISITNUM VISIT VISITDY SVSTDTC EPOCH SVENDTC"
                        + " SVSTDY SVENDY SVUPDES"),
                PermittedColumnOrder.resolve(p, MISPLACED, NO_RESOLVER));
        p.modelRows = null;
        assertEquals(PLACED, PermittedColumnOrder.resolve(p, MISPLACED, NO_RESOLVER),
                "no IG and no model role: the walks' role (Timing) makes SVSTDTC an anchor again");
    }


    /**
     * Q2 / M3 / L-b: an own variable takes its IG role over the model's, and keeps it when the
     * dataset does not carry the variable (the IG rows are read unfiltered).
     */
    @Test
    void anOwnVariableAbsentFromTheDatasetKeepsItsIgRole()
    {
        Provider p = svProvider();
        p.modelRows = maps(SV); // the model says SVSTDTC is Timing
        List<RoleRow> ig = new ArrayList<>(SV);
        ig.set(6, new RoleRow("SVSTDTC", "Record Qualifier")); // planted: the IG disagrees
        p.ig = maps(ig);
        assertEquals(
                list("STUDYID DOMAIN USUBJID VISITNUM VISIT VISITDY SVSTDTC EPOCH SVENDTC"
                        + " SVSTDY SVENDY SVUPDES"),
                PermittedColumnOrder.resolve(p, sv("STUDYID", "VISITDY", "EPOCH"), NO_RESOLVER));
        p.ig = maps(SV);
        assertEquals(PLACED,
                PermittedColumnOrder.resolve(p, sv("STUDYID", "VISITDY", "EPOCH"), NO_RESOLVER),
                "the control: the IG agrees, SVSTDTC anchors");
    }
}
