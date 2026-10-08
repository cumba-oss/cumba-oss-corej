package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueMissing;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * {@code contains_any(x, list)} — {@code PLAN-engine-gap-list-elementwise} §6.1 Q1 (b), runbook W1b
 * unit LE1′: true when {@code x} contains any element of {@code list} as a substring. The edges are
 * the runbook R2 note's (coordinator D5), each the existing {@code contains} behaviour: a list at
 * {@code x} is {@code PARAMETER_TYPE} (never the membership arm); a genuinely missing {@code x} or
 * a missing {@code list} ⇒ {@code false}; a missing element never matches and does not make the
 * call false; {@code []} ⇒ {@code false}; an element {@code ""} matches every present {@code x}; a
 * numeric element renders as {@code contains}' canonical needle; no case folding; the negation is
 * {@code not contains_any(…)}.
 */
class ContainsAnyTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }


    /** Explicit cells: {@code null} is a genuine missing ({@code MIS}), never a blank. */
    private static Vector cells(String... values)
    {
        return ComputedVector.typed(values.length, DataValueType.STRING,
                row -> values[row] == null ? new DataValueMissing(MissingValue.MIS)
                        : DataValues.of(values[row]));
    }


    private static BitSet containsAny(Vector x, Object list, int rows)
    {
        return Primitives.containsAny(x, ConstVector.of(list), rows);
    }

    // ---- the kernel ----


    @Test
    void anElementOccurringAnywhereInXFires()
    {
        Vector x = cells("HEADACHE", "NAUSEA", "RASH", "SEASICK");
        assertEquals(bits(0, 1, 3), containsAny(x, List.of("ACHE", "SEA"), 4));
        assertEquals(bits(2), containsAny(x, List.of("RAS"), 4), "a single element");
    }


    @Test
    void aMissingXIsFalseAndABlankXIsAPresentEmptyString()
    {
        Vector x = cells(null, "", "A");
        assertEquals(bits(1, 2), containsAny(x, List.of(""), 3),
                "\"\" matches every PRESENT x (D34 #1, as contains(X, \"\")), never a missing one");
        assertEquals(bits(2), containsAny(x, List.of("A", "B"), 3));
    }


    @Test
    void aMissingListIsFalseOnEveryRow()
    {
        Vector x = cells("A", "");
        Vector missingList = ComputedVector.typed(2, DataValueType.STRING,
                _ -> new DataValueMissing(MissingValue.MIS));
        assertEquals(new BitSet(), Primitives.containsAny(x, missingList, 2));
    }


    @Test
    void aMissingElementNeverMatchesAndDoesNotMakeTheCallFalse()
    {
        Vector x = cells("HEADACHE", ".", "NAUSEA");
        assertEquals(bits(0), containsAny(x, List.of(MissingValue.MIS, "ACHE"), 3),
                "the missing element is skipped, the present one still answers");
        assertEquals(new BitSet(), containsAny(x, List.of(MissingValue.MIS), 3),
                "a missing element is not its display text \".\" (D34 #5-2)");
        assertEquals(new BitSet(), containsAny(x, List.of(DataValues.of(MissingValue.MIS_A)), 3),
                "a missing cell element never matches either");
    }


    @Test
    void theEmptyListIsFalse()
    {
        assertEquals(new BitSet(), containsAny(cells("A", ""), List.of(), 2));
    }


    @Test
    void aNumericElementRendersAsContainsRendersANumericNeedle()
    {
        Vector x = cells("CODE100", "CODE100.5", "CODE7");
        assertEquals(bits(0, 1), containsAny(x, List.of(100.0), 3),
                "100.0 probes \"100\" (canonical number text), as contains(X, 100) does");
        assertEquals(bits(1), containsAny(x, List.of(100.5), 3));
    }


    @Test
    void thereIsNoCaseFolding()
    {
        Vector x = cells("HEADACHE");
        assertEquals(new BitSet(), containsAny(x, List.of("ache"), 1));
        assertEquals(bits(0), containsAny(x, List.of("ache", "HEAD"), 1));
    }


    @Test
    void aPerRowListIsReadOnItsOwnRow()
    {
        Vector x = cells("HEADACHE", "HEADACHE", "HEADACHE", "HEADACHE");
        Vector perRow = new ComputedVector(4, DataValueType.STRING, row -> switch (row)
        {
        case 0 -> List.of("ACHE");
        case 1 -> List.of("NAUSEA");
        case 2 -> DataValues.of(MissingValue.MIS);
        default -> List.of("X", "HEAD");
        });
        assertEquals(bits(0, 3), Primitives.containsAny(x, perRow, 4));
    }


    @Test
    void aListAtXIsTheRulesErrorNeverMembership()
    {
        Vector listAtX = ConstVector.of(List.of("A", "B"));
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> Primitives.containsAny(listAtX, ConstVector.of(List.of("A")), 1));
        assertTrue(ex.getMessage().contains("contains_any"), ex.getMessage());
    }


    @Test
    void aScalarAtListIsTheRulesError()
    {
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> Primitives.containsAny(cells("A"), ConstVector.of("A"), 1));
        assertTrue(ex.getMessage().contains("list"), ex.getMessage());
    }

    // ---- registration, stage A and the run ----


    @Test
    void theDescriptorTypesXAsAStringAndListAsAListOfStrings()
    {
        FunctionDescriptor d = FunctionRegistry.descriptor("contains_any");
        assertNotNull(d, "contains_any is registered");
        assertEquals(FunctionKind.BOOLEAN, d.kind());
        assertEquals(List.of("x", "list"), d.parameters().stream().map(Parameter::name).toList());
        assertEquals("string", d.parameters().get(0).type().describe());
        assertEquals("list<string>", d.parameters().get(1).type().describe());
    }


    @Test
    void aBoundListAndAnInlineListLoadAndAnswerAlike() throws Exception
    {
        IDataTable ae = MockTable.of().col("AETERM", "HEADACHE", "NAUSEA", "RASH", "").name("AE")
                .build();
        Rule bound = load("contains_any(AETERM, $terms)", "$terms", "[\"ACHE\", \"SEA\"]");
        Rule inline = load("contains_any(AETERM, [\"ACHE\", \"SEA\"])");
        assertEquals(List.of(1L, 2L), firingRows(bound, ae), "violation rows are 1-based");
        assertEquals(List.of(1L, 2L), firingRows(inline, ae));
        Rule negated = load("not contains_any(AETERM, $terms)", "$terms", "[\"ACHE\", \"SEA\"]");
        assertEquals(List.of(3L, 4L), firingRows(negated, ae), "the negation is `not`");
    }


    @Test
    void aPerRowSplitByListLoadsAndAnswersPerRow() throws Exception
    {
        IDataTable ae = MockTable.of().col("AETERM", "HEADACHE", "HEADACHE", "HEADACHE")
                .col("TOKENS", "ACHE;X", "Y;Z", "").name("AE").build();
        Rule rule = load("contains_any(AETERM, split_by(TOKENS, \";\"))");
        // the third row: split_by("") is the computed MIS — a missing list ⇒ false
        assertEquals(List.of(1L), firingRows(rule, ae));
    }


    @Test
    void stageARefusesAListAtXAndAScalarAtList() throws Exception
    {
        assertParameterType(load("contains_any($terms, \"A\")", "$terms", "[\"A\"]"), "x");
        assertParameterType(load("contains_any(AETERM, \"ACHE\")"), "list");
        assertParameterType(load("contains_any(AETERM, [1, 2])"), "list");
    }


    private static void assertParameterType(Rule rule, String parameter)
    {
        String error = rule.getLoadError();
        assertNotNull(error, "must be a load error");
        assertTrue(error.contains("argument '" + parameter + "' of 'contains_any'"), error);
    }


    private static List<Long> firingRows(Rule rule, IDataTable table)
    {
        assertNull(rule.getLoadError(), rule.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        return result.getViolations().stream().map(Violation::getRowNumber).toList();
    }


    private static Rule load(String check, String... bindings) throws Exception
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "T-CONTAINS-ANY"));
        rule.put("Scope", Map.of("Domains", Map.of("Include", List.of("AE"))));
        List<Map<String, String>> declared = new ArrayList<>();
        for (int i = 0; i < bindings.length; i += 2)
        {
            declared.add(Map.of("name", bindings[i], "expression", bindings[i + 1]));
        }
        if (!declared.isEmpty())
        {
            rule.put("Bindings", declared);
        }
        rule.put("Check", Map.of("expression", check));
        rule.put("Outcome", Map.of("Message", "m"));
        String json = MAPPER.writeValueAsString(Map.of("rules", Map.of("x", rule)));
        Rule loaded = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(loaded, "the rule parses");
        return loaded;
    }
}
