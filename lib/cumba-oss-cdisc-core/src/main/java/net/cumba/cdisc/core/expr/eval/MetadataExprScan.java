package net.cumba.cdisc.core.expr.eval;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import net.cumba.cdisc.core.expr.ast.Expr;

/**
 * Static analysis of a compiled-rule {@link Expr} for the metadata accessor functions
 * ({@code var_*} / {@code ds_*}). Used to decide whether a rule must be evaluated natively
 * (variable / dataset granularity) and which metadata-provider levels it requires (so a rule that
 * reads an absent DEFINE / LIBRARY provider can be reported SKIPPED rather than silently producing
 * no findings).
 */
public final class MetadataExprScan
{

    private MetadataExprScan()
    {
    }

    /** The two current-variable native functions: {@code varname()} (NAME) and {@code value()}. */
    private static final String VARNAME_FN = "varname";

    private static final String VALUE_FN = "value";

    /** {@code true} iff {@code e} contains any {@code var_*} / {@code ds_*} accessor call. */
    public static boolean containsMetadataFunction(Expr e)
    {
        return switch (e)
        {
        case Expr.And a -> anyContains(a.parts());
        case Expr.Or o -> anyContains(o.parts());
        case Expr.Not n -> containsMetadataFunction(n.inner());
        case Expr.Binary b -> containsMetadataFunction(b.left())
                || containsMetadataFunction(b.right());
        case Expr.Call c -> MetadataAttribute.fromFunction(c.name()) != null
                || c.name().startsWith("vlm_")
                || "library_variable_code_pair_matches".equals(c.name()) || anyContains(c.args())
                || anyContains(c.kwargs().values());
        case Expr.Ref _,Expr.Lit _ -> false;
        };
    }


    /**
     * {@code true} iff {@code e} contains the {@code varname()} current-variable-name function —
     * the native surface of the standalone {@code variable_name} operand. A rule whose only
     * variable-scope operand is {@code varname()} (no {@code var_*} accessor) is still a
     * per-variable broadcast rule (one verdict / finding per column), so the routing must treat it
     * like a metadata rule.
     */
    public static boolean containsVarname(Expr e)
    {
        return containsZeroArgCall(e, VARNAME_FN);
    }


    /**
     * Whether {@code e} references the {@code variable_name} anchor as a bare operand (P4b — the
     * raised form of a {@code variable_name}-anchored operand rule, e.g.
     * {@code variable_name not in $allowed_variables}). The anchor resolves the same per-column
     * cursor as {@code varname()}, so an anchor-bearing pure-metadata rule is per-variable
     * broadcast-evaluable exactly like a {@code varname()}-only rule.
     */
    public static boolean containsVariableNameAnchor(Expr e)
    {
        return switch (e)
        {
        case Expr.And a -> a.parts().stream()
                .anyMatch(MetadataExprScan::containsVariableNameAnchor);
        case Expr.Or o -> o.parts().stream().anyMatch(MetadataExprScan::containsVariableNameAnchor);
        case Expr.Not n -> containsVariableNameAnchor(n.inner());
        case Expr.Binary b -> containsVariableNameAnchor(b.left())
                || containsVariableNameAnchor(b.right());
        case Expr.Call c -> c.args().stream().anyMatch(MetadataExprScan::containsVariableNameAnchor)
                || c.kwargs().values().stream()
                        .anyMatch(MetadataExprScan::containsVariableNameAnchor);
        case Expr.Ref r -> "variable_name".equals(r.name());
        case Expr.Lit _ -> false;
        };
    }


    /**
     * {@code true} iff {@code e} contains the {@code value()} current-variable-value function — the
     * native surface of the standalone per-row {@code variable_value} operand. A rule using
     * {@code value()} reads per-row column data for the current variable, so it is <em>not</em>
     * broadcast-evaluable: it takes the per-variable × row native path instead.
     */
    public static boolean usesCurrentVariableValue(Expr e)
    {
        return containsZeroArgCall(e, VALUE_FN);
    }


    private static boolean containsZeroArgCall(Expr e, String fn)
    {
        return switch (e)
        {
        case Expr.And a -> a.parts().stream().anyMatch(p -> containsZeroArgCall(p, fn));
        case Expr.Or o -> o.parts().stream().anyMatch(p -> containsZeroArgCall(p, fn));
        case Expr.Not n -> containsZeroArgCall(n.inner(), fn);
        case Expr.Binary b -> containsZeroArgCall(b.left(), fn)
                || containsZeroArgCall(b.right(), fn);
        case Expr.Call c -> (fn.equals(c.name()) && c.args().isEmpty() && c.kwargs().isEmpty())
                || c.args().stream().anyMatch(p -> containsZeroArgCall(p, fn))
                || c.kwargs().values().stream().anyMatch(p -> containsZeroArgCall(p, fn));
        case Expr.Ref _,Expr.Lit _ -> false;
        };
    }


    /**
     * {@code true} iff {@code e} uses any variable-scope accessor ({@code var_*}). Such a rule is
     * evaluated per column (one finding per failing variable); a rule using only dataset-scope
     * ({@code ds_*}) accessors is evaluated once for the dataset.
     */
    public static boolean usesVariableScope(Expr e)
    {
        return switch (e)
        {
        case Expr.And a -> anyVariableScope(a.parts());
        case Expr.Or o -> anyVariableScope(o.parts());
        case Expr.Not n -> usesVariableScope(n.inner());
        case Expr.Binary b -> usesVariableScope(b.left()) || usesVariableScope(b.right());
        // varname() is variable-scope (the per-column "current variable" NAME), like a var_*
        // accessor: it makes the rule iterate per variable (one verdict per column). value() is NOT
        // variable-scope — it is the per-row current-variable VALUE (the legacy variable_value
        // operand classifies as ROW, not VARIABLE), so it never on its own triggers per-variable
        // iteration; a value()-using rule needs a separate varname()/var_* guard.
        case Expr.Call c -> isVariableScope(c.name()) || isVarnameFn(c)
                || anyVariableScope(c.args()) || anyVariableScope(c.kwargs().values());
        case Expr.Ref _,Expr.Lit _ -> false;
        };
    }


    /** Whether {@code c} is the zero-arg {@code varname()} current-variable-name function. */
    private static boolean isVarnameFn(Expr.Call c)
    {
        return VARNAME_FN.equals(c.name()) && c.args().isEmpty() && c.kwargs().isEmpty();
    }


    /**
     * {@code true} iff every operand of {@code e} is row-independent — only metadata accessors,
     * literals, and the {@code variable_name} anchor (no bare column reference). Such an expression
     * has a single broadcast verdict and is safe to evaluate over one synthetic row
     * ({@code evaluateBroadcast}); an expression that reads per-row column data is not.
     */
    public static boolean isPureMetadata(Expr e)
    {
        return switch (e)
        {
        case Expr.And a -> a.parts().stream().allMatch(MetadataExprScan::isPureMetadata);
        case Expr.Or o -> o.parts().stream().allMatch(MetadataExprScan::isPureMetadata);
        case Expr.Not n -> isPureMetadata(n.inner());
        case Expr.Binary b -> isPureMetadata(b.left()) && isPureMetadata(b.right());
        // value() reads the current variable's per-row column cells — a row-dependent operand, so a
        // value()-using expression is NOT broadcast-evaluable. varname() (the current-variable
        // NAME)
        // is broadcast-constant, like the variable_name anchor. Every other call is pure iff its
        // arguments are.
        case Expr.Call c -> !VALUE_FN.equals(c.name())
                && c.args().stream().allMatch(MetadataExprScan::isPureMetadata)
                && c.kwargs().values().stream().allMatch(MetadataExprScan::isPureMetadata);
        case Expr.Lit _ -> true;
        // Row-independent references in a metadata expression: the variable_name anchor and
        // $-operation results (P4 — broadcast-constant per evaluation; a per-variable
        // VariableMetadataResult is projected onto the per-column cursor context by
        // RuleRunner.evaluateMetadataNative, and a per-row GroupedResult is excluded at dispatch
        // by RuleRunner's runtime guard, mirroring the removed legacy engine's ROW leaf
        // classification).
        // Any other reference is a per-row column read.
        case Expr.Ref r -> "variable_name".equals(r.name())
                || r.kind() == net.cumba.cdisc.core.expr.OperandKind.OPERATION_REF;
        };
    }


    private static boolean isVariableScope(String fn)
    {
        MetadataAttribute attr = MetadataAttribute.fromFunction(fn);
        return attr != null && attr.scope() == MetadataAttribute.Scope.VARIABLE;
    }


    private static boolean anyVariableScope(java.util.Collection<Expr> exprs)
    {
        for (Expr e : exprs)
        {
            if (usesVariableScope(e))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * The provider-backed levels ({@link MetadataLevel#DEFINE} / {@link MetadataLevel#LIBRARY})
     * read by any metadata accessor in {@code e}. {@link MetadataLevel#DATA} is never returned (it
     * needs no provider). An accessor whose level literal is unparseable is skipped (the compiler
     * raises the {@code RuleDefinitionException} for it).
     */
    public static Set<MetadataLevel> providerLevelsUsed(Expr e)
    {
        Set<MetadataLevel> levels = EnumSet.noneOf(MetadataLevel.class);
        collectLevels(e, levels);
        return levels;
    }


    private static void collectLevels(Expr e, Set<MetadataLevel> out)
    {
        switch (e)
        {
        case Expr.And a -> a.parts().forEach(p -> collectLevels(p, out));
        case Expr.Or o -> o.parts().forEach(p -> collectLevels(p, out));
        case Expr.Not n -> collectLevels(n.inner(), out);
        case Expr.Binary b ->
        {
            collectLevels(b.left(), out);
            collectLevels(b.right(), out);
        }
        case Expr.Call c ->
        {
            if (MetadataAttribute.fromFunction(c.name()) != null)
            {
                addLevel(c, out);
            }
            // VLM accessors (vlm_*, Value Check against Define XML VLM) read the Define-XML
            // value-level metadata, so they require the DEFINE provider: the rule SKIPs when no
            // Define-XML is supplied (RuleRunner's DEFINE provider gate), like every define_* rule.
            if (c.name().startsWith("vlm_"))
            {
                out.add(MetadataLevel.DEFINE);
            }
            // E9 — the library-level paired code/decode match reads CDISC Library codelist
            // metadata,
            // so it requires the LIBRARY provider: the rule SKIPs when no Library is supplied.
            if ("library_variable_code_pair_matches".equals(c.name()))
            {
                out.add(MetadataLevel.LIBRARY);
            }
            // Fix #123 — the variable-level paired code/decode match reads the Define-XML ItemDef
            // codelist, so it requires the DEFINE provider: the rule SKIPs without a define.
            if ("define_variable_decode_matches".equals(c.name()))
            {
                out.add(MetadataLevel.DEFINE);
            }
            c.args().forEach(a -> collectLevels(a, out));
            c.kwargs().values().forEach(a -> collectLevels(a, out));
        }
        case Expr.Ref _,Expr.Lit _ ->
        {
            // ⭐ A BARE operand names its provider. `library_variable_label_values` is not an
            // accessor call, so nothing above sees it — yet it is served by the LIBRARY provider,
            // and a run without one must SKIP rather than read nothing: an absent operand looks
            // exactly like conformant data. Same shape as Fix #369.
            //
            // ⚑ Measured before generalising (Plan 2 Phase 6): the shipped corpus contains exactly
            // TWO rules with a genuinely bare `library_*` operand — DRAFT-900021 and DRAFT-900024 —
            // and BOTH already derive Requirements.Library=true from a sibling accessor
            // (`var_codelist_extensible("LIBRARY")`). `library_variables` / `library_order` are
            // `$`-refs (declared operations, surface 1) and `library_variable_code_pair_matches` is
            // a call handled above. So this prefix rule changes no existing rule's routing.
            if (e instanceof Expr.Ref r)
            {
                if (r.name().startsWith("library_"))
                {
                    out.add(MetadataLevel.LIBRARY);
                }
                else if (r.name().startsWith("define_"))
                {
                    out.add(MetadataLevel.DEFINE);
                }
            }
        }
        }
    }


    private static void addLevel(Expr.Call call, Set<MetadataLevel> out)
    {
        List<Expr> args = call.args();
        if (args.isEmpty())
        {
            return;
        }
        Expr levelArg = args.get(args.size() - 1);
        if (levelArg instanceof Expr.Lit lit && lit.kind() == Expr.LitKind.STRING)
        {
            // An unparseable level yields null here; the compiler fails that rule separately.
            MetadataLevel level = MetadataLevel.tryParse((String) lit.value());
            if (level != null && level != MetadataLevel.DATA)
            {
                out.add(level);
            }
        }
    }


    private static boolean anyContains(java.util.Collection<Expr> exprs)
    {
        for (Expr e : exprs)
        {
            if (containsMetadataFunction(e))
            {
                return true;
            }
        }
        return false;
    }

}
