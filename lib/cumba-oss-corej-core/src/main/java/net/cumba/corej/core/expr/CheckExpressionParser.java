package net.cumba.corej.core.expr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.ast.Expr;
import org.jspecify.annotations.Nullable;

/**
 * Recursive-descent parser that turns an expression-syntax string into an {@link Expr} tree.
 * Precedence, loosest to tightest: logical-or, then logical-and, then unary-not, then the
 * comparison / membership / regex operators, then additive and multiplicative arithmetic, then the
 * postfix substitution {@code ~ /pattern/replacement/}. Operands are literals (string, number,
 * bool, {@code /regex/}, list), bare references (classified by {@link OperandClassifier}), or
 * function calls with positional and {@code key=value} arguments.
 *
 * <p>
 * The parser is purely syntactic — it does not know which functions exist or whether a construct
 * can be evaluated; that is the lowering pass's job. It fails loudly (via
 * {@link ExpressionException}) on malformed input: unexpected tokens, missing brackets, a trailing
 * comparison with no right-hand operand, or trailing input after a complete expression.
 * </p>
 */
public final class CheckExpressionParser
{

    private final List<Token> tokens;

    private int idx;

    private CheckExpressionParser(List<Token> tokens)
    {
        this.tokens = tokens;
    }


    /**
     * Parses an expression string into an {@link Expr} tree.
     *
     * @param source
     *            the expression text
     * @return the parsed (boolean-typed) expression
     * @throws ExpressionException
     *             on any lexical or syntactic error; a null or blank source is rejected as
     *             "Expression must not be empty"
     */
    public static Expr parse(@Nullable String source)
    {
        if (source == null || source.isBlank())
        {
            throw new ExpressionException("Expression must not be empty");
        }
        CheckExpressionParser p = new CheckExpressionParser(ExpressionLexer.tokenize(source));
        Expr e = p.parseOr();
        if (p.peek().type() != TokenType.EOF)
        {
            throw new ExpressionException("Unexpected trailing input '" + p.peek().text() + "'",
                    p.peek().position());
        }
        return e;
    }


    private Expr parseOr()
    {
        Expr first = parseAnd();
        if (peek().type() != TokenType.OR)
        {
            return first;
        }
        List<Expr> parts = new ArrayList<>();
        parts.add(first);
        while (peek().type() == TokenType.OR)
        {
            advance();
            parts.add(parseAnd());
        }
        return new Expr.Or(parts);
    }


    private Expr parseAnd()
    {
        Expr first = parseUnary();
        if (peek().type() != TokenType.AND)
        {
            return first;
        }
        List<Expr> parts = new ArrayList<>();
        parts.add(first);
        while (peek().type() == TokenType.AND)
        {
            advance();
            parts.add(parseUnary());
        }
        return new Expr.And(parts);
    }


    private Expr parseUnary()
    {
        if (peek().type() == TokenType.NOT)
        {
            advance();
            return new Expr.Not(parseUnary());
        }
        return parsePrimary();
    }


    private Expr parsePrimary()
    {
        // No parenthesis arm of its own any more (runbook W2a): every `(` is parseAtom's, which
        // parses a whole expression inside the parentheses and then lets the arithmetic and
        // comparison chain continue after them — `((A - B) / B) * 100` and `(A == 1) and B == 2`
        // alike. A second arm here consumed a leading `(…)` as a complete primary and could not
        // continue with `/ B` after it.
        return parseComparison();
    }


    private Expr parseComparison()
    {
        Expr left = parseOperand();
        TokenType t = peek().type();
        Expr.BinOp op = comparisonOp(t);
        if (op != null)
        {
            advance();
            return new Expr.Binary(op, left, parseOperand());
        }
        if (t == TokenType.IN)
        {
            advance();
            return new Expr.Binary(Expr.BinOp.IN, left, parseOperand());
        }
        if (t == TokenType.NOT && peekAt(1).type() == TokenType.IN)
        {
            advance();
            advance();
            return new Expr.Binary(Expr.BinOp.NOT_IN, left, parseOperand());
        }
        // No comparison operator: left stands alone as a boolean predicate (e.g. exists(X)).
        return left;
    }


    private static Expr.@Nullable BinOp comparisonOp(TokenType t)
    {
        return switch (t)
        {
        case EQEQ -> Expr.BinOp.EQ;
        case NEQ -> Expr.BinOp.NEQ;
        case LT -> Expr.BinOp.LT;
        case GT -> Expr.BinOp.GT;
        case LE -> Expr.BinOp.LE;
        case GE -> Expr.BinOp.GE;
        case MATCH -> Expr.BinOp.MATCH;
        case NMATCH -> Expr.BinOp.NMATCH;
        default -> null;
        };
    }


    /**
     * Parses a value operand, arithmetic-aware: additive ({@code + -}) over multiplicative
     * ({@code * /}) over atoms, with {@code (…)} grouping. Arithmetic binds tighter than the
     * comparison/membership operators, so {@code X != A / B} parses as {@code X != (A / B)}.
     */
    private Expr parseOperand()
    {
        return parseSum();
    }


    private Expr parseSum()
    {
        Expr left = parseProduct();
        while (peek().type() == TokenType.PLUS || peek().type() == TokenType.MINUS)
        {
            Expr.BinOp op = peek().type() == TokenType.PLUS ? Expr.BinOp.ADD : Expr.BinOp.SUB;
            advance();
            left = new Expr.Binary(op, left, parseProduct());
        }
        return left;
    }


    private Expr parseProduct()
    {
        Expr left = parsePostfix();
        while (peek().type() == TokenType.STAR || peek().type() == TokenType.SLASH)
        {
            Expr.BinOp op = peek().type() == TokenType.STAR ? Expr.BinOp.MUL : Expr.BinOp.DIV;
            advance();
            left = new Expr.Binary(op, left, parsePostfix());
        }
        return left;
    }


    /**
     * The substitution operator {@code X ~ /pattern/replacement/}
     * ({@code PLAN-engine-gap-string-extract} §3, runbook W1b SX): a postfix on a primary — the
     * tightest binding, under {@code * /} — left-associative ({@code X ~ /a/b/ ~ /b/c/}), its right
     * side exactly one substitution literal (the lexer reads it only directly behind {@code ~}, so
     * a string, a reference or a parenthesised operand there is this parse error).
     */
    private Expr parsePostfix()
    {
        Expr left = parseAtom();
        while (peek().type() == TokenType.SUBST)
        {
            advance();
            Token literal = peek();
            if (literal.type() != TokenType.SUBSTITUTION || literal.replacement() == null)
            {
                throw new ExpressionException(
                        "the right-hand side of ~ must be a /pattern/replacement/ literal, not '"
                                + literal.text() + "'",
                        literal.position());
            }
            advance();
            left = new Expr.Binary(Expr.BinOp.SUBST, left, new Expr.Lit(Expr.LitKind.SUBSTITUTION,
                    new Expr.Substitution(literal.text(), literal.replacement())));
        }
        return left;
    }


    private Expr parseAtom()
    {
        Token tok = peek();
        return switch (tok.type())
        {
        case STRING ->
        {
            advance();
            yield new Expr.Lit(Expr.LitKind.STRING, tok.text());
        }
        case NUMBER ->
        {
            advance();
            yield new Expr.Lit(Expr.LitKind.NUMBER, Double.valueOf(tok.text()));
        }
        case REGEX ->
        {
            advance();
            yield new Expr.Lit(Expr.LitKind.REGEX, tok.text());
        }
        case TRUE ->
        {
            advance();
            yield new Expr.Lit(Expr.LitKind.BOOL, true);
        }
        case FALSE ->
        {
            advance();
            yield new Expr.Lit(Expr.LitKind.BOOL, false);
        }
        case LPAREN ->
        {
            // A parenthesised operand is a whole expression, so a boolean may be handed to a
            // function as an argument — `read_value(X, domain=D, filter=(TSPARMCD == "SPECIES"))`
            // (owner D9 / D12; runbook W2a). Arithmetic grouping `(A + B) * C` parses as before:
            // parseOr descends through comparison to the sum.
            advance();
            Expr e = parseOr();
            expect(TokenType.RPAREN, ")");
            yield e;
        }
        case LBRACKET -> parseList();
        case LBRACE -> parseSided();
        case IDENT -> parseIdentOperand(tok);
        default -> throw new ExpressionException(
                "Expected an operand but found '" + tok.text() + "'", tok.position());
        };
    }


    private Expr parseIdentOperand(Token tok)
    {
        advance();
        if (peek().type() == TokenType.LPAREN)
        {
            return parseCall(tok.text());
        }
        return new Expr.Ref(tok.text(), OperandClassifier.classify(tok.text(), tok.position()));
    }


    private Expr parseList()
    {
        expect(TokenType.LBRACKET, "[");
        List<Expr> items = new ArrayList<>();
        if (peek().type() != TokenType.RBRACKET)
        {
            items.add(parseOperand());
            while (peek().type() == TokenType.COMMA)
            {
                advance();
                items.add(parseOperand());
            }
        }
        expect(TokenType.RBRACKET, "]");
        return new Expr.Lit(Expr.LitKind.LIST, items);
    }


    /**
     * The sided key literal {@code {left: X, right: Y}} (runbook W1b RO, R3): exactly the two keys
     * {@code left} and {@code right}, in either order, each a column operand; printed in the
     * canonical {@code left, right} order. Where it may stand is the loader's judgement
     * ({@code RulePackageLoader.validateSidedKeys}: a {@code group=} member under {@code domain=}).
     */
    private Expr parseSided()
    {
        Token open = peek();
        expect(TokenType.LBRACE, "{");
        Expr left = null;
        Expr right = null;
        for (int i = 0; i < 2; i++)
        {
            Token key = peek();
            if (key.type() != TokenType.IDENT
                    || !("left".equals(key.text()) || "right".equals(key.text())))
            {
                throw new ExpressionException(
                        "a sided key is written {left: X, right: Y}, not '" + key.text() + "'",
                        key.position());
            }
            advance();
            expect(TokenType.COLON, ":");
            Expr side = parseOperand();
            if ("left".equals(key.text()) ? left != null : right != null)
            {
                throw new ExpressionException(
                        "a sided key names " + key.text() + " once: {left: X, right: Y}",
                        key.position());
            }
            if ("left".equals(key.text()))
            {
                left = side;
            }
            else
            {
                right = side;
            }
            if (i == 0)
            {
                expect(TokenType.COMMA, ",");
            }
        }
        expect(TokenType.RBRACE, "}");
        if (left == null || right == null)
        {
            throw new ExpressionException("a sided key names both sides: {left: X, right: Y}",
                    open.position());
        }
        return new Expr.Lit(Expr.LitKind.SIDED, new Expr.SidedKey(left, right));
    }


    private Expr parseCall(String name)
    {
        expect(TokenType.LPAREN, "(");
        List<Expr> args = new ArrayList<>();
        Map<String, Expr> kwargs = new LinkedHashMap<>();
        if (net.cumba.corej.core.exec.JoinedRowFold.JOINED.equals(name)
                && peek().type() != TokenType.RPAREN)
        {
            // PLAN-cg0218-transition-day-precision P1: joined(<condition>) takes a boolean by
            // declaration, so its argument is a whole expression without the parentheses an
            // ordinary boolean argument needs (`filter=(A == "b")`, D9 / D12 — that arm stays
            // exactly as it was for every other call). ExpressionPrinter prints it bare again.
            // A keyword or a second argument is named for what it is (review C8 L3).
            if (peek().type() == TokenType.IDENT && peekAt(1).type() == TokenType.EQ)
            {
                throw new ExpressionException(
                        "joined(…) takes exactly one condition, not a keyword argument",
                        peek().position());
            }
            args.add(parseOr());
            if (peek().type() == TokenType.COMMA)
            {
                throw new ExpressionException("joined(…) takes exactly one condition",
                        peek().position());
            }
        }
        else if (net.cumba.corej.core.exec.JoinedRowFold.isQuantifier(name)
                && peek().type() != TokenType.RPAREN)
        {
            // a quantifier whose argument fails to parse hints the one shape it takes
            try
            {
                parseArg(args, kwargs);
                while (peek().type() == TokenType.COMMA)
                {
                    advance();
                    parseArg(args, kwargs);
                }
            }
            catch (ExpressionException e)
            {
                ExpressionException hinted = new ExpressionException(
                        e.getMessage() + " — " + name + "(…) takes joined(<condition>)",
                        e.getPosition());
                hinted.initCause(e);
                throw hinted;
            }
            if (peek().type() != TokenType.RPAREN)
            {
                throw new ExpressionException("Expected ')' but found '" + peek().text() + "' — "
                        + name + "(…) takes joined(<condition>)", peek().position());
            }
        }
        else if (peek().type() != TokenType.RPAREN)
        {
            parseArg(args, kwargs);
            while (peek().type() == TokenType.COMMA)
            {
                advance();
                parseArg(args, kwargs);
            }
        }
        expect(TokenType.RPAREN, ")");
        return new Expr.Call(name, args, kwargs);
    }


    private void parseArg(List<Expr> args, Map<String, Expr> kwargs)
    {
        // key=value keyword argument: IDENT immediately followed by '='.
        if (peek().type() == TokenType.IDENT && peekAt(1).type() == TokenType.EQ)
        {
            String key = peek().text();
            advance(); // key
            advance(); // '='
            if (kwargs.put(key, parseOperand()) != null)
            {
                throw new ExpressionException("Duplicate keyword argument '" + key + "'",
                        peek().position());
            }
            return;
        }
        if (!kwargs.isEmpty())
        {
            throw new ExpressionException("Positional argument after keyword argument",
                    peek().position());
        }
        args.add(parseOperand());
    }


    private void expect(TokenType type, String lexeme)
    {
        if (peek().type() != type)
        {
            throw new ExpressionException(
                    "Expected '" + lexeme + "' but found '" + peek().text() + "'",
                    peek().position());
        }
        advance();
    }


    private Token peek()
    {
        return tokens.get(idx);
    }


    private Token peekAt(int ahead)
    {
        int i = idx + ahead;
        return i < tokens.size() ? tokens.get(i) : tokens.get(tokens.size() - 1);
    }


    private void advance()
    {
        if (idx < tokens.size() - 1)
        {
            idx++;
        }
    }

}
