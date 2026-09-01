package net.cumba.cdisc.core.exec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.cumba.cdisc.core.expr.MetadataOperandMapping;
import net.cumba.cdisc.core.expr.OperandKind;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.expr.eval.MetadataExprScan;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionAny;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.CheckConditionNot;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.OperationType;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.OutputVariableToken;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.Scope;
import org.jspecify.annotations.Nullable;

/**
 * Derives the effective {@code Outcome.Output_Variables} of a rule from its Check, its Operations
 * and its rule type — the load-time half of PLAN-auto-output-variables (EC-37).
 *
 * <p>
 * Two invariants (plan §1): the authored <em>include</em> entries are kept verbatim and in order —
 * the derivation only <em>appends</em> entries the author omitted (D1) — and no source file is ever
 * rewritten; the result lands on {@link Rule}'s runtime-only {@code effectiveOutputVariables}
 * field, installed by {@code RulePackageLoader#deriveOutputVariables}
 * ({@link net.cumba.cdisc.core.RulePackageLoader}). The one subtraction D1 admits is the author's
 * own: an {@code !X} entry ({@link OutputVariableToken}, E-2 of
 * {@code PLAN-authoring-grammar-unique-set-and-output-exclusion}) removes {@code X} from the
 * effective list <em>after</em> the derivation and both hoists — so an excluded variable is absent
 * on every projection path — and never creates an entry. The exclusion set is exposed by
 * {@link #excludedOf} and stored on {@code Rule#excludedOutputVariables} for the runtime fallbacks
 * that infer columns outside this class.
 * </p>
 *
 * <p>
 * Derivation is dataset-independent: {@code --} prefixes, {@code ${*}} wildcards and the
 * {@code additional_columns_*} numeric-suffix expansion are resolved by the existing runtime steps
 * in {@link RuleRunner}, not here. For the same reason the legacy-tree fallback (a rule the loader
 * could not raise to a native {@code checkExpr}) skips {@code additional_columns_*} leaves entirely
 * — their expansion needs table metadata, so they stay with the runtime fallback at the
 * {@link RuleRunner} call sites.
 * </p>
 */
public final class OutputVariableDeriver
{

    private OutputVariableDeriver()
    {
    }

    /**
     * §4.1 — operations whose result is a bulk list (codelist terms, column orders, study-wide name
     * lists): their {@code $id} is never derived, only honoured when authored (D1). The one
     * list-valued exception is {@code MINUS}, whose result <em>is</em> the finding (the missing
     * members), so it derives normally. Signed off 2026-07-29. Applied as a global post-filter on
     * every derived {@code $}-id, not merely a D4a gate — a bulk {@code $}-ref reaches the derived
     * set through the Check walk too (plan §11.3, CDISC-CG0014).
     */
    private static final EnumSet<OperationType> BULK_RESULT_OPERATIONS = EnumSet.of(
            OperationType.CODELIST_TERMS, OperationType.GET_CODELIST_ATTRIBUTES,
            OperationType.VALID_CODELIST_DATES, OperationType.DISTINCT,
            OperationType.EXTRACT_METADATA, OperationType.DATASET_NAMES,
            OperationType.STUDY_DOMAINS, OperationType.STANDARD_DOMAINS,
            OperationType.DEFINE_DATASET_NAMES, OperationType.VARIABLE_NAMES,
            OperationType.DEFINE_VARIABLE_NAMES, OperationType.EXPECTED_VARIABLES,
            OperationType.REQUIRED_VARIABLES, OperationType.GET_DATASET_FILTERED_VARIABLES,
            OperationType.GET_MODEL_FILTERED_VARIABLES, OperationType.DUPLICATE_LABEL_VARIABLES,
            OperationType.GET_COLUMN_ORDER_FROM_DATASET,
            OperationType.GET_COLUMN_ORDER_FROM_LIBRARY, OperationType.GET_MODEL_COLUMN_ORDER,
            OperationType.GET_PARENT_MODEL_COLUMN_ORDER, OperationType.NATURAL_KEY_VARIABLES,
            OperationType.DEFINE_KEY_VARIABLES, OperationType.CROSS_DATASET_VARIABLE_METADATA,
            OperationType.COLUMN_SERIES_METADATA);

    /**
     * D2c — functions whose trailing positional operands / {@code keys=[…]} list are COLUMN
     * references (grouping keys). Everywhere else a bare token in a value-list position is a
     * literal enumeration value (R-9.8): {@code contains_all(TSPARMCD, keys=[ADDON, …])} lists
     * required TS parameter <em>codes</em>, which {@code ExprCompiler#compileNotContainsAll} reads
     * as required values even though the same {@code keys=[…]} surface on {@code is_unique_set} is
     * a grouping-column list. The {@code within=[…]} / {@code by=[…]} kwargs are always
     * column-valued and are walked normally regardless of this set. Locked against the compiler's
     * reading by {@code OutputVariableDeriverTest}.
     */
    private static final Set<String> COLUMN_KEYS_FUNCTIONS = Set.of("is_unique_set",
            "is_not_unique_set", "is_unique_value", "is_not_unique_value", "is_unique_relationship",
            "is_not_unique_relationship", "is_inconsistent_across_dataset",
            "is_consistent_across_dataset", "has_multiple_values_for",
            "has_mixed_emptiness_within_group");

    /**
     * D5 — identity columns the engine attaches to every record-level finding out-of-band (R-9.6b,
     * {@code RuleRunner#readRowIdentity}). An explicit set, never a regex — SRCSEQ, IDVARVAL and
     * other {@code …SEQ}-suffixed non-identity variables must not match.
     */
    private static final Set<String> LOCATION_VARIABLES = Set.of("USUBJID", "ASEQ", "--SEQ");

    /**
     * {@code true} for a D5 location variable — the names an {@code !X} may not exclude (E-3.4).
     */
    public static boolean isLocationVariable(String name)
    {
        return LOCATION_VARIABLES.contains(name);
    }


    /**
     * {@code true} when {@code name} is a D5 location variable <em>for {@code rule}</em>: either a
     * member of the set verbatim, or the per-domain resolution of a wildcard member ({@code --SEQ})
     * against a domain the rule pins.
     *
     * <p>
     * ⚠ E-3.4 rejects {@code !--SEQ}, but the per-domain expansion
     * ({@code RuleGenerator#expandSdtmPrefixRules}) re-validates the <em>substituted</em> rule,
     * where the same authored token now reads {@code !LBSEQ} — not a member of the set, so the
     * verbatim test alone lets it through on the way out ({@code Fix #356}; latent, 0 carriers).
     * Resolving the wildcard members here against {@link #pinnedDomains} — the identical population
     * D5's own {@code <D>SEQ} removal walks in {@link #derivedSet} — makes the token a load error
     * on both sides of the expansion, with the same message. Both the full domain code and the
     * two-character prefix the expansion actually substitutes are matched, so a longer pinned
     * domain cannot slip past.
     * </p>
     */
    public static boolean isLocationVariable(@Nullable Rule rule, String name)
    {
        if (isLocationVariable(name))
        {
            return true;
        }
        if (rule == null)
        {
            return false;
        }
        for (String domain : pinnedDomains(rule))
        {
            String code = domain.toUpperCase(Locale.ROOT);
            for (String member : LOCATION_VARIABLES)
            {
                if (!member.startsWith("--"))
                {
                    continue;
                }
                String suffix = member.substring(2);
                if (name.equals(code + suffix)
                        || (code.length() >= 2 && name.equals(code.substring(0, 2) + suffix)))
                {
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * The effective Output_Variables for {@code rule}: the authored include entries verbatim and in
     * order, followed by every entry derivable from the rule's Check, Operations and rule type that
     * the author omitted (D1/D10), minus every name the author excluded with {@code !X}
     * ({@link OutputVariableToken}). {@code variable_name} is hoisted to index 0 and a derived
     * {@code variable_value} to index 1 on the per-variable path (D7/R-9.7) — the two reorders the
     * derivation performs; the exclusion is subtracted <em>last</em>, after both hoists, so
     * {@code !variable_name} removes the hoisted lead where {@code variable_name} is derived. The
     * result is deduplicated (R-9.10) and immutable.
     */
    public static List<String> derive(@Nullable Rule rule)
    {
        LinkedHashSet<String> effective = new LinkedHashSet<>(authoredOf(rule));
        effective.addAll(derivedSet(rule));
        List<String> out = new ArrayList<>(effective);
        boolean vlm = readsVlm(rule);
        if (vlm || perVariablePath(rule))
        {
            // D7 / R-9.7: variable_name leads; a derived variable_value follows it directly.
            boolean valueDerived = out.contains("variable_value")
                    && !authoredOf(rule).contains("variable_value");
            out.remove("variable_name");
            out.add(0, "variable_name");
            if (valueDerived)
            {
                out.remove("variable_value");
                out.add(1, "variable_value");
            }
        }
        // E-2: the author's exclusions come off LAST — after the union and after both hoists —
        // so nothing downstream can re-introduce an excluded name from this list.
        out.removeAll(excludedOf(rule));
        return List.copyOf(out);
    }


    /**
     * The names {@code rule}'s author excluded with {@code !X} entries (E-2), in authored order;
     * empty when there are none. Computed from the authored list alone — it is the same set whether
     * or not the derivation runs, which is what lets the runtime fallbacks
     * ({@code Rule#effectiveOutputVariablesOrAuthored}, {@code RuleRunner}'s Fix #15 inference)
     * honour it without a derived list.
     */
    public static Set<String> excludedOf(@Nullable Rule rule)
    {
        Outcome outcome = rule == null ? null : rule.getOutcome();
        return OutputVariableToken
                .exclusions(outcome == null ? null : outcome.getOutputVariables());
    }


    /**
     * The derived-only contribution ({@code DERIVED \ AUTHORED}), for reporting and linting. Both
     * sides are post-exclusion: {@link #derive} has already subtracted the {@code !X} names and
     * {@link #authoredOf} carries only the include entries, so an excluded name is on neither side.
     */
    public static List<String> derivedOnly(@Nullable Rule rule)
    {
        List<String> authored = authoredOf(rule);
        List<String> delta = new ArrayList<>();
        for (String name : derive(rule))
        {
            if (!authored.contains(name))
            {
                delta.add(name);
            }
        }
        return List.copyOf(delta);
    }

    // ------------------------------------------------------------------ assembly


    /**
     * The authored <em>include</em> entries, verbatim and in order — every {@code !X} exclusion
     * token stripped ({@link OutputVariableToken#includes}): an author writing {@code !X} is not
     * asking for {@code X}, so an exclusion never creates an entry.
     */
    private static List<String> authoredOf(@Nullable Rule rule)
    {
        Outcome outcome = rule == null ? null : rule.getOutcome();
        return OutputVariableToken.includes(outcome == null ? null : outcome.getOutputVariables());
    }


    /**
     * DERIVED after every derivation-side exclusion (D2–D8), in walk order — before the D1 merge
     * and before the author's {@code !X} subtraction. Public because it is the set E-3.1 judges an
     * exclusion against: {@code !X} is valid iff {@code X} is in here
     * ({@code RulePackageLoader#validateOutputVariableExclusions}). Contains {@code variable_name}
     * (and a value-read {@code variable_value}) on the per-variable / VLM path, so those are
     * excludable exactly where they are derived.
     */
    public static Set<String> derivedSet(@Nullable Rule rule)
    {
        Walk walk = new Walk();
        if (rule == null)
        {
            return Collections.unmodifiableSet(walk.derived);
        }
        // ⚑ Plan C §3.3: Outcome.Output_Variables is SHARED across a rule's check levels, so the
        // derivation is the join over every level — a name a weaker level reports must be in the
        // projection whichever level ends up claiming the row. One entry (and it IS getCheckExpr()
        // / getCheck()) for every rule that authors a plain Check:.
        List<Expr> exprs = checkExprsOf(rule);
        if (!exprs.isEmpty())
        {
            for (Expr expr : exprs)
            {
                walk.walk(expr, false, false);
            }
        }
        else
        {
            for (CheckCondition level : rule.checkConditions())
            {
                walkLegacy(level, walk);
            }
        }
        contributeOperations(rule, walk);

        // D3 — a name seen ONLY under negated existence contributes nothing; a name that also
        // occurs positively is kept, and the suppression extends to operation-input entries.
        walk.suppressed.removeAll(walk.checkPositive);
        walk.derived.removeAll(walk.suppressed);

        // §4.1 — global post-filter on $-ids, wherever they entered the derived set.
        walk.derived.removeAll(bulkOperationIds(rule));

        // D5 — location variables; <D>SEQ for every concretely pinned scope domain.
        walk.derived.removeAll(LOCATION_VARIABLES);
        for (String domain : pinnedDomains(rule))
        {
            walk.derived.remove(domain.toUpperCase(Locale.ROOT) + "SEQ");
        }

        // D6 — variable-scope virtuals only where a per-variable path can resolve them
        // (dataset-scope virtuals derive unconditionally; Phase 3 resolves them).
        if (!perVariablePath(rule))
        {
            walk.derived.removeIf(OutputVariableDeriver::isVariableScopeName);
        }
        walk.derived.remove("dataset_metadata");

        // D7 — the per-variable / VLM guarantees (D10 ordering is applied in derive()).
        boolean vlm = readsVlm(rule);
        if (vlm || perVariablePath(rule))
        {
            walk.derived.add("variable_name");
            if (vlm || exprs.stream().anyMatch(MetadataExprScan::usesCurrentVariableValue))
            {
                walk.derived.add("variable_value");
            }
        }
        return Collections.unmodifiableSet(walk.derived);
    }


    /**
     * Whether the Check reads the Define-XML value-level metadata — a {@code vlm_*} accessor or a
     * bare {@code define_vlm_*} operand — the CERTAIN content signal that used to be the
     * {@code Value Check against Define XML VLM} type (leaf-scope phase 7: the VLM output-variable
     * ordering is re-keyed on the operand, never on a type).
     */
    private static boolean readsVlm(@Nullable Rule rule)
    {
        if (rule == null)
        {
            return false;
        }
        List<Expr> exprs = checkExprsOf(rule);
        if (!exprs.isEmpty())
        {
            return exprs.stream().anyMatch(OutputVariableDeriver::readsVlm);
        }
        return rule.checkConditions().stream().anyMatch(OutputVariableDeriver::checkReadsVlm);
    }


    /**
     * Every declared check level's compiled expression, strictest first (Plan C &#167;3.3) — a
     * one-element list holding exactly {@code rule.getCheckExpr()} for every single-level rule, and
     * empty when nothing compiled.
     */
    private static List<Expr> checkExprsOf(@Nullable Rule rule)
    {
        if (rule == null)
        {
            return List.of();
        }
        Map<net.cumba.datatable.report.Severity, Expr> levels = rule.getCheckLevelExprs();
        if (levels != null && !levels.isEmpty())
        {
            return List.copyOf(levels.values());
        }
        Expr expr = rule.getCheckExpr();
        return expr == null ? List.of() : List.of(expr);
    }


    private static boolean readsVlm(Expr e)
    {
        return switch (e)
        {
        case Expr.Call c -> c.name().startsWith("vlm_")
                || c.args().stream().anyMatch(OutputVariableDeriver::readsVlm)
                || c.kwargs().values().stream().anyMatch(OutputVariableDeriver::readsVlm);
        case Expr.And a -> a.parts().stream().anyMatch(OutputVariableDeriver::readsVlm);
        case Expr.Or o -> o.parts().stream().anyMatch(OutputVariableDeriver::readsVlm);
        case Expr.Not n -> readsVlm(n.inner());
        case Expr.Binary b -> readsVlm(b.left()) || readsVlm(b.right());
        case Expr.Ref r -> r.name().startsWith("define_vlm_");
        case Expr.Lit _ -> false;
        };
    }


    private static boolean checkReadsVlm(CheckCondition check)
    {
        return switch (check)
        {
        case CheckConditionAll all -> all.getConditions().stream()
                .anyMatch(OutputVariableDeriver::checkReadsVlm);
        case CheckConditionAny any -> any.getConditions().stream()
                .anyMatch(OutputVariableDeriver::checkReadsVlm);
        case CheckConditionNot not -> checkReadsVlm(not.getCondition());
        case CheckConditionLeaf leaf -> (leaf.getName() != null
                && leaf.getName().startsWith("define_vlm_"))
                || (leaf.getValue() != null && leaf.getValue().isTextual()
                        && leaf.getValue().asText().startsWith("define_vlm_"));
        default -> false;
        };
    }


    /** D6/D7 — the finding will be built by a per-variable projection path (plan §2.2). */
    private static boolean perVariablePath(@Nullable Rule rule)
    {
        // ⚑ Plan C §3.3: the per-variable projection is a property of the RULE — every level
        // shares one evaluation domain (the join) — so any level demanding the VAR cursor puts the
        // whole rule on the per-variable path.
        return checkExprsOf(rule).stream()
                .anyMatch(expr -> MetadataExprScan.containsMetadataFunction(expr)
                        || MetadataExprScan.containsVarname(expr)
                        || MetadataExprScan.containsVariableNameAnchor(expr)
                        || MetadataExprScan.usesCurrentVariableValue(expr));
    }


    private static boolean isVariableScopeName(String name)
    {
        return name.startsWith("variable_") || name.startsWith("library_variable_")
                || name.startsWith("define_variable_") || name.startsWith("define_vlm_");
    }


    private static Set<String> bulkOperationIds(Rule rule)
    {
        List<Operation> operations = rule.getOperations();
        if (operations == null)
        {
            return Set.of();
        }
        Set<String> ids = new HashSet<>();
        for (Operation op : operations)
        {
            if (op == null || op.getId() == null)
            {
                continue;
            }
            OperationType type = OperationType.fromJson(op.getOperator());
            if (type != null && BULK_RESULT_OPERATIONS.contains(type))
            {
                ids.add(op.getId());
            }
        }
        return ids;
    }


    private static List<String> pinnedDomains(Rule rule)
    {
        Scope scope = rule.getScope();
        if (scope == null || scope.getDomains() == null || scope.getDomains().getInclude() == null)
        {
            return List.of();
        }
        List<String> pinned = new ArrayList<>();
        for (String domain : scope.getDomains().getInclude())
        {
            if (domain != null && !domain.isBlank() && !"ALL".equalsIgnoreCase(domain))
            {
                pinned.add(domain);
            }
        }
        return pinned;
    }

    // ------------------------------------------------------------------ D4 operations


    private static void contributeOperations(Rule rule, Walk walk)
    {
        List<Operation> operations = rule.getOperations();
        if (operations == null)
        {
            return;
        }
        List<String> pinned = pinnedDomains(rule);
        for (Operation op : operations)
        {
            if (op == null || op.getId() == null)
            {
                continue;
            }
            // D4a — every surviving operation result; the §4.1 post-filter prunes bulk ids.
            walk.derived.add(op.getId());
            // D4b — inputs only when the operation reads the evaluation dataset. A
            // foreign-domain operation's inputs are not columns of the dataset the finding is
            // on; they are already represented by the $id result.
            String domain = op.getDomain();
            boolean local = domain == null || domain.isBlank()
                    || pinned.stream().anyMatch(d -> d.equalsIgnoreCase(domain));
            if (!local)
            {
                continue;
            }
            addName(walk, op.getName());
            addNames(walk, op.getNames());
            addNames(walk, op.getGroup());
            addName(walk, op.getReference());
            addName(walk, op.getOrdering());
            addName(walk, op.getKeyName());
            addName(walk, op.getExternalDictionaryTermVariable());
            addName(walk, op.getDictionaryParent());
            addNames(walk, op.getQualifyingAnyPopulated());
            addNames(walk, op.getMinuendMatch());
            String offset = op.getOffset();
            if (offset != null && !offset.isBlank() && !offset.matches("-?\\d+"))
            {
                walk.derived.add(offset);
            }
            addName(walk, op.getSubtract());
            if (op.getFilter() != null)
            {
                // filter KEYS are columns of the operation's dataset; filter VALUES are literals
                for (String key : op.getFilter().keySet())
                {
                    addName(walk, key);
                }
            }
        }
    }


    private static void addName(Walk walk, @Nullable String name)
    {
        if (name != null && !name.isBlank())
        {
            walk.derived.add(name);
        }
    }


    private static void addNames(Walk walk, @Nullable List<String> names)
    {
        if (names != null)
        {
            for (String name : names)
            {
                addName(walk, name);
            }
        }
    }

    // ------------------------------------------------------------------ D2 native walk

    private static final class Walk
    {

        /** Everything contributed so far, in first-encounter order. */
        final LinkedHashSet<String> derived = new LinkedHashSet<>();

        /** Names seen positively in the Check — immune to D3 suppression. */
        final Set<String> checkPositive = new HashSet<>();

        /** Names seen under a negated-existence position (D3). */
        final Set<String> suppressed = new LinkedHashSet<>();

        /**
         * @param negatedExistence
         *            inside {@code not var_exists(…)} / {@code var_not_exists(…)} — D3.
         * @param inValueList
         *            inside a list position whose members are VALUES, not columns — D2c; bare refs
         *            contribute nothing there, {@code $}-refs still do.
         */
        void walk(Expr e, boolean negatedExistence, boolean inValueList)
        {
            switch (e)
            {
            case Expr.And a ->
            {
                for (Expr part : a.parts())
                {
                    walk(part, negatedExistence, false);
                }
            }
            case Expr.Or o ->
            {
                for (Expr part : o.parts())
                {
                    walk(part, negatedExistence, false);
                }
            }
            case Expr.Not n -> walk(n.inner(), negatedExistence || isExistenceCall(n.inner()),
                    false);
            case Expr.Binary b ->
            {
                walk(b.left(), negatedExistence, false);
                // D2c — the RHS of an in / not in comparison is a value list
                boolean rhsValues = b.op() == Expr.BinOp.IN || b.op() == Expr.BinOp.NOT_IN;
                walk(b.right(), negatedExistence, rhsValues);
            }
            case Expr.Lit l ->
            {
                // D2 — a list literal can hold refs; other literals are not variable
                // references (R-9.8).
                if (l.kind() == Expr.LitKind.LIST && l.value() instanceof List<?> items)
                {
                    for (Object item : items)
                    {
                        if (item instanceof Expr inner)
                        {
                            walk(inner, negatedExistence, inValueList);
                        }
                    }
                }
            }
            case Expr.Ref r ->
            {
                // D2c — a bare token in a value list is a literal, not a column; a $-ref
                // still names a materialised operation result.
                if (!inValueList || r.kind() == OperandKind.OPERATION_REF)
                {
                    contribute(r.name(), negatedExistence);
                }
            }
            case Expr.Call c -> walkCall(c, negatedExistence);
            }
        }


        private void walkCall(Expr.Call c, boolean negatedExistence)
        {
            if ("ds_exists".equals(c.name()) || "ds_not_exists".equals(c.name()))
            {
                return; // D8 — the operand names a dataset, never a reportable variable
            }
            boolean neg = negatedExistence || isNegatedExistenceCall(c);
            String operand = MetadataOperandMapping.reverseAnyAccessor(c);
            if (operand != null)
            {
                contribute(operand, neg);
            }
            boolean columnKeys = COLUMN_KEYS_FUNCTIONS.contains(c.name());
            List<Expr> args = c.args();
            for (int i = 0; i < args.size(); i++)
            {
                // arg 0 is always the subject reference; later positionals follow the D2c role
                walk(args.get(i), neg, i > 0 && !columnKeys);
            }
            for (Map.Entry<String, Expr> kwarg : c.kwargs().entrySet())
            {
                walk(kwarg.getValue(), neg, "keys".equals(kwarg.getKey()) && !columnKeys);
            }
        }


        void contribute(@Nullable String name, boolean negatedExistence)
        {
            if (name == null || name.isBlank())
            {
                return;
            }
            if (negatedExistence)
            {
                suppressed.add(name);
            }
            else
            {
                checkPositive.add(name);
                derived.add(name);
            }
        }


        private static boolean isExistenceCall(Expr e)
        {
            return e instanceof Expr.Call c
                    && ("var_exists".equals(c.name()) || "ds_exists".equals(c.name()));
        }


        private static boolean isNegatedExistenceCall(Expr.Call c)
        {
            return "var_not_exists".equals(c.name()) || "ds_not_exists".equals(c.name());
        }
    }

    // ------------------------------------------------------------------ D2 legacy fallback

    /**
     * Fix #15 behaviour for rules with no native {@code checkExpr}: leaf targets, skipping
     * {@code var_not_exists} leaves (D3), {@code additional_columns_*} leaves (runtime-expanded —
     * see the class javadoc) and the {@code ds_*} presence leaves, whose name is a dataset
     * identifier (D8).
     */
    private static void walkLegacy(CheckCondition check, Walk walk)
    {
        switch (check)
        {
        case CheckConditionAll all ->
        {
            for (CheckCondition c : all.getConditions())
            {
                walkLegacy(c, walk);
            }
        }
        case CheckConditionAny any ->
        {
            for (CheckCondition c : any.getConditions())
            {
                walkLegacy(c, walk);
            }
        }
        case CheckConditionNot not -> walkLegacy(not.getCondition(), walk);
        case CheckConditionLeaf leaf ->
        {
            String name = leaf.getName();
            String operator = leaf.getOperator();
            if (name != null && !name.isEmpty() && !"var_not_exists".equals(operator)
                    && !"ds_not_exists".equals(operator) && !"ds_exists".equals(operator)
                    && !"additional_columns_empty".equals(operator)
                    && !"additional_columns_not_empty".equals(operator))
            {
                walk.contribute(name, false);
            }
        }
        default ->
        {
            // CheckConditionConstant / CheckConditionExpression — no leaf columns here
        }
        }
    }
}
