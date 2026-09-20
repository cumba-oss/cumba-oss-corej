package net.cumba.corej.core.expr.eval.spi;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionKind;
import net.cumba.corej.core.expr.eval.FunctionProvider;
import net.cumba.corej.core.expr.eval.Parameter;
import net.cumba.corej.core.expr.typed.ExprType.ListOf;
import net.cumba.corej.core.expr.typed.ExprType.Primitive;
import net.cumba.corej.core.expr.typed.ExprType.Unknown;

/**
 * ⭐ Phase 7 (D119h / D91f (ii)) — declarations for every <b>compiler-dispatched</b> callable: the
 * boolean calls {@code ExprCompiler.compileBoolCall} used to admit through the retired
 * {@code HARDCODED_BOOLEAN_CALLS} name set, the two negation-dispatched group operators
 * ({@code ExprCompiler.compileNot}'s Q1 arms), and the per-record metadata value calls
 * {@code operandPlan} routes to dedicated plans ({@code vlm_*}, {@code max_value_length}, the two
 * decode/code-pair accessors). Each gets a real {@link FunctionDescriptor} on the same shape as
 * {@code OperationDescriptors}: a typed, named parameter list with {@code fn == null} — "compiles
 * through a dedicated {@code ExprCompiler} plan instead of the registry's implementation".
 *
 * <p>
 * <b>What this buys</b>: {@code ExprCompiler.isBooleanCall} answers from the registry alone (a set
 * and an if-cascade can no longer drift apart about what is boolean); the stage-A checker binds
 * these calls against a declared arity/keyword contract ({@code StageAErrorKind.ARITY}, armed)
 * instead of waving every spelling through; and the kwarg surfaces ({@code within=}, {@code keys=},
 * {@code by=}, {@code ordering=}, {@code relation=}, {@code regex=}, {@code keep_missings=},
 * {@code include_empty=}, {@code negative=}) are finally <em>declared</em> rather than
 * compiler-consumed folklore. The dispatch itself deliberately stays the {@code compileBoolCall}
 * if-cascade — plan §5 keeps the bespoke plans — and {@code CompilerDispatchDriftGateTest} holds
 * the two authorities together: every descriptor here must be dispatchable, and a cascade arm for
 * an undeclared name is unreachable by construction (the pre-cascade registry guard in
 * {@code compileBoolCall}).
 * </p>
 *
 * <p>
 * <b>Types</b> follow SPEC §1.6's rule (declare what the semantics require) with the per-element
 * knowledge of {@code plans/findings/SCAN6-element-map.tsv} (normative by reference, SPEC §1.6) —
 * within the phase-2 partiality contract ({@link Parameter}): where a surface legally admits
 * several static spellings (a name operand may be a bareword reference <em>or</em> a quoted string
 * literal, §1.2 equivalence — 1 649 of the corpus' 1 686 {@code var_exists} sites quote the name;
 * {@code within=} takes a single reference, a list, or EC-24's coalesced list-of-lists), the
 * parameter is declared {@link Unknown#UNKNOWN}, because a union type is not expressible and a
 * known-vs-known mismatch on a legal spelling would be checker noise, not a finding. Boolean flags
 * ({@code keep_missings}, {@code include_empty}), {@code relation} (a string spelling of
 * {@code NextRecordRelation}) and the column-reference lists ({@code keys=[…]} on the group
 * operators, {@code by=[asc(…), …]}) are declared precisely. ⚠ {@code regex=} is declared
 * {@link Primitive#STRING}, not {@link Primitive#REGEX}: the compiler accepts <em>only</em> a
 * string literal there today ({@code compileStringPart}, {@code optionalRegexLiteral}), so a
 * {@code REGEX}-typed declaration would flag every legal spelling; retype when the surface takes a
 * regex literal (the D92b {@code name_pattern} precedent).
 * </p>
 *
 * <p>
 * ⚠ Deliberately <b>not</b> declared, so their treatment stays visible:
 * </p>
 * <ul>
 * <li>{@code exists} / {@code not_exists} — retired generic presence operators; their
 * {@code compileBoolCall} arm is an error message, not a plan, and a descriptor would legalise them
 * at stage A while the loader rejects them.</li>
 * <li>{@code asc} / {@code desc} — {@code is_sorted_by}'s sort-key descriptor literals inside
 * {@code by=[…]}, consumed structurally by {@code compileTargetIsNotSortedBy}; they are not
 * callables in their own right (stage A types them as column references).</li>
 * <li>{@code min_count=} on {@code present_on_multiple_rows_within} — the parser and printer
 * round-trip it, but <b>no</b> compiler plan reads it (the Python reference engine's knob; Java
 * fires on &gt;1 occurrence unconditionally) and no shipped rule spells it. Declaring it would
 * bless a silently-dropped kwarg — the exact FDA-SD1078 shape this model exists to reject — so it
 * stays undeclared and a rule spelling it now errors at stage A instead of being ignored.</li>
 * <li>{@code has_different_values} — a leaf-era spelling with <b>no home left at all</b>; the
 * compiler has no arm for it (the expression corpus spells {@code not has_same_values(…)}) and it
 * appears nowhere in either corpus. ⚑ The reason this entry is written down, preserved and
 * re-pointed (R2-5): it used to survive in {@code BroadcastFold.WHOLE_COLUMN_VERDICT_OPERATORS},
 * and {@code D121} removed it from that set — leaving the name typeable nowhere. It is listed here
 * so a future reader meeting the spelling in an old rule learns that it now errors at stage A
 * ({@code StageAChecker} consults {@code isWholeColumnVerdictCall} to type such a call <em>without
 * a descriptor</em>; with the set entry gone it falls through to a descriptor lookup that has none)
 * rather than being silently accepted, and does not "restore" it by declaring a descriptor for a
 * name no plan can compile.</li>
 * <li>{@code invalid_duration} — dispatched by the cascade too (its arity-1 kwarg-carrying arm),
 * but it is a <em>registered</em> function with an implementation and was declared in
 * {@link BuiltinFunctions} ({@code negative=}, D119f) — one descriptor per name.</li>
 * </ul>
 */
public final class CompilerDispatchedCalls implements FunctionProvider
{

    /** Names dispatched by {@code ExprCompiler.compileBoolCall}'s if-cascade (kind BOOLEAN). */
    private static final List<FunctionDescriptor> BOOL_CALLS = buildBoolCalls();

    /**
     * Names dispatched only under {@code not} ({@code ExprCompiler.compileNot}'s Q1 arms — the
     * converter spells {@code does_not_have_next_corresponding_record} /
     * {@code target_is_not_sorted_by} as {@code not <positive>(…)}); kind BOOLEAN.
     */
    private static final List<FunctionDescriptor> NEGATION_DISPATCHED = buildNegationDispatched();

    /**
     * Per-record metadata value calls routed by {@code ExprCompiler.operandPlan} to dedicated plans
     * (kind VALUE).
     */
    private static final List<FunctionDescriptor> VALUE_CALLS = buildValueCalls();

    @Override
    public List<FunctionDescriptor> functions()
    {
        List<FunctionDescriptor> all = new ArrayList<>(
                BOOL_CALLS.size() + NEGATION_DISPATCHED.size() + VALUE_CALLS.size());
        all.addAll(BOOL_CALLS);
        all.addAll(NEGATION_DISPATCHED);
        all.addAll(VALUE_CALLS);
        return all;
    }


    /**
     * The boolean calls {@code compileBoolCall} dispatches by name — the successor of the retired
     * {@code ExprCompiler.HARDCODED_BOOLEAN_CALLS} set, now derived from the descriptors
     * themselves. Public for the expression-syntax documentation census
     * ({@code ExpressionCheckSpecCensus} in {@code corej-rules}) and the dispatch drift gate.
     */
    public static Set<String> booleanCallNames()
    {
        return names(BOOL_CALLS);
    }


    /** The two boolean group operators dispatched only under {@code not} (Q1). */
    public static Set<String> negationDispatchedBooleanCallNames()
    {
        return names(NEGATION_DISPATCHED);
    }


    /** The per-record metadata value calls with dedicated {@code operandPlan} arms. */
    public static Set<String> valueCallNames()
    {
        return names(VALUE_CALLS);
    }


    private static Set<String> names(List<FunctionDescriptor> descriptors)
    {
        Set<String> out = new LinkedHashSet<>();
        for (FunctionDescriptor d : descriptors)
        {
            out.add(d.name());
        }
        return Set.copyOf(out);
    }


    private static List<FunctionDescriptor> buildBoolCalls()
    {
        List<FunctionDescriptor> fns = new ArrayList<>();
        // Presence facts. `name` is a dataset name (ds_*) / column name (var_*), spelled as a
        // bareword reference or a quoted string (§1.2 equivalence; var_exists also takes
        // varname() and the ${...} per-row template form) — hence unknown, not column-reference.
        bool(fns, "ds_exists", req("name"));
        bool(fns, "ds_not_exists", req("name"));
        bool(fns, "var_exists", req("name"));
        bool(fns, "var_not_exists", req("name"));
        // T5a broadcast all-null cursor predicate: column reference, string, or varname().
        bool(fns, "var_is_null", req("name"));
        // Legacy string-part comparison: capture group 1 of regex= extracted from VALUE per row.
        bool(fns, "does_not_equal_string_part", req("name"), req("value"), regex(true));
        // String-length (in)equality against a per-row integral operand (literal, numeric column,
        // or char column parsing to an int — legacy asInt parity), hence unknown.
        bool(fns, "has_not_equal_length", req("name"), req("length"));
        bool(fns, "has_equal_length", req("name"), req("length"));
        // Group operators (GroupSemantics). within= admits a single reference, a list, or EC-24's
        // coalesced list-of-lists — unknown by the union rule above.
        bool(fns, "has_multiple_values_for", req("name"), req("key"), within(), keepMissings(),
                includeEmpty());
        bool(fns, "present_on_multiple_rows_within", req("name"), reqWithin(), keepMissings());
        bool(fns, "empty_within_except_last_row", req("name"), req("group"),
                Parameter.required("ordering", Unknown.UNKNOWN), keepMissings());
        // Relationship uniqueness: the comparator side is ONE extra positional or the keys= list
        // (GroupSemantics treats the key list as a value tuple) — at least one, which a
        // requiredness flag cannot express; compileNotUniqueRelationship enforces it.
        bool(fns, "is_not_unique_relationship", req("name"), opt("value"), columnKeys());
        bool(fns, "is_unique_relationship", req("name"), opt("value"), columnKeys());
        // Tuple uniqueness: ONE positional list literal of members (the collapsed single-list
        // grammar, 2026-08-23); the pre-2026-08-23 keys= spelling is refused at load and stays
        // undeclared here on purpose.
        bool(fns, "is_not_unique_set", members(), regex(false), keepMissings());
        bool(fns, "is_unique_set", members(), regex(false), keepMissings());
        bool(fns, "is_not_unique_value", req("name"));
        bool(fns, "is_unique_value", req("name"));
        bool(fns, "is_inconsistent_across_dataset", req("name"), opt("key"), columnKeys(),
                includeEmpty(), keepMissings());
        bool(fns, "inconsistent_enumerated_columns", req("name"));
        // Whole-column set verdicts. The source/required/value operands admit column references,
        // $-operation lists, list-valued metadata accessors and per-row token producers — unknown
        // by the union rule; keys= members are VALUES (raised as references from JSON arrays, or
        // quoted literals in authored text), hence list<unknown>.
        bool(fns, "not_contains_all", req("source"), opt("required"), valueKeys());
        bool(fns, "contains_all", req("source"), opt("required"), valueKeys());
        bool(fns, "has_same_values", req("name"));
        bool(fns, "shares_no_elements_with", req("name"), opt("value"), valueKeys());
        bool(fns, "shares_elements_with", req("name"), opt("value"), valueKeys());
        bool(fns, "is_not_ordered_subset_of", req("name"), req("value"));
        bool(fns, "is_ordered_subset_of", req("name"), req("value"));
        return List.copyOf(fns);
    }


    private static List<FunctionDescriptor> buildNegationDispatched()
    {
        List<FunctionDescriptor> fns = new ArrayList<>();
        bool(fns, "has_next_corresponding_record", req("name"), req("value"), reqWithin(),
                Parameter.required("ordering", Unknown.UNKNOWN),
                Parameter.optional("relation", Primitive.STRING), keepMissings());
        bool(fns, "is_sorted_by", req("target"),
                Parameter.required("by", new ListOf(Primitive.COLUMN_REFERENCE)), within(),
                keepMissings());
        return List.copyOf(fns);
    }


    private static List<FunctionDescriptor> buildValueCalls()
    {
        List<FunctionDescriptor> fns = new ArrayList<>();
        // Each takes the current cursor variable when the argument is absent / varname(), or an
        // explicitly named column (reference or string literal) — unknown by the union rule.
        value(fns, "max_value_length");
        value(fns, "vlm_data_type");
        value(fns, "vlm_length");
        value(fns, "vlm_mandatory");
        value(fns, "vlm_codelist_coded_values");
        value(fns, "vlm_codelist_coded_codes");
        value(fns, "vlm_type_conforms");
        value(fns, "vlm_codelist_extensible");
        value(fns, "vlm_value_length");
        value(fns, "vlm_has_codelist");
        value(fns, "vlm_decode_matches");
        value(fns, "library_variable_code_pair_matches");
        value(fns, "define_variable_decode_matches");
        return List.copyOf(fns);
    }


    private static void bool(List<FunctionDescriptor> fns, String name, Parameter... params)
    {
        fns.add(new FunctionDescriptor(name, List.of(params), FunctionKind.BOOLEAN, null));
    }


    private static void value(List<FunctionDescriptor> fns, String name)
    {
        fns.add(new FunctionDescriptor(name, List.of(Parameter.optional("name", Unknown.UNKNOWN)),
                FunctionKind.VALUE, null));
    }


    private static Parameter req(String name)
    {
        return Parameter.required(name, Unknown.UNKNOWN);
    }


    private static Parameter opt(String name)
    {
        return Parameter.optional(name, Unknown.UNKNOWN);
    }


    /** {@code within=} — single reference, list, or EC-24 coalesced list-of-lists. */
    private static Parameter within()
    {
        return Parameter.optional("within", Unknown.UNKNOWN);
    }


    /** {@code within=} where the compiler requires exactly one partition column. */
    private static Parameter reqWithin()
    {
        return Parameter.required("within", Unknown.UNKNOWN);
    }


    /** {@code keys=[…]} of column references (group/relationship operators). */
    private static Parameter columnKeys()
    {
        return Parameter.optional("keys", new ListOf(Primitive.COLUMN_REFERENCE));
    }


    /** {@code keys=[…]} of values (set operators; raised array members arrive as references). */
    private static Parameter valueKeys()
    {
        return Parameter.optional("keys", new ListOf(Unknown.UNKNOWN));
    }


    /** {@code regex=} — a string literal today (see class javadoc), required or optional. */
    private static Parameter regex(boolean required)
    {
        return required ? Parameter.required("regex", Primitive.STRING)
                : Parameter.optional("regex", Primitive.STRING);
    }


    private static Parameter keepMissings()
    {
        return Parameter.optional("keep_missings", Primitive.BOOLEAN);
    }


    private static Parameter includeEmpty()
    {
        return Parameter.optional("include_empty", Primitive.BOOLEAN);
    }


    /** The single positional member-list literal of the uniqueness-set operators. */
    private static Parameter members()
    {
        return Parameter.required("members", new ListOf(Primitive.COLUMN_REFERENCE));
    }
}
