package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.expr.eval.NativeExprEvaluator;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.model.BoundBinding;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * The list form of {@code upper} / {@code lower} — confirmed by the owner (2026-09-28,
 * {@code PLAN-case-insensitive-templates} §1 / register {@code CIT §3}: <i>"upper allows a list of
 * strings as parameter and returns a list of these strings converted to upper case"</i>, and
 * {@code lower} <i>"for the same list as well"</i>) as the rule-side way to compare a dataset's
 * column names against the library's upper-case names: a string that holds a column name is still a
 * string, so the comparison operators stay exactly as ruled and the RULE normalises the dataset
 * side. Mockito-free: real tables, real compiled bindings, the real evaluator.
 */
class UpperLowerListFormTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    private static Rule load(String check, String... bindings) throws Exception
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "CIT3-UPPER-LIST"));
        List<Map<String, String>> declared = new ArrayList<>();
        for (int i = 0; i < bindings.length; i += 2)
        {
            declared.add(Map.of("name", bindings[i], "expression", bindings[i + 1]));
        }
        rule.put("Bindings", declared);
        rule.put("Check", Map.of("expression", check));
        rule.put("Outcome", Map.of("Message", "m", "Output_Variables", List.of()));
        String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
        Rule loaded = Objects
                .requireNonNull(RulePackageLoader.loadFromString(json).getRules().get("x"));
        assertNull(loaded.getLoadError(), loaded.getLoadError());
        return loaded;
    }


    private static EvaluationContext ctx(Rule rule, IDataTable table, Map<String, Object> raw)
    {
        Map<String, Object> variables = new LinkedHashMap<>(raw);
        for (BoundBinding bound : rule.bindingOrder())
        {
            if (bound instanceof BoundBinding.OfOperation of)
            {
                // A single operation call in Bindings is an Operation: executed, its raw result
                // enters the context exactly as RuleRunner hands it over.
                variables.put(bound.name(), OperationExecutorCalls.executeOne(of.operation(), table,
                        NO_RESOLVER, null, variables));
                continue;
            }
            CompiledBinding compiled = rule.compiledBinding(bound.name());
            if (compiled != null)
            {
                variables.put(bound.name(), new BindingValue(compiled));
            }
        }
        return EvaluationContext.builder().table(table).datasetResolver(NO_RESOLVER)
                .variables(variables).build();
    }


    private static BitSet eval(Rule rule, EvaluationContext ctx)
    {
        return NativeExprEvaluator.evaluate(Objects.requireNonNull(rule.getCheckExpr()), ctx);
    }


    private static BitSet bits(int... set)
    {
        BitSet b = new BitSet();
        for (int i : set)
        {
            b.set(i);
        }
        return b;
    }


    private static IDataTable lowercaseBw()
    {
        return RealTables.of("BW").str("studyid", "S1", "S1").str("domain", "BW", "BW")
                .str("usubjid", "001", "002").build();
    }


    @Test
    void upperFoldsTheDatasetColumnListElementWiseAndLeavesTheSourceAlone() throws Exception
    {
        Rule rule = load("not contains_all($dataset_variables_upper, $required)",
                "$dataset_variables", "get_column_order_from_dataset()", "$dataset_variables_upper",
                "upper($dataset_variables)", "$required", "[\"STUDYID\", \"DOMAIN\"]");
        EvaluationContext c = ctx(rule, lowercaseBw(), Map.of());
        assertEquals(List.of("STUDYID", "DOMAIN", "USUBJID"),
                c.resolveVariable("$dataset_variables_upper"),
                "every element of the list is upper-cased, order kept");
        assertEquals(List.of("studyid", "domain", "usubjid"),
                c.resolveVariable("$dataset_variables"),
                "the source list keeps the dataset's own spelling for output");
        assertEquals(bits(), eval(rule, c),
                "the upper-cased dataset names satisfy the library's upper-case required names");
    }


    @Test
    void withoutTheFoldAListOfNamesComparesAsStringsDo() throws Exception
    {
        // NEGATIVE CONTROL and the owner's principle in one: a string holding a column name is
        // still a string, so the un-normalised lowercase list does NOT contain "STUDYID".
        Rule rule = load("not contains_all($dataset_variables, $required)", "$dataset_variables",
                "get_column_order_from_dataset()", "$required", "[\"STUDYID\", \"DOMAIN\"]");
        assertEquals(bits(0, 1), eval(rule, ctx(rule, lowercaseBw(), Map.of())),
                "case-sensitive as ruled: every row fires");
    }


    @Test
    void aGenuinelyMissingNameStillFiresThroughTheFold() throws Exception
    {
        Rule rule = load("not contains_all($dataset_variables_upper, $required)",
                "$dataset_variables", "get_column_order_from_dataset()", "$dataset_variables_upper",
                "upper($dataset_variables)", "$required", "[\"STUDYID\", \"BWSEQ\"]");
        assertEquals(bits(0, 1), eval(rule, ctx(rule, lowercaseBw(), Map.of())),
                "BWSEQ is absent whatever the case — the fold changes case only");
    }


    @Test
    void lowerFoldsAListElementWiseToo() throws Exception
    {
        Rule rule = load("not contains_all($names_lower, $wanted)", "$names",
                "get_column_order_from_dataset()", "$names_lower", "lower($names)", "$wanted",
                "[\"aeseq\", \"aeterm\"]");
        IDataTable ae = RealTables.of("AE").str("AESEQ", "1").str("AeTerm", "X").str("aedecod", "Y")
                .build();
        EvaluationContext c = ctx(rule, ae, Map.of());
        assertEquals(List.of("aeseq", "aeterm", "aedecod"), c.resolveVariable("$names_lower"),
                "every element lower-cased, order kept");
        assertEquals(List.of("AESEQ", "AeTerm", "aedecod"), c.resolveVariable("$names"),
                "the source keeps its spelling");
        assertEquals(bits(), eval(rule, c));
    }


    @Test
    void aMissingElementStaysTheMissingValue()
    {
        // Register D36 (propagation): missing propagates through the string functions, upper
        // named first. The list form is new behaviour this plan defines, so it follows D36: a
        // MissingValue element is carried through UNCHANGED (never folded to "" nor to its
        // rendered marker), in its position; the present elements around it fold. No authored
        // list can spell a missing member, so the fold is called as the compiler calls it, on a
        // list that carries one.
        List<Object> withMissing = new ArrayList<>();
        withMissing.add("aeSeq");
        withMissing.add(MissingValue.MIS);
        withMissing.add(MissingValue.MIS_A);
        IDataTable ae = RealTables.of("AE").str("USUBJID", "S1").build();
        EvalRun run = EvalRun.fullRange(EvaluationContext.builder().table(ae)
                .datasetResolver(NO_RESOLVER).variables(new LinkedHashMap<>()).build());
        assertEquals(Arrays.asList("AESEQ", MissingValue.MIS, MissingValue.MIS_A),
                fold("upper", run, withMissing));
        assertEquals(Arrays.asList("aeseq", MissingValue.MIS, MissingValue.MIS_A),
                fold("lower", run, withMissing));
    }


    private static Object fold(String function, EvalRun run, Object argument)
    {
        FunctionDescriptor descriptor = Objects
                .requireNonNull(FunctionRegistry.descriptor(function));
        Object out = Objects.requireNonNull(descriptor.fn()).apply(run,
                List.of(ConstVector.of(argument)));
        return out instanceof Vector v ? v.value(0).resolved() : out;
    }


    @Test
    void theScalarFormIsUnchanged() throws Exception
    {
        Rule rule = load("$u == \"AESOSP\"", "$u", "upper(QNAM)");
        IDataTable supp = RealTables.of("SUPPAE").str("QNAM", "aesosp", "AESOSP", "AESER").build();
        assertEquals(bits(0, 1), eval(rule, ctx(rule, supp, Map.of())),
                "upper(QNAM) == literal is the authored case-insensitive comparison of a cell");
    }

}
