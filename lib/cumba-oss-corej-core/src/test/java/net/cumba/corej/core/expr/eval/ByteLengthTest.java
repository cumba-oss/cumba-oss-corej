package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-engine-gap-byte-length} §5.1–5.2 (runbook W1b BL1, owner-ruled 2026-10-07): the
 * {@code unit=} / {@code encoding=} parameters of {@code len} / {@code length} and
 * {@code max_value_length}. {@code unit="bytes"} counts the value encoded in {@code encoding};
 * {@code encoding} omitted reads the evaluated table's own {@code Encoding} metadata, and a table
 * without one — or declaring {@code MIXED} — answers <b>missing</b>, never a guess.
 * {@code unit="chars"} is the default and counts code points in both functions ({@code len} since
 * runbook W2 BL2; before it, UTF-16 units).
 */
class ByteLengthTest
{

    private static Expr parse(String source)
    {
        return CheckExpressionParser.parse(source);
    }


    private static EvaluationContext ctx(IDataTable t)
    {
        return EvaluationContext.builder().table(t).datasetResolver(_ -> null).domainName("DM")
                .build();
    }


    private static BitSet eval(String source, IDataTable t)
    {
        return NativeExprEvaluator.evaluate(parse(source), ctx(t));
    }

    /** {@code e-acute} is 1 UTF-16 unit, 2 UTF-8 bytes, 1 windows-1252 byte. */
    private static final String E_ACUTE = "é";

    /** A supplementary-plane character: 2 UTF-16 units, 1 code point, 4 UTF-8 bytes. */
    private static final String SMILEY = "😀";

    @Test
    void lenInBytesCountsTheValueEncodedInTheWrittenCharset()
    {
        IDataTable t = MockTable.of().col("X", E_ACUTE, "abc", "", SMILEY).build();
        assertTrue(eval("len(X, unit=\"bytes\", encoding=\"UTF-8\") == 2", t).get(0));
        assertTrue(eval("len(X, unit=\"bytes\", encoding=\"UTF-8\") == 3", t).get(1));
        assertTrue(eval("len(X, unit=\"bytes\", encoding=\"UTF-8\") == 0", t).get(2),
                "an empty string is a present value of zero bytes");
        assertTrue(eval("len(X, unit=\"bytes\", encoding=\"UTF-8\") == 4", t).get(3));
        assertTrue(eval("len(X, unit=\"bytes\", encoding=\"windows-1252\") == 1", t).get(0),
                "the same value is one byte in a single-byte charset");
        assertTrue(eval("length(X, unit=\"bytes\", encoding=\"UTF-8\") == 2", t).get(0),
                "length is the alias of len, parameters included");
    }


    @Test
    void lenInCharsCountsCodePoints()
    {
        // runbook W2 BL2 (PLAN-engine-gap-byte-length §4.2, owner Q3 "agree to change and use
        // code points"): len(x) and len(x, unit="chars") count Unicode code points, as
        // max_value_length always has — a supplementary-plane character is ONE character, not
        // its two UTF-16 units (BL1 pinned 2 here until this unit). A BMP character is one either
        // way, and so is the alias.
        IDataTable t = MockTable.of().col("X", SMILEY, E_ACUTE, SMILEY + "ab", "").build();
        assertTrue(eval("len(X) == 1", t).get(0));
        assertTrue(eval("len(X, unit=\"chars\") == 1", t).get(0));
        assertTrue(eval("length(X) == 1", t).get(0), "the alias counts the same");
        assertTrue(eval("len(X, unit=\"chars\") == 1", t).get(1));
        assertTrue(eval("len(X) == 3", t).get(2));
        assertTrue(eval("len(X) == 0", t).get(3), "an empty string is still 0");
        assertTrue(eval("len(X) == max_value_length(X)", t).get(2),
                "len and max_value_length agree on the longest value's unit");
    }


    @Test
    void lenInBytesDefaultsToTheTablesOwnEncoding()
    {
        IDataTable t = MockTable.of().col("X", E_ACUTE).metaValue("Encoding", "windows-1252")
                .build();
        assertTrue(eval("len(X, unit=\"bytes\") == 1", t).get(0),
                "windows-1252 is the table's declared decode charset");
        IDataTable utf8 = MockTable.of().col("X", E_ACUTE).metaValue("Encoding", "UTF-8").build();
        assertTrue(eval("len(X, unit=\"bytes\") == 2", utf8).get(0));
    }


    @Test
    void lenInBytesIsMissingWhenTheTableDeclaresNoUsableEncoding()
    {
        IDataTable none = MockTable.of().col("X", E_ACUTE).build();
        assertTrue(eval("empty(len(X, unit=\"bytes\"))", none).get(0),
                "no Encoding metadata: the byte count is unknown, so the answer is missing");
        IDataTable mixed = MockTable.of().col("X", E_ACUTE).metaValue("Encoding", "MIXED").build();
        assertTrue(eval("empty(len(X, unit=\"bytes\"))", mixed).get(0),
                "MIXED names no single charset, so the answer is missing");
        assertFalse(eval("len(X, unit=\"bytes\") > 0", mixed).get(0),
                "a missing answer never fires a comparison");
        // the written encoding wins over the table's
        assertTrue(eval("len(X, unit=\"bytes\", encoding=\"UTF-8\") == 2", mixed).get(0));
    }


    @Test
    void lenInBytesKeepsTheMissingIdentity()
    {
        IDataTable t = MockTable.of().col("X", "abc", (String) null).build();
        assertTrue(eval("empty(len(X, unit=\"bytes\", encoding=\"UTF-8\"))", t).get(1),
                "a missing input has no length in any unit");
        assertFalse(eval("empty(len(X, unit=\"bytes\", encoding=\"UTF-8\"))", t).get(0));
    }


    @Test
    void maxValueLengthInBytesCountsTheLongestEncodedValue()
    {
        // code points: 3 (SMILEY + "ab"); UTF-8 bytes: 6 (4 + 2); windows-1252 cannot encode
        // the smiley — the longest ENCODABLE answer is what the charset produces for it (a
        // replacement byte), so the UTF-8 arm is the one pinned on a non-ASCII value.
        IDataTable t = MockTable.of().col("X", SMILEY + "ab", E_ACUTE + E_ACUTE, "")
                .metaValue("Encoding", "UTF-8").build();
        assertTrue(eval("max_value_length(X) == 3", t).get(0),
                "the default unit stays code points (unchanged)");
        assertTrue(eval("max_value_length(X, unit=\"chars\") == 3", t).get(0));
        assertTrue(eval("max_value_length(X, unit=\"bytes\", encoding=\"UTF-8\") == 6", t).get(0));
        assertTrue(eval("max_value_length(X, unit=\"bytes\") == 6", t).get(0),
                "encoding omitted reads the table's Encoding");
        assertTrue(eval("max_value_length(\"X\", unit=\"bytes\") == 6", t).get(0),
                "the string form names the column (a name slot)");
        assertTrue(
                eval("max_value_length(X, unit=\"bytes\", encoding=\"windows-1252\") == 3", t)
                        .get(0),
                "the smiley is one replacement byte in windows-1252, plus 'ab': 3; the two"
                        + " e-acutes are 2");
    }


    @Test
    void maxValueLengthInBytesIsMissingWithoutAUsableTableEncoding()
    {
        IDataTable none = MockTable.of().col("X", "abc").build();
        assertTrue(eval("empty(max_value_length(X, unit=\"bytes\"))", none).get(0));
        IDataTable mixed = MockTable.of().col("X", "abc").metaValue("Encoding", "MIXED").build();
        assertTrue(eval("empty(max_value_length(X, unit=\"bytes\"))", mixed).get(0));
        assertTrue(
                eval("max_value_length(X, unit=\"bytes\", encoding=\"UTF-8\") == 3", mixed).get(0),
                "a written encoding overrides MIXED");
        assertTrue(eval("max_value_length(X, unit=\"chars\") == 3", none).get(0),
                "chars never needs an encoding");
    }


    @Test
    void theUnitVocabularyAndTheCharsetAreCheckedAtLoad()
    {
        IDataTable t = MockTable.of().col("X", "abc").build();
        ExpressionException unit = assertThrows(ExpressionException.class,
                () -> eval("len(X, unit=\"octets\") > 0", t));
        assertTrue(unit.getMessage().contains("unit"), unit.getMessage());
        assertTrue(unit.getMessage().contains("bytes"), unit.getMessage());
        ExpressionException charset = assertThrows(ExpressionException.class,
                () -> eval("len(X, unit=\"bytes\", encoding=\"no-such-charset\") > 0", t));
        assertTrue(charset.getMessage().contains("no-such-charset"), charset.getMessage());
        ExpressionException computed = assertThrows(ExpressionException.class,
                () -> eval("len(X, unit=X) > 0", t));
        assertTrue(computed.getMessage().contains("unit"), computed.getMessage());
        ExpressionException mvl = assertThrows(ExpressionException.class,
                () -> eval("max_value_length(X, unit=\"octets\") > 0", t));
        assertTrue(mvl.getMessage().contains("unit"), mvl.getMessage());
        ExpressionException mvlCharset = assertThrows(ExpressionException.class,
                () -> eval("max_value_length(X, unit=\"bytes\", encoding=\"nope-1\") > 0", t));
        assertTrue(mvlCharset.getMessage().contains("nope-1"), mvlCharset.getMessage());
        // the positional spelling binds the same parameters
        assertTrue(eval("len(X, \"bytes\", \"UTF-8\") == 3", t).get(0));
    }


    @Test
    void theSurfaceListsTheTwoParametersWithTheirVocabulary()
    {
        String surface = net.cumba.corej.core.RuleLanguage.surface();
        assertTrue(
                surface.contains("\nlen(x:unknown, unit:string?, encoding:string?) kind=value\n"),
                surface);
        assertTrue(
                surface.contains(
                        "\nlength(x:unknown, unit:string?, encoding:string?) kind=value\n"),
                surface);
        assertTrue(surface.contains("\nmax_value_length(name:unknown@name?, unit:string?,"
                + " encoding:string?) kind=value\n"), surface);
        assertTrue(surface.contains("\nlen.unit: literal {bytes,chars}\n"), surface);
        assertTrue(surface.contains("\nlen.encoding: literal\n"), surface);
        assertTrue(surface.contains("\nlength.unit: literal {bytes,chars}\n"), surface);
        assertTrue(surface.contains("\nmax_value_length.unit: literal {bytes,chars}\n"), surface);
        assertTrue(surface.contains("\nmax_value_length.encoding: literal\n"), surface);
        assertEquals(1, surface.split("\nlen\\.unit: ", -1).length - 1, "listed once");
    }
}
