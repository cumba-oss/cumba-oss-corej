package net.cumba.cdisc.core.expr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.expr.eval.MetadataAttribute;
import net.cumba.cdisc.core.expr.eval.MetadataLevel;
import org.jspecify.annotations.Nullable;

/**
 * The bijective mapping between the engine's metadata <em>operands</em> ({@code variable_*} /
 * {@code library_variable_*} / {@code define_variable_*} and the {@code dataset_*} /
 * {@code library_dataset_*} / {@code define_dataset_*} families) and the {@code var_*} /
 * {@code ds_*} <em>accessor functions</em>.
 *
 * <p>
 * {@link CheckToExpr} uses {@link #forwardOperand} to prefer the function form when raising a Check
 * to the {@link Expr} IR; {@link ExprLowering} uses {@link #reverseToOperand} to lower the
 * {@code variable_name}-anchored / current-dataset function form back to the exact operand name, so
 * the {@code Check → Expr → Check} round-trip is preserved. The arbitrary-literal function form
 * (e.g. {@code var_label("AESTDTC", "DEFINE")}) has no operand and reverses to {@code null}
 * (native-only).
 * </p>
 *
 * <p>
 * Operands with no function equivalent — {@code variable_name} (the anchor itself),
 * {@code variable_value} (a row value), and {@code dataset_metadata} (loadError-tagged, R-P4) — are
 * left as operands ({@link #forwardOperand} returns {@code null}). The former Tier-B exclusions
 * {@code define_variable_ccode} / {@code define_variable_codelist_coded_codes} map to
 * {@code var_ccode} / {@code var_codelist_coded_codes} since R-P3
 * ({@code plans/done/PLAN-native-engine-residuals.md}).
 * </p>
 */
public final class MetadataOperandMapping
{

    private record Prefix(String text, MetadataLevel level, MetadataAttribute.Scope scope)
    {
    }

    /** Operand prefixes, longest-first so {@code library_variable_} wins over {@code variable_}. */
    private static final List<Prefix> PREFIXES = List.of(
            new Prefix("library_variable_", MetadataLevel.LIBRARY,
                    MetadataAttribute.Scope.VARIABLE),
            new Prefix("define_variable_", MetadataLevel.DEFINE, MetadataAttribute.Scope.VARIABLE),
            new Prefix("variable_", MetadataLevel.DATA, MetadataAttribute.Scope.VARIABLE),
            new Prefix("library_dataset_", MetadataLevel.LIBRARY, MetadataAttribute.Scope.DATASET),
            new Prefix("define_dataset_", MetadataLevel.DEFINE, MetadataAttribute.Scope.DATASET),
            new Prefix("dataset_", MetadataLevel.DATA, MetadataAttribute.Scope.DATASET));

    private static final String VARIABLE_NAME = "variable_name";

    /** Fix #123 — the define variable-level paired code/decode match (operand == accessor). */
    private static final String DEFINE_VARIABLE_DECODE_MATCHES = "define_variable_decode_matches";

    /** E9 — the library-level paired code/decode match operand and accessor share this name. */
    private static final String LIBRARY_CODE_PAIR_MATCHES = "library_variable_code_pair_matches";

    /**
     * VLM operand -&gt; per-record {@code vlm_*} accessor function name. These Define-XML
     * value-level operands have no {@code var_*} cousin (the applicable codelist / type / length is
     * chosen per-record by the value-level WhereClause), so they are mapped explicitly, mirroring
     * the Python VLM frame's {@code define_vlm_*} column names. See
     * {@code ExprCompiler#compileVlmAccessor}.
     */
    private static final Map<String, String> VLM_OPERAND_SUFFIXES = Map.ofEntries(
            Map.entry("variable_value_length", "vlm_value_length"),
            Map.entry("define_vlm_data_type", "vlm_data_type"),
            Map.entry("define_vlm_length", "vlm_length"),
            Map.entry("define_vlm_mandatory", "vlm_mandatory"),
            Map.entry("define_vlm_codelist_coded_values", "vlm_codelist_coded_values"),
            Map.entry("define_vlm_codelist_coded_codes", "vlm_codelist_coded_codes"),
            Map.entry("define_vlm_type_conforms", "vlm_type_conforms"),
            Map.entry("define_vlm_codelist_extensible", "vlm_codelist_extensible"),
            Map.entry("define_vlm_has_codelist", "vlm_has_codelist"),
            Map.entry("define_vlm_decode_matches", "vlm_decode_matches"));

    private MetadataOperandMapping()
    {
    }


    /**
     * The {@code var_*} / {@code ds_*} call for a metadata operand name, or {@code null} when the
     * operand has no function equivalent (so the caller keeps it as an operand reference).
     */
    public static @Nullable Expr forwardOperand(String operand)
    {
        // The anchor itself and the row value are never migrated.
        if (VARIABLE_NAME.equals(operand) || "variable_value".equals(operand))
        {
            return null;
        }
        // T5b — the two length facts the Python reference engine exposes on a Variable Metadata
        // Check's variables-metadata frame have no `var_<suffix>` cousin (their suffixes `size` /
        // `max_size` are not the accessor names `length` / a max reader), so they are mapped
        // explicitly. `variable_size` (declared length) reads the DATA-level `var_length` accessor
        // that already ships; `variable_max_size` (max stored value length) reads the new
        // `max_value_length` cursor value function. Both anchor on the current variable
        // (variable_name), mirroring every other variable-scope operand.
        if ("variable_size".equals(operand))
        {
            Expr nameRef = new Expr.Ref(VARIABLE_NAME, OperandKind.BUILTIN);
            Expr levelLit = new Expr.Lit(Expr.LitKind.STRING, MetadataLevel.DATA.name());
            return new Expr.Call("var_length", List.of(nameRef, levelLit), Map.of());
        }
        if ("variable_max_size".equals(operand))
        {
            Expr nameRef = new Expr.Ref(VARIABLE_NAME, OperandKind.BUILTIN);
            return new Expr.Call("max_value_length", List.of(nameRef), Map.of());
        }
        // VLM (Value Check against Define XML VLM): each define value-level fact — and the value's
        // type-aware stored length (variable_value_length ⇒ vlm_value_length, mirroring the Python
        // VLM builder's type-aware calculate_variable_value_length) — maps to a per-record vlm_*
        // accessor anchored on the current variable. See ExprCompiler#compileVlmAccessor.
        if (VLM_OPERAND_SUFFIXES.containsKey(operand))
        {
            Expr nameRef = new Expr.Ref(VARIABLE_NAME, OperandKind.BUILTIN);
            return new Expr.Call(VLM_OPERAND_SUFFIXES.get(operand), List.of(nameRef), Map.of());
        }
        // E9 (Value Check against Library Metadata): the library-level paired code/decode match has
        // no var_* cousin (it correlates two variables' CDISC-CT concept ids), so it is mapped
        // explicitly to the per-record library_variable_code_pair_matches accessor anchored on the
        // current (code) variable. See ExprCompiler#compileLibraryCodePairMatches.
        if (LIBRARY_CODE_PAIR_MATCHES.equals(operand))
        {
            Expr nameRef = new Expr.Ref(VARIABLE_NAME, OperandKind.BUILTIN);
            return new Expr.Call(LIBRARY_CODE_PAIR_MATCHES, List.of(nameRef), Map.of());
        }
        // Fix #123 (Value Check against Define XML Variable): the variable-level paired
        // code/decode match correlates two variables, so like its VLM and library cousins it has
        // no var_* form and is mapped explicitly to a varname()-anchored call of the same name.
        // Must stay AHEAD of the PREFIXES loop, which would otherwise rewrite the
        // define_variable_ prefix to var_decode_matches(variable_name, "DEFINE").
        if (DEFINE_VARIABLE_DECODE_MATCHES.equals(operand))
        {
            Expr nameRef = new Expr.Ref(VARIABLE_NAME, OperandKind.BUILTIN);
            return new Expr.Call(DEFINE_VARIABLE_DECODE_MATCHES, List.of(nameRef), Map.of());
        }
        Prefix match = null;
        for (Prefix p : PREFIXES)
        {
            if (operand.startsWith(p.text()))
            {
                match = p;
                break;
            }
        }
        if (match == null)
        {
            return null;
        }
        String fn = functionName(match.scope(), operand.substring(match.text().length()));
        MetadataAttribute attr = MetadataAttribute.fromFunction(fn);
        if (attr == null || attr.scope() != match.scope() || !attr.supports(match.level()))
        {
            return null; // e.g. dataset_metadata, define_variable_ccode — no accessor
        }
        Expr levelLit = new Expr.Lit(Expr.LitKind.STRING, match.level().name());
        if (match.scope() == MetadataAttribute.Scope.VARIABLE)
        {
            Expr nameRef = new Expr.Ref(VARIABLE_NAME, OperandKind.BUILTIN);
            return new Expr.Call(fn, List.of(nameRef, levelLit), Map.of());
        }
        return new Expr.Call(fn, List.of(levelLit), Map.of());
    }


    /**
     * Canonicalizes every <em>bare metadata-operand reference</em> in {@code e} to its
     * {@code var_*} / {@code ds_*} accessor call, leaving the rest of the tree intact. A bare
     * {@link Expr.Ref} of kind {@link OperandKind#BUILTIN} whose name maps via
     * {@link #forwardOperand} (e.g. {@code variable_label}, {@code library_variable_data_type},
     * {@code define_variable_format}) becomes the equivalent {@code var_<attr>(variable_name,
     * LEVEL)} accessor; the {@code variable_name} anchor and {@code variable_value} (which both
     * reverse to {@code null} from {@link #forwardOperand}) and every non-metadata reference are
     * preserved verbatim.
     *
     * <p>
     * This is the load-time bridge (Epic B4) that lets an operand-based Variable-Metadata-Check
     * rule whose operands were raised to bare references in non-comparison positions — inside
     * {@code len(variable_label)}, a regex match on {@code variable_label}, a {@code non_empty}
     * predicate, a membership LHS, etc., where {@link CheckToExpr#leafToExpr} emits a plain
     * {@code ref(name)} rather than the metadata-preferring {@code migratingRef} — still route to
     * the native metadata-broadcast path, exactly as a rule whose operand sat in a plain comparison
     * position already does. The rewrite is semantics-preserving: the bare operand and the accessor
     * resolve to the same per-variable metadata cell (see {@code RuleRunner}'s
     * {@code buildVariableMetadata} vs {@code ExprCompiler}'s {@code metadataPlan}).
     * </p>
     */
    public static Expr canonicalizeMetadataOperands(Expr e)
    {
        return switch (e)
        {
        case Expr.Ref r ->
        {
            if (r.kind() == OperandKind.BUILTIN)
            {
                // R-P2: the row-count fact has no var_*/ds_* accessor family — it maps to the
                // dedicated record_count() builtin (the native mirror of the legacy dataset
                // fold's "record_count" read).
                if ("record_count".equals(r.name()))
                {
                    yield new Expr.Call("record_count", List.of(), Map.of());
                }
                Expr migrated = forwardOperand(r.name());
                if (migrated != null)
                {
                    yield migrated;
                }
            }
            yield r;
        }
        case Expr.Call c ->
        {
            // A var_*/ds_* accessor's own arguments (the variable_name anchor, level literal) must
            // not be rewritten — forwardOperand already returns null for variable_name, so the
            // generic recursion below is safe, but recursing is still required for value calls such
            // as len(variable_label) whose argument is the operand to migrate. The tree is rebuilt
            // unconditionally (records are cheap; this runs once at load), keeping the recursion
            // free of reference-equality short-circuits.
            yield new Expr.Call(c.name(), mapList(c.args()), mapMap(c.kwargs()));
        }
        case Expr.Binary b ->
        {
            Expr left = canonicalizeMetadataOperands(b.left());
            Expr right = canonicalizeMetadataOperands(b.right());
            // CORE-001079 class: in the metadata families a varname() comparison's textual RHS is
            // ALWAYS a literal in the legacy per-variable cascade (evaluateLeafAgainstMetadata
            // resolves the value against the metadata map and otherwise falls back to the literal
            // string — it never reads a data column). A rule authored without value_is_literal
            // raises that RHS as a bare COLUMN reference, which would make the expression impure
            // (and read per-row data if a same-named column existed). Canonicalize it to the
            // string literal the cascade actually compares against.
            if (isVarnameCall(left) && right instanceof Expr.Ref r && r.kind() == OperandKind.COLUMN
                    && !isCascadeResolvableName(r.name()))
            {
                right = new Expr.Lit(Expr.LitKind.STRING, r.name());
            }
            yield new Expr.Binary(b.op(), left, right);
        }
        case Expr.And a -> new Expr.And(mapList(a.parts()));
        case Expr.Or o -> new Expr.Or(mapList(o.parts()));
        case Expr.Not n -> new Expr.Not(canonicalizeMetadataOperands(n.inner()));
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST)
            {
                @SuppressWarnings("unchecked")
                List<Expr> items = (List<Expr>) lit.value();
                yield new Expr.Lit(Expr.LitKind.LIST, mapList(items));
            }
            yield lit;
        }
        };
    }


    /**
     * R-P2 ({@code plans/done/PLAN-native-engine-residuals.md}): the <b>dataset-facts-only</b>
     * canonicalization for the NON-metadata rule types (Record Data, Domain Presence, …). Only the
     * dataset-level facts the legacy Step-1 fold reads — {@code dataset_*} /
     * {@code library_dataset_*} / {@code define_dataset_*} (→ their {@code ds_*} accessor) and
     * {@code record_count} (→ the {@code record_count()} builtin) — are rewritten; every other
     * reference (columns, {@code $}-operations, variable-scope operands) is preserved verbatim, so
     * the row-level native path stays bit-identical. This makes pure dataset-fact checks
     * broadcast-flaggable (fold-equivalent) and mixed checks evaluate the fact leaf natively with
     * the same value the legacy fold substitutes.
     */
    public static Expr canonicalizeDatasetFacts(Expr e)
    {
        return canonicalizeFacts(e, false, false);
    }

    /**
     * Boolean-call names whose legacy operators the dataset fold supports
     * ({@code CheckConditionOptimizer.SUPPORTED_METADATA_OPERATORS}); their leading argument is a
     * fact-resolvable NAME position.
     */
    private static final java.util.Set<String> FOLD_BOOL_CALLS = java.util.Set.of("empty",
            "non_empty", "contains", "not_contains", "starts_with", "ends_with", "matches",
            "not_matches", "prefix_matches", "suffix_matches");

    /**
     * Pure value wrappers whose legacy operators are fold-supported ({@code len} for
     * longer/shorter_than, {@code upper}/{@code lower} for the case-insensitive variants,
     * {@code str} for type-insensitive equality, {@code prefix}/{@code suffix} for the affix
     * family); their leading argument inherits the enclosing fact-resolvability.
     */
    private static final java.util.Set<String> FOLD_VALUE_WRAPPERS = java.util.Set.of("len",
            "length", "upper", "upcase", "lower", "lowcase", "str", "prefix", "suffix");

    /** The presence calls whose argument is a name, never a fact to canonicalize. */
    private static final java.util.Set<String> PRESENCE_CALLS = java.util.Set.of("ds_exists",
            "ds_not_exists", "var_exists", "var_not_exists");

    /**
     * Position- and predicate-aware rewrite for the NON-metadata rule types (R-P2 + R-P7 review
     * M2). A dataset fact is kept in (or rewritten to) its accessor form ONLY where the legacy
     * engine actually resolves the fact — the leaf's NAME position ({@code valuePos == false})
     * under a fold-supported predicate ({@code allowFact == true}: comparisons/regex/membership
     * {@code Binary}s, the {@link #FOLD_BOOL_CALLS} predicates, through
     * {@link #FOLD_VALUE_WRAPPERS}) and ONLY for the facts the DATA-level fold reads
     * ({@code dataset_name} / {@code dataset_label} / {@code record_count}). Everywhere else the
     * accessor the raise (D6 {@code migratingRef}) may have emitted is DEMOTED back to the bare
     * operand, restoring the legacy behaviour verbatim: VALUE positions resolve via the literal
     * fallback; name positions under non-fold predicates (date/length-equality/…) and the non-DATA
     * facts ({@code dataset_class}, {@code library_dataset_*}, {@code define_dataset_*}, which the
     * non-metadata legacy paths resolve as a missing column / empty ctx variable) never read the
     * real fact. Presence-call arguments are untouched ({@code compileExists} expects a bare
     * reference or a name literal).
     */
    private static Expr canonicalizeFacts(Expr e, boolean valuePos, boolean allowFact)
    {
        return switch (e)
        {
        case Expr.Ref r ->
        {
            if (!valuePos && allowFact && r.kind() == OperandKind.BUILTIN
                    && isFoldFactName(r.name()))
            {
                if ("record_count".equals(r.name()))
                {
                    yield new Expr.Call("record_count", List.of(), Map.of());
                }
                Expr migrated = forwardOperand(r.name());
                if (migrated != null)
                {
                    yield migrated;
                }
            }
            yield r;
        }
        case Expr.Call c ->
        {
            if (PRESENCE_CALLS.contains(c.name()))
            {
                yield c;
            }
            // Demote a dataset-scope accessor (raised by migratingRef) wherever the legacy
            // engine does NOT resolve the fact: any value position, any non-fold predicate
            // context, and any fact outside the DATA-level fold trio.
            MetadataAttribute attr = MetadataAttribute.fromFunction(c.name());
            if (attr != null && attr.scope() == MetadataAttribute.Scope.DATASET)
            {
                String operand = reverseToOperand(c);
                if (operand != null && (valuePos || !allowFact || !isFoldFactName(operand)))
                {
                    yield new Expr.Ref(operand, OperandKind.BUILTIN);
                }
                yield c; // a kept accessor's own (literal) args need no rewrite
            }
            boolean leadAllow = FOLD_BOOL_CALLS.contains(c.name())
                    || (FOLD_VALUE_WRAPPERS.contains(c.name()) && allowFact);
            List<Expr> args = new ArrayList<>(c.args().size());
            for (int i = 0; i < c.args().size(); i++)
            {
                // The leading argument carries the leaf's NAME position (len(x), prefix(x, n),
                // matches(x, p), contains(x, s) — all raised with the name first); the rest are
                // value-position operands.
                args.add(
                        canonicalizeFacts(c.args().get(i), valuePos || i > 0, i == 0 && leadAllow));
            }
            yield new Expr.Call(c.name(), args, mapFactsMap(c.kwargs()));
        }
        case Expr.Binary b ->
        {
            boolean foldOp = switch (b.op())
            {
            case EQ, NEQ, LT, GT, LE, GE, MATCH, NMATCH, IN, NOT_IN -> true;
            case ADD, SUB, MUL, DIV -> false; // no legacy arithmetic leaves — never fold-resolved
            };
            yield new Expr.Binary(b.op(), canonicalizeFacts(b.left(), valuePos, foldOp),
                    canonicalizeFacts(b.right(), true, false));
        }
        case Expr.And a -> new Expr.And(mapFacts(a.parts(), valuePos));
        case Expr.Or o -> new Expr.Or(mapFacts(o.parts(), valuePos));
        case Expr.Not n -> new Expr.Not(canonicalizeFacts(n.inner(), valuePos, false));
        case Expr.Lit lit ->
        {
            if (lit.kind() == Expr.LitKind.LIST)
            {
                @SuppressWarnings("unchecked")
                List<Expr> items = (List<Expr>) lit.value();
                yield new Expr.Lit(Expr.LitKind.LIST, mapFacts(items, true));
            }
            yield lit;
        }
        };
    }


    /**
     * The DATA-level facts that resolve at dataset level on the NON-metadata rule types: the three
     * the legacy dataset fold read ({@code dataset_name}, {@code dataset_label},
     * {@code record_count}) plus {@code dataset_domain}. Deliberately NOT {@code dataset_class} (a
     * row-level column miss on the non-metadata legacy paths) nor {@code library_dataset_*} /
     * {@code define_dataset_*} (ctx variables injected only for Dataset-Metadata-Check rules).
     *
     * <p>
     * ⚠⚠ <b>This predicate — not the {@link #PREFIXES} table — is what makes a bareword fold.</b>
     * {@link #forwardOperand} would happily lower any {@code dataset_<suffix>} operand to its
     * {@code ds_<suffix>("DATA")} accessor, but on a Record-Data rule {@link #canonicalizeFacts}
     * only applies that lowering to the names listed here, and <em>demotes</em> an already-raised
     * accessor for every other name. A {@code dataset_domain} left off this list compiles as a
     * plain column read of a column called {@code "dataset_domain"} — absent on every dataset —
     * i.e. it fails silently and keeps per-record reporting. {@code dataset_domain} is a Java-only
     * native fact with no legacy fold counterpart, so there is no legacy behaviour to preserve by
     * excluding it.
     * </p>
     */
    private static boolean isFoldFactName(String name)
    {
        return "record_count".equals(name) || "dataset_name".equals(name)
                || "dataset_label".equals(name) || "dataset_domain".equals(name);
    }


    /** Maps {@link #canonicalizeFacts} over a list of operands in the given position. */
    private static List<Expr> mapFacts(List<Expr> list, boolean valuePos)
    {
        List<Expr> out = new ArrayList<>(list.size());
        for (Expr e : list)
        {
            out.add(canonicalizeFacts(e, valuePos, false));
        }
        return out;
    }


    /** Maps {@link #canonicalizeFacts} over keyword arguments (always value position). */
    private static Map<String, Expr> mapFactsMap(Map<String, Expr> map)
    {
        Map<String, Expr> out = new LinkedHashMap<>();
        for (Map.Entry<String, Expr> e : map.entrySet())
        {
            out.put(e.getKey(), canonicalizeFacts(e.getValue(), true, false));
        }
        return out;
    }


    /** The zero-arg {@code varname()} current-variable-name call. */
    private static boolean isVarnameCall(Expr e)
    {
        return e instanceof Expr.Call c && "varname".equals(c.name()) && c.args().isEmpty()
                && c.kwargs().isEmpty();
    }


    /**
     * Whether {@code e} anchors a {@code var_<attr>} call on the current variable: the bare
     * {@code variable_name} operand or a {@code varname()} call. Both reverse to the same metadata
     * operand as the level-only overload (change #6).
     */
    private static boolean isVariableNameAnchor(Expr e)
    {
        return (e instanceof Expr.Ref r && VARIABLE_NAME.equals(r.name())) || isVarnameCall(e);
    }


    /**
     * Names the legacy per-variable cascade RESOLVES from the injected metadata map rather than
     * treating as literals ({@code evaluateLeafAgainstMetadata}'s {@code containsKey} hit:
     * {@code DOMAIN} via Fix #10, the dataset-level facts). A varname() comparison against one of
     * these must NOT be rewritten to a string literal (P9 review finding 4) — it stays a reference,
     * keeping the rule on the legacy cascade where the injected value resolves.
     */
    private static boolean isCascadeResolvableName(String name)
    {
        return "DOMAIN".equals(name) || "record_count".equals(name) || "dataset_name".equals(name)
                || "dataset_label".equals(name);
    }


    /** Maps {@link #canonicalizeMetadataOperands} over a list of operands. */
    private static List<Expr> mapList(List<Expr> items)
    {
        List<Expr> out = new ArrayList<>(items.size());
        for (Expr in : items)
        {
            out.add(canonicalizeMetadataOperands(in));
        }
        return out;
    }


    /** Maps {@link #canonicalizeMetadataOperands} over a kwargs map's values, keys preserved. */
    private static Map<String, Expr> mapMap(Map<String, Expr> kwargs)
    {
        Map<String, Expr> out = LinkedHashMap.newLinkedHashMap(kwargs.size());
        for (Map.Entry<String, Expr> en : kwargs.entrySet())
        {
            out.put(en.getKey(), canonicalizeMetadataOperands(en.getValue()));
        }
        return out;
    }


    /**
     * The operand name for a reversible {@code var_*} / {@code ds_*} call — the
     * {@code variable_name}-anchored variable form or the current-dataset ({@code ds_*(level)})
     * form — or {@code null} when {@code e} is not such a call (an arbitrary-literal name, a named
     * dataset, kwargs, etc., which are native-only and have no operand).
     */
    public static @Nullable String reverseToOperand(Expr e)
    {
        if (!(e instanceof Expr.Call c) || !c.kwargs().isEmpty())
        {
            return null;
        }
        MetadataAttribute attr = MetadataAttribute.fromFunction(c.name());
        if (attr == null)
        {
            return null;
        }
        List<Expr> args = c.args();
        Expr levelArg;
        if (attr.scope() == MetadataAttribute.Scope.VARIABLE)
        {
            if (args.size() == 1)
            {
                // var_<attr>(level): the level-only overload defaults to the current variable, so
                // it reverses to the same operand as the variable_name-anchored form (change #6).
                levelArg = args.get(0);
            }
            else if (args.size() == 2 && isVariableNameAnchor(args.get(0)))
            {
                levelArg = args.get(1);
            }
            else
            {
                return null;
            }
        }
        else
        {
            if (args.size() != 1)
            {
                return null;
            }
            levelArg = args.get(0);
        }
        if (!(levelArg instanceof Expr.Lit lit) || lit.kind() != Expr.LitKind.STRING)
        {
            return null;
        }
        MetadataLevel level = MetadataLevel.tryParse((String) lit.value());
        if (level == null)
        {
            return null;
        }
        return operandPrefix(attr.scope(), level) + operandSuffix(c.name());
    }


    /**
     * The operand name for <em>any</em> per-record accessor call the finding projection can resolve
     * — the reversible {@code var_*}/{@code ds_*} forms of {@link #reverseToOperand} plus the
     * cursor accessors ({@code varname()}, {@code value()}), {@code record_count()},
     * {@code max_value_length(…)}, the value-level {@code vlm_*} family and the paired code/decode
     * matchers — or {@code null} when the call has no operand equivalent (an arbitrary-literal
     * anchor, a named dataset, a plain computation call). Keeps the forward/reverse mapping in one
     * place for the Output_Variables derivation (EC-37); the cursor and VLM branches deliberately
     * mirror {@code ExprCompiler}'s accessor dispatch.
     */
    public static @Nullable String reverseAnyAccessor(Expr e)
    {
        String operand = reverseToOperand(e);
        if (operand != null)
        {
            return operand;
        }
        if (!(e instanceof Expr.Call c))
        {
            return null;
        }
        if (c.args().isEmpty() && c.kwargs().isEmpty())
        {
            String cursor = switch (c.name())
            {
            case "varname" -> VARIABLE_NAME;
            case "value" -> "variable_value";
            case "record_count" -> "record_count";
            default -> null;
            };
            if (cursor != null)
            {
                return cursor;
            }
        }
        for (Map.Entry<String, String> en : VLM_OPERAND_SUFFIXES.entrySet())
        {
            if (en.getValue().equals(c.name()))
            {
                return en.getKey();
            }
        }
        if ("max_value_length".equals(c.name()))
        {
            return "variable_max_size";
        }
        if (LIBRARY_CODE_PAIR_MATCHES.equals(c.name())
                || DEFINE_VARIABLE_DECODE_MATCHES.equals(c.name()))
        {
            return c.name();
        }
        return null;
    }


    private static String functionName(MetadataAttribute.Scope scope, String operandSuffix)
    {
        String suffix = "data_type".equals(operandSuffix) ? "type" : operandSuffix;
        return (scope == MetadataAttribute.Scope.VARIABLE ? "var_" : "ds_") + suffix;
    }


    /**
     * The operand suffix for a function name: strips {@code var_}/{@code ds_},
     * {@code type→data_type}.
     */
    private static String operandSuffix(String functionName)
    {
        String suffix = functionName.startsWith("var_") ? functionName.substring(4)
                : functionName.substring(3);
        return "type".equals(suffix) ? "data_type" : suffix;
    }


    private static String operandPrefix(MetadataAttribute.Scope scope, MetadataLevel level)
    {
        for (Prefix p : PREFIXES)
        {
            if (p.scope() == scope && p.level() == level)
            {
                return p.text();
            }
        }
        throw new IllegalStateException("no operand prefix for " + scope + "/" + level);
    }

}
