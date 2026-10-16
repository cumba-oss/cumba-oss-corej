package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import net.cumba.cdisc.define.CheckValue;
import net.cumba.cdisc.define.CodeList;
import net.cumba.cdisc.define.CodeListItem;
import net.cumba.cdisc.define.CodeListRef;
import net.cumba.cdisc.define.Decode;
import net.cumba.cdisc.define.ItemDef;
import net.cumba.cdisc.define.ItemGroupDef;
import net.cumba.cdisc.define.ItemRef;
import net.cumba.cdisc.define.MetaDataVersion;
import net.cumba.cdisc.define.RangeCheck;
import net.cumba.cdisc.define.TranslatedText;
import net.cumba.cdisc.define.ValueListDef;
import net.cumba.cdisc.define.ValueListRef;
import net.cumba.cdisc.define.WhereClauseDef;
import net.cumba.cdisc.define.WhereClauseRef;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.ExprPrefixResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.spi.BuiltinFunctions;
import net.cumba.corej.core.expr.eval.spi.CompilerDispatchedCalls;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.expr.typed.ExprType.ListOf;
import net.cumba.corej.core.expr.typed.ExprType.Primitive;
import net.cumba.corej.core.expr.typed.StageAChecker;
import net.cumba.corej.core.expr.typed.StageAErrorKind;
import net.cumba.corej.core.metadata.VlmResolver;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.dictionary.house.RuntimeDictionaryProvider;
import net.cumba.datatable.dictionary.house.ValueMapDictionary;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ⭐ {@code PLAN-string-literal-prefix-rewrite} N5 — the name-slot roster <b>derived from
 * behaviour</b>, in the manner of {@link StageAResidueDriftTest}: which call slots read a STRING
 * literal as a <em>name</em> is measured, never listed, and the declarations ({@link NameSlots}:
 * the registry's marked parameters, the metadata accessors' name positions) are held to the
 * measurement in both directions.
 *
 * <p>
 * <b>The instrument.</b> Two worlds that differ in ONE name {@code n} only — {@code ZZQN} in one,
 * {@code ZZRN} in the other: a column {@code n} of the evaluated table (filled, and in a second
 * variant all-missing, so a content-status predicate can tell), its decode partner, a dataset
 * {@code n} in the study inventory, Define-XML and Library metadata for a variable and a dataset
 * {@code n}, value-level metadata for {@code n}. The literal {@code "ZZQN"} is planted at a slot of
 * a known-good call; the slot is a <b>name slot</b> when the call answers differently in the two
 * worlds while a neutral literal {@code "a"} planted at the same slot answers alike (the world is
 * not what moved). A slot whose literal stage A or the compiler refuses is <b>refused</b>; any
 * other is <b>data</b>. Every parameter of every descriptor of both providers is probed (read
 * directly, so a stub cannot hide a name), and every {@link MetadataAttribute}.
 * </p>
 *
 * <p>
 * <b>The contract.</b> A descriptor slot measures as a name slot exactly when it is marked
 * ({@link NameSlots#isNameSlot(Parameter)}) — except a slot whose template the specialiser keeps
 * ({@link ExprPrefixResolver#keepsTemplate}: D92a / D92b), which may read as a name and is never
 * marked. A metadata accessor's measured name slot is marked, and a marked one is a name slot or
 * refused. The hand-kept call sets that read argument 0 as a name are each a subset of the marked
 * slots.
 * </p>
 *
 * <p>
 * Non-vacuity: every descriptor has a base call that evaluates in the fixture world; no slot may be
 * left unmeasured or ambiguous; floors on the measured name slots; and a permanent planted control
 * — the same comparison over a marker with {@code var_is_empty} unmarked names exactly that slot.
 * </p>
 */
class NameSlotRosterTest
{

    private static final String NAME = "ZZQN";

    private static final String OTHER = "ZZRN";

    private static final String NEUTRAL = "a";

    /** A literal of {@link #NAME}'s shape that names nothing in either world. */
    private static final String SAME_SHAPE = "QQQN";

    enum Verdict
    {
        NAME, DATA, REFUSED, AMBIGUOUS, UNPROBEABLE
    }


    /** One measured slot: {@code function.parameter}, its verdict and why. */
    record Slot(String function, String parameter, Verdict verdict, String detail)
    {

        String key()
        {
            return function + "." + parameter;
        }
    }

    /**
     * A known-good call per function whose auto-built call does not evaluate (a static literal it
     * needs, a negation-only dispatch, a provider answer); {@code "fn.param"} overrides it for one
     * parameter. The planted call is the first call of that name inside the base expression.
     */
    private static final Map<String, String> BASE = new LinkedHashMap<>();

    static
    {
        // runbook W1b VA (PLAN-engine-gap-value-addressed V1 / V4): the value-addressed record
        // count and key verdict over the dataset the row's RDOMAIN names (DM in the world);
        // `dataset` reads as a name (marked), `value` / `key_variable` are values — the
        // key_variable templates resolve against the TARGET, so the specialiser keeps them
        // (ExprPrefixResolver.keepsTemplate), and `keys` is a column-reference list
        BASE.put("referenced_record_count", "referenced_record_count(RDOMAIN, USUBJID,"
                + " key_variable=\"USUBJID\", keys=[USUBJID])");
        BASE.put("referenced_key_is_unique",
                "referenced_key_is_unique(RDOMAIN, key_variable=\"USUBJID\", keys=[USUBJID])");
        BASE.put("all", "all(joined(X == \"a\"))");
        BASE.put("any", "any(joined(X == \"a\"))");
        BASE.put("none", "none(joined(X == \"a\"))");
        BASE.put("joined", "any(joined(X == \"a\"))");
        BASE.put("codelist_terms", "codelist_terms([\"C1\"], \"term\", \"value\")");
        // PLAN-ct-class-exemption-data-rules CC1: both parameters are closed vocabularies
        BASE.put("ct_class_terms", "ct_class_terms(\"Clinical Classification\", \"Test Code\")");
        // runbook W1b BL1: unit= / encoding= are static literals with a closed vocabulary / a
        // charset name, so the auto-built call cannot find an evaluating combination
        BASE.put("len", "len(X, \"bytes\", \"UTF-8\")");
        BASE.put("length", "length(X, \"bytes\", \"UTF-8\")");
        BASE.put("max_value_length", "max_value_length(X, \"bytes\", \"UTF-8\")");
        BASE.put("contains_all", "contains_all(X, keys=[\"a\"])");
        BASE.put("not_contains_all", "not_contains_all(X, keys=[\"a\"])");
        BASE.put("shares_elements_with", "shares_elements_with(X, keys=[\"a\"])");
        BASE.put("shares_no_elements_with", "shares_no_elements_with(X, keys=[\"a\"])");
        BASE.put("is_ordered_subset_of", "is_ordered_subset_of(X, Y)");
        BASE.put("is_not_ordered_subset_of", "is_not_ordered_subset_of(X, Y)");
        BASE.put("cross_dataset_variable_metadata",
                "cross_dataset_variable_metadata(\"label\", \"DM\")");
        BASE.put("column_series_metadata",
                "column_series_metadata(\"SER0\", name_pattern=\"^SER[0-9]+$\")");
        BASE.put("dictionary_has_decode",
                "dictionary_has_decode(X, external_dictionary_type=\"meddra\")");
        BASE.put("valid_external_dictionary_code", "valid_external_dictionary_code(X,"
                + " external_dictionary_type=\"meddra\", dictionary_term_type=\"PT\")");
        BASE.put("valid_external_dictionary_value", "valid_external_dictionary_value(X,"
                + " external_dictionary_type=\"meddra\", dictionary_term_type=\"PT\")");
        BASE.put("valid_external_dictionary_code_term_pair",
                "valid_external_dictionary_code_term_pair(X, Y,"
                        + " external_dictionary_type=\"meddra\")");
        // runbook W4 DI2: the hierarchy reads the dictionary at the MedDRA levels its two columns
        // name, so the base call names two (X / Y name none: a load error, and loud at run time)
        BASE.put("valid_external_dictionary_hierarchy", "valid_external_dictionary_hierarchy(AEHLT,"
                + " AESOC, external_dictionary_type=\"meddra\")");
        BASE.put("distinct", "distinct(X)");
        // runbook W1b RO / SK: sequence on distinct's plan, the ordered-list functions over
        // lists, and by= on the three renamed single-key readers (O17)
        BASE.put("sequence", "sequence(X)");
        // (a per-row list: a BOOLEAN registry call with a list LITERAL at parameter 0 does not
        // compile today — "a list literal is not a scalar operand" — the same for contains_all)
        BASE.put("is_prefix_of", "is_prefix_of(split_by(X, \",\"), split_by(X, \",\"))");
        BASE.put("is_not_prefix_of", "is_not_prefix_of(split_by(X, \",\"), split_by(X, \",\"))");
        BASE.put("is_increasing", "is_increasing(split_by(X, \",\"))");
        BASE.put("collapse_runs", "collapse_runs(split_by(X, \",\"))");
        BASE.put("is_last_in_group.by", "is_last_in_group(group=[USUBJID], by=AESEQ)");
        BASE.put("empty_within_except_last_row.by",
                "empty_within_except_last_row(X, USUBJID, by=AESEQ)");
        BASE.put("has_next_corresponding_record.by",
                "has_next_corresponding_record(X, Y, within=USUBJID, by=AESEQ)");
        BASE.put("max", "max(NUM, group=[USUBJID])");
        BASE.put("max_date", "max_date(DT, group=[USUBJID])");
        BASE.put("min_date", "min_date(DT, group=[USUBJID])");
        BASE.put("read_value", "read_value(X, domain=\"DM\", mode=\"FIRST\")");
        BASE.put("record_count", "record_count()");
        BASE.put("does_not_equal_string_part", "does_not_equal_string_part(X, Y, regex=\"(a)\")");
        BASE.put("empty_within_except_last_row",
                "empty_within_except_last_row(X, USUBJID, by=AESEQ)");
        BASE.put("get_codelist_attributes",
                "get_codelist_attributes(CTREF, CTVER, ct_attribute=\"Term CCODE\")");
        BASE.put("get_dataset_filtered_variables",
                "get_dataset_filtered_variables(key_name=\"core\", key_value=\"Req\")");
        BASE.put("get_model_filtered_variables",
                "get_model_filtered_variables(key_name=\"core\", key_value=\"Req\")");
        BASE.put("get_parent_model_column_order", "get_parent_model_column_order(RDOMAIN)");
        BASE.put("has_equal_length", "has_equal_length(X, 1)");
        BASE.put("has_not_equal_length", "has_not_equal_length(X, 1)");
        BASE.put("has_multiple_values_for", "has_multiple_values_for(X, Y)");
        BASE.put("has_next_corresponding_record",
                "has_next_corresponding_record(X, Y, within=USUBJID, by=AESEQ)");
        BASE.put("is_inconsistent_across_dataset",
                "is_inconsistent_across_dataset(X, keys=[USUBJID])");
        BASE.put("is_not_unique_relationship", "is_not_unique_relationship(X, Y)");
        BASE.put("is_unique_relationship", "is_unique_relationship(X, Y)");
        BASE.put("is_not_unique_set", "is_not_unique_set([X, USUBJID])");
        BASE.put("is_unique_set", "is_unique_set([X, USUBJID])");
        BASE.put("is_sorted_by", "is_sorted_by(AESEQ, by=[asc(AESEQ)], within=USUBJID)");
        BASE.put("present_on_multiple_rows_within",
                "present_on_multiple_rows_within(X, within=USUBJID)");
        BASE.put("printf", "printf(\"%s\", X)");
        BASE.put("minus", "minus([\"a\", \"b\"], [\"a\"])");
        BASE.put("union", "union([\"a\"], [\"b\"], [\"c\"])");
        BASE.put("tuple", "tuple(X, Y)");
        BASE.put("variable_count", "variable_count(\"X\")");
        BASE.put("values", "values([X, USUBJID])");
        BASE.put("variable_count.name_pattern", "variable_count(name_pattern=\"^Q$\")");
    }

    private static RuntimeDictionaryProvider dictionary;

    private static List<Slot> roster;

    /** The keyword spellings of the positional name slots ({@link #probeKeyword}). */
    private static final List<Slot> keywords = new ArrayList<>();

    @BeforeAll
    static void measure() throws Exception
    {
        dictionary = new RuntimeDictionaryProvider(
                Map.of("meddra", ValueMapDictionary.load(Paths.get("dictionaries/meddra.json"))));
        // Pitest re-runs @BeforeAll in the same JVM, a new mutant each time: the static list must
        // not accumulate, the worlds are rebuilt so no resolver-keyed engine memo
        // (SplitDomainResolution) carries a resolution computed under an earlier mutant, and the
        // compiled-program cache is emptied (its isolation extension only runs before each test).
        keywords.clear();
        WORLDS.clear();
        NativeExprEvaluator.clearCacheForTesting();
        roster = measureRoster();
    }


    /** The shared worlds' mocks record every call made on them: release them with the class. */
    @AfterAll
    static void releaseWorlds()
    {
        WORLDS.clear();
    }

    // ================================================================== the contract


    @Test
    void everyDescriptorSlotIsANameSlotExactlyWhenMarkedOrKept()
    {
        assertEquals(List.of(), mismatches(roster, null),
                "a slot whose literal reads as a name must be marked (NameSlots), and a marked"
                        + " slot must read as one — measured, not listed");
    }


    @Test
    void noSlotIsLeftUnmeasuredAndTheRosterIsNotVacuous()
    {
        List<String> open = roster.stream()
                .filter(s -> s.verdict() == Verdict.UNPROBEABLE || s.verdict() == Verdict.AMBIGUOUS)
                .map(s -> s.key() + " " + s.verdict() + " " + s.detail()).toList();
        assertEquals(List.of(), open, "every slot is measured NAME, DATA or REFUSED");
        Set<String> probed = new TreeSet<>();
        roster.forEach(s -> probed.add(s.function()));
        Set<String> registry = new TreeSet<>();
        // every descriptor with a parameter (a nullary one has no slot to probe)
        providers().stream().filter(d -> !d.parameters().isEmpty())
                .forEach(d -> registry.add(d.name()));
        for (MetadataAttribute a : MetadataAttribute.values())
        {
            registry.add(a.functionName());
        }
        assertEquals(registry, probed,
                "every descriptor with a parameter and every metadata accessor is probed");
        long names = roster.stream().filter(s -> s.verdict() == Verdict.NAME).count();
        long data = roster.stream().filter(s -> s.verdict() == Verdict.DATA).count();
        assertTrue(names >= 60, "measured name slots: " + names);
        assertTrue(data >= 100, "measured data slots: " + data);
    }


    /**
     * Review LOW-5: a name slot's keyword spelling ({@code vlm_length(name="X")}) answers as its
     * positional one or is refused loudly — never silently ignored (DATA), which is what the
     * {@code vlm_*} / {@code max_value_length} arms did when they read positionals only.
     */
    @Test
    void theKeywordSpellingOfANameSlotIsNeverIgnored()
    {
        List<String> ignored = keywords.stream()
                .filter(s -> s.verdict() != Verdict.NAME && s.verdict() != Verdict.REFUSED)
                .map(s -> s.key() + " " + s.verdict() + " " + s.detail()).toList();
        assertEquals(List.of(), ignored);
        assertTrue(keywords.size() >= 15, "keyword spellings probed: " + keywords.size());
    }


    @Test
    void thePlantedControlNamesExactlyTheUnmarkedSlot()
    {
        // a marker that forgot var_is_empty: the very comparison above names that slot, and only it
        List<String> found = mismatches(roster, "var_is_empty");
        assertEquals(1, found.size(), found.toString());
        assertTrue(found.get(0).startsWith("var_is_empty.name "), found.toString());
    }


    @Test
    void theMetadataAccessorsNamePositionsAreTheMarkedOnes()
    {
        List<String> wrong = new ArrayList<>();
        int names = 0;
        for (Slot s : roster)
        {
            MetadataAttribute attr = MetadataAttribute.fromFunction(s.function());
            if (attr == null || FunctionRegistry.descriptor(s.function()) != null)
            {
                continue;
            }
            boolean marked = metadataMarked(attr, s.parameter());
            if (s.verdict() == Verdict.NAME)
            {
                names++;
            }
            boolean unmarkedName = s.verdict() == Verdict.NAME && !marked;
            boolean markedData = marked && s.verdict() != Verdict.NAME
                    && s.verdict() != Verdict.REFUSED;
            if (unmarkedName || markedData)
            {
                wrong.add(s.key() + " " + s.verdict() + " marked=" + marked);
            }
        }
        assertEquals(List.of(), wrong);
        assertTrue(names >= 50, "metadata accessor name slots measured: " + names);
        // PLAN-metadata-accessor-default-level D3 / V12 (runbook W1a MA-r1): in the one-argument
        // form, argument 0 is the NAME unless it is a level literal — so the probe's non-level
        // literal is marked on every var_* accessor and a level literal is marked nowhere; the
        // ds_* one-argument form is never a name (a dataset name always needs its level, D3)
        for (MetadataAttribute attr : MetadataAttribute.values())
        {
            String fn = attr.functionName();
            assertFalse(
                    NameSlots.isNameSlot((Expr.Call) call(fn, List.of(lit("DATA")), Map.of()), 0),
                    fn + ": a level literal is not a name");
            assertFalse(NameSlots
                    .isNameSlot((Expr.Call) call(fn, List.of(lit("library")), Map.of()), 0),
                    fn + ": a level literal in any case is not a name");
            assertEquals(attr.scope() == MetadataAttribute.Scope.VARIABLE,
                    NameSlots.isNameSlot((Expr.Call) call(fn, List.of(lit(NAME)), Map.of()), 0),
                    fn + ": the one-argument non-level form");
        }
    }


    @Test
    void theHandKeptNameSetsAreSubsetsOfTheMarkedSlots() throws Exception
    {
        Map<String, Set<String>> sets = new TreeMap<>();
        sets.put("AbsentDatasetSkip.NAME_ARG_CALLS",
                field("net.cumba.corej.core.exec.AbsentDatasetSkip", "NAME_ARG_CALLS"));
        sets.put("OutputVariableDeriver.NAME_LITERAL_TARGET_CALLS", field(
                "net.cumba.corej.core.exec.OutputVariableDeriver", "NAME_LITERAL_TARGET_CALLS"));
        sets.put("BroadcastFold.EXISTS_CALLS",
                field("net.cumba.corej.core.expr.eval.BroadcastFold", "EXISTS_CALLS"));
        sets.put("RuleClassifier.VARIABLE_PRESENCE",
                field("net.cumba.corej.core.exec.RuleClassifier", "VARIABLE_PRESENCE"));
        // review LOW-4: the other name sets whose argument 0 is a name
        sets.put("RuleClassifier.DATASET_PRESENCE",
                field("net.cumba.corej.core.exec.RuleClassifier", "DATASET_PRESENCE"));
        sets.put("AbsentDatasetSkip.PRESENCE_CALLS",
                field("net.cumba.corej.core.exec.AbsentDatasetSkip", "PRESENCE_CALLS"));
        sets.put("BroadcastFold.BROADCAST_COLUMN_PREDICATES",
                BroadcastFold.BROADCAST_COLUMN_PREDICATES);
        sets.put("StudyRuleClassifier.STUDY_SAFE_CALLS",
                field("net.cumba.corej.core.exec.StudyRuleClassifier", "STUDY_SAFE_CALLS"));
        List<String> outside = new ArrayList<>();
        int members = 0;
        for (Map.Entry<String, Set<String>> e : sets.entrySet())
        {
            assertFalse(e.getValue().isEmpty(), e.getKey());
            for (String fn : e.getValue())
            {
                members++;
                Expr.Call call = new Expr.Call(fn, List.of(lit(NAME)), Map.of());
                if (!NameSlots.isNameSlot(call, 0))
                {
                    outside.add(e.getKey() + ": " + fn);
                }
            }
        }
        assertEquals(List.of(), outside, "argument 0 of every hand-kept name call is a name slot");
        assertEquals(21, members, "members checked (5 + 1 + 4 + 2 + 2 + 2 + 3 + 2)");
        // BindingInliner's set is gone: it asks NameSlots (N6); so does WildcardExpander (W2 WX)
        assertFalse(java.util.Arrays
                .stream(Class.forName("net.cumba.corej.core.expr.convert.BindingInliner")
                        .getDeclaredFields())
                .anyMatch(f -> "VARIABLE_PRESENCE".equals(f.getName())));
        assertFalse(java.util.Arrays
                .stream(Class.forName("net.cumba.corej.core.gen.WildcardExpander")
                        .getDeclaredFields())
                .anyMatch(f -> "EXISTS_NAME_CALLS".equals(f.getName())));
    }


    /**
     * Runbook W2 WX ({@code PLAN-wildcard-expander-name-slots} X2): the wildcard expander expands a
     * quoted template exactly at the marked name slots — positional and keyword, every descriptor
     * and every metadata accessor — minus the two ruled exclusions, {@code find_vars} (W2: it
     * resolves its template itself) and {@code cross_dataset_variable_metadata.domain} (W3:
     * {@code "*"} there is every dataset). An equality, not the former subset of a hand-kept list.
     */
    @Test
    void theWildcardExpanderExpandsExactlyTheMarkedSlotsMinusTheRuledExclusions() throws Exception
    {
        Class<?> expander = Class.forName("net.cumba.corej.core.gen.WildcardExpander");
        java.lang.reflect.Method atPosition = expander.getDeclaredMethod("isExpandableNameSlot",
                Expr.Call.class, int.class);
        java.lang.reflect.Method atKeyword = expander.getDeclaredMethod("isExpandableNameSlot",
                Expr.Call.class, String.class);
        atPosition.setAccessible(true);
        atKeyword.setAccessible(true);
        Set<String> excluded = new TreeSet<>();
        List<String> wrong = new ArrayList<>();
        int expandable = 0;
        List<Expr.Call> probes = new ArrayList<>();
        for (FunctionDescriptor d : providers())
        {
            for (int i = 0; i < d.parameters().size(); i++)
            {
                List<Expr> args = new ArrayList<>();
                for (int j = 0; j <= i; j++)
                {
                    args.add(lit(NAME));
                }
                Expr.Call positional = new Expr.Call(d.name(), args, Map.of());
                String key = d.name() + "." + d.parameters().get(i).name();
                boolean slot = NameSlots.isNameSlot(positional, i);
                boolean expands = (Boolean) atPosition.invoke(null, positional, i);
                expandable += expands ? 1 : 0;
                if (expands && !slot)
                {
                    wrong.add(key + " @" + i + " expands but is not a name slot");
                }
                else if (slot && !expands)
                {
                    excluded.add(key + "@" + i);
                }
                String kw = d.parameters().get(i).name();
                Expr.Call keyword = new Expr.Call(d.name(), List.of(), Map.of(kw, lit(NAME)));
                boolean kwSlot = NameSlots.isNameSlot(keyword, kw);
                boolean kwExpands = (Boolean) atKeyword.invoke(null, keyword, kw);
                if (kwExpands && !kwSlot)
                {
                    wrong.add(key + " =" + " expands but is not a name slot");
                }
                else if (kwSlot && !kwExpands)
                {
                    excluded.add(key + "=");
                }
            }
        }
        for (MetadataAttribute attr : MetadataAttribute.values())
        {
            String fn = attr.functionName();
            probes.add(new Expr.Call(fn, List.of(lit(NAME)), Map.of()));
            probes.add(new Expr.Call(fn, List.of(lit(NAME), lit("DATA")), Map.of()));
            probes.add(new Expr.Call(fn, List.of(lit("DATA")), Map.of()));
            for (Expr.Call c : probes)
            {
                for (int i = 0; i < c.args().size(); i++)
                {
                    boolean slot = NameSlots.isNameSlot(c, i);
                    if (slot != (Boolean) atPosition.invoke(null, c, i))
                    {
                        wrong.add(fn + " @" + i + " of " + c.args().size() + ": slot=" + slot);
                    }
                }
            }
            probes.clear();
            Expr.Call dataset = new Expr.Call(fn, List.of(lit(NAME), lit("DATA")),
                    Map.of("dataset", lit(NAME)));
            if (NameSlots.isNameSlot(dataset,
                    "dataset") != (Boolean) atKeyword.invoke(null, dataset, "dataset"))
            {
                wrong.add(fn + " dataset=");
            }
        }
        assertEquals(List.of(), wrong);
        // per form: the positional slot (@index) and the keyword (=) are each excluded, so an
        // exclusion dropped from either reader's form reds here
        assertEquals(Set.of("cross_dataset_variable_metadata.domain@1",
                "cross_dataset_variable_metadata.domain=", "find_vars.entry@0", "find_vars.entry="),
                excluded,
                "the marked slots the expander leaves alone: exactly the two ruled exclusions");
        // the four former EXISTS_NAME_CALLS are among them
        for (String fn : List.of("ds_exists", "ds_not_exists", "var_exists", "var_not_exists"))
        {
            assertTrue((Boolean) atPosition.invoke(null,
                    new Expr.Call(fn, List.of(lit(NAME)), Map.of()), 0), fn);
        }
        assertTrue(expandable >= 20, "descriptor slots that expand: " + expandable);
    }


    @SuppressWarnings("unchecked")
    private static Set<String> field(String className, String fieldName) throws Exception
    {
        Field f = Class.forName(className).getDeclaredField(fieldName);
        f.setAccessible(true);
        return (Set<String>) f.get(null);
    }

    // ================================================================== the comparison


    /**
     * The declarations under test — a descriptor slot is marked when {@link NameSlots} says so —
     * with {@code unmarked}'s slots taken out (the planted control), or none.
     */
    private static boolean marked(FunctionDescriptor d, Parameter p, @Nullable String unmarked)
    {
        return !d.name().equals(unmarked) && NameSlots.isNameSlot(p);
    }


    /**
     * The descriptor slots whose measurement disagrees with {@code marked} ∪ kept — the one
     * comparison the contract test and the planted control both run.
     */
    private static List<String> mismatches(List<Slot> measured, @Nullable String unmarked)
    {
        List<String> out = new ArrayList<>();
        for (FunctionDescriptor d : providers())
        {
            for (Parameter p : d.parameters())
            {
                Slot s = measured.stream().filter(
                        m -> m.function().equals(d.name()) && m.parameter().equals(p.name()))
                        .findFirst().orElse(null);
                if (s == null)
                {
                    out.add(d.name() + "." + p.name() + " not measured");
                    continue;
                }
                boolean isMarked = marked(d, p, unmarked);
                boolean measuredName = s.verdict() == Verdict.NAME;
                // a kept slot (D92a / D92b) is never specialised: it may read as a name (a fold
                // template, a regex over the column names) and must not be marked
                boolean kept = ExprPrefixResolver.keepsTemplate(d.name(), p.name());
                if (kept ? isMarked : isMarked != measuredName)
                {
                    out.add(s.key() + " measured " + s.verdict() + ", marked=" + isMarked
                            + ", kept=" + kept + " " + s.detail());
                }
            }
        }
        return out;
    }

    /**
     * The one-argument form probed with a non-level string: since
     * {@code PLAN-metadata-accessor-default-level} D3 (runbook W1a MA-r1) that argument is the NAME
     * at the default {@code DATA} level for a {@code var_*} accessor, and a dataset name a
     * {@code ds_*} accessor refuses without a level.
     */
    private static final String LEVEL_FORM = "arg0(level)";

    private static boolean metadataMarked(MetadataAttribute attr, String parameter)
    {
        if (parameter.startsWith("arg0@"))
        {
            return NameSlots.isNameSlot(
                    new Expr.Call(attr.functionName(), List.of(lit(NAME), lit("DATA")), Map.of()),
                    0);
        }
        if (parameter.equals(LEVEL_FORM))
        {
            return NameSlots.isNameSlot(
                    new Expr.Call(attr.functionName(), List.of(lit(NAME)), Map.of()), 0);
        }
        return NameSlots.isNameSlot(
                new Expr.Call(attr.functionName(), List.of(lit("X"), lit("DATA")), Map.of()),
                parameter);
    }

    // ================================================================== the measurement


    private static List<FunctionDescriptor> providers()
    {
        List<FunctionDescriptor> all = new ArrayList<>();
        all.addAll(new BuiltinFunctions().functions());
        all.addAll(new CompilerDispatchedCalls().functions());
        return all;
    }


    private static List<Slot> measureRoster()
    {
        List<Slot> out = new ArrayList<>();
        for (FunctionDescriptor d : providers())
        {
            for (int i = 0; i < d.parameters().size(); i++)
            {
                Slot positional = probe(d, i);
                out.add(positional);
                Slot keyword = positional.verdict() == Verdict.NAME ? probeKeyword(d, i) : null;
                if (keyword != null)
                {
                    keywords.add(keyword);
                }
            }
        }
        for (MetadataAttribute attr : MetadataAttribute.values())
        {
            out.addAll(probeMetadata(attr));
        }
        return out;
    }


    private static Slot probe(FunctionDescriptor d, int index)
    {
        Parameter p = d.parameters().get(index);
        boolean list = p.type() instanceof ListOf;
        Expr plant = list ? list(lit(NAME)) : lit(NAME);
        Expr neutral = list ? list(lit(NEUTRAL)) : lit(NEUTRAL);
        Expr base = baseCall(d, p);
        if (base == null)
        {
            return new Slot(d.name(), p.name(), Verdict.UNPROBEABLE, "no evaluating base call");
        }
        Expr planted = plant(base, d, index, plant);
        Expr control = plant(base, d, index, neutral);
        if (stageARefuses(planted, p.name()))
        {
            return new Slot(d.name(), p.name(), Verdict.REFUSED, "stage A");
        }
        return classify(d.name(), p.name(), planted, control);
    }


    /**
     * The KEYWORD spelling of a slot the base call binds as its last positional — {@code f(x)}
     * written {@code f(name=x)} — or {@code null} when the base does not bind it that way (review
     * LOW-5: a keyword the compiler silently ignores must not pass for a name slot).
     */
    private static @Nullable Slot probeKeyword(FunctionDescriptor d, int index)
    {
        Parameter p = d.parameters().get(index);
        Expr base = baseCall(d, p);
        if (base == null || p.collector())
        {
            return null;
        }
        Expr.Call target = firstCall(base, d.name());
        if (target == null || index != target.args().size() - 1)
        {
            return null;
        }
        Expr planted = asKeyword(base, d, index, lit(NAME));
        if (stageARefuses(planted, p.name()))
        {
            return new Slot(d.name(), p.name() + "=", Verdict.REFUSED, "stage A");
        }
        return classify(d.name(), p.name() + "=", planted, asKeyword(base, d, index, lit(NEUTRAL)));
    }


    private static Expr.@Nullable Call firstCall(Expr e, String fn)
    {
        List<Expr.Call> found = new ArrayList<>();
        boolean[] done =
        {
                false
        };
        replaceCall(e, fn, c ->
        {
            found.add(c);
            return c;
        }, done);
        return found.isEmpty() ? null : found.get(0);
    }


    /** {@code base} with its first {@code d} call's last positional moved to its keyword. */
    private static Expr asKeyword(Expr base, FunctionDescriptor d, int index, Expr value)
    {
        Parameter p = d.parameters().get(index);
        boolean[] done =
        {
                false
        };
        return replaceCall(base, d.name(), c ->
        {
            List<Expr> args = new ArrayList<>(c.args().subList(0, index));
            Map<String, Expr> kwargs = new LinkedHashMap<>(c.kwargs());
            kwargs.put(p.name(), value);
            return new Expr.Call(c.name(), args, kwargs);
        }, done);
    }


    private static List<Slot> probeMetadata(MetadataAttribute attr)
    {
        List<Slot> out = new ArrayList<>();
        String fn = attr.functionName();
        for (MetadataLevel level : MetadataLevel.values())
        {
            if (attr.supports(level))
            {
                out.add(classify(fn, "arg0@" + level,
                        call(fn, List.of(lit(NAME), lit(level.name())), Map.of()),
                        call(fn, List.of(lit(NEUTRAL), lit(level.name())), Map.of())));
            }
        }
        if (attr.scope() == MetadataAttribute.Scope.VARIABLE)
        {
            out.add(classify(fn, "dataset",
                    call(fn, List.of(lit("X"), lit("DATA")), Map.of("dataset", lit(NAME))),
                    call(fn, List.of(lit("X"), lit("DATA")), Map.of("dataset", lit(NEUTRAL)))));
        }
        out.add(classify(fn, LEVEL_FORM, call(fn, List.of(lit(NAME)), Map.of()),
                call(fn, List.of(lit(NEUTRAL)), Map.of())));
        return out;
    }


    private static Slot classify(String fn, String parameter, Expr planted, Expr control)
    {
        String named = eval(planted, NAME);
        String other = eval(planted, OTHER);
        if (isError(named) && isError(other))
        {
            // refused only when the slot refuses the neutral literal as well (review LOW-3): a
            // literal that errors where "a" answers is not a refusal of literals but a reading of
            // THIS literal — unexplained, so the roster may not file it
            String neutral = eval(control, NAME);
            if (isError(neutral))
            {
                return new Slot(fn, parameter, Verdict.REFUSED, named);
            }
            // a value check on the literal's SHAPE (lpad's one-character fill): a literal of the
            // same shape that names nothing is refused too — a data slot refusing this value
            String shape = eval(swap(planted, NAME, SAME_SHAPE), NAME);
            return isError(shape) ? new Slot(fn, parameter, Verdict.REFUSED, named)
                    : new Slot(fn, parameter, Verdict.AMBIGUOUS,
                            named + " while the neutral literal answers " + neutral);
        }
        if (named.equals(other))
        {
            return new Slot(fn, parameter, Verdict.DATA, named);
        }
        String c1 = eval(control, NAME);
        String c0 = eval(control, OTHER);
        return c1.equals(c0) ? new Slot(fn, parameter, Verdict.NAME, named + " / " + other)
                : new Slot(fn, parameter, Verdict.AMBIGUOUS,
                        named + " / " + other + " / " + c1 + " / " + c0);
    }


    private static boolean stageARefuses(Expr root, String parameter)
    {
        Expr checked = isBoolean(root) ? root : call("empty", List.of(root), Map.of());
        SequencedMap<Severity, Expr> levels = new LinkedHashMap<>();
        levels.put(Severity.ERROR, checked);
        return StageAChecker.check(new Rule(), levels).findings().stream()
                .anyMatch(f -> f.kind() == StageAErrorKind.PARAMETER_TYPE
                        && f.message().contains("'" + parameter + "'"));
    }

    // ---------------------------------------------------------------- base calls


    /** The base expression for planting at {@code p}: the explicit one, else an auto-built call. */
    private static @Nullable Expr baseCall(FunctionDescriptor d, Parameter p)
    {
        String text = BASE.getOrDefault(d.name() + "." + p.name(), BASE.get(d.name()));
        if (text != null)
        {
            Expr base = CheckExpressionParser.parse(text);
            return isError(eval(base, NAME)) ? null : base;
        }
        return autoBase(d, d.parameters().indexOf(p));
    }


    /**
     * A call binding every required parameter positionally, with the first combination of per-type
     * candidates (the planted slot's included) that evaluates in the named world.
     */
    private static @Nullable Expr autoBase(FunctionDescriptor d, int planted)
    {
        List<Parameter> ps = d.parameters();
        // positionals bind by position: every parameter up to the last required one (or the
        // planted one) is given, so none shifts; a trailing collector only when it is planted
        int last = planted;
        for (int i = 0; i < ps.size(); i++)
        {
            if (ps.get(i).required())
            {
                last = Math.max(last, i);
            }
        }
        int[] choice = new int[ps.size()];
        for (int attempt = 0; attempt < 400; attempt++)
        {
            List<Expr> args = new ArrayList<>();
            for (int i = 0; i <= last; i++)
            {
                Parameter p = ps.get(i);
                if (!p.collector() || i == planted)
                {
                    List<Expr> c = candidates(p.type());
                    args.add(c.get(choice[i] % c.size()));
                }
            }
            Expr base = call(d.name(), args, Map.of());
            if (!isError(eval(base, NAME)))
            {
                return base;
            }
            if (!advance(choice, ps, planted))
            {
                return null;
            }
        }
        return null;
    }


    private static boolean advance(int[] choice, List<Parameter> ps, int planted)
    {
        for (int j = 0; j < ps.size(); j++)
        {
            if (j != planted && (!ps.get(j).required() || ps.get(j).collector()))
            {
                continue;
            }
            choice[j]++;
            if (choice[j] < candidates(ps.get(j).type()).size())
            {
                return true;
            }
            choice[j] = 0;
        }
        return false;
    }


    private static List<Expr> candidates(ExprType t)
    {
        if (t == Primitive.STRING)
        {
            return List.of(lit(NEUTRAL), lit("DATA"), lit("X"));
        }
        if (t == Primitive.NUMBER)
        {
            return List.of(new Expr.Lit(Expr.LitKind.NUMBER, 1.0));
        }
        if (t == Primitive.BOOLEAN)
        {
            return List.of(new Expr.Lit(Expr.LitKind.BOOL, true),
                    new Expr.Binary(Expr.BinOp.EQ, ref("X"), lit(NEUTRAL)));
        }
        if (t == Primitive.REGEX)
        {
            return List.of(new Expr.Lit(Expr.LitKind.REGEX, NEUTRAL));
        }
        if (t == Primitive.DATE || t == Primitive.TIME)
        {
            return List.of(ref("DT"), lit("2020-01-01"));
        }
        if (t == Primitive.COLUMN_REFERENCE)
        {
            return List.of(ref("X"));
        }
        if (t == Primitive.DATASET_REFERENCE)
        {
            return List.of(lit("DM"));
        }
        if (t instanceof ListOf)
        {
            return List.of(list(ref("X")), list(lit(NEUTRAL)));
        }
        return List.of(ref("X"), ref("NUM"), lit(NEUTRAL), list(ref("X")),
                call("varname", List.of(), Map.of()));
    }


    /**
     * {@code base} with parameter {@code index} of its first {@code d} call set to {@code value}.
     */
    private static Expr plant(Expr base, FunctionDescriptor d, int index, Expr value)
    {
        Parameter p = d.parameters().get(index);
        boolean[] done =
        {
                false
        };
        return replaceCall(base, d.name(), c ->
        {
            List<Expr> args = new ArrayList<>(c.args());
            Map<String, Expr> kwargs = new LinkedHashMap<>(c.kwargs());
            if (p.collector())
            {
                args.add(value);
            }
            else if (index < args.size())
            {
                args.set(index, value);
            }
            else
            {
                kwargs.put(p.name(), value);
            }
            return new Expr.Call(c.name(), args, kwargs);
        }, done);
    }


    /**
     * {@code e} with every STRING literal {@code from} (list members included) spelled {@code to}.
     */
    private static Expr swap(Expr e, String from, String to)
    {
        return switch (e)
        {
        case Expr.Lit l when l.kind() == Expr.LitKind.STRING && from.equals(l.value()) -> lit(to);
        case Expr.Lit l when l.kind() == Expr.LitKind.LIST
                && l.value() instanceof List<?> items -> new Expr.Lit(Expr.LitKind.LIST,
                        items.stream().map(i -> swap((Expr) i, from, to)).toList());
        case Expr.Call c ->
        {
            List<Expr> args = c.args().stream().map(a -> swap(a, from, to)).toList();
            Map<String, Expr> kwargs = new LinkedHashMap<>();
            c.kwargs().forEach((k, v) -> kwargs.put(k, swap(v, from, to)));
            yield new Expr.Call(c.name(), args, kwargs);
        }
        case Expr.Binary b -> new Expr.Binary(b.op(), swap(b.left(), from, to),
                swap(b.right(), from, to));
        case Expr.Not n -> new Expr.Not(swap(n.inner(), from, to));
        default -> e;
        };
    }


    private static Expr replaceCall(Expr e, String fn, UnaryOperator<Expr.Call> f, boolean[] done)
    {
        if (done[0])
        {
            return e;
        }
        return switch (e)
        {
        case Expr.Call c when c.name().equals(fn) ->
        {
            done[0] = true;
            yield f.apply(c);
        }
        case Expr.Call c ->
        {
            List<Expr> args = new ArrayList<>();
            c.args().forEach(a -> args.add(replaceCall(a, fn, f, done)));
            Map<String, Expr> kwargs = new LinkedHashMap<>();
            c.kwargs().forEach((k, v) -> kwargs.put(k, replaceCall(v, fn, f, done)));
            yield new Expr.Call(c.name(), args, kwargs);
        }
        case Expr.Binary b -> new Expr.Binary(b.op(), replaceCall(b.left(), fn, f, done),
                replaceCall(b.right(), fn, f, done));
        case Expr.Not n -> new Expr.Not(replaceCall(n.inner(), fn, f, done));
        default -> e;
        };
    }

    // ---------------------------------------------------------------- evaluation


    /** The call's answer in both content variants of the world named {@code n}. */
    private static String eval(Expr root, String n)
    {
        String filled = eval1(root, n, false);
        String missing = eval1(root, n, true);
        return isError(filled) && isError(missing) ? filled : filled + " || " + missing;
    }


    private static boolean isError(String answer)
    {
        return answer.startsWith("ERR") && !answer.contains(" || ");
    }


    private static boolean isBoolean(Expr root)
    {
        if (root instanceof Expr.Call c)
        {
            FunctionDescriptor d = FunctionRegistry.descriptor(c.name());
            return d != null && d.kind() == FunctionKind.BOOLEAN;
        }
        return !(root instanceof Expr.Lit) && !(root instanceof Expr.Ref);
    }

    private static final Set<String> NEGATION_ONLY = CompilerDispatchedCalls
            .negationDispatchedBooleanCallNames();

    private static String eval1(Expr root0, String n, boolean missing)
    {
        EvaluationContext ctx = world(n, missing);
        Expr root = root0 instanceof Expr.Call c && NEGATION_ONLY.contains(c.name())
                ? new Expr.Not(root0)
                : root0;
        try
        {
            if (isBoolean(root))
            {
                return "B" + NativeExprEvaluator.evaluate(root, ctx);
            }
            Vector v = ExprCompiler.evaluateValueExpression(root, ctx);
            if (v == null)
            {
                return "null";
            }
            StringBuilder sb = new StringBuilder("V");
            for (int r = 0; r < ctx.rowCount(); r++)
            {
                TypedValue tv = v.value(r);
                sb.append('|')
                        .append(tv == null || tv.cell() == null ? null : tv.cell().getValue());
            }
            return sb.toString();
        }
        catch (RuntimeException e)
        {
            return "ERR " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    // ---------------------------------------------------------------- the two worlds


    /**
     * The world named {@code n}: everything that bears the name exists under {@code n} — the column
     * (and its decode partner {@code n} minus its trailing {@code N}), a dataset, Define / Library
     * metadata, value-level metadata; everything else is the same in both worlds.
     *
     * <p>
     * Only the context is built per evaluation; its tables, resolver, metadata and VLM are built
     * once per world and shared, since nothing the evaluator does writes to them. Rebuilding them
     * per evaluation made the measurement dominate the suite under Pitest, which re-runs it for
     * every covered mutant.
     */
    private static EvaluationContext world(String n, boolean missing)
    {
        World w = WORLDS.computeIfAbsent(n + (missing ? "/missing" : "/filled"),
                k -> buildWorld(n, missing));
        return EvaluationContext.builder().table(w.primary()).datasetResolver(w.resolver())
                .domainName("AE").domainPrefix("AE").variableWildcardPrefix("AE")
                .className("EVENTS").defineProvider(w.metadata()).libraryProvider(w.metadata())
                .vlmResolver(w.vlm()).dictionaryProvider(dictionary)
                .variables(Map.of("variable_name", "X")).build();
    }

    /** The immutable parts of one world, shared by every evaluation in it. */
    private record World(IDataTable primary, DatasetResolver.WithInventory resolver,
            Metadata metadata, VlmResolver vlm)
    {
    }

    private static final Map<String, World> WORLDS = new HashMap<>();

    private static World buildWorld(String n, boolean missing)
    {
        MockTable primary = MockTable.of().name("AE");
        primary = missing ? primary.colSasMissing(n, null, null) : primary.col(n, "q1", "q2");
        IDataTable ae = primary.col(n.substring(0, n.length() - 1), "Decoded1", "Other")
                .colMeta(n, "Lbl", 8, "$8.").col("X", "a", "b").col("Y", "a", "a")
                .col("USUBJID", "S1", "S2").col("AESEQ", "1", "2").colLong("NUM", 1L, 2L)
                .col("DT", "2020-01-01", "2020-02-01").col("SER2", "s", "s").col("SER3", "s", "s")
                .col("CTREF", "CDISC CT", "CDISC CT").col("CTVER", "2020-03-27", "2020-03-27")
                .col("RDOMAIN", "DM", "DM").col("AEHLT", "Headaches NEC", "Headaches NEC")
                .col("AESOC", "Nervous system disorders", "Nervous system disorders").build();
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S1", "S2").col("X", "a", "b")
                .build();
        IDataTable named = MockTable.of().name(n).col("USUBJID", "S1", "S2").col("X", "a", "c")
                .col(n, "q1", "q3").col("DT", "2021-01-01", "2021-02-01").colLong("NUM", 5L, 7L)
                .colMeta("X", "OtherLbl", 4, "$4.").label("OtherDs").build();
        Map<String, IDataTable> inventory = new LinkedHashMap<>();
        inventory.put("AE", ae);
        inventory.put("DM", dm);
        inventory.put(n, named);
        DatasetResolver.WithInventory resolver = new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                return name == null ? null : inventory.get(name.toUpperCase(Locale.ROOT));
            }


            @Override
            public Set<String> availableDatasets()
            {
                return inventory.keySet();
            }
        };
        return new World(ae, resolver, new Metadata(n), vlm(n));
    }


    /** VLM for variable {@code n}: where {@code X EQ "a"}, a float of length 3 with a decode. */
    private static VlmResolver vlm(String n)
    {
        CheckValue a = new CheckValue();
        a.setValue("a");
        TranslatedText text = new TranslatedText();
        text.setValue("Decoded1");
        Decode decode = new Decode();
        decode.setTranslatedTexts(List.of(text));
        ItemDef x = ItemDef.builder().oid("IT.X").name("X").dataType("text").build();
        ItemDef target = ItemDef.builder().oid("IT.N").name(n).dataType("text")
                .valueListRef(ValueListRef.builder().valueListOID("VL.N").build()).build();
        CodeList codes = CodeList.builder().oid("CL.U")
                .codeListItems(
                        List.of(CodeListItem.builder().codedValue("q1").decode(decode).build()))
                .build();
        ItemDef valueLevel = ItemDef.builder().oid("IT.N.A").name("SET_A").dataType("float")
                .length(3).codeListRef(CodeListRef.builder().codeListOID("CL.U").build()).build();
        WhereClauseDef where = WhereClauseDef.builder().oid("WC.A").rangeChecks(List.of(RangeCheck
                .builder().comparator("EQ").itemOID("IT.X").checkValues(List.of(a)).build()))
                .build();
        ValueListDef values = ValueListDef.builder().oid("VL.N")
                .itemRefs(List.of(ItemRef.builder().itemOID("IT.N.A").orderNumber(1)
                        .mandatory("Yes")
                        .whereClauseRefs(
                                List.of(WhereClauseRef.builder().whereClauseOID("WC.A").build()))
                        .build()))
                .build();
        ItemGroupDef group = ItemGroupDef.builder().oid("IG.AE").name("AE").domain("AE")
                .itemRefs(List.of(ItemRef.builder().itemOID("IT.N").build(),
                        ItemRef.builder().itemOID("IT.X").build()))
                .build();
        MetaDataVersion mdv = MetaDataVersion.builder().itemGroupDefs(List.of(group))
                .itemDefs(List.of(x, target, valueLevel)).valueListDefs(List.of(values))
                .whereClauseDefs(List.of(where)).codeLists(List.of(codes)).build();
        return Objects.requireNonNull(VlmResolver.from(mdv));
    }

    /** Define / Library metadata that knows exactly one variable and one dataset: {@code n}. */
    private static final class Metadata implements MetadataProvider
    {

        private final String name;

        Metadata(String aName)
        {
            name = aName;
        }


        private static String sample(MetadataAttribute a)
        {
            return switch (a.normalization())
            {
            case NUMERIC -> "8";
            case BOOLEAN, MANDATORY -> "Yes";
            case TYPE -> "text";
            case ROLE -> "Identifier";
            case CORE -> "Req";
            default -> a.isList() ? "[\"q1\"]" : "v";
            };
        }


        private static Map<String, String> meta(MetadataAttribute.Scope scope)
        {
            Map<String, String> m = new HashMap<>();
            for (MetadataAttribute a : MetadataAttribute.values())
            {
                if (a.scope() == scope)
                {
                    m.put(a.providerKey(), sample(a));
                }
            }
            return m;
        }


        @Override
        public List<String> getRequiredVariables(String domain)
        {
            return List.of("X");
        }


        @Override
        public List<String> getExpectedVariables(String domain)
        {
            return List.of("X");
        }


        @Override
        public List<String> getColumnOrder(String domain)
        {
            return List.of("X");
        }


        @Override
        public boolean isDomainCustom(String domain)
        {
            return false;
        }


        @Override
        public List<String> getCodelistTerms(String codelistCode)
        {
            return List.of(NEUTRAL);
        }


        @Override
        public List<String> getCodelistAttribute(String aCtPackageId, String aCtAttribute)
        {
            return List.of("C1");
        }


        @Override
        public @Nullable List<String> getStandardModelVariables(IDataTable aTable,
                DatasetResolver aResolver)
        {
            return List.of("X");
        }


        @Override
        public Map<String, String> getVariableMetadata(String domain, String variable)
        {
            if (!name.equalsIgnoreCase(variable))
            {
                return Map.of();
            }
            Map<String, String> m = meta(MetadataAttribute.Scope.VARIABLE);
            // runbook W1b DV: the known variable's codelist decodes its cells (q1 / q2), so
            // codelist_decode(<name>) answers and codelist_decode(<neutral>) is missing
            m.put("codelist_code_decode", net.cumba.corej.core.metadata.DefineMetadataListCodec
                    .encodeStringMap(Map.of("q1", "Decoded1", "q2", "Other")));
            return m;
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String domain)
        {
            // The row serves `core`, the base call's key: a key_name no row carries SKIPs the
            // rule (CDW-F1), which would leave get_dataset_filtered_variables without a base.
            return List.of(Map.of("name", "X", "core", "Req"));
        }


        @Override
        public Map<String, String> getDatasetMetadata(String domain)
        {
            return name.equalsIgnoreCase(domain) ? meta(MetadataAttribute.Scope.DATASET) : Map.of();
        }


        @Override
        public Optional<Boolean> isCodelistExtensible(String codelistName)
        {
            return Optional.of(Boolean.TRUE);
        }


        @Override
        public String getStandard()
        {
            return "SDTMIG";
        }
    }

    // ---------------------------------------------------------------- shorthand

    private static Expr lit(String s)
    {
        return new Expr.Lit(Expr.LitKind.STRING, s);
    }


    private static Expr ref(String s)
    {
        return new Expr.Ref(s, OperandKind.COLUMN);
    }


    private static Expr list(Expr... members)
    {
        return new Expr.Lit(Expr.LitKind.LIST, List.of(members));
    }


    private static Expr call(String fn, List<Expr> args, Map<String, Expr> kwargs)
    {
        return new Expr.Call(fn, args, kwargs);
    }
}
