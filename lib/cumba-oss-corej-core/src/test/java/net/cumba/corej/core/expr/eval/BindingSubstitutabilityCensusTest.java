package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.model.Rule;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-binding-substitutability} §6 (2), runbook W1a BS0 — the <b>synthetic census</b>: for
 * every parameter of every registry descriptor, a dataset-level expression of the declared type is
 * planted twice — bound ({@code $x: <expr>}, read as {@code $x}) and the same expression inline —
 * and the two load outcomes are compared. D3 / D16 (G1) say they must agree: a binding is only a
 * name for its expression, so the only legitimate difference is none. Every (function, parameter)
 * whose outcomes differ is a row of {@link #EXPECTED}, an EXACT roster (memory
 * {@code count-pin-blind-to-rekey}): a seam that refuses one spelling and admits the other shows up
 * here as a new row, and a widening that closes one reds until its row is removed.
 *
 * <p>
 * Reading the roster: {@code f[i] p: bound=… | inline=…} — the planted expression and the binding
 * name are normalised to {@code ‹arg›} so that only a genuine difference in judgement differs, not
 * the printed operand. Rows are classified in the javadoc of {@link #EXPECTED} against the plan's
 * candidate table (§3): the ones the W1a units BS1–BS3 widen leave with those units; the declared
 * exceptions (G2: a name or a literal — C7, C8, C12–C14 — and the cursor-defining binding, C15)
 * stay.
 * </p>
 */
class BindingSubstitutabilityCensusTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The planted dataset-level expression per declared type (a computed one, never a literal). */
    private static String planted(ExprType type, boolean nameSlot)
    {
        if (nameSlot)
        {
            return "\"X\"";
        }
        return switch (type)
        {
        case ExprType.Primitive p -> switch (p)
        {
        case STRING -> "upper(\"a\")";
        case NUMBER -> "1 + 1";
        case BOOLEAN -> "true";
        case DATE -> "\"2020-01-01\"";
        case TIME -> "\"10:00\"";
        case REGEX -> "/a/";
        case COLUMN_REFERENCE -> "X";
        case DATASET_REFERENCE -> "DM";
        case METADATA_LEVEL -> "\"DATA\"";
        };
        case ExprType.ListOf l -> l.element() == ExprType.Primitive.STRING
                ? "minus([\"a\"], subtract=[\"b\"])"
                : "distinct(X)";
        case ExprType.Unknown _ -> "upper(\"a\")";
        };
    }


    /** A plain filler for the parameters not under test. */
    private static String filler(Parameter p)
    {
        if (p.nameSlot())
        {
            return "X";
        }
        return switch (p.type())
        {
        case ExprType.Primitive prim -> switch (prim)
        {
        case STRING -> "\"a\"";
        case NUMBER -> "1";
        case BOOLEAN -> "true";
        case DATE -> "\"2020-01-01\"";
        case TIME -> "\"10:00\"";
        case REGEX -> "/a/";
        case COLUMN_REFERENCE -> "X";
        case DATASET_REFERENCE -> "DM";
        case METADATA_LEVEL -> "\"DATA\"";
        };
        case ExprType.ListOf _ -> "[\"a\"]";
        case ExprType.Unknown _ -> "\"a\"";
        };
    }

    /**
     * The measured asymmetries (clone HEAD after W1a U3, 2026-10-07), one row per (function,
     * parameter), classified against the plan's §3 table:
     * <ul>
     * <li><b>C10 — left with BS1 (2026-10-08):</b> {@code case_sensitive} of the five dictionary
     * functions was refused bound and admitted inline ({@code rejectNonLiteralDictionaryFlags}); it
     * is any dataset-level boolean now, judged by its level
     * ({@code ListOperandLevel.PER_CALL_PARAMETERS}). C9 / C11 (the open-vocabulary strings, the
     * static lists) were symmetric here before BS1 — both spellings refused by one seam — and are
     * symmetric after it — both admitted; {@code BindingSubstitutabilityTest} carries their
     * evidence.</li>
     * <li><b>C7 — declared exception (G2, a {@code regex} is a literal type):</b> the
     * {@code pattern} of {@code imatches} / {@code prefix_matches} / {@code suffix_matches} takes
     * the {@code /regex/} literal only; a binding holding one is refused. Stays.</li>
     * <li><b>NEW candidate (not in §3), reported by BS0:</b> {@code distinct}'s {@code name} — the
     * quoted name {@code "X"} is admitted inline, but a name-form binding {@code $x: "X"} is
     * refused by the tailored reader ("must be a plain column reference"): the parameter is not
     * declared a name slot, so {@code inlineBoundNames} never inlines it (compare {@code colref},
     * {@code var_*}). Class X-inconsistent; a ruling on whether to declare it a name slot is owed
     * to the plan, not taken here.</li>
     * </ul>
     * Everything else the census visits (static strings, static lists, closed vocabularies,
     * {@code external_dictionary_type}, the list-set operands since U3, the splice slots, every
     * typed parameter) refuses or admits both spellings alike.
     */
    private static final Set<String> EXPECTED = new TreeSet<>(Set.of(
            "distinct[0] name: bound=argument 'name' of 'distinct' must be a plain column reference; ‹arg› is not one | inline=clean",
            "imatches[1] pattern: bound=imatches requires a /regex/ literal pattern argument | inline=clean",
            "prefix_matches[1] pattern: bound=prefix_matches requires a /regex/ literal pattern argument | inline=clean",
            "suffix_matches[1] pattern: bound=suffix_matches requires a /regex/ literal pattern argument | inline=clean"));

    /** Floor of (descriptor, parameter) pairs the census must visit — the registry was walked. */
    private static final int VISITED_FLOOR = 150;

    @Test
    void everyParameterJudgesTheBoundAndTheInlineSpellingAlike() throws Exception
    {
        Set<String> rows = new TreeSet<>();
        int visited = 0;
        for (FunctionDescriptor d : FunctionRegistry.all())
        {
            List<Parameter> params = d.parameters();
            for (int i = 0; i < params.size(); i++)
            {
                Parameter p = params.get(i);
                String plant = planted(p.type(), p.nameSlot());
                String bound = outcome(d, i, "$x", plant);
                String inline = outcome(d, i, plant, null);
                visited++;
                if (!bound.equals(inline))
                {
                    rows.add(d.name() + "[" + i + "] " + p.name() + ": bound=" + bound
                            + " | inline=" + inline);
                }
            }
        }
        assertTrue(visited >= VISITED_FLOOR, "the registry was walked: " + visited);
        assertEquals(EXPECTED, rows,
                () -> "the substitutability census moved — a seam judges the"
                        + " bound and the inline spelling differently (D3 / D16); rows:\n"
                        + String.join("\n", rows));
    }


    /**
     * The normalised load outcome of {@code f(…, arg at i, …)}: {@code clean}, or the load error
     * with the operand and the binding name normalised.
     */
    private static String outcome(FunctionDescriptor d, int at, String arg,
            @Nullable String binding)
        throws Exception
    {
        List<Parameter> params = d.parameters();
        int last = at;
        for (int i = 0; i < params.size(); i++)
        {
            if (params.get(i).required() && !params.get(i).collector())
            {
                last = Math.max(last, i);
            }
        }
        List<String> positionals = new ArrayList<>();
        for (int i = 0; i <= last; i++)
        {
            positionals.add(i == at ? arg : filler(params.get(i)));
        }
        String call = d.name() + "(" + String.join(", ", positionals) + ")";
        List<String[]> bindings = new ArrayList<>();
        if (binding != null)
        {
            bindings.add(new String[]
            {
                    "$x", binding
            });
        }
        String check;
        if (d.kind() == FunctionKind.BOOLEAN)
        {
            check = call;
        }
        else
        {
            bindings.add(new String[]
            {
                    "$v", call
            });
            check = "not empty($v)";
        }
        String planted = binding != null ? binding : arg;
        String error;
        try
        {
            error = load(check, bindings).getLoadError();
        }
        catch (com.fasterxml.jackson.databind.JsonMappingException unparseable)
        {
            error = "unparseable: " + unparseable.getOriginalMessage();
        }
        if (error == null)
        {
            return "clean";
        }
        return normalise(error.replace(planted, "‹arg›").replace("$x", "‹arg›"));
    }


    /**
     * A seam describes the refused operand in its own words ("the reference $x", "Call", "the call
     * upper(…)"); the description is not the judgement, so it is folded to {@code ‹operand›} and
     * the message cut to its first 160 characters — two spellings refused by the SAME seam then
     * compare equal, and only a different verdict (clean vs refused, or another seam) is a row.
     */
    private static String normalise(String error)
    {
        String folded = error.split("\n", 2)[0].replaceAll(
                "\\bthe (column reference|reference|call|list literal|literal)\\b[^,:;]*",
                "‹operand›").replaceAll(", not [A-Z][A-Za-z.]*\\b", ", not ‹operand›");
        folded = folded.replace("Expression construct not supported by the native evaluator: ", "");
        return folded.length() > 120 ? folded.substring(0, 120) : folded;
    }


    private static Rule load(String check, List<String[]> bindings) throws Exception
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "BS0-CENSUS"));
        List<Map<String, String>> declared = new ArrayList<>();
        for (String[] b : bindings)
        {
            declared.add(Map.of("name", b[0], "expression", b[1]));
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
