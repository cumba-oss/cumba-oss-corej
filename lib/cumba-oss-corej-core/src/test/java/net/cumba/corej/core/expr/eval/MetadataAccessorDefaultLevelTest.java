package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.TreeSet;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.MetadataOperandMapping;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.convert.BindingInliner;
import net.cumba.corej.core.expr.typed.StageAChecker;
import net.cumba.corej.core.expr.typed.StageAErrorKind;
import net.cumba.corej.core.expr.typed.StageAFinding;
import net.cumba.corej.core.expr.typed.StageAReport;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-metadata-accessor-default-level} table 2 rows 1–7 (runbook W1a unit MA-r1): a
 * metadata accessor call with no level reads the {@code DATA} level — filled in once at load by
 * {@link MetadataOperandMapping#canonicalizeMetadataOperands} (P1) and, defensively, by the
 * compiler and stage A on a tree that skipped canonicalisation; an attribute with no {@code DATA}
 * level called without one is a load error naming the levels it supports (P2 / D2); in the
 * one-argument form a string is the NAME unless it is a level literal ({@code var_label("AETERM")},
 * D3 with string names, V12); a {@code ds_*} accessor with one string that is not a level is
 * refused with a hint (P4); the {@code Filter} of a {@code Match_Datasets} entry is canonicalised
 * in its cache so P1 holds on every path (V11). ⚑ Owner O15 (2026-10-08): ruling R1 wins over D3's
 * bare-column form — <b>P3 is dropped</b>: a bare column in the name position stays refused in both
 * arities, the refusal naming the string spelling.
 */
class MetadataAccessorDefaultLevelTest
{

    private static final List<String> DATA_ACCESSORS = List.of("ds_class", "ds_domain", "ds_label",
            "ds_name", "var_format", "var_label", "var_length", "var_name", "var_ordinal",
            "var_type");

    private static IDataTable aeTable()
    {
        return MockTable.of().name("AE").label("Adverse Events").colLong("AESEQ", 1L)
                .col("AETERM", "HEADACHE").col("DOMAIN", "AE")
                .colMeta("AESEQ", "Sequence Number", 8, "8.")
                .colMeta("AETERM", "Reported Term", 200, null).build();
    }


    private static EvaluationContext ctx()
    {
        return EvaluationContext.builder().table(aeTable()).domainName("AE").className("EVENTS")
                .variables(Map.of("variable_name", "AETERM")).build();
    }


    private static Expr parse(String text)
    {
        return CheckExpressionParser.parse(text);
    }


    private static Expr canonical(String text)
    {
        return MetadataOperandMapping.canonicalizeMetadataOperands(parse(text));
    }


    private static BitSet eval(Expr e)
    {
        return NativeExprEvaluator.evaluate(e, ctx());
    }


    private static StageAReport stageA(Expr e)
    {
        SequencedMap<Severity, Expr> levels = new LinkedHashMap<>();
        levels.put(Severity.ERROR, e);
        return StageAChecker.check(new Rule(), levels);
    }


    private static List<String> findings(Expr e)
    {
        List<String> out = new ArrayList<>();
        for (var f : stageA(e).findings())
        {
            out.add(f.kind() + ": " + f.message());
        }
        return out;
    }


    private static Expr wrap(MetadataAttribute attr, Expr call)
    {
        // a boolean root for stage A: a string / number accessor compared, a list one counted, a
        // boolean one as is
        return switch (StageAChecker.accessorType(attr).describe())
        {
        case "boolean" -> call;
        case "list<string>" -> new Expr.Binary(Expr.BinOp.EQ,
                new Expr.Call("count", List.of(call), Map.of()),
                new Expr.Lit(Expr.LitKind.NUMBER, 1.0));
        case "number" -> new Expr.Binary(Expr.BinOp.EQ, call,
                new Expr.Lit(Expr.LitKind.NUMBER, 1.0));
        default -> new Expr.Binary(Expr.BinOp.EQ, call, new Expr.Lit(Expr.LitKind.STRING, "x"));
        };
    }


    private static Expr call(String fn, Expr... args)
    {
        return new Expr.Call(fn, List.of(args), Map.of());
    }


    private static Expr lit(String s)
    {
        return new Expr.Lit(Expr.LitKind.STRING, s);
    }


    @Test
    void theRosterOfDataCapableAccessorsIsExact()
    {
        TreeSet<String> data = new TreeSet<>();
        TreeSet<String> nonData = new TreeSet<>();
        for (MetadataAttribute attr : MetadataAttribute.values())
        {
            (attr.supports(MetadataLevel.DATA) ? data : nonData).add(attr.functionName());
        }
        assertEquals(new TreeSet<>(DATA_ACCESSORS), data);
        assertEquals(18, nonData.size(), nonData.toString());
        assertEquals(28, MetadataAttribute.values().length);
    }


    @Test
    void aLevelLessCallIsFilledWithDataAtLoad()
    {
        for (String fn : DATA_ACCESSORS)
        {
            assertEquals(parse(fn + "(\"DATA\")"), canonical(fn + "()"), fn);
            // the fill is idempotent and leaves the explicit forms alone
            assertEquals(parse(fn + "(\"DATA\")"), canonical(fn + "(\"DATA\")"), fn);
        }
        assertEquals(parse("var_role(\"DEFINE\")"), canonical("var_role(\"DEFINE\")"));
        assertEquals(parse("len(var_label(\"DATA\")) > 2"), canonical("len(var_label()) > 2"),
                "filled at any depth");
        // an attribute with no DATA level is left for stage A (P2)
        assertEquals(parse("var_role()"), canonical("var_role()"));
        assertEquals(parse("ds_structure()"), canonical("ds_structure()"));
    }


    @Test
    void theFilledFormAnswersWhatTheExplicitFormAnswers()
    {
        for (String expr : List.of("ds_name() == \"AE\"", "ds_label() == \"Adverse Events\"",
                "ds_domain() == \"AE\"", "ds_class() == \"EVENTS\"", "var_name() == \"AETERM\"",
                "var_label() == \"Reported Term\"", "var_length() == \"200\"",
                "var_type() == \"Char\"", "non_empty(var_ordinal())",
                "var_label(\"AESEQ\") == \"Sequence Number\"", "var_format(\"AESEQ\") == \"8.\""))
        {
            String explicit = expr.replace("()", "(\"DATA\")").replace("(\"AESEQ\")",
                    "(\"AESEQ\", \"DATA\")");
            BitSet expected = eval(parse(explicit));
            assertEquals(1, expected.cardinality(), "the explicit form fires: " + explicit);
            assertEquals(expected, eval(canonical(expr)), "canonicalised: " + expr);
            // the compiler's own default, for a tree that skipped canonicalisation
            assertEquals(expected, eval(parse(expr)), "raw: " + expr);
        }
    }


    @Test
    void stageAAcceptsTheFilledAndTheRawShortForms()
    {
        for (String fn : DATA_ACCESSORS)
        {
            MetadataAttribute attr = MetadataAttribute.fromFunction(fn);
            assertEquals(List.of(), findings(wrap(attr, canonical(fn + "()"))), fn);
            assertEquals(List.of(), findings(wrap(attr, parse(fn + "()"))), fn + " raw");
            assertEquals(List.of(), findings(wrap(attr, parse(fn + "(\"DATA\")"))), fn);
        }
    }


    @Test
    void anAttributeWithoutADataLevelCalledWithoutOneNamesItsLevels()
    {
        int checked = 0;
        for (MetadataAttribute attr : MetadataAttribute.values())
        {
            if (attr.supports(MetadataLevel.DATA))
            {
                continue;
            }
            checked++;
            String fn = attr.functionName();
            List<String> found = findings(wrap(attr, parse(fn + "()")));
            assertEquals(1, found.size(), fn + ": " + found);
            String f = found.get(0);
            assertTrue(f.startsWith(StageAErrorKind.METADATA_LEVEL_ILLEGAL + ": "), f);
            assertTrue(f.contains(fn + " has no DATA level"), f);
            for (MetadataLevel level : attr.supportedLevels())
            {
                assertTrue(f.contains(fn + "(\"" + level + "\")"), f + " names " + level);
            }
            assertFalse(f.contains("\"DATA\""), f);
            // the one-argument NAME form of a var_* attribute without DATA is the same error, the
            // name kept in the levels it names
            if (attr.scope() == MetadataAttribute.Scope.VARIABLE)
            {
                List<String> named = findings(wrap(attr, parse(fn + "(\"AETERM\")")));
                assertEquals(1, named.size(), fn + ": " + named);
                assertTrue(named.get(0).contains(fn + " has no DATA level"), named.get(0));
                assertTrue(named.get(0).contains(fn + "(\"AETERM\", \""), named.get(0));
            }
        }
        assertEquals(18, checked);
    }


    @Test
    void aStringIsTheNameInTheOneArgumentForm()
    {
        assertEquals(parse("var_label(\"AETERM\", \"DATA\")"), canonical("var_label(\"AETERM\")"));
        assertEquals(parse("var_role(\"AETERM\", \"LIBRARY\")"),
                canonical("var_role(\"AETERM\", \"LIBRARY\")"), "the explicit form untouched");
        MetadataAttribute label = MetadataAttribute.VAR_LABEL;
        assertEquals(List.of(), findings(wrap(label, parse("var_label(\"AETERM\")"))));
        assertEquals(List.of(), findings(wrap(label, canonical("var_label(\"AETERM\")"))));
        // the anchors keep working in the one-argument form too
        assertEquals(parse("var_label(variable_name, \"DATA\")"),
                canonical("var_label(variable_name)"));
        assertEquals(parse("var_label(varname(), \"DATA\")"), canonical("var_label(varname())"));
        assertEquals(eval(parse("var_label(\"AETERM\", \"DATA\") == \"Reported Term\"")),
                eval(parse("var_label(\"AETERM\") == \"Reported Term\"")), "raw string form");
        // a level literal in any case stays the level (V12)
        assertEquals(parse("var_label(\"define\")"), canonical("var_label(\"define\")"));
    }


    /**
     * Owner O15 (2026-10-08): ruling R1 wins — a bare column (or {@code DS.X}, {@code --X}) in an
     * accessor's name position stays refused in BOTH arities, by stage A and by the compiler, and
     * the refusal names the string spelling. The load-time fill leaves the one-argument column form
     * alone, so the hint repeats what the author wrote (no level they did not write).
     */
    @Test
    void aBareColumnInTheNamePositionStaysRefusedInBothAritiesWithTheStringSpelling()
    {
        MetadataAttribute label = MetadataAttribute.VAR_LABEL;
        assertEquals(parse("var_label(AETERM)"), canonical("var_label(AETERM)"), "not filled");
        assertEquals(parse("var_label(AETERM, \"DEFINE\")"),
                canonical("var_label(AETERM, \"DEFINE\")"), "not lowered");
        for (String[] c : new String[][]
        {
                {
                        "var_label(AETERM)", "write var_label(\"AETERM\")"
                },
                {
                        "var_label(AETERM, \"DEFINE\")", "write var_label(\"AETERM\", \"DEFINE\")"
                },
                {
                        "var_label(DM.AETERM)", "write var_label(\"DM.AETERM\")"
                }
        })
        {
            for (Expr e : List.of(parse(c[0]), canonical(c[0])))
            {
                // exactly one name finding (a DS.X additionally meets DOTTED_REF_UNDECLARED here)
                List<String> found = findings(wrap(label, e)).stream()
                        .filter(f -> f.startsWith(StageAErrorKind.NON_STATIC_NAME + ": ")).toList();
                assertEquals(1, found.size(), c[0] + ": " + findings(wrap(label, e)));
                assertTrue(found.get(0).contains("var_label name must be a string literal"),
                        found.get(0));
                assertTrue(found.get(0).contains(c[1]), found.get(0));
            }
            RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                    () -> eval(parse(c[0] + " == \"x\"")), c[0]);
            assertTrue(ex.getMessage().contains("name must be a string"), ex.getMessage());
            assertTrue(ex.getMessage().contains(c[1]), ex.getMessage());
        }
        assertEquals(1, findings(wrap(label, parse("var_label(AETERM)"))).size(),
                "the one-argument column form: one finding, the name refusal");
        // a non-DATA attribute with a bare column: the level finding names the STRING spelling
        List<String> role = findings(wrap(MetadataAttribute.VAR_ROLE, parse("var_role(AETERM)")));
        assertTrue(role.stream().anyMatch(f -> f.contains("var_role(\"AETERM\", \"DEFINE\")")),
                role.toString());
        assertTrue(
                role.stream().anyMatch(f -> f.startsWith(StageAErrorKind.NON_STATIC_NAME + ": ")),
                role.toString());
    }


    /**
     * V12 through the name-slot inliner: a binding holding a STRING name answers in the
     * one-argument form as that string written there, and reads the DATA level; a binding holding a
     * bare column inlines the column, which stays refused (R1) exactly as in the two-argument form.
     */
    @Test
    void aBoundNameInTheOneArgumentFormInlinesAsTheNameWrittenThere()
    {
        Map<String, Expr> bound = new LinkedHashMap<>();
        bound.put("$c", parse("\"AETERM\""));
        bound.put("$col", parse("AETERM"));
        Expr inlined = BindingInliner
                .inlineNameSlotBindings(parse("var_label($c) == \"Reported Term\""), bound);
        assertEquals(parse("var_label(\"AETERM\") == \"Reported Term\""), inlined);
        assertEquals(eval(parse("var_label(\"AETERM\", \"DATA\") == \"Reported Term\"")),
                eval(MetadataOperandMapping.canonicalizeMetadataOperands(inlined)));
        Expr column = BindingInliner.inlineNameSlotBindings(parse("var_label($col) == \"x\""),
                bound);
        assertEquals(parse("var_label(AETERM) == \"x\""), column);
        assertEquals(List.of(StageAErrorKind.NON_STATIC_NAME),
                stageA(column).findings().stream().map(StageAFinding::kind).toList());
    }


    @Test
    void aDatasetAccessorWithOneStringThatIsNotALevelIsRefusedWithAHint()
    {
        List<String> found = findings(wrap(MetadataAttribute.DS_NAME, parse("ds_name(\"DM\")")));
        assertEquals(1, found.size(), found.toString());
        assertTrue(found.get(0).startsWith(StageAErrorKind.METADATA_LEVEL_ILLEGAL + ": "),
                found.get(0));
        assertTrue(found.get(0).contains("a dataset name needs a level: ds_name(\"DM\", \"DATA\")"),
                found.get(0));
        // the level stays required whenever a dataset name is given (D3): no fill
        assertEquals(parse("ds_name(\"DM\")"), canonical("ds_name(\"DM\")"));
        // a bare name in the dataset position is not a column: the same refusal, same hint
        List<String> bare = findings(wrap(MetadataAttribute.DS_NAME, parse("ds_name(DM)")));
        assertEquals(1, bare.size(), bare.toString());
        assertTrue(bare.get(0).contains("ds_name(\"DM\", \"DATA\")"), bare.get(0));
    }


    @Test
    void aMatchDatasetsFilterIsCanonicalisedInItsCache()
    {
        MatchDataset match = new MatchDataset();
        match.setName("AE");
        match.setKeys(List.of("USUBJID"));
        match.setFilter("ds_name() == \"AE\" and dataset_domain == \"AE\"");
        Expr filter = match.filterExpr();
        assertEquals(parse("ds_name(\"DATA\") == \"AE\" and ds_domain(\"DATA\") == \"AE\""),
                filter);
        assertTrue(filter == match.filterExpr(), "memoised once");
        match.setFilter("var_label(\"AETERM\") == \"x\"");
        assertEquals(parse("var_label(\"AETERM\", \"DATA\") == \"x\""), match.filterExpr());
    }


    @Test
    void theNameSlotAndTheFillDecideTheOneArgumentFormTheSameWay()
    {
        // decision V12: in the one-argument form, argument 0 is the NAME unless it is a level
        // literal — NameSlots and the fill share NameSlots.accessorNamePosition
        Expr levelForm = call("var_label", lit("DATA"));
        Expr nameForm = call("var_label", lit("AETERM"));
        Expr column = call("var_label",
                new Expr.Ref("AETERM", net.cumba.corej.core.expr.OperandKind.COLUMN));
        assertFalse(NameSlots.isNameSlot((Expr.Call) levelForm, 0));
        assertTrue(NameSlots.isNameSlot((Expr.Call) nameForm, 0));
        assertTrue(NameSlots.isNameSlot((Expr.Call) column, 0),
                "a name slot holding a reference — refused there, never re-read as a level");
        assertFalse(NameSlots.isNameSlot((Expr.Call) call("ds_name", lit("DM")), 0),
                "a ds_* accessor's one argument is never a name (D3)");
        assertEquals(parse("var_label(\"DATA\")"),
                MetadataOperandMapping.canonicalizeMetadataOperands(levelForm));
        assertEquals(parse("var_label(\"AETERM\", \"DATA\")"),
                MetadataOperandMapping.canonicalizeMetadataOperands(nameForm));
        assertEquals(column, MetadataOperandMapping.canonicalizeMetadataOperands(column),
                "a bare column is neither lowered nor filled (O15: R1 wins)");
    }
}
