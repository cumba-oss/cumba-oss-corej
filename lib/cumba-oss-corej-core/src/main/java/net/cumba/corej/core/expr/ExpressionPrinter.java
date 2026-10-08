package net.cumba.corej.core.expr;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.cumba.corej.core.expr.ast.Expr;

/**
 * Renders an {@link Expr} tree back to expression-syntax text — the inverse of
 * {@link CheckExpressionParser}. Parenthesisation is precedence-driven so that
 * {@code parse(print(e))} reproduces {@code e}'s structure. Used by the old→new converter and by
 * round-trip tests.
 */
public final class ExpressionPrinter
{

    private static final int PREC_OR = 1;

    private static final int PREC_AND = 2;

    private static final int PREC_NOT = 3;

    private static final int PREC_ATOM = 4;

    private ExpressionPrinter()
    {
    }


    /**
     * Renders an expression tree to text.
     *
     * @param e
     *            the expression
     * @return the expression-syntax string
     */
    public static String print(Expr e)
    {
        return emit(e, 0);
    }


    private static int precedence(Expr e)
    {
        return switch (e)
        {
        case Expr.Or _ -> PREC_OR;
        case Expr.And _ -> PREC_AND;
        case Expr.Not _ -> PREC_NOT;
        default -> PREC_ATOM;
        };
    }


    private static String emit(Expr e, int parentPrec)
    {
        String raw = raw(e);
        return precedence(e) < parentPrec ? "(" + raw + ")" : raw;
    }


    private static String raw(Expr e)
    {
        return switch (e)
        {
        case Expr.Or o -> o.parts().stream().map(p -> emit(p, PREC_OR))
                .collect(Collectors.joining(" or "));
        case Expr.And a -> a.parts().stream().map(p -> emit(p, PREC_AND))
                .collect(Collectors.joining(" and "));
        case Expr.Not n -> "not " + emit(n.inner(), PREC_NOT);
        case Expr.Binary b -> binary(b);
        case Expr.Call c -> call(c);
        case Expr.Ref r -> operand(r.name());
        case Expr.Lit l -> literal(l);
        };
    }


    private static String binary(Expr.Binary b)
    {
        String op = switch (b.op())
        {
        case EQ -> "==";
        case NEQ -> "!=";
        case LT -> "<";
        case GT -> ">";
        case LE -> "<=";
        case GE -> ">=";
        case MATCH -> "=~";
        case NMATCH -> "!~";
        case IN -> "in";
        case NOT_IN -> "not in";
        case ADD -> "+";
        case SUB -> "-";
        case MUL -> "*";
        case DIV -> "/";
        case SUBST -> "~";
        };
        return value(b.left()) + " " + op + " " + value(b.right());
    }


    private static String call(Expr.Call c)
    {
        StringBuilder sb = new StringBuilder(c.name()).append('(');
        boolean first = true;
        // joined(<condition>) takes a whole boolean expression bare (CheckExpressionParser's one
        // name-keyed arm, PLAN-cg0218-transition-day-precision P1); every other call's boolean
        // argument keeps its own parentheses.
        boolean bare = net.cumba.corej.core.exec.JoinedRowFold.JOINED.equals(c.name())
                && c.args().size() == 1 && c.kwargs().isEmpty();
        for (Expr a : c.args())
        {
            if (!first)
            {
                sb.append(", ");
            }
            sb.append(bare ? raw(a) : value(a));
            first = false;
        }
        // Emit kwargs sorted by key so the printed form is deterministic regardless of the
        // (unspecified) iteration order of Expr.Call's kwargs map.
        for (Map.Entry<String, Expr> kw : new java.util.TreeMap<>(c.kwargs()).entrySet())
        {
            if (!first)
            {
                sb.append(", ");
            }
            sb.append(kw.getKey()).append('=').append(value(kw.getValue()));
            first = false;
        }
        return sb.append(')').toString();
    }


    /**
     * Renders an operand (value-level node). A substitution {@code X ~ /p/r/} prints bare in
     * operand position: it is the tightest-binding operator, so no parentheses are ever needed
     * around it (and {@code X ~ /a/b/ ~ /b/c/} re-parses left-associatively, as built).
     */
    private static String value(Expr e)
    {
        return switch (e)
        {
        case Expr.Ref r -> operand(r.name());
        case Expr.Lit l -> literal(l);
        case Expr.Call c -> call(c);
        case Expr.Binary b when b.op() == Expr.BinOp.SUBST -> binary(b);
        default -> "(" + raw(e) + ")";
        };
    }


    private static String literal(Expr.Lit l)
    {
        return switch (l.kind())
        {
        case STRING -> quote((String) l.value());
        case REGEX -> "/" + slashDelimited((String) l.value()) + "/";
        case NUMBER -> numberText((Double) l.value());
        case BOOL -> String.valueOf(l.value());
        case LIST -> listText(l);
        case SUBSTITUTION ->
        {
            Expr.Substitution s = (Expr.Substitution) l.value();
            yield "/" + slashDelimited(s.pattern()) + "/" + slashDelimited(s.replacement()) + "/";
        }
        case SIDED ->
        {
            Expr.SidedKey key = (Expr.SidedKey) l.value();
            yield "{left: " + value(key.left()) + ", right: " + value(key.right()) + "}";
        }
        };
    }


    /** One slash-delimited part, the lexer's escapes applied: {@code \} and the delimiter. */
    private static String slashDelimited(String text)
    {
        return text.replace("\\", "\\\\").replace("/", "\\/");
    }


    private static String numberText(Double d)
    {
        if (!d.isInfinite() && Double.compare(d, Math.rint(d)) == 0)
        {
            return Long.toString(d.longValue());
        }
        return d.toString();
    }


    private static String listText(Expr.Lit l)
    {
        @SuppressWarnings("unchecked")
        List<Expr> items = (List<Expr>) l.value();
        return items.stream().map(ExpressionPrinter::value)
                .collect(Collectors.joining(", ", "[", "]"));
    }


    private static String quote(String s)
    {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }


    /**
     * Renders a reference operand: a bare identifier (including {@code --}/{@code *} wildcard and
     * {@code ${...}} substitution names) verbatim, otherwise backtick-quoted so non-identifier
     * names (whitespace, …) re-lex faithfully. Public so generators outside this class can decide
     * printability against the same rule.
     *
     * @param name
     *            the reference name
     * @return the printable operand text
     */
    public static String operand(String name)
    {
        return isBareOperand(name) ? name
                : "`" + name.replace("\\", "\\\\").replace("`", "\\`") + "`";
    }


    /** Whether {@code name} re-lexes as exactly one bare {@code IDENT} token spanning the whole. */
    private static boolean isBareOperand(String name)
    {
        List<Token> tokens;
        try
        {
            tokens = ExpressionLexer.tokenize(name);
        }
        catch (ExpressionException ex)
        {
            return false;
        }
        return tokens.size() == 2 && tokens.get(0).type() == TokenType.IDENT
                && tokens.get(1).type() == TokenType.EOF && name.equals(tokens.get(0).text());
    }

}
