package net.cumba.corej.ruletest.cdt.ruletest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.EngineLimits;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionNot;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario.Verdict;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.jspecify.annotations.Nullable;

/**
 * Runtime-capture helper that snapshots rule-test scenarios while the existing Java tests execute.
 * Activated only when {@code -Dgenerate.scenarios=true} is passed to the test runner so capture
 * mode is opt-in. Each capture call writes one extended-CDT scenario file under
 * {@code <module>/src/test/resources/net/cumba/corej/core/ruletestsuites/<family>/<coreId>/}, where
 * {@code <family>} is the captured rule's family: the leading letter run of its {@code Core.Id},
 * lower-cased ({@code CDISC-CG0001 -> cdisc}, {@code FDA-SD0007 -> fda},
 * {@code PMDA-AD0001 -> pmda}, {@code DRAFT-… -> draft}). That is the subtree the family's
 * {@code RuleTestSuites<Family>FactoryTest} replays, so a captured scenario is picked up by the
 * factory that owns its rule. A capture whose family directory does not exist is refused loudly
 * rather than written where nothing replays it.
 *
 * <p>
 * ⚑ <b>Retargeted 2026-09-02.</b> This wrote to
 * {@code src/test/resources/net/cumba/corej/ruletest/<sdtm|adam>/} until then &mdash; a tree that
 * no longer exists and that no factory replays. Two things had moved underneath it: the scenario
 * corpus relocated to {@code net/cumba/corej/core/ruletestsuites/} and is now keyed by rule
 * <em>family</em> ({@code cdisc}, {@code core}, {@code fda}, {@code pmda}, {@code draft}), not by
 * standard; and the path was CWD-relative, so under the root pom's surefire
 * {@code workingDirectory} it resolved beneath {@code target/test-cwd/}. Captured scenarios
 * therefore landed somewhere nothing reads, silently &mdash; the regenerate-with
 * {@code -Dgenerate.scenarios=true} procedure that ~23 suite javadocs describe was writing into the
 * void. The identical pair of bugs was fixed in the sibling {@code ScenarioTrimmer} (since deleted;
 * it exists in no repository of the stack any more) by {@code plans/done/PLAN-corej-restructure.md}
 * §1, which left this one.
 * </p>
 *
 * <p>
 * Plan reference: see §6 (Generator to migrate existing Java tests) of
 * {@code cdt-test-file-plan.md}. This implements Option A (runtime capture) — the simpler of the
 * two approaches the plan lays out.
 * </p>
 */
// Test fixture / helper exposing LinkedHashMap/LinkedHashSet for ordered iteration.
@SuppressWarnings("NonApiType")
public final class ScenarioCapture
{

    public static final String FLAG = "generate.scenarios";

    /*
     * ⚠ Resolved through a method, not a static initialiser. `projectBasedir` is set by surefire
     * (root pom systemPropertyVariables) but not by a bare IDE / JUnit launcher run, and
     * Path.of(null, ...) throws — which in a static initialiser would be an
     * ExceptionInInitializerError for every suite that merely calls isEnabled(), i.e. all of them.
     * The property names the MODULE being built, which for a capture run is always the module
     * holding the suites (corej-rules), so this lands in that module's own corpus.
     */
    private static Path resourceRoot()
    {
        String base = System.getProperty("projectBasedir");
        if (base == null)
        {
            throw new IllegalStateException("The 'projectBasedir' system property is not set. "
                    + "Scenario capture writes into the running module's own test resources; "
                    + "surefire sets this property, a bare IDE run does not. Run capture with: "
                    + "mvn test -Dgenerate.scenarios=true, from the root of the "
                    + "rules repository.");
        }
        return Path.of(base, "src/test/resources/net/cumba/corej/core/ruletestsuites");
    }

    private static final Pattern VERDICT_SUFFIX = Pattern.compile("(valid|invalid)(\\d*)");

    /** Every scenario file this JVM has captured, so a second capture onto one name is refused. */
    private static final Set<Path> WRITTEN = ConcurrentHashMap.newKeySet();

    private ScenarioCapture()
    {
    }


    /** True iff {@code -Dgenerate.scenarios=true} was passed on the command line. */
    public static boolean isEnabled()
    {
        return Boolean.getBoolean(FLAG);
    }


    /**
     * Sibling-aware capture: in addition to the primary table, snapshot every dataset the rule
     * might read via {@code Match_Datasets}, {@code Operations}, or Domain-Presence-Check leaves.
     * The scenario file ends up self-contained: the factory's {@link ScenarioResolver} reproduces
     * the rule's evaluation inputs without needing the original shared study.
     *
     * <ul>
     * <li>{@code Match_Datasets} siblings are snapshotted as <em>data-carrying</em> blocks filtered
     * to rows whose join-key value appears in the primary's same-named column.</li>
     * <li>Operations with a {@code domain} field get the full sibling (to be trimmed later).</li>
     * <li>Domain-Presence-Check leaves (operators {@code exists}/{@code not_exists}) get a minimal
     * 1-row stub so the resolver reports the name as available. Names that resolve to {@code null}
     * in the live resolver (i.e. the test simulated "domain absent") are simply omitted.</li>
     * <li>If the resolver is an {@link OverridingResolver}, its explicit overrides become siblings
     * too, and its dropped names are excluded everywhere.</li>
     * <li>If the primary's dataset name is in the dropped set (e.g. a Domain Presence Check like
     * CDISC-CG0368 that simulates a missing DM), the primary is renamed to an "absent-proxy" so the
     * scenario resolver does not re-include it.</li>
     * </ul>
     */
    public static void captureWithSiblings(String aCoreId, Verdict aVerdict, String aDomain,
            OverlayDataTable aPrimary, @Nullable Rule aRule, @Nullable DatasetResolver aResolver)
    {
        captureWithSiblings(aCoreId, aVerdict, aDomain, aPrimary, aRule, aResolver, null);
    }


    /**
     * Sibling-aware capture with an optional Library provider. When a non-null
     * {@link MapBackedLibraryMetadataProvider} is passed, its state is serialised into the captured
     * scenario as a series of {@code #library} directives. Library providers of other
     * implementations are ignored (capture still runs but emits no library directives).
     */
    public static void captureWithSiblings(String aCoreId, Verdict aVerdict, String aDomain,
            OverlayDataTable aPrimary, @Nullable Rule aRule, @Nullable DatasetResolver aResolver,
            @Nullable MetadataProvider aLibrary)
    {
        if (!isEnabled())
        {
            return;
        }
        // Skip single-column tables that have any all-null row: CdtWriter only emits
        // the `.` all-null sentinel when colCount > 1, so the round-trip would drop the
        // row. Tables with non-null values on every row are safe to capture even when
        // single-column (e.g. CDISC-AD0497 against an ADLBC fixture with STUDYID only).
        if (aPrimary.getMetaData().getColumnCount() < 2 && hasAllNullRow(aPrimary))
        {
            return;
        }
        StackWalker.StackFrame frame = findTestFrame(aCoreId);
        if (frame == null)
        {
            // The method name supplies the file's verdict token (valid-2, invalid-3, ...); without
            // it two variants of one rule would silently share one file name.
            throw new IllegalStateException("cannot name the captured scenario for " + aCoreId
                    + ": no test method on the stack is named after the rule (expected a method"
                    + " starting with " + methodPrefix(aCoreId) + ")");
        }
        String methodName = frame.getMethodName();

        // Figure out dropped names (for primary-rename decision + sibling exclusion).
        Set<String> dropped = new HashSet<>();
        Map<String, IDataTable> explicitOverrides = new LinkedHashMap<>();
        if (aResolver instanceof OverridingResolver or)
        {
            dropped.addAll(or.getDropped());
            explicitOverrides.putAll(or.getOverrides());
        }

        // The scenario's #test domain= directive selects the primary dataset AND is
        // passed as domainPrefix for `--` expansion. If Java's domainPrefix differs
        // from the primary's declared name (e.g. CDISC-CG0367 uses an APAE primary
        // with domainPrefix="AP"), rename the primary so the directive matches.
        String effectiveDomain = aDomain;
        OverlayDataTable effectivePrimary = aPrimary;
        String primaryName = aPrimary.getMetaData().getName();

        // F-corej-L3-04: relabel a WRAPPER (asOverlayDataTable), never the caller's live table
        // in place — under -Dgenerate.scenarios=true an in-place rename would mutate the state
        // of the very object the suite is still testing.
        if (primaryName != null && dropped.contains(primaryName.toUpperCase(Locale.ROOT)))
        {
            // Dropped-name override: resolverWithout(...) hides the primary's name.
            String proxyName = primaryName + "_DROPPED";
            effectivePrimary = asOverlayDataTable(aPrimary, proxyName);
            effectiveDomain = proxyName;
        }
        else if (primaryName == null || !primaryName.equalsIgnoreCase(effectiveDomain))
        {
            // Primary name differs from domainPrefix — relabel so the scenario's
            // domain= directive matches the emitted dataset's declared name.
            effectivePrimary = asOverlayDataTable(aPrimary, effectiveDomain);
        }

        // Collect sibling datasets.
        LinkedHashMap<String, OverlayDataTable> siblings = new LinkedHashMap<>();
        if (aRule != null && aResolver != null)
        {
            collectSiblings(aRule, effectivePrimary, aResolver, effectiveDomain, dropped,
                    explicitOverrides, siblings);
        }

        List<OverlayDataTable> datasets = new ArrayList<>();
        datasets.add(effectivePrimary);
        for (OverlayDataTable s : siblings.values())
        {
            datasets.add(s);
        }

        // F-corej-L3-02: the guard above states a property of the WRITER ("CdtWriter only emits
        // the `.` all-null sentinel when colCount > 1"), which holds for every dataset in the
        // scenario, not only the primary. A single-column all-null SIBLING would silently mint a
        // fixture that replays with one row fewer than was captured, so refuse the capture
        // exactly as for an unrepresentable primary.
        for (OverlayDataTable d : datasets)
        {
            if (d.getMetaData().getColumnCount() < 2 && hasAllNullRow(d))
            {
                return;
            }
        }

        MapBackedLibraryMetadataProvider libraryToWrite = aLibrary instanceof MapBackedLibraryMetadataProvider m
                ? m
                : null;
        // Location directives: re-run the rule against the captured datasets so the emitted
        // #expectViolationAt lines match what the factory will later verify on this exact file.
        ViolationLocationCheck.Expectations exp = locationExpectations(aRule, aVerdict,
                effectivePrimary, effectiveDomain, datasets, aLibrary);
        Path out = scenarioPath(aCoreId, aVerdict, effectiveDomain, methodName);
        writeScenario(aCoreId, aVerdict, effectiveDomain, null, datasets, libraryToWrite, exp, out,
                methodName);
    }


    /**
     * Compute the location expectations for a captured VIOLATION scenario by running the rule
     * against the captured datasets exactly as the factory will (self-contained resolver). Returns
     * {@code null} for non-violation captures, when no rule is available, or when the rule does not
     * reproduce on the captured data (a zero-count would contradict {@code expect=violation}).
     */
    private static ViolationLocationCheck.@Nullable Expectations locationExpectations(
            @Nullable Rule aRule, Verdict aVerdict, OverlayDataTable aPrimary, String aDomain,
            List<OverlayDataTable> aDatasets, @Nullable MetadataProvider aLibrary)
    {
        if (aVerdict != Verdict.VIOLATION || aRule == null)
        {
            return null;
        }
        DatasetResolver.WithInventory resolver = ScenarioResolver.of(aDatasets);
        // The engine's one entry point, with exactly the defaults the retired 6-argument
        // convenience forwarded (no Define-XML provider, unlimited findings, no caches, no
        // dictionary or VLM provider, empty coverage sets, the default severity threshold) —
        // PLAN-retire-dead-multi-match-lookup K5.
        RuleExecutionResult res = RuleRunner.execute(aRule, aPrimary, resolver, aDomain, aLibrary,
                null, null, Integer.MAX_VALUE, null, null, null, Set.of(), Set.of(),
                EngineLimits.DEFAULT_SEVERITY_THRESHOLD);
        // Row-bearing domain (the leaf-scope successor of RuleType.isValueBased()).
        boolean valueBased = aRule.getEvaluationDomain() == null
                || aRule.getEvaluationDomain().rowCursor();
        List<Violation> v = res.getViolations() != null ? res.getViolations() : List.of();
        ViolationLocationCheck.Expectations e = ViolationLocationCheck.toExpectations(v,
                res.getViolationCount(), res.isTruncated(), valueBased, aDomain);
        return e.count() != null && e.count() > 0 ? e : null;
    }


    /**
     * Populate {@code aOut} with the sibling tables the rule may read during evaluation. See
     * {@link #captureWithSiblings} for the rules of engagement.
     */
    private static void collectSiblings(Rule aRule, OverlayDataTable aPrimary,
            DatasetResolver aResolver, String aDomain, Set<String> aDropped,
            Map<String, IDataTable> aExplicitOverrides,
            LinkedHashMap<String, OverlayDataTable> aOut)
    {
        String primaryNameUpper = aDomain == null ? "" : aDomain.toUpperCase(Locale.ROOT);

        // 1) Explicit overrides from an OverridingResolver go in first.
        for (Map.Entry<String, IDataTable> e : aExplicitOverrides.entrySet())
        {
            String nameUpper = e.getKey();
            if (nameUpper.equals(primaryNameUpper)) continue;
            aOut.put(nameUpper, asOverlayDataTable(e.getValue(), nameUpper));
        }

        // 2) Match_Datasets — filter rows by join keys vs the primary's same-named column.
        if (aRule.getMatchDatasets() != null)
        {
            for (MatchDataset md : aRule.getMatchDatasets())
            {
                if (md == null || md.getName() == null) continue;
                String nameUpper = md.getName().toUpperCase(Locale.ROOT);
                if (aDropped.contains(nameUpper) || aOut.containsKey(nameUpper)
                        || nameUpper.equals(primaryNameUpper))
                {
                    continue;
                }
                IDataTable live = aResolver.resolve(md.getName());
                if (live == null)
                {
                    continue;
                }
                OverlayDataTable filtered = filterByJoinKeys(live, aPrimary, md.getKeys());
                if (filtered != null)
                {
                    filtered.setTableName(nameUpper);
                    aOut.put(nameUpper, filtered);
                }
            }
        }

        // 3) Operation.domain references — capture full sibling (trimmer reduces later).
        Set<String> referenceDomainsViaOps = new LinkedHashSet<>();
        boolean anyInventoryOp = false;
        if (aRule.getOperations() != null)
        {
            for (Operation op : aRule.getOperations())
            {
                if (op == null) continue;
                String opName = op.getOperator();
                if (opName == null) continue;
                if (opName.equals("dataset_names") || opName.equals("study_domains"))
                {
                    anyInventoryOp = true;
                }
                String dom = op.getDomain();
                if (dom != null)
                {
                    referenceDomainsViaOps.add(dom.toUpperCase(Locale.ROOT));
                }
            }
        }
        for (String nameUpper : referenceDomainsViaOps)
        {
            if (aDropped.contains(nameUpper) || aOut.containsKey(nameUpper)
                    || nameUpper.equals(primaryNameUpper))
            {
                continue;
            }
            IDataTable live = aResolver.resolve(nameUpper);
            if (live == null) continue;
            aOut.put(nameUpper, asOverlayDataTable(live, nameUpper));
        }

        // 4) Dataset presence — walk the Check for ds_exists/ds_not_exists calls. Each
        // name found becomes either a stub sibling (if the resolver has it) or is simply omitted
        // (if resolver returns null — i.e. test expects absent). Since the leaf-scope plan no
        // rule type gates this: any rule may carry a presence call, and a name already served
        // as a data sibling above is skipped.
        {
            Set<String> presenceNames = new LinkedHashSet<>();
            // Every declared level (Plan C §3.3), never getCheck() alone: a ds_exists /
            // ds_not_exists leaf in a weaker level would otherwise not be stubbed, and the
            // captured scenario would not reproduce the run.
            for (CheckCondition levelCondition : aRule.checkConditions())
            {
                collectDomainPresenceNames(levelCondition, presenceNames);
            }
            for (String nameUpper : presenceNames)
            {
                if (aDropped.contains(nameUpper) || aOut.containsKey(nameUpper)
                        || nameUpper.equals(primaryNameUpper))
                {
                    continue;
                }
                IDataTable live = aResolver.resolve(nameUpper);
                if (live == null) continue;
                aOut.put(nameUpper, stubFor(nameUpper));
            }
        }

        // 5) Inventory-scanning operations — add stubs of every resolver-known domain
        // not already present, excluding dropped and the primary.
        if (anyInventoryOp && aResolver instanceof DatasetResolver.WithInventory wi)
        {
            for (String nameUpper : new LinkedHashSet<>(wi.availableDatasets()))
            {
                if (aDropped.contains(nameUpper) || aOut.containsKey(nameUpper)
                        || nameUpper.equals(primaryNameUpper))
                {
                    continue;
                }
                IDataTable live = aResolver.resolve(nameUpper);
                if (live == null) continue;
                aOut.put(nameUpper, stubFor(nameUpper));
            }
        }
    }


    /**
     * Walk the Check collecting every dataset name a {@code ds_exists} / {@code ds_not_exists} call
     * tests.
     *
     * <p>
     * ⭐ Phase 7d (D121): re-pointed from the retired operator-leaf tree at the compiled expression.
     * The leaf walker had been silently vacuous since the lowering left the load path — every
     * rule's Check is a {@code CheckConditionExpression}, whose presence calls the old walker could
     * not see, so captured scenarios stopped stubbing presence siblings with nothing red.
     * </p>
     */
    private static void collectDomainPresenceNames(@Nullable CheckCondition aCond, Set<String> aOut)
    {
        if (aCond == null) return;
        // ⛔ R2-7 (review round 2): an EXHAUSTIVE pattern switch over the sealed CheckCondition,
        // not an `instanceof` chain. This walker is the one phase 7d already paid for once (see
        // the javadoc above): a shape it cannot see costs nothing loudly, it just stops stubbing
        // presence siblings. With no `default` arm a FIFTH implementor fails to COMPILE here
        // (CheckCondition permits four: All, Any, Not, Expression).
        switch (aCond)
        {
        case CheckConditionAll all ->
        {
            for (CheckCondition c : all.getConditions())
                collectDomainPresenceNames(c, aOut);
        }
        case CheckConditionAny any ->
        {
            for (CheckCondition c : any.getConditions())
                collectDomainPresenceNames(c, aOut);
        }
        case CheckConditionNot not -> collectDomainPresenceNames(not.getCondition(), aOut);
        case net.cumba.corej.core.model.CheckConditionExpression expression -> collectDomainPresenceNames(
                expression.expr(), aOut);
        }
    }


    /** The {@link Expr} arm of {@link #collectDomainPresenceNames(CheckCondition, Set)}. */
    private static void collectDomainPresenceNames(Expr aExpr, Set<String> aOut)
    {
        switch (aExpr)
        {
        case Expr.And and -> and.parts().forEach(part -> collectDomainPresenceNames(part, aOut));
        case Expr.Or or -> or.parts().forEach(part -> collectDomainPresenceNames(part, aOut));
        case Expr.Not not -> collectDomainPresenceNames(not.inner(), aOut);
        case Expr.Binary binary ->
        {
            collectDomainPresenceNames(binary.left(), aOut);
            collectDomainPresenceNames(binary.right(), aOut);
        }
        case Expr.Call call ->
        {
            if (("ds_exists".equals(call.name()) || "ds_not_exists".equals(call.name()))
                    && !call.args().isEmpty())
            {
                // The name operand is a bare reference or the equivalent string literal
                // (the two spellings the compiler accepts for a dataset-presence test).
                String name = switch (call.args().get(0))
                {
                case Expr.Ref ref -> ref.name();
                case Expr.Lit lit when lit.kind() == Expr.LitKind.STRING -> (String) lit.value();
                default -> null;
                };
                if (name != null)
                {
                    aOut.add(name.toUpperCase(Locale.ROOT));
                }
            }
            call.args().forEach(arg -> collectDomainPresenceNames(arg, aOut));
            call.kwargs().values().forEach(value -> collectDomainPresenceNames(value, aOut));
        }
        case Expr.Ref _,Expr.Lit _ ->
        {
            // no presence call here
        }
        }
    }


    /**
     * Filter {@code aLive} to rows whose join-key value appears in the primary's same-named column.
     * If no keys are supplied, return the full live table wrapped as a {@link OverlayDataTable} for
     * emission.
     */
    private static OverlayDataTable filterByJoinKeys(IDataTable aLive, OverlayDataTable aPrimary,
            @Nullable List<String> aKeys)
    {
        if (aKeys == null || aKeys.isEmpty())
        {
            return asOverlayDataTable(aLive, aLive.getMetaData().getName());
        }
        // F-corej-L3-03: a join on N keys is a test on the key TUPLE. Testing each key column's
        // value-set independently is the Cartesian relaxation of the intended join and
        // over-captures rows the real join can never produce.
        int keyCount = aKeys.size();
        int[] primaryIdx = new int[keyCount];
        for (int k = 0; k < keyCount; k++)
        {
            primaryIdx[k] = aPrimary.getMetaData().getColumnIndex(aKeys.get(k));
            if (primaryIdx[k] < 0)
            {
                // Primary doesn't have the key column — can't filter meaningfully.
                // Return full sibling to be safe.
                return asOverlayDataTable(aLive, aLive.getMetaData().getName());
            }
        }
        // The primary-side tuples, one per row; a row with any missing key value joins nothing.
        Set<List<String>> primaryTuples = new LinkedHashSet<>();
        long primaryRows = aPrimary.getRowCount();
        for (long r = 0; r < primaryRows; r++)
        {
            List<String> tuple = rowTuple(aPrimary, primaryIdx, r);
            if (tuple != null) primaryTuples.add(tuple);
        }
        // Walk live rows, keep only those whose OWN tuple is a primary-side tuple. A live table
        // missing a key column keeps no rows (as before: nothing can match).
        int[] liveIdx = new int[keyCount];
        boolean liveHasAllKeys = true;
        for (int k = 0; k < keyCount; k++)
        {
            liveIdx[k] = aLive.getMetaData().getColumnIndex(aKeys.get(k));
            if (liveIdx[k] < 0)
            {
                liveHasAllKeys = false;
                break;
            }
        }
        Set<Long> keepRows = new LinkedHashSet<>();
        if (liveHasAllKeys)
        {
            for (long r = 0; r < aLive.getRowCount(); r++)
            {
                List<String> tuple = rowTuple(aLive, liveIdx, r);
                if (tuple != null && primaryTuples.contains(tuple)) keepRows.add(r);
            }
        }
        // Keep-rows → a fresh OverlayDataTable with same columns.
        return tableWithRows(aLive, keepRows);
    }


    /**
     * The row's join-key tuple as string values, or {@code null} when any key cell is missing (a
     * row with a missing key value participates in no equi-join match).
     */
    private static @Nullable List<String> rowTuple(IDataTable aTable, int[] aKeyIdx, long aRow)
    {
        List<String> tuple = new ArrayList<>(aKeyIdx.length);
        for (int idx : aKeyIdx)
        {
            Object v = extractRaw(aTable.getValue(aRow, idx));
            if (v == null) return null;
            tuple.add(v.toString());
        }
        return tuple;
    }


    /** Build a OverlayDataTable that wraps {@code aLive} and re-labels the dataset name. */
    private static OverlayDataTable asOverlayDataTable(IDataTable aLive, @Nullable String aName)
    {
        OverlayDataTable t = new OverlayDataTable(aLive);
        if (aName != null) t.setTableName(aName);
        return t;
    }


    /** Return a OverlayDataTable containing only the specified row indexes of {@code aLive}. */
    private static OverlayDataTable tableWithRows(IDataTable aLive, Set<Long> aKeepRows)
    {
        // Use OverlayDataTable overlay to "remove" the other rows by leaving a raw value
        // overlay for kept rows and a null-value overlay for dropped rows. Simpler:
        // build an empty table of the same shape and set values only for kept rows.
        String name = Objects.requireNonNull(aLive.getMetaData().getName(),
                "live sibling table must have a name");
        String label = aLive.getMetaData().getLabel();
        int keepCount = aKeepRows.size();
        OverlayDataTable out = OverlayDataTable.empty(name, label != null ? label : name,
                keepCount);
        DataTableColumnMeta[] cols = aLive.getMetaData().getColumns();
        for (DataTableColumnMeta c : cols)
        {
            out.addColumn(c.getName(), c.getType(),
                    c.getLabel() != null ? c.getLabel() : c.getName());
            if (c.getLength() > 0) out.setColumnLength(c.getName(), c.getLength());
            if (c.getDisplayFormat() != null)
                out.setColumnFormat(c.getName(), c.getDisplayFormat());
        }
        int destRow = 0;
        List<Long> sortedKeep = new ArrayList<>(aKeepRows);
        Collections.sort(sortedKeep);
        for (long srcRow : sortedKeep)
        {
            for (int c = 0; c < cols.length; c++)
            {
                Object v = extractRaw(aLive.getValue(srcRow, c));
                if (v != null)
                {
                    out.setValue(destRow, cols[c].getName(), v);
                }
            }
            destRow++;
        }
        return out;
    }


    /** 1-row stub with STUDYID identity column — enough to make the name resolvable. */
    private static OverlayDataTable stubFor(String aName)
    {
        OverlayDataTable t = OverlayDataTable.empty(aName, aName, 1);
        t.addColumn("STUDYID", net.cumba.datatable.values.DataValueType.STRING, "Study Identifier");
        t.setValue(0, "STUDYID", "STUDY01");
        return t;
    }


    /** Unwrap IDataValue/MissingValue wrappers to a raw Java Object for writing. */
    private static @Nullable Object extractRaw(@Nullable Object aValue)
    {
        if (aValue == null) return null;
        if (aValue instanceof net.cumba.datatable.values.MissingValue) return null;
        if (aValue instanceof net.cumba.datatable.values.IDataValue dv)
        {
            if (dv.isMissingOrInvalid()) return null;
            return dv.getValue();
        }
        return aValue;
    }

    // ---- internals -----------------------------------------------------------------


    /**
     * The test-method prefix of a rule: its {@code Core.Id} with every {@code -} turned into
     * {@code _} ({@code CDISC-CG0554-C -> CDISC_CG0554_C}). Every rule-test suite method is named
     * that way, followed by {@code _valid…} / {@code _invalid…}.
     */
    private static String methodPrefix(String aCoreId)
    {
        return aCoreId.replace('-', '_');
    }


    /**
     * Walk up the call stack and return the first frame whose method is named after the captured
     * rule: its name is {@link #methodPrefix(String)} of {@code aCoreId}, alone or followed by
     * {@code _}. The method name supplies the verdict suffix of the file name.
     *
     * <p>
     * ⚑ Keyed on the rule id since 2026-09-26 (PLAN-dead-code-followups F-1). This used to accept
     * only methods starting {@code CORE_} (the retired CORE family) or {@code CDISC_AD}, so a
     * capture from any SDTM suite of the current corpus ({@code CDISC_CG…}, {@code FDA_SD…}) found
     * no frame and failed.
     * </p>
     */
    private static StackWalker.@Nullable StackFrame findTestFrame(String aCoreId)
    {
        String prefix = methodPrefix(aCoreId);
        return StackWalker.getInstance().walk(frames -> frames.filter(f ->
        {
            String name = f.getMethodName();
            return name.equals(prefix) || name.startsWith(prefix + "_");
        }).findFirst().orElse(null));
    }


    private static Path scenarioPath(String aCoreId, Verdict aVerdict, String aDomain,
            @Nullable String aMethodName)
    {
        Path familyDir = familyDirectory(resourceRoot(), aCoreId);
        String verdictToken = verdictToken(aVerdict, aMethodName);
        String fileName = aCoreId + "-" + verdictToken + "-" + aDomain + ".cdt";
        return familyDir.resolve(aCoreId).resolve(fileName);
    }


    /**
     * The corpus <b>family</b> directory a scenario of rule {@code aCoreId} belongs in: the leading
     * run of ASCII letters of the id, lower-cased ({@code CDISC-CG0001 -> cdisc},
     * {@code FDA-SD0007 -> fda}), resolved under {@code aRoot}. That is the subtree the family's
     * {@code RuleTestSuites<Family>FactoryTest} replays — the rule's family decides it, not the
     * package of the suite that happened to capture it.
     *
     * <p>
     * ⚑ PLAN-dead-code-followups F-1 (2026-09-26). This used to derive the directory from the
     * calling suite's package: {@code .adam -> cdisc/} and {@code .sdtm -> core/}. The
     * {@code core/} root was deleted with the CORE family in 2026-09, and an SDTM suite now tests
     * CDISC, FDA and PMDA rules side by side, so no single directory was right for a package.
     * </p>
     *
     * @throws IllegalStateException
     *             when the id has no leading letters, or when the family directory does not exist —
     *             a scenario written there would be replayed by nothing
     */
    static Path familyDirectory(Path aRoot, String aCoreId)
    {
        int i = 0;
        while (i < aCoreId.length() && isAsciiLetter(aCoreId.charAt(i)))
        {
            i++;
        }
        if (i == 0)
        {
            throw new IllegalStateException("cannot derive the scenario family of '" + aCoreId
                    + "': a Core.Id starts with its family (CDISC-, FDA-, PMDA-, DRAFT-)");
        }
        String family = aCoreId.substring(0, i).toLowerCase(Locale.ROOT);
        Path dir = aRoot.resolve(family);
        if (!Files.isDirectory(dir))
        {
            throw new IllegalStateException("refusing to capture " + aCoreId + ": its family"
                    + " directory " + dir + " does not exist, so no RuleTestSuites factory would"
                    + " replay the scenario");
        }
        return dir;
    }


    private static boolean isAsciiLetter(char c)
    {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }


    /**
     * Convert the trailing segment of the test method name into a filename verdict token.
     * {@code _valid} / {@code _invalid} map to {@code valid} / {@code invalid}; a trailing digit
     * ({@code _valid2}, {@code _invalid3}) adds a dashed suffix ({@code valid-2},
     * {@code invalid-3}) so repeated variants produce distinct files. Falls back to the default
     * verdict word when the method name doesn't match.
     */
    private static String verdictToken(Verdict aVerdict, @Nullable String aMethodName)
    {
        String defaultToken = aVerdict == Verdict.VIOLATION ? "invalid" : "valid";
        if (aMethodName == null)
        {
            return defaultToken;
        }
        int last = aMethodName.lastIndexOf('_');
        if (last < 0)
        {
            return defaultToken;
        }
        String tail = aMethodName.substring(last + 1);
        Matcher m = VERDICT_SUFFIX.matcher(tail);
        if (!m.matches())
        {
            return defaultToken;
        }
        String word = m.group(1);
        String num = m.group(2);
        return num.isEmpty() ? word : word + "-" + num;
    }


    /** True iff any row of {@code aTable} has no non-null values. */
    private static boolean hasAllNullRow(OverlayDataTable aTable)
    {
        int colCount = aTable.getMetaData().getColumnCount();
        long rowCount = aTable.getRowCount();
        for (long r = 0; r < rowCount; r++)
        {
            boolean allNull = true;
            for (int c = 0; c < colCount; c++)
            {
                net.cumba.datatable.values.IDataValue v = aTable.getDataValue(r, c);
                if (v != null && !v.isMissingOrInvalid())
                {
                    allNull = false;
                    break;
                }
            }
            if (allNull)
            {
                return true;
            }
        }
        return false;
    }


    private static void writeScenario(String aCoreId, Verdict aVerdict, String aDomain,
            @Nullable String aNote, List<OverlayDataTable> aDatasets,
            @Nullable MapBackedLibraryMetadataProvider aLibrary,
            ViolationLocationCheck.@Nullable Expectations aExpectations, Path aOut,
            @Nullable String aMethodName)
    {
        RuleTestScenario.RuleTestScenarioBuilder b = RuleTestScenario.builder().coreId(aCoreId)
                .expect(aVerdict).domain(aDomain).note(aNote).datasets(aDatasets).library(aLibrary)
                .source(aOut.toString());
        if (aExpectations != null)
        {
            b.expectViolationCount(aExpectations.count()).expectedViolations(aExpectations.ats());
        }
        RuleTestScenario s = b.build();
        if (!WRITTEN.add(aOut.toAbsolutePath().normalize()))
        {
            // Two captures mapping onto one file name (e.g. CDISC_CG0370_invalid and
            // CDISC_CG0370_invalid_cross_domain_idvar, whose tail is no verdict token) would let
            // the second silently replace the first.
            throw new IllegalStateException("scenario capture collision: " + aOut + " was already"
                    + " written in this run; give "
                    + (aMethodName != null ? aMethodName : "the capturing method")
                    + " a distinct _valid<N> / _invalid<N> suffix");
        }
        try
        {
            Files.createDirectories(aOut.getParent());
            RuleTestCdt.write(s, aOut);
        }
        catch (IOException e)
        {
            throw new RuntimeException("scenario capture failed: " + aOut, e);
        }
        String line = (aMethodName != null ? aMethodName : "<unknown>") + " -> " + aOut;
        System.out.println("ScenarioCapture: migrated " + line);
    }
}
