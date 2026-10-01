package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.spi.BuiltinFunctions;
import net.cumba.corej.core.expr.eval.spi.CompilerDispatchedCalls;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.expr.typed.ExprType.ListOf;
import net.cumba.corej.core.expr.typed.ExprType.Primitive;
import net.cumba.corej.core.expr.typed.ExprType.Unknown;
import net.cumba.corej.core.expr.typed.StageAChecker;
import net.cumba.corej.core.expr.typed.StageAErrorKind;
import net.cumba.corej.core.expr.typed.StageAFinding;
import net.cumba.corej.core.expr.typed.StageAReport;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.report.Severity;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-stage-a-parameter-type-arming} §3.3 (phase 4, hardened by review round 1 seams M2 /
 * tests 4): the loader's seam pass stands three seam cases down for the armed stage A — the R1
 * literal at a column slot, a wrong-typed literal at a BOOLEAN {@code case_sensitive}, a
 * wrong-typed literal at a STRING static parameter. Each stand-down is a bet on stage A's
 * BEHAVIOUR, so this test plants the exact literal at every such parameter of every descriptor of
 * <b>both providers</b> (read directly, not through the registry — a stub registered through the
 * package-private seam cannot hide or add a name) and expects the armed {@code PARAMETER_TYPE}
 * finding naming the parameter. {@link StageAChecker#judgesParameters} — the routing predicate the
 * kept set is derived from — is held to the same behaviour, and the kept set
 * ({@code ExprCompiler.STAGE_A_SHORT_CIRCUITED_COLUMN_CALLS}) is pinned to what the behaviour
 * leaves: nothing.
 *
 * <p>
 * Non-vacuity: population floors on each planted set, an exact-equality pin on the kept set, and a
 * control that the planted literal in a legal position is NOT a finding (the harness can see a
 * clean report).
 * </p>
 */
class StageAResidueDriftTest
{

    private static List<FunctionDescriptor> providers()
    {
        List<FunctionDescriptor> all = new ArrayList<>();
        all.addAll(new BuiltinFunctions().functions());
        all.addAll(new CompilerDispatchedCalls().functions());
        return all;
    }


    private static boolean isColumnReference(ExprType t)
    {
        return t == Primitive.COLUMN_REFERENCE
                || (t instanceof ListOf list && list.element() == Primitive.COLUMN_REFERENCE);
    }


    /** A plausible argument for a parameter — a bare column for anything not literal-typed. */
    private static Expr plausible(Parameter p)
    {
        ExprType t = p.type();
        if (t == Primitive.STRING)
        {
            return new Expr.Lit(Expr.LitKind.STRING, "x");
        }
        if (t == Primitive.NUMBER)
        {
            return new Expr.Lit(Expr.LitKind.NUMBER, 1.0);
        }
        if (t == Primitive.BOOLEAN)
        {
            return new Expr.Lit(Expr.LitKind.BOOL, true);
        }
        if (t == Primitive.REGEX)
        {
            return new Expr.Lit(Expr.LitKind.REGEX, "x");
        }
        if (t == Primitive.DATASET_REFERENCE)
        {
            return new Expr.Ref("DM", OperandKind.COLUMN);
        }
        if (t instanceof ListOf)
        {
            return new Expr.Lit(Expr.LitKind.LIST, List.of(new Expr.Ref("X", OperandKind.COLUMN)));
        }
        return new Expr.Ref("X", OperandKind.COLUMN);
    }


    /**
     * The call with {@code planted} at parameter {@code index} and plausible arguments elsewhere,
     * wrapped into a boolean Check root.
     */
    private static Expr callWith(FunctionDescriptor d, int index, Expr planted)
    {
        List<Expr> args = new ArrayList<>();
        Map<String, Expr> kwargs = new LinkedHashMap<>();
        List<Parameter> params = d.parameters();
        for (int i = 0; i < params.size(); i++)
        {
            Parameter p = params.get(i);
            if (p.collector())
            {
                // a trailing collector takes extra positionals: plant the literal there only
                if (i == index)
                {
                    args.add(planted);
                }
                continue;
            }
            Expr value = i == index ? planted : plausible(p);
            if (i < 2)
            {
                args.add(value);
            }
            else
            {
                kwargs.put(p.name(), value);
            }
        }
        Expr call = new Expr.Call(d.name(), args, kwargs);
        // a VALUE call is wrapped in empty(): a root that cannot itself add a type finding
        return d.kind() == FunctionKind.BOOLEAN ? call
                : new Expr.Call("empty", List.of(call), Map.of());
    }


    private static List<StageAFinding> parameterTypeFindings(Expr root)
    {
        java.util.SequencedMap<Severity, Expr> levels = new LinkedHashMap<>();
        levels.put(Severity.ERROR, root);
        StageAReport report = StageAChecker.check(new Rule(), levels);
        return report.findings().stream().filter(f -> f.kind() == StageAErrorKind.PARAMETER_TYPE)
                .toList();
    }


    private static boolean namesParameter(List<StageAFinding> findings, Parameter p)
    {
        return findings.stream().anyMatch(f -> f.message().contains("'" + p.name() + "'"));
    }


    @Test
    void aStringLiteralAtEveryColumnReferenceParameterIsStageAsFindingAndTheKeptSetIsEmpty()
    {
        Set<String> columnBearing = new TreeSet<>();
        Set<String> residue = new TreeSet<>();
        List<String> misses = new ArrayList<>();
        for (FunctionDescriptor d : providers())
        {
            List<Parameter> params = d.parameters();
            for (int i = 0; i < params.size(); i++)
            {
                Parameter p = params.get(i);
                if (!isColumnReference(p.type()))
                {
                    continue;
                }
                columnBearing.add(d.name());
                Expr planted = p.type() instanceof ListOf
                        ? new Expr.Lit(Expr.LitKind.LIST,
                                List.of(new Expr.Lit(Expr.LitKind.STRING, "QUOTED")))
                        : new Expr.Lit(Expr.LitKind.STRING, "QUOTED");
                boolean refused = namesParameter(parameterTypeFindings(callWith(d, i, planted)), p);
                boolean judged = StageAChecker.judgesParameters(d.name());
                if (refused != judged)
                {
                    misses.add(d.name() + "." + p.name() + " refused=" + refused + " judged="
                            + judged);
                }
                if (!refused && d.fn() != null)
                {
                    residue.add(d.name());
                }
            }
        }
        assertTrue(columnBearing.size() >= 15, "column-reference descriptors: " + columnBearing);
        assertEquals(List.of(), misses,
                "judgesParameters must agree with what stage A actually refuses");
        // the kept set IS the residue the behaviour leaves — pinned to exactly empty
        assertEquals(Set.of(), residue, "every registry-evaluated column-reference parameter is"
                + " stage A's, so the loader's seam pass keeps the R1 seam for none");
        assertEquals(residue, ExprCompiler.STAGE_A_SHORT_CIRCUITED_COLUMN_CALLS);
        // the registry serves exactly the providers' names
        Set<String> registry = new TreeSet<>();
        FunctionRegistry.all().forEach(d -> registry.add(d.name()));
        Set<String> provided = new TreeSet<>();
        providers().forEach(d -> provided.add(d.name()));
        assertEquals(provided, registry);
    }


    @Test
    void aNumberLiteralAtEveryStaticStringParameterIsStageAsFinding()
    {
        Map<String, Set<String>> statics = ExprCompiler.staticStringParameters();
        int planted = 0;
        for (FunctionDescriptor d : providers())
        {
            Set<String> names = statics.getOrDefault(d.name(), Set.of());
            List<Parameter> params = d.parameters();
            for (int i = 0; i < params.size(); i++)
            {
                Parameter p = params.get(i);
                if (!names.contains(p.name()))
                {
                    continue;
                }
                assertEquals(Primitive.STRING, p.type(), d.name() + "." + p.name()
                        + " must be declared STRING for the S3 literal" + " case to be stage A's");
                planted++;
                Expr root = callWith(d, i, new Expr.Lit(Expr.LitKind.NUMBER, 5.0));
                assertTrue(namesParameter(parameterTypeFindings(root), p), d.name() + "." + p.name()
                        + ": a number literal must be refused by stage A");
            }
        }
        assertTrue(planted >= 4, "static string parameters planted: " + planted);
    }


    @Test
    void aStringLiteralAtEveryDictionaryCaseSensitiveIsStageAsFinding()
    {
        int planted = 0;
        for (FunctionDescriptor d : providers())
        {
            ProviderNeed need = d.provider();
            if (need == null || need.kind() != ProviderNeed.Kind.DICTIONARY)
            {
                continue;
            }
            List<Parameter> params = d.parameters();
            for (int i = 0; i < params.size(); i++)
            {
                Parameter p = params.get(i);
                if (!"case_sensitive".equals(p.name()))
                {
                    continue;
                }
                assertEquals(Primitive.BOOLEAN, p.type(), d.name() + ".case_sensitive must be"
                        + " declared BOOLEAN for the S2 literal case to be stage A's");
                planted++;
                Expr root = callWith(d, i, new Expr.Lit(Expr.LitKind.STRING, "false"));
                assertTrue(namesParameter(parameterTypeFindings(root), p),
                        d.name() + ": a string literal case_sensitive must be refused by stage A");
            }
        }
        assertTrue(planted >= 4, "dictionary case_sensitive parameters planted: " + planted);
    }


    @Test
    void theHarnessSeesACleanReportAndThePredicateDistinguishes()
    {
        // control: the planted shape in a LEGAL position is not a finding
        FunctionDescriptor tuple = FunctionRegistry.descriptor("tuple");
        assertNotNull(tuple);
        assertEquals(List.of(), parameterTypeFindings(
                callWith(tuple, 0, new Expr.Ref("ARMCD", OperandKind.COLUMN))));
        for (String name : List.of("var_exists", "ds_exists", "var_is_null", "var_label",
                "vlm_length", "max_value_length", "date", "colref", "num"))
        {
            assertFalse(StageAChecker.judgesParameters(name), name);
        }
        for (String name : List.of("tuple", "dy", "date_diff_days", "referenced_dataset_variables",
                "valid_external_dictionary_code_term_pair", "record_count", "library_available",
                "dictionary_available", "is_last_in_group"))
        {
            assertTrue(StageAChecker.judgesParameters(name), name);
        }
        // every unknown-typed name slot of a short-circuited call is a value the checker never
        // binds — the shape the kept set would have to name
        for (FunctionDescriptor d : providers())
        {
            if (!StageAChecker.judgesParameters(d.name()))
            {
                for (Parameter p : d.parameters())
                {
                    assertTrue(p.type() == Unknown.UNKNOWN || !isColumnReference(p.type()), d.name()
                            + "." + p.name() + " is short-circuited by stage A yet declares"
                            + " a column reference: it must join the kept set or be exempt ("
                            + RulePackageLoader.registryCallSeamExemptions().contains(d.name())
                            + ")");
                }
            }
        }
    }
}
