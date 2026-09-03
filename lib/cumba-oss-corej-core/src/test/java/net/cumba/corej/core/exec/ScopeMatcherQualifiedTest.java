package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Fix #124 — qualified ({@code DATASET.VARIABLE}) entries in {@code Requirements.Variables}.
 * <p>
 * Covers the Include/Exclude truth table, the qualifier resolution order (exact name → split-domain
 * union → SUPP-pivot, with {@code --} resolved against the primary), the variable half keeping its
 * glob / regex / wildcard-marker semantics, the "no inventory ⇒ ignore" policy, and the two
 * agreement properties against the {@code Check}-side dotted {@code exists}.
 * </p>
 */
class ScopeMatcherQualifiedTest
{

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static Rule include(String... entries)
    {
        return scoped(Arrays.asList(entries), null);
    }


    private static Rule exclude(String... entries)
    {
        return scoped(null, Arrays.asList(entries));
    }


    private static Rule scoped(@Nullable List<String> include, @Nullable List<String> exclude)
    {
        Rule rule = new Rule();
        VariableRequirement vr = new VariableRequirement();
        vr.setAll(include);
        vr.setNone(exclude);
        Requirements req = new Requirements();
        req.setVariables(vr);
        rule.setRequirements(req);
        return rule;
    }


    /** Primary dataset under validation — AE with a couple of its own columns. */
    private static IDataTable primary()
    {
        return MockTable.of().name("AE").col("USUBJID", "S1").col("AESEQ", "1").build();
    }


    /** A {@code WithInventory} resolver over the given name → table map (exact-name lookup). */
    private static DatasetResolver.WithInventory inventory(Map<String, IDataTable> byName)
    {
        return new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                return name == null ? null : byName.get(name);
            }


            @Override
            public Set<String> availableDatasets()
            {
                return byName.keySet();
            }
        };
    }


    private static Map<String, IDataTable> map(Object... nameTablePairs)
    {
        Map<String, IDataTable> m = new LinkedHashMap<>();
        for (int i = 0; i < nameTablePairs.length; i += 2)
        {
            m.put((String) nameTablePairs[i], (IDataTable) nameTablePairs[i + 1]);
        }
        return m;
    }


    private static ScopeVariableSource sourceOf(Map<String, IDataTable> byName)
    {
        ScopeVariableSource src = ScopeVariableSource.of(inventory(byName), primary());
        assertNotNull(src, "an inventory-capable resolver must yield a source");
        return src;
    }


    private static @Nullable String check(Rule rule, ScopeVariableSource src)
    {
        return ScopeMatcher.describeVariablesMismatch(rule, primary().getMetaData(), "AE", src);
    }

    // ------------------------------------------------------------------
    // Include — the three states
    // ------------------------------------------------------------------


    @Test
    void includeSkipsWhenForeignDatasetIsAbsent()
    {
        ScopeVariableSource src = sourceOf(map());
        String reason = check(include("DM.ARM"), src);
        assertNotNull(reason, "absent DM must skip the rule");
        assertTrue(reason.contains("DM.ARM"), reason);
        assertTrue(reason.contains("dataset DM not available"), reason);
    }


    @Test
    void includeSkipsWhenForeignDatasetLacksTheColumn()
    {
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S1").build();
        String reason = check(include("DM.ARM"), sourceOf(map("DM", dm)));
        assertNotNull(reason, "DM without ARM must skip the rule");
        assertTrue(reason.contains("not present in dataset DM"), reason);
    }


    @Test
    void includeAppliesWhenForeignColumnIsPresent()
    {
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S1").col("ARM", "A").build();
        assertNull(check(include("DM.ARM"), sourceOf(map("DM", dm))));
    }

    // ------------------------------------------------------------------
    // Exclude — the mirror image
    // ------------------------------------------------------------------


    @Test
    void excludeAppliesWhenForeignDatasetIsAbsent()
    {
        assertNull(check(exclude("DM.ARM"), sourceOf(map())),
                "an absent dataset cannot carry the excluded variable");
    }


    @Test
    void excludeAppliesWhenForeignDatasetLacksTheColumn()
    {
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S1").build();
        assertNull(check(exclude("DM.ARM"), sourceOf(map("DM", dm))));
    }


    @Test
    void excludeSkipsWhenForeignColumnIsPresent()
    {
        IDataTable dm = MockTable.of().name("DM").col("ARM", "A").build();
        String reason = check(exclude("DM.ARM"), sourceOf(map("DM", dm)));
        assertNotNull(reason, "present DM.ARM must skip the rule");
        assertTrue(reason.contains("present in dataset DM"), reason);
    }

    // ------------------------------------------------------------------
    // The variable half keeps its own pattern semantics
    // ------------------------------------------------------------------


    @Test
    void globVariableHalfMatchesAtLeastOneForeignColumn()
    {
        IDataTable dm = MockTable.of().name("DM").col("RFSTDTC", "2020").build();
        assertNull(check(include("DM.*DTC"), sourceOf(map("DM", dm))));

        IDataTable noDtc = MockTable.of().name("DM").col("ARM", "A").build();
        String reason = check(include("DM.*DTC"), sourceOf(map("DM", noDtc)));
        assertNotNull(reason);
        assertTrue(reason.contains("no variable matching"), reason);
        assertTrue(reason.contains("in dataset DM"), reason);
    }


    @Test
    void regexVariableHalfIsCompiledFromTheVariableOnly()
    {
        IDataTable dm = MockTable.of().name("DM").col("ARMCD", "A1").build();
        assertNull(check(include("DM./^ARM.*$/"), sourceOf(map("DM", dm))));
    }


    @Test
    void wholeEntryRegexIsNotSplitAndTestsThePrimary()
    {
        // "/^AE.*$/" is a regex, not a qualified entry: it must be matched against the PRIMARY
        // dataset's columns (AESEQ matches), never treated as dataset "/^AE" + variable "*$/".
        assertNull(check(include("/^AESEQ$/"), sourceOf(map())));
    }


    @Test
    void wildcardMarkerVariableHalfMatchesAtLeastOneForeignColumn()
    {
        IDataTable adsl = MockTable.of().name("ADSL").col("TRT01PN", "1").build();
        assertNull(check(include("ADSL.TRTxxPN"), sourceOf(map("ADSL", adsl))),
                "TRT01PN satisfies the ADSL.TRTxxPN marker entry");

        IDataTable noTrt = MockTable.of().name("ADSL").col("USUBJID", "S1").build();
        String reason = check(include("ADSL.TRTxxPN"), sourceOf(map("ADSL", noTrt)));
        assertNotNull(reason, "no TRTnnPN column in ADSL must skip");
        assertTrue(reason.contains("ADSL.TRTxxPN"), reason);
    }


    @Test
    void wildcardMarkerVariableHalfWithADigitBearingStem()
    {
        // The qualifier splits off first, then the variable half parses as "R2A" + y + "LO":
        // the stem digit stays literal, so only an R2A<digits>LO column in ADSL satisfies it.
        IDataTable adsl = MockTable.of().name("ADSL").col("R2A1LO", "x").build();
        assertNull(check(include("ADSL.R2AyLO"), sourceOf(map("ADSL", adsl))));

        IDataTable otherStem = MockTable.of().name("ADSL").col("R1A1LO", "x").build();
        assertEquals(
                "no variable matching Requirements.Variables.All entry ADSL.R2AyLO present in"
                        + " dataset ADSL",
                check(include("ADSL.R2AyLO"), sourceOf(map("ADSL", otherStem))));

        // 'yy' is not a marker — the variable half stays a literal name.
        assertEquals("Requirements.Variables.All variable ADSL.R2AyyLO not present in dataset ADSL",
                check(include("ADSL.R2AyyLO"), sourceOf(map("ADSL", adsl))));
    }


    @Test
    void wildcardMarkerExcludeWithADigitBearingStemNamesTheForeignColumn()
    {
        IDataTable adsl = MockTable.of().name("ADSL").col("R2A1LO", "x").build();
        assertEquals(
                "variable R2A1LO matches Requirements.Variables.None entry ADSL.R2AyLO in dataset ADSL",
                check(exclude("ADSL.R2AyLO"), sourceOf(map("ADSL", adsl))));

        // The literal stem digit is not part of the marker, so R1A1LO does not trip the Exclude.
        IDataTable otherStem = MockTable.of().name("ADSL").col("R1A1LO", "x").build();
        assertNull(check(exclude("ADSL.R2AyLO"), sourceOf(map("ADSL", otherStem))));
    }

    // ------------------------------------------------------------------
    // Qualifier resolution
    // ------------------------------------------------------------------


    @Test
    void qualifierDatasetWildcardResolvesAgainstThePrimary()
    {
        // Primary is AE, so SUPP--.QVAL resolves to SUPPAE.QVAL.
        IDataTable suppae = MockTable.of().name("SUPPAE").col("QVAL", "x").build();
        assertNull(check(include("SUPP--.QVAL"), sourceOf(map("SUPPAE", suppae))));

        String reason = check(include("SUPP--.QVAL"), sourceOf(map()));
        assertNotNull(reason, "no SUPPAE must skip");
        assertTrue(reason.contains("dataset SUPPAE not available"),
                "the message names the RESOLVED dataset: " + reason);
    }


    @Test
    void splitDomainQualifierUnionsItsMembers()
    {
        // No dataset literally named LB; the domain is split across lbch/lbhe, and only lbch
        // carries LBORRES. tablesForDomain reads each member's DOMAIN cell.
        IDataTable lbch = MockTable.of().name("lbch").col("DOMAIN", "LB").col("LBORRES", "5")
                .build();
        IDataTable lbhe = MockTable.of().name("lbhe").col("DOMAIN", "LB").col("LBTEST", "t")
                .build();
        ScopeVariableSource src = sourceOf(map("lbch", lbch, "lbhe", lbhe));
        assertNull(check(include("LB.LBORRES"), src),
                "the split-domain union must see lbch's LBORRES");
        assertNull(check(include("LB.LBTEST"), src), "…and lbhe's LBTEST");

        String reason = check(include("LB.LBSTRESC"), src);
        assertNotNull(reason, "a column in no member must skip");
        assertTrue(reason.contains("not present in dataset LB"), reason);
    }


    @Test
    void suppPivotSatisfiesALiteralForeignVariable()
    {
        // AE has no AETRTEM column, but SUPPAE delivers it as a QNAM qualifier — the same
        // Fix #39 fallback the Check-side dotted `exists` applies.
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1").build();
        IDataTable suppae = MockTable.of().name("SUPPAE").col("QNAM", "AETRTEM").build();
        assertNull(check(include("AE.AETRTEM"), sourceOf(map("AE", ae, "SUPPAE", suppae))));

        IDataTable otherQnam = MockTable.of().name("SUPPAE").col("QNAM", "AESOSP").build();
        assertNotNull(check(include("AE.AETRTEM"), sourceOf(map("AE", ae, "SUPPAE", otherQnam))),
                "a different QNAM must not satisfy the entry");
    }


    @Test
    void suppPivotAppliesEvenWhenTheParentDatasetIsAbsent()
    {
        // Review H2: `exists AE.AETRTEM` on the Check side falls through to the SUPPAE scan when
        // resolve("AE") is null, so the scope gate must too — otherwise a hoist of that guard
        // would flip a running rule to SKIPPED on a study that ships SUPPAE without AE.
        IDataTable suppae = MockTable.of().name("SUPPAE").col("QNAM", "AETRTEM").build();
        Map<String, IDataTable> tables = map("SUPPAE", suppae);
        DatasetResolver.WithInventory resolver = inventory(tables);
        ScopeVariableSource src = ScopeVariableSource.of(resolver, primary());
        assertNotNull(src);

        assertTrue(checkSideExists(resolver, "AE.AETRTEM"),
                "fixture precondition: the Check side resolves it via the SUPP pivot");
        assertNull(check(include("AE.AETRTEM"), src),
                "the scope gate must agree, not report AE unavailable");
        assertNotNull(check(exclude("AE.AETRTEM"), src), "Exclude must reject symmetrically");
        // A column the SUPP partner does not deliver still reports the dataset as unavailable.
        String reason = check(include("AE.AESEV"), src);
        assertNotNull(reason);
        assertTrue(reason.contains("dataset AE not available"), reason);
    }


    @Test
    void splitDomainUnionIsNotAttemptedForALongQualifier()
    {
        // Review M3: tablesForDomain resolves EVERY dataset in the inventory (in production that
        // loads and parses each one). It can only match a 2-char DOMAIN code, so a longer absent
        // qualifier must be answered without touching the inventory.
        AtomicInteger resolveCalls = new AtomicInteger();
        DatasetResolver.WithInventory counting = new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                resolveCalls.incrementAndGet();
                return null;
            }


            @Override
            public Set<String> availableDatasets()
            {
                return Set.of("AE", "DM", "LB", "VS", "EX");
            }
        };
        ScopeVariableSource src = ScopeVariableSource.of(counting, primary());
        assertNotNull(src);
        assertTrue(src.metasOf("ADSL").isEmpty());
        assertEquals(1, resolveCalls.get(),
                "a 4-char absent qualifier must cost exactly one resolve, not an inventory walk");

        resolveCalls.set(0);
        assertTrue(src.metasOf("LB").isEmpty());
        assertTrue(resolveCalls.get() > 1,
                "a 2-char qualifier may still be a split domain, so the union is attempted");
    }


    @Test
    void suppPivotIsCaseSensitiveLikeTheCheckSide()
    {
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1").build();
        IDataTable suppae = MockTable.of().name("SUPPAE").col("QNAM", "aetrtem").build();
        assertNotNull(check(include("AE.AETRTEM"), sourceOf(map("AE", ae, "SUPPAE", suppae))),
                "existsInSuppQnam compares case-sensitively; the scope gate must not diverge");
    }


    @Test
    void suppPivotDoesNotRecurseForASuppQualifier()
    {
        // A SUPP qualifier must not look for SUPPSUPPAE.
        IDataTable suppae = MockTable.of().name("SUPPAE").col("QNAM", "QVAL").build();
        assertNotNull(check(include("SUPPAE.QVAL"), sourceOf(map("SUPPAE", suppae))),
                "SUPPAE has no QVAL column and must not be SUPP-pivoted onto itself");
    }


    @Test
    void foreignLookupHonoursTheForeignMetadataCasing()
    {
        // The lookup delegates to the foreign table's own getColumnIndex rather than an internal
        // normalised name set, so a casing mismatch behaves exactly as that table defines it.
        IDataTable dm = MockTable.of().name("DM").col("ARM", "A").build();
        assertEquals(-1, dm.getMetaData().getColumnIndex("arm"),
                "fixture precondition: this meta resolves column names case-sensitively");
        assertNotNull(check(include("DM.arm"), sourceOf(map("DM", dm))),
                "the gate must report what the foreign meta reports");
    }

    // ------------------------------------------------------------------
    // No inventory ⇒ qualified entries are ignored
    // ------------------------------------------------------------------


    @Test
    void bareResolverYieldsNoSourceAndQualifiedEntriesAreIgnored()
    {
        assertNull(ScopeVariableSource.of(_ -> null, primary()),
                "a bare DatasetResolver cannot enumerate datasets");
        assertNull(ScopeMatcher.describeVariablesMismatch(include("DM.ARM"),
                primary().getMetaData(), "AE", null),
                "without a source a qualified entry must not skip the rule");
        assertNull(ScopeMatcher.describeVariablesMismatch(exclude("DM.ARM"),
                primary().getMetaData(), "AE", null));
    }


    @Test
    void nullResolverYieldsNoSource()
    {
        assertNull(ScopeVariableSource.of(null, primary()));
    }

    // ------------------------------------------------------------------
    // Unqualified entries are untouched
    // ------------------------------------------------------------------


    @Test
    void unqualifiedEntriesKeepTheirPreExistingBehaviour()
    {
        ScopeVariableSource src = sourceOf(map());
        assertNull(check(include("AESEQ"), src), "present on the primary");
        assertNull(check(include("--SEQ"), src), "-- resolves against the primary's AE prefix");
        assertNotNull(check(include("AESTDTC"), src), "absent on the primary");
        assertNotNull(check(exclude("AESEQ"), src), "present ⇒ excluded");
        // The three-arg overload must agree with the four-arg one passing null.
        assertEquals(
                ScopeMatcher.describeVariablesMismatch(include("AESTDTC"), primary().getMetaData(),
                        "AE"),
                ScopeMatcher.describeVariablesMismatch(include("AESTDTC"), primary().getMetaData(),
                        "AE", null));
    }


    @Test
    void mixedQualifiedAndUnqualifiedIncludeIsAndCombined()
    {
        IDataTable dm = MockTable.of().name("DM").col("ARM", "A").build();
        ScopeVariableSource src = sourceOf(map("DM", dm));
        assertNull(check(include("AESEQ", "DM.ARM"), src));
        assertNotNull(check(include("AESTDTC", "DM.ARM"), src), "primary half fails");
        assertNotNull(check(include("AESEQ", "DM.ACTARM"), src), "foreign half fails");
    }

    // ------------------------------------------------------------------
    // Agreement with the Check-side dotted `exists`
    // ------------------------------------------------------------------


    /**
     * Evaluates {@code var_exists(<name>)} the way a Check leaf would, over the same resolver — via
     * the very probe the native compiler calls for a {@code var_exists} plan.
     */
    private static boolean checkSideExists(DatasetResolver resolver, String name)
    {
        IDataTable t = primary();
        EvaluationContext ctx = EvaluationContext.builder().table(t).datasetResolver(resolver)
                .build();
        return OperatorRegistry.existsAsVariable(ctx, name);
    }


    @Test
    void plainQualifiersAgreeWithTheCheckSideDottedExists()
    {
        IDataTable dm = MockTable.of().name("DM").col("ARM", "A").build();
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1").build();
        IDataTable suppae = MockTable.of().name("SUPPAE").col("QNAM", "AETRTEM").build();
        Map<String, IDataTable> tables = map("DM", dm, "AE", ae, "SUPPAE", suppae);
        DatasetResolver.WithInventory resolver = inventory(tables);
        ScopeVariableSource src = ScopeVariableSource.of(resolver, primary());
        assertNotNull(src);

        // Present literal, absent literal, absent dataset, and the SUPP-pivot case.
        for (String name : List.of("DM.ARM", "DM.ACTARM", "VS.VSORRES", "AE.AETRTEM"))
        {
            boolean viaCheck = checkSideExists(resolver, name);
            boolean viaScope = check(include(name), src) == null;
            assertEquals(viaCheck, viaScope,
                    "scope gate and Check-side `exists` must agree for " + name);
        }
    }


    @Test
    void wildcardQualifiersAreDeliberatelyBroaderThanTheCheckSide_splitDomainsNowAgree()
    {
        // Pinned divergence, HALVED by Fix #358 (PLAN-match-datasets-split-union.md Phase 2f):
        // the Check-side dotted `exists` now resolves a split domain through the same
        // exact-name-then-union helper the join sites use, so the split-domain half of the old
        // asymmetry is GONE — scope gate and Check side agree. The `--` dataset-wildcard half
        // remains: only the scope gate resolves `SUPP--`, so a hoist of THAT shape is still not
        // behaviour-preserving.
        IDataTable suppae = MockTable.of().name("SUPPAE").col("QVAL", "x").build();
        IDataTable lbch = MockTable.of().name("lbch").col("DOMAIN", "LB").col("LBORRES", "5")
                .build();
        Map<String, IDataTable> tables = map("SUPPAE", suppae, "lbch", lbch);
        DatasetResolver.WithInventory resolver = inventory(tables);
        ScopeVariableSource src = ScopeVariableSource.of(resolver, primary());
        assertNotNull(src);

        assertFalse(checkSideExists(resolver, "SUPP--.QVAL"),
                "the Check side does not resolve a `--` dataset wildcard");
        assertNull(check(include("SUPP--.QVAL"), src), "the scope gate does");

        assertTrue(checkSideExists(resolver, "LB.LBORRES"),
                "Fix #358: the Check side unions split-domain members, same as the scope gate");
        assertNull(check(include("LB.LBORRES"), src), "and the scope gate still does");
    }

    // ------------------------------------------------------------------
    // Memoisation
    // ------------------------------------------------------------------


    @Test
    void qualifierResolutionIsMemoised()
    {
        AtomicInteger resolveCalls = new AtomicInteger();
        IDataTable dm = MockTable.of().name("DM").col("ARM", "A").build();
        DatasetResolver.WithInventory counting = new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                resolveCalls.incrementAndGet();
                return "DM".equals(name) ? dm : null;
            }


            @Override
            public Set<String> availableDatasets()
            {
                return Set.of("DM");
            }
        };
        ScopeVariableSource src = ScopeVariableSource.of(counting, primary());
        assertNotNull(src);
        assertEquals(List.of(dm.getMetaData()), src.metasOf("DM"));
        int afterFirst = resolveCalls.get();
        assertEquals(List.of(dm.getMetaData()), src.metasOf("DM"));
        assertEquals(afterFirst, resolveCalls.get(), "second metasOf must be served from the memo");
    }


    @Test
    void absentQualifierIsMemoisedToo()
    {
        AtomicInteger resolveCalls = new AtomicInteger();
        DatasetResolver.WithInventory counting = new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                resolveCalls.incrementAndGet();
                return null;
            }


            @Override
            public Set<String> availableDatasets()
            {
                return Set.of();
            }
        };
        ScopeVariableSource src = ScopeVariableSource.of(counting, primary());
        assertNotNull(src);
        assertTrue(src.metasOf("DM").isEmpty());
        int afterFirst = resolveCalls.get();
        assertTrue(src.metasOf("DM").isEmpty());
        assertEquals(afterFirst, resolveCalls.get(),
                "an absent dataset must be memoised, not re-resolved per rule");
    }

}
