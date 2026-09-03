package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * EC-43 §2.6 <b>Table A</b> — the compile methods that must never receive the absent-column fold,
 * pinned <b>structurally</b>: they resolve their target by raw column name (via
 * {@code OperatorRegistry} / {@code GroupSemantics} / {@code groupOperandName}), so the fold cannot
 * reach them and their own absent-column contract stays theirs.
 *
 * <p>
 * <b>The plan's stated premise for this table was too strong and is corrected here.</b> It said
 * these methods "never [go] through {@code operandPlan(_, true)}". Three of them do:
 * {@code compileNotContainsAll} calls the <em>two-argument</em> {@code operandPlan(x, true)} at its
 * list-accessor source branch (both operands) and again for the per-row token operand, and
 * {@code compileMembership}'s list/accessor branches do the same. What is actually true — and what
 * this test asserts — is narrower and exact: <b>none of them passes the fold flag</b>, i.e. no
 * {@code operandPlan(x, true, true)} appears in their bodies. The two-argument overload delegates
 * with {@code foldAbsentColumn = false}, so a two-argument call is by construction fold-free.
 * </p>
 *
 * <p>
 * A structural assertion is the right shape here: a behavioural one would have to encode each
 * operator's own absent-column verdict (which is the very thing this table says is none of EC-43's
 * business), whereas this fails loudly the moment a refactor routes one of these through the folded
 * path.
 * </p>
 */
class AbsentColumnFoldArchitectureTest
{

    /**
     * Table A. Every compile method whose target is resolved by raw column name. The
     * {@code is_unique_value} / {@code is_not_unique_value} pair is dispatched into
     * {@code compileUniqueSet}, and the six {@code *exists*} spellings into {@code compileExists},
     * so the method list is shorter than the operator list the plan prints.
     */
    private static final List<String> TABLE_A = List.of("compileExists", "compileVarIsNull",
            "compileUniqueSet", "compileHasMultipleValuesFor",
            "compileInconsistentEnumeratedColumns", "compileInconsistentAcrossDataset",
            "compileMultipleRowsWithin", "compileEmptyWithinExceptLastRow",
            "compileNextCorrespondingRecord", "compileTargetIsNotSortedBy",
            "compileNotUniqueRelationship", "compileNotContainsAll", "compileHasSameValues",
            "compileSharesNoElementsWith", "compileIsNotOrderedSubsetOf");

    /** A folding call: {@code operandPlan(<anything>, true, <anything but false>)}. */
    private static final Pattern FOLDING_CALL = Pattern
            .compile("operandPlan\\(([^;]*?),\\s*true\\s*,\\s*(?!false\\s*\\))([^;]*?)\\)");

    private static String source;

    @BeforeAll
    static void readCompilerSource() throws IOException
    {
        // `projectBasedir` is set by the surefire configuration in the root pom precisely because
        // the working directory is a scratch dir under target/.
        Path file = Path.of(System.getProperty("projectBasedir", "."), "src", "main", "java", "net",
                "cumba", "corej", "core", "expr", "eval", "ExprCompiler.java");
        assertTrue(Files.isRegularFile(file), () -> "ExprCompiler source not found at " + file);
        source = Files.readString(file, StandardCharsets.UTF_8);
    }


    /**
     * The body of {@code name}, from its declaration to the closing brace at method indentation.
     * The file is Spotless-formatted with the project's eclipse formatter, so a method's closing
     * brace is always {@code "\n    }"} — the only brace at four-space indentation.
     */
    private static String bodyOf(String name)
    {
        Matcher decl = Pattern.compile("\\n    (?:private|static|public).*\\b" + name + "\\(")
                .matcher(source);
        assertTrue(decl.find(), () -> "no method named " + name + " in ExprCompiler");
        int from = decl.start();
        int to = source.indexOf("\n    }", from);
        assertTrue(to > from, () -> "unterminated method body for " + name);
        return source.substring(from, to);
    }


    @DisplayName("Table A: no compile method resolving a raw column name passes the fold flag")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings =
    {
            "compileExists", "compileVarIsNull", "compileUniqueSet", "compileHasMultipleValuesFor",
            "compileInconsistentEnumeratedColumns", "compileInconsistentAcrossDataset",
            "compileMultipleRowsWithin", "compileEmptyWithinExceptLastRow",
            "compileNextCorrespondingRecord", "compileTargetIsNotSortedBy",
            "compileNotUniqueRelationship", "compileNotContainsAll", "compileHasSameValues",
            "compileSharesNoElementsWith", "compileIsNotOrderedSubsetOf",
    })
    void tableAMethodsNeverFold(String method)
    {
        Matcher folding = FOLDING_CALL.matcher(bodyOf(method));
        assertFalse(folding.find(),
                () -> method + " routes an operand through the EC-43 fold (" + method
                        + " resolves its target by raw column name, so folding it would give the "
                        + "operator a second, contradictory absent-column contract): "
                        + (folding.reset().find() ? folding.group() : ""));
    }


    @Test
    @DisplayName("the pin is not vacuous: folding calls DO exist elsewhere in the file")
    void foldingCallsExistOutsideTableA()
    {
        List<String> all = new ArrayList<>();
        Matcher m = FOLDING_CALL.matcher(source);
        while (m.find())
        {
            all.add(m.group());
        }
        assertTrue(all.size() >= 8, () -> "expected the EC-43 fold to be applied at many sites; "
                + "if this fails the regex no longer matches the call form and every Table A "
                + "assertion above has become vacuous. found=" + all);
    }


    @Test
    @DisplayName("Table A is stated in terms of the fold flag, not of operandPlan itself")
    void tableAMethodsMayStillUseTheNonFoldingOverload()
    {
        // Documents the correction: the plan's "these never call operandPlan(_, true)" is FALSE.
        // compileNotContainsAll calls the two-argument (non-folding) form three times.
        long twoArgCalls = Pattern.compile("operandPlan\\([^;]*?,\\s*true\\s*\\)")
                .matcher(bodyOf("compileNotContainsAll")).results().count();
        assertEquals(3, twoArgCalls,
                "compileNotContainsAll DOES call the two-argument operandPlan(x, true) — at its "
                        + "list-accessor source branch (source + required) and for the per-row "
                        + "token operand. That is fold-free, which is all Table A needs; the "
                        + "plan's stronger claim was wrong.");
    }


    @Test
    @DisplayName("every Table A method exists — the list cannot rot into a no-op")
    void tableAMethodsAllExist()
    {
        for (String method : TABLE_A)
        {
            assertFalse(bodyOf(method).isEmpty(), method);
        }
    }
}
