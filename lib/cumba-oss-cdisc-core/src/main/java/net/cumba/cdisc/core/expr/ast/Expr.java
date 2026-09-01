package net.cumba.cdisc.core.expr.ast;

import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.expr.OperandKind;

/**
 * The expression intermediate representation. The parser produces an {@code Expr} tree from
 * expression text; the v1 lowering pass ({@code ExprLowering}) compiles a (boolean-typed)
 * {@code Expr} into the existing {@link net.cumba.cdisc.core.model.CheckCondition} AST so the
 * current engine evaluates it unchanged. {@code Expr} is intentionally richer than the operator-
 * leaf AST — it is the seam for the future native evaluator, which will evaluate {@code Expr}
 * directly and remove the v1 lowering restrictions.
 *
 * <p>
 * Nodes are immutable records. Value-typed nodes ({@link Lit}, {@link Ref}, value {@link Call}s)
 * appear as operands; boolean-typed nodes ({@link Binary}, boolean {@link Call}s, {@link And},
 * {@link Or}, {@link Not}) form the condition tree. The distinction is contextual, resolved during
 * lowering rather than encoded in the type.
 * </p>
 */
public sealed interface Expr
        permits
        Expr.Lit,
        Expr.Ref,
        Expr.Call,
        Expr.Binary,
        Expr.And,
        Expr.Or,
        Expr.Not
{

    /** Infix comparison / membership operators. */
    enum BinOp
    {
        EQ, NEQ, LT, GT, LE, GE, MATCH, NMATCH, IN, NOT_IN, ADD, SUB, MUL, DIV
    }


    /** Lexical category of a literal. */
    enum LitKind
    {
        STRING, NUMBER, BOOL, REGEX, LIST
    }


    /**
     * A literal value. {@code value} is a {@link String} (STRING/REGEX), {@link Double} (NUMBER),
     * {@link Boolean} (BOOL), or {@code List<Expr>} of {@link Lit} (LIST).
     */
    record Lit(LitKind kind, Object value) implements Expr
    {

    }


    /** A bare reference operand (column / wildcard / {@code $}-op / dotted / built-in). */
    record Ref(String name, OperandKind kind) implements Expr
    {

    }


    /** A function application, e.g. {@code exists(AEOCCUR)}, {@code date(MHSTDTC)}. */
    record Call(String name, List<Expr> args, Map<String, Expr> kwargs) implements Expr
    {

        public Call
        {
            args = List.copyOf(args);
            // Insertion-ordered on purpose (T2 review M7): Map.copyOf iterates under a
            // per-JVM-run salt, which made the REST overlay's kwarg rendering
            // (filter=filter(A=…, B=…)) differ from the shipped text nondeterministically.
            kwargs = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(kwargs));
        }
    }


    /** An infix comparison / membership expression. */
    record Binary(BinOp op, Expr left, Expr right) implements Expr
    {

    }


    /** Conjunction (lowers to {@code all}). */
    record And(List<Expr> parts) implements Expr
    {

        public And
        {
            parts = List.copyOf(parts);
        }
    }


    /** Disjunction (lowers to {@code any}). */
    record Or(List<Expr> parts) implements Expr
    {

        public Or
        {
            parts = List.copyOf(parts);
        }
    }


    /** Negation (lowers to {@code not}). */
    record Not(Expr inner) implements Expr
    {

    }

}
