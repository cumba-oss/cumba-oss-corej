package net.cumba.corej.core.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.cumba.corej.core.exec.RecordKeyResolver;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.model.Executability;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.report.FindingKind;
import net.cumba.datatable.report.FindingLocations;
import net.cumba.datatable.report.FindingScope;
import net.cumba.datatable.report.RowFindingSlab;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.report.SkippedRuleEntry;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationFindingLocation;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import org.jspecify.annotations.Nullable;

/**
 * Aggregates the output of multiple {@link RuleExecutionResult}s into a {@link ValidationReport}.
 *
 * <p>
 * Rule violations become {@link ValidationFinding}s with {@link FindingKind#RULE_VIOLATION}; engine
 * errors become {@link FindingKind#ENGINE_ERROR}; library warnings become
 * {@link FindingKind#LIBRARY_WARNING}. All engine-generated findings use {@link #SOURCE}. Skipped
 * rule executions never become findings — they accumulate as {@link SkippedRuleEntry}s on the built
 * report (one entry per rule × dataset, in insertion order).
 * </p>
 *
 * <p>
 * Instances are NOT thread-safe.
 * </p>
 */
public final class ValidationReportBuilder
{

    /** Stable source id for all engine-generated findings. */
    public static final String SOURCE = "cumba.core";

    /** Domain string used for library-level findings. */
    public static final String LIBRARY_LEVEL_DOMAIN = "";

    /**
     * Synthetic rule id for the finding emitted when a requested dataset cannot be opened as a
     * table (corrupt / unreadable / wrong format). Such failures are recorded as a real ERROR
     * finding on the affected domain rather than aborting the run.
     */
    public static final String DATASET_LOAD_ERROR_RULE_ID = "CUMBA-DATASET-LOAD";

    private static final String VARIABLE_NAME = "variable_name";

    private static final String USUBJID = "USUBJID";

    /**
     * The canonical report key for the row's sequence value. Note this is <em>not</em> a real
     * column name — the data set's column is {@code <DOMAIN>SEQ} — which is exactly why D5 keeps it
     * out of the finding's location.
     */
    private static final String SEQ = "SEQ";

    private final Map<String, MemberAccumulator> members = new LinkedHashMap<>();

    /**
     * Skipped (rule × dataset) entries in insertion order. Fed from two sources: execution-time
     * SKIPPED {@link RuleExecutionResult}s via {@link #add} and generation-time scope skips via
     * {@link #skippedRule}. Never findings — surfaced as the report's own skipped-rules section.
     */
    private final List<SkippedRuleEntry> skippedRules = new ArrayList<>();

    /**
     * CORE ids of the rules that recorded at least one <em>non-skipped</em> execution, in
     * first-seen order.
     *
     * <p>
     * A clean execution produces no finding and therefore leaves no other trace on the report, so
     * without this set a consumer cannot tell <em>"skipped on every dataset"</em> from <em>"ran and
     * found nothing"</em>. Recording the executed side (rather than inferring it from the absence
     * of findings) is what keeps a <em>partially</em> skipped rule out of the skipped bucket: it
     * really did run somewhere.
     * </p>
     */
    private final Set<String> executedCoreIds = new LinkedHashSet<>();

    private @Nullable String libraryUri;

    public ValidationReportBuilder libraryUri(@Nullable String aLibraryUri)
    {
        libraryUri = aLibraryUri;
        return this;
    }


    public ValidationReportBuilder add(String aDomain, @Nullable String aFileName, Rule aRule,
            RuleExecutionResult aResult)
    {
        Objects.requireNonNull(aDomain, "domain");
        Objects.requireNonNull(aRule, "rule");
        Objects.requireNonNull(aResult, "result");

        if (aResult.isSkipped())
        {
            // Execution-time skip — record the (rule × dataset) pair with the runner's full
            // status message (e.g. "Rule skipped — no Library access") instead of dropping it.
            skippedRules.add(SkippedRuleEntry.builder().coreId(coreIdOf(aRule)).dataset(aDomain)
                    .reason(aResult.getStatusMessage()).build());
            return this;
        }
        // Everything below this point is a real execution — error, violations, or a clean pass.
        // Recorded here, once, rather than before each of the three returns that follow.
        String executedCoreId = coreIdOf(aRule);
        if (executedCoreId != null)
        {
            executedCoreIds.add(executedCoreId);
        }
        if (aResult.isError())
        {
            accumulatorFor(aDomain, aFileName)
                    .add(buildEngineErrorFinding(aDomain, aRule, aResult));
            return this;
        }
        if (!aResult.hasViolations())
        {
            return this;
        }
        MemberAccumulator acc = accumulatorFor(aDomain, aFileName);
        for (ValidationFinding f : buildRuleFindings(aDomain, aRule, aResult))
        {
            acc.add(f);
        }
        return this;
    }


    public ValidationReportBuilder libraryWarning(String aMessage)
    {
        Objects.requireNonNull(aMessage, "message");
        MemberAccumulator acc = accumulatorFor(LIBRARY_LEVEL_DOMAIN, libraryUri);
        acc.add(ValidationFinding.builder().source(SOURCE).kind(FindingKind.LIBRARY_WARNING)
                .severity(Severity.WARNING).scope(FindingScope.DATASET).message(aMessage)
                .variableNames(List.of()).location(ValidationFindingLocation.EMPTY)
                .rows(RowFindingSlab.EMPTY).build());
        return this;
    }


    /**
     * Records a dataset-scoped ERROR finding for a requested target that could not be opened as a
     * table. Attached to the dataset's own domain (not the library-level domain) so it surfaces in
     * the per-domain findings and finding count; the run continues with the remaining datasets.
     */
    public ValidationReportBuilder datasetLoadError(String aDomain, @Nullable String aFileName,
            String aMessage)
    {
        Objects.requireNonNull(aDomain, "domain");
        Objects.requireNonNull(aMessage, "message");
        accumulatorFor(aDomain, aFileName).add(ValidationFinding.builder().source(SOURCE)
                .ruleId(DATASET_LOAD_ERROR_RULE_ID).kind(FindingKind.ENGINE_ERROR)
                .severity(Severity.ERROR).scope(FindingScope.DATASET).message(aMessage)
                .variableNames(List.of()).location(ValidationFindingLocation.builder()
                        .dataset(aDomain).variableNames(List.of()).build())
                .rows(RowFindingSlab.EMPTY).build());
        return this;
    }


    /**
     * Records a generation-time skip: a rule whose scope did not match the dataset and that was
     * therefore filtered out before execution. The reason is the scope describer's text verbatim
     * (e.g. {@code "domain EX not in Scope.Domains.Include [AE, CM]"}). One entry per (rule ×
     * dataset) pair — skipping is a per-dataset verdict.
     *
     * @param aDomain
     *            the dataset (table / domain name) the rule was skipped on
     * @param aFileName
     *            the dataset's source file name, accepted for symmetry with
     *            {@link #add(String, String, Rule, RuleExecutionResult)}; skipped entries carry no
     *            file name today
     * @param aRule
     *            the skipped rule
     * @param aReason
     *            the failing scope criterion
     * @return this builder
     */
    public ValidationReportBuilder skippedRule(String aDomain, @Nullable String aFileName,
            Rule aRule, String aReason)
    {
        Objects.requireNonNull(aDomain, "domain");
        Objects.requireNonNull(aRule, "rule");
        Objects.requireNonNull(aReason, "reason");
        skippedRules.add(SkippedRuleEntry.builder().coreId(coreIdOf(aRule)).dataset(aDomain)
                .reason(aReason).build());
        return this;
    }


    public ValidationReport build()
    {
        List<ValidationReportMember> out = new ArrayList<>(members.size());
        for (MemberAccumulator acc : members.values())
        {
            out.add(ValidationReportMember.builder().domain(acc.domain).fileName(acc.fileName)
                    .findings(List.copyOf(acc.findings)).build());
        }
        return ValidationReport.builder().members(Collections.unmodifiableList(out))
                .skippedRules(List.copyOf(skippedRules))
                .executedCoreIds(List.copyOf(executedCoreIds)).build();
    }

    // ------------------------------------------------------------------
    // Finding construction
    // ------------------------------------------------------------------


    /**
     * Build one or more {@link ValidationFinding}s from the violations of a single rule execution.
     * <p>
     * Violations are grouped by their <em>collapsed</em> schema — the column list obtained after
     * applying the {@code variable_name} + partner collapse. Record-level rules typically produce a
     * single group (all violations share the same column). Variable-metadata rules that report on
     * different columns produce one finding per column, so each column's slab has a dense
     * single-column schema rather than a sparse union of every violating column.
     * </p>
     */
    private static List<ValidationFinding> buildRuleFindings(String aDomain, Rule aRule,
            RuleExecutionResult aResult)
    {
        List<Violation> violations = aResult.getViolations();
        if (violations.isEmpty())
        {
            return List.of();
        }

        // Group violations by their collapsed schema — preserve insertion order. Each violation's
        // values map is augmented with USUBJID / SEQ from the Violation's identity fields (Python's
        // ValidationErrorEntity.USUBJID/SEQ live next to `value`, not in it; the engine carries
        // them
        // the same way). The augmentation lets JsonReportWriter and the UI keep reading USUBJID/SEQ
        // from the slab values map exactly as before.
        Map<GroupKey, List<Violation>> byKey = new LinkedHashMap<>();
        for (Violation v : violations)
        {
            LinkedHashSet<String> schemaSet = new LinkedHashSet<>();
            collectSchema(withIdentity(v), schemaSet);
            // ⛔ Plan C §3.4: the CLAIMING LEVEL is part of the key. Before per-level Checks a
            // group could only hold one level, so grouping on the collapsed schema alone was
            // enough and `severityOf` could read the group's first violation as representative.
            // A multi-level rule breaks that: an ERROR row and an INFO row of the same rule share
            // a schema, and one finding cannot be both. Keying on the level splits them into two
            // findings — which is also what a reader wants, since the message (§3.6) is resolved
            // per level too. ⚑ Every violation of a single-level rule carries a null level, so the
            // partition — and therefore every shipped finding — is byte-identical.
            GroupKey key = new GroupKey(List.copyOf(schemaSet), v.getLevel());
            byKey.computeIfAbsent(key, _ -> new ArrayList<>()).add(v);
        }

        FindingScope scope = scopeOf(aRule);
        String executability = executabilityOf(aRule);
        List<ValidationFinding> out = new ArrayList<>(byKey.size());
        for (Map.Entry<GroupKey, List<Violation>> e : byKey.entrySet())
        {
            List<String> names = e.getKey().names();
            int vc = names.size();
            List<Violation> group = e.getValue();

            RowFindingSlab.Builder slabBuilder = RowFindingSlab.builder(vc);
            for (Violation v : group)
            {
                @Nullable
                String[] values = rowValues(withIdentity(v), names);
                slabBuilder.addRow(Math.toIntExact(v.getRow()), values);
            }

            // EC-40 record key: schema from the group's violations, values in a parallel slab so
            // the reported variable = value pairs above stay exactly the rule's Output_Variables.
            List<String> keyNames = withoutAllEmptyColumns(keyNamesOf(group), group);
            RowFindingSlab keySlab = RowFindingSlab.EMPTY;
            if (!keyNames.isEmpty())
            {
                RowFindingSlab.Builder keyBuilder = RowFindingSlab.builder(keyNames.size());
                for (Violation v : group)
                {
                    @Nullable
                    String[] keyValues = new String[keyNames.size()];
                    Map<String, String> vKeys = v.getKeys();
                    for (int i = 0; i < keyNames.size(); i++)
                    {
                        keyValues[i] = vKeys == null ? null : vKeys.get(keyNames.get(i));
                    }
                    keyBuilder.addRow(Math.toIntExact(v.getRow()), keyValues);
                }
                keySlab = keyBuilder.build();
            }

            // Location columns: the engine's collapsed `names` are already real columns, so just
            // drop any non-column tokens ($scalars, dataset-qualified refs). No name marker — the
            // variable_name collapse already resolved the targeted column into `names`. A null
            // scope is intentional: it tells columnsFor to keep the real variable columns.
            //
            // D5/D17: the identity entries withIdentity() injected are excluded here. They are
            // "which row" facts, not "what is wrong" facts, and one of them ("SEQ") is not even a
            // real column of the data set — it is the canonical report key, while the actual
            // column is <DOMAIN>SEQ. Leaving them in put a non-existent column into a field
            // documented as real-columns-only. Entries a rule genuinely declared in its own
            // Output_Variables are kept (see declaredNames).
            List<String> flagged = withoutInjectedIdentity(names, declaredNames(aRule, group));
            ValidationFindingLocation location = ValidationFindingLocation.builder()
                    .dataset(aDomain)
                    .variableNames(FindingLocations.columnsFor(scope, flagged, List.of(), Set.of()))
                    .keyVariableNames(keyNames).keySource(keySourceOf(aResult, keyNames)).build();

            out.add(ValidationFinding.builder().source(SOURCE).ruleId(coreIdOf(aRule))
                    .kind(FindingKind.RULE_VIOLATION)
                    .severity(severityOf(e.getKey().level(), aResult, aRule))
                    .executability(executability).scope(scope)
                    .message(messageOf(aRule, e.getKey().level(), aResult)).variableNames(names)
                    .location(location).rows(slabBuilder.build()).keyRows(keySlab).build());
        }
        return out;
    }

    /**
     * The grouping key of a {@code RULE_VIOLATION} finding: the collapsed column schema <b>and the
     * claiming level</b> (Plan C §3.4).
     *
     * @param names
     *            the collapsed schema — the column list after the {@code variable_name} + partner
     *            collapse
     * @param level
     *            the level that claimed these rows, or {@code null} when the producing site
     *            resolved none (every single-level rule)
     */
    private record GroupKey(List<String> names, @Nullable Severity level)
    {

    }

    /**
     * The message a {@code RULE_VIOLATION} finding reports (Plan C §3.6, ruling 6): the <b>claiming
     * level's</b> {@code Message} when that level declares one, else the rule's
     * {@code Outcome.Message} as carried on the execution result.
     *
     * <p>
     * ⚑ Resolved <b>here, at report time</b>, and never by copying the rule's message into every
     * level at load: copying it would put the same string in the {@code rules/} package once per
     * level and make {@code Outcome.Message} unchangeable without editing every level.
     * </p>
     *
     * <p>
     * The {@code getCheckLevels() != null} guard keeps the whole resolution off the shipped path —
     * a rule with no level map cannot declare a per-level message, so there is nothing to look up.
     * </p>
     */
    private static @Nullable String messageOf(Rule aRule, @Nullable Severity aLevel,
            RuleExecutionResult aResult)
    {
        if (aLevel != null && aRule.getCheckLevels() != null)
        {
            net.cumba.corej.core.model.LevelCheck level = aRule.getCheckLevels().get(aLevel);
            if (level != null && level.message() != null)
            {
                return level.message();
            }
        }
        return aResult.getMessage();
    }


    /**
     * The severity a {@code RULE_VIOLATION} finding reports at — <b>read</b>, never decided here.
     *
     * <p>
     * ⚠⚠ This method must stay a pure read. The level is decided in {@code RuleRunner} and carried
     * out on the execution result, because the {@code .cdt} scenario checker
     * {@code ViolationLocationCheck} is handed a {@code RuleExecutionResult} and never sees a
     * {@code ValidationFinding} — a severity invented here would be invisible to every scenario.
     * </p>
     *
     * <p>
     * Precedence: the claiming level carried on the row, then the severity carried on the
     * execution, then <b>the rule's own effective severity</b>. The claiming level is part of the
     * finding's {@link GroupKey}, so every row of the group carries it by construction.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>The rule is the authoritative fallback, not {@code ERROR}.</b> Reading the execution
     * result alone made the reported level depend on which execution path produced it — and a path
     * that builds its result from scratch instead of stamping it silently reported {@code ERROR}
     * for a rule authored {@code Warning}. Measured on {@code testdata/study}: <b>15 rules / 944
     * finding rows</b> came out an ERROR they were not. The rule is in scope here, its
     * {@code Severity} is the authored fact, and reading it makes the report correct no matter how
     * the result was built.
     * </p>
     *
     * <p>
     * ⚑ <b>Plan C phase 4 closed the "first is representative" hazard at its source:</b> the level
     * is part of the grouping key, so a group can no longer span two levels and there is no "rest
     * of the group" left to mislabel.
     * </p>
     */
    private static Severity severityOf(@Nullable Severity aLevel, RuleExecutionResult aResult,
            Rule aRule)
    {
        if (aLevel != null)
        {
            return aLevel;
        }
        if (aResult.getSeverity() != null)
        {
            return aResult.getSeverity();
        }
        return aRule.effectiveSeverity();
    }


    /**
     * The record-key schema for a violation group — the first non-empty key's names.
     *
     * <p>
     * Every violation in a group comes from the same rule × dataset and therefore the same
     * {@code RowKeySpec}, so the names are identical across the group; taking the first non-empty
     * one is enough, and tolerates a group that mixes key-carrying and key-less violations.
     * </p>
     */
    private static List<String> keyNamesOf(List<Violation> aGroup)
    {
        for (Violation v : aGroup)
        {
            Map<String, String> keys = v.getKeys();
            if (keys != null && !keys.isEmpty())
            {
                return List.copyOf(keys.keySet());
            }
        }
        return List.of();
    }


    /**
     * D7 — drops key columns that are empty (or absent) for <em>every</em> row of the finding.
     *
     * <p>
     * The always-append sponsor identifiers ({@code --SPID}, {@code --REFID}) are Permissible and
     * routinely declared-but-unpopulated, so without this a wide, entirely blank key would be
     * emitted on every row. Dropping is per finding, not per dataset: a column blank across this
     * finding's rows but populated elsewhere is still keyed on the finding that can use it.
     * </p>
     */
    private static List<String> withoutAllEmptyColumns(List<String> aKeyNames,
            List<Violation> aGroup)
    {
        if (aKeyNames.isEmpty())
        {
            return aKeyNames;
        }
        List<String> out = new ArrayList<>(aKeyNames.size());
        for (String name : aKeyNames)
        {
            for (Violation v : aGroup)
            {
                Map<String, String> keys = v.getKeys();
                String value = keys == null ? null : keys.get(name);
                if (value != null && !value.isEmpty())
                {
                    out.add(name);
                    break;
                }
            }
        }
        return out;
    }


    /**
     * The key source label for a finding, or {@code null} when no key was resolved. The engine
     * resolves one tier per rule × dataset, so it is carried on the execution result rather than
     * repeated on every violation.
     */
    private static @Nullable String keySourceOf(RuleExecutionResult aResult, List<String> aKeyNames)
    {
        if (aKeyNames.isEmpty() || aResult.getKeySource() == null
                || aResult.getKeySource() == RecordKeyResolver.KeySource.NONE)
        {
            return null;
        }
        return aResult.getKeySource().name();
    }


    /**
     * Every variable name the rule itself projected into any violation's {@code values} — i.e. the
     * names that came from the rule's {@code Output_Variables} rather than from identity
     * augmentation.
     *
     * <p>
     * The union across the whole group is deliberate. EC-37's omit-don't-null contract means an
     * unresolved {@code Output_Variable} is absent from <em>some</em> rows' values, so sampling a
     * single violation could misclassify a genuinely declared {@code USUBJID} as injected and
     * wrongly strip it from the location.
     * </p>
     */
    private static Set<String> declaredNames(Rule aRule, List<Violation> aGroup)
    {
        Set<String> declared = new LinkedHashSet<>();
        // The rule's own declaration is authoritative and survives even when the variable did not
        // resolve on any row: EC-37 omits unresolved entries from `values`, so a rule declaring
        // USUBJID on a data set where it cannot be projected would otherwise look like injected
        // identity and be stripped from the location.
        List<String> authored = aRule.effectiveOutputVariablesOrAuthored();
        if (authored != null)
        {
            declared.addAll(authored);
        }
        for (Violation v : aGroup)
        {
            Map<String, String> values = v.getValues();
            if (values != null)
            {
                declared.addAll(values.keySet());
            }
        }
        return declared;
    }


    /**
     * Drops the identity keys {@link #withIdentity} injects, unless the rule declared them itself.
     */
    private static List<String> withoutInjectedIdentity(List<String> aNames, Set<String> aDeclared)
    {
        List<String> out = new ArrayList<>(aNames.size());
        for (String name : aNames)
        {
            boolean injectedIdentity = (USUBJID.equalsIgnoreCase(name)
                    || SEQ.equalsIgnoreCase(name)) && !aDeclared.contains(name);
            if (!injectedIdentity)
            {
                out.add(name);
            }
        }
        return out;
    }


    private static ValidationFinding buildEngineErrorFinding(String aDomain, Rule aRule,
            RuleExecutionResult aResult)
    {
        String message = aResult.getStatusMessage();
        if (message == null || message.isEmpty())
        {
            message = "Rule engine error";
        }
        return ValidationFinding.builder().source(SOURCE).ruleId(coreIdOf(aRule))
                .kind(FindingKind.ENGINE_ERROR).severity(Severity.ERROR)
                .executability(executabilityOf(aRule)).scope(FindingScope.DATASET).message(message)
                .variableNames(List.of()).location(ValidationFindingLocation.builder()
                        .dataset(aDomain).variableNames(List.of()).build())
                .rows(RowFindingSlab.EMPTY).build();
    }


    /**
     * Render the rule's declared {@link Executability} as the lower-cased Python form (e.g.
     * {@code "fully executable"}). Returns {@code null} when the rule does not declare one — the
     * Python report emits {@code null} for those rows too.
     */
    private static @Nullable String executabilityOf(Rule aRule)
    {
        Executability e = aRule.getExecutability();
        return e != null ? e.getPythonValue() : null;
    }

    /**
     * Partner keys for the {@code variable_name} collapse, in priority order (most specific wins).
     * When a violation's value map contains {@code variable_name} plus one of these keys, the two
     * are collapsed into a single schema entry named after the variable name's value (the actual
     * column in the data table); the partner's value becomes the row value for that slot. This is
     * what makes rules targeting a specific variable flag the correct table column in the UI.
     *
     * <p>
     * Covers both the record-level partner {@code variable_value} (cell-level findings) and the
     * five variable-level metadata attributes ({@code variable_name}, {@code variable_label},
     * {@code variable_data_type}, {@code variable_length}, {@code variable_format}).
     * </p>
     */
    private static final List<String> VARIABLE_NAME_PARTNERS = List.of("variable_value",
            "variable_label", "variable_data_type", "variable_length", "variable_format");

    /**
     * Populate {@code aOut} with the effective column names contributed by a single violation's
     * value map, applying the variable_name/partner collapse.
     *
     * <p>
     * When {@code variable_name} is present, its <em>value</em> is always added to the schema (so
     * findings flag the targeted column) regardless of whether a partner is present. The partner,
     * if any, is absorbed into the same column; all other keys are added as-is.
     * </p>
     */
    private static void collectSchema(Map<String, String> aValues, Set<String> aOut)
    {
        if (aValues == null || aValues.isEmpty())
        {
            return;
        }
        String varName = aValues.get(VARIABLE_NAME);
        boolean hasName = aValues.containsKey(VARIABLE_NAME) && varName != null
                && !varName.isBlank();
        String partnerKey = null;
        if (hasName)
        {
            for (String candidate : VARIABLE_NAME_PARTNERS)
            {
                if (aValues.containsKey(candidate))
                {
                    partnerKey = candidate;
                    break;
                }
            }
            // Always hoist the variable name's value into the schema so the UI can flag the
            // correct column, even when no known partner is present.
            aOut.add(varName);
        }
        for (Map.Entry<String, String> e : aValues.entrySet())
        {
            String key = e.getKey();
            if (hasName && VARIABLE_NAME.equals(key))
            {
                continue; // already represented by varName
            }
            if (hasName && partnerKey != null && partnerKey.equals(key))
            {
                continue; // partner value is carried on the varName slot
            }
            aOut.add(key);
        }
    }


    /**
     * Returns {@code aViolation.getValues()} augmented with {@code USUBJID}/{@code SEQ} from the
     * violation's identity fields when those keys aren't already present. Returns the original map
     * when no augmentation is needed (no identity, or both keys already populated by the rule's
     * Output_Variables).
     */
    private static Map<String, String> withIdentity(Violation aViolation)
    {
        String usubjid = aViolation.getUsubjid();
        String seq = aViolation.getSeq();
        Map<String, String> values = aViolation.getValues();
        if (usubjid == null && seq == null)
        {
            return values != null ? values : Map.of();
        }
        if (values == null)
        {
            Map<String, String> m = new LinkedHashMap<>();
            if (usubjid != null) m.put(USUBJID, usubjid);
            if (seq != null) m.put(SEQ, seq);
            return m;
        }
        boolean haveUsubjid = values.containsKey(USUBJID);
        boolean haveSeq = values.containsKey(SEQ);
        if ((haveUsubjid || usubjid == null) && (haveSeq || seq == null))
        {
            return values;
        }
        Map<String, String> m = new LinkedHashMap<>(values);
        if (usubjid != null && !haveUsubjid) m.put(USUBJID, usubjid);
        if (seq != null && !haveSeq) m.put(SEQ, seq);
        return m;
    }


    /**
     * Project a violation's value map onto the given name schema, applying the variable_name
     * collapse. Absent keys yield null.
     */
    private static @Nullable String[] rowValues(Map<String, String> aValues, List<String> aNames)
    {
        @Nullable
        String[] out = new String[aNames.size()];
        if (aValues == null || aValues.isEmpty() || aNames.isEmpty())
        {
            return out;
        }
        String varName = aValues.get(VARIABLE_NAME);
        boolean hasName = aValues.containsKey(VARIABLE_NAME) && varName != null
                && !varName.isBlank();
        String combinedValue = null;
        if (hasName)
        {
            for (String candidate : VARIABLE_NAME_PARTNERS)
            {
                if (aValues.containsKey(candidate))
                {
                    combinedValue = aValues.get(candidate);
                    break;
                }
            }
        }
        for (int i = 0; i < aNames.size(); i++)
        {
            String name = aNames.get(i);
            if (hasName && name.equals(varName))
            {
                out[i] = combinedValue; // partner value, or null when no partner was present
            }
            else if (aValues.containsKey(name))
            {
                out[i] = aValues.get(name);
            }
            else
            {
                out[i] = null;
            }
        }
        return out;
    }


    private static @Nullable String coreIdOf(Rule aRule)
    {
        return aRule.effectiveId();
    }


    /**
     * §3.3 of {@code PLAN-leaf-scope-domain-inference.md} (owner rulings 2 and 9): the
     * {@code FindingScope} is a pure projection of the rule's evaluation domain — {@code {}} ⇒
     * {@code DATASET} (one verdict per dataset; a {@code $}-only rule included), {@code {VAR}} ⇒
     * {@code VARIABLE}, any row-bearing domain ⇒ {@code RECORD}. {@code null} when the rule never
     * compiled natively (no domain) so the finding's own shape decides.
     */
    private static @Nullable FindingScope scopeOf(Rule aRule)
    {
        net.cumba.corej.core.expr.eval.Domain domain = aRule.getEvaluationDomain();
        if (domain == null)
        {
            return null;
        }
        if (domain.rowCursor())
        {
            return FindingScope.RECORD;
        }
        return domain.varCursor() ? FindingScope.VARIABLE : FindingScope.DATASET;
    }

    // ------------------------------------------------------------------
    // Accumulator
    // ------------------------------------------------------------------


    private MemberAccumulator accumulatorFor(String aDomain, @Nullable String aFileName)
    {
        String key = aDomain.toUpperCase(Locale.ROOT);
        MemberAccumulator acc = members.get(key);
        if (acc == null)
        {
            acc = new MemberAccumulator(aDomain, aFileName);
            members.put(key, acc);
        }
        else if (acc.fileName == null && aFileName != null)
        {
            acc.fileName = aFileName;
        }
        return acc;
    }

    private static final class MemberAccumulator
    {

        final String domain;

        @Nullable
        String fileName;

        final List<ValidationFinding> findings = new ArrayList<>();

        MemberAccumulator(String aDomain, @Nullable String aFileName)
        {
            domain = aDomain;
            fileName = aFileName;
        }


        void add(ValidationFinding aFinding)
        {
            findings.add(aFinding);
        }
    }

}
