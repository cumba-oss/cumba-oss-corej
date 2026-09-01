package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import net.cumba.cdisc.core.exec.OperandSubstitutor.Driver;
import net.cumba.cdisc.core.exec.OperandSubstitutor.Literal;
import net.cumba.cdisc.core.exec.OperandSubstitutor.OperandParseException;
import net.cumba.cdisc.core.exec.OperandSubstitutor.OperatorMismatchException;
import net.cumba.cdisc.core.exec.OperandSubstitutor.ParsedOperand;
import net.cumba.cdisc.core.exec.OperandSubstitutor.Position;
import net.cumba.cdisc.core.exec.OperandSubstitutor.Scalar;
import net.cumba.cdisc.core.exec.OperandSubstitutor.SubstitutionException;
import net.cumba.cdisc.core.exec.OperandSubstitutor.Wildcard;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Fix #37 — unit tests for {@link OperandSubstitutor}: parse / validate / substituteScalar /
 * toColumnPattern.
 */
class OperandSubstitutorTest
{

    // ---------------------------------------------------------------------
    // parse — happy paths
    // ---------------------------------------------------------------------

    @Test
    void parse_plainColumnName_isScalarWithSingleLiteral()
    {
        ParsedOperand p = OperandSubstitutor.parse("AESTDY");
        assertTrue(p instanceof Scalar);
        Scalar s = (Scalar) p;
        assertNull(s.foreignDataset());
        assertEquals(1, s.segments().size());
        assertEquals(new Literal("AESTDY"), s.segments().get(0));
        assertFalse(s.hasDrivers());
    }


    @Test
    void parse_driverScalar_localTable()
    {
        ParsedOperand p = OperandSubstitutor.parse("AP${APERIOD:%02d}SDT");
        assertTrue(p instanceof Scalar);
        Scalar s = (Scalar) p;
        assertNull(s.foreignDataset());
        assertEquals(3, s.segments().size());
        assertEquals(new Literal("AP"), s.segments().get(0));
        assertEquals(new Driver("APERIOD", "%02d"), s.segments().get(1));
        assertEquals(new Literal("SDT"), s.segments().get(2));
        assertTrue(s.hasDrivers());
    }


    @Test
    void parse_driverScalar_foreignDataset()
    {
        ParsedOperand p = OperandSubstitutor.parse("ADSL.AP${APERIOD:%02d}SDT");
        Scalar s = (Scalar) p;
        assertEquals("ADSL", s.foreignDataset());
        assertEquals(3, s.segments().size());
        assertEquals(new Literal("AP"), s.segments().get(0));
        assertEquals(new Driver("APERIOD", "%02d"), s.segments().get(1));
        assertEquals(new Literal("SDT"), s.segments().get(2));
    }


    @Test
    void parse_wildcard_foreignDataset()
    {
        ParsedOperand p = OperandSubstitutor.parse("ADSL.PH${*}SDT");
        assertTrue(p instanceof Wildcard);
        Wildcard w = (Wildcard) p;
        assertEquals("ADSL", w.foreignDataset());
        // One star => two fragments.
        assertEquals(2, w.fragments().size());
        assertEquals(1, w.fragments().get(0).size());
        assertEquals(new Literal("PH"), w.fragments().get(0).get(0));
        assertEquals(1, w.fragments().get(1).size());
        assertEquals(new Literal("SDT"), w.fragments().get(1).get(0));
        assertFalse(w.hasDrivers());
    }


    @Test
    void parse_wildcard_localTable()
    {
        ParsedOperand p = OperandSubstitutor.parse("PH${*}SDT");
        Wildcard w = (Wildcard) p;
        assertNull(w.foreignDataset());
        assertEquals(2, w.fragments().size());
        assertEquals(new Literal("PH"), w.fragments().get(0).get(0));
        assertEquals(new Literal("SDT"), w.fragments().get(1).get(0));
    }


    @Test
    void parse_twoWildcards_threeFragments()
    {
        ParsedOperand p = OperandSubstitutor.parse("ADSL.TR${*}PG${*}");
        assertTrue(p instanceof Wildcard);
        Wildcard w = (Wildcard) p;
        assertEquals("ADSL", w.foreignDataset());
        // Two stars => three fragments; the trailing fragment is empty.
        assertEquals(3, w.fragments().size());
        assertEquals(new Literal("TR"), w.fragments().get(0).get(0));
        assertEquals(new Literal("PG"), w.fragments().get(1).get(0));
        assertTrue(w.fragments().get(2).isEmpty());
        assertFalse(w.hasDrivers());
    }


    @Test
    void parse_threeWildcards_fourFragments()
    {
        ParsedOperand p = OperandSubstitutor.parse("A${*}B${*}C${*}D");
        Wildcard w = (Wildcard) p;
        assertEquals(4, w.fragments().size());
        assertEquals(new Literal("A"), w.fragments().get(0).get(0));
        assertEquals(new Literal("B"), w.fragments().get(1).get(0));
        assertEquals(new Literal("C"), w.fragments().get(2).get(0));
        assertEquals(new Literal("D"), w.fragments().get(3).get(0));
    }


    @Test
    void parse_twoWildcards_separatedByLiteral_isAllowed()
    {
        // EC-16: `${*}A${*}` is now a valid two-star template (previously rejected).
        ParsedOperand p = OperandSubstitutor.parse("${*}A${*}");
        Wildcard w = (Wildcard) p;
        assertEquals(3, w.fragments().size());
        assertTrue(w.fragments().get(0).isEmpty());
        assertEquals(new Literal("A"), w.fragments().get(1).get(0));
        assertTrue(w.fragments().get(2).isEmpty());
    }


    @Test
    void parse_driverWithoutFormat()
    {
        ParsedOperand p = OperandSubstitutor.parse("X${VAR}Y");
        Scalar s = (Scalar) p;
        assertEquals(3, s.segments().size());
        assertEquals(new Driver("VAR", null), s.segments().get(1));
    }

    // ---------------------------------------------------------------------
    // parse — rejection paths
    // ---------------------------------------------------------------------


    @Test
    void parse_rejects_unterminatedDollarBrace()
    {
        assertThrows(OperandParseException.class, () -> OperandSubstitutor.parse("${"));
    }


    @Test
    void parse_rejects_unterminatedDriver()
    {
        assertThrows(OperandParseException.class, () -> OperandSubstitutor.parse("${VAR"));
    }


    @Test
    void parse_rejects_emptyFormat()
    {
        assertThrows(OperandParseException.class, () -> OperandSubstitutor.parse("${VAR:}"));
    }


    @Test
    void parse_rejects_emptyVarName()
    {
        assertThrows(OperandParseException.class, () -> OperandSubstitutor.parse("${}"));
    }


    @Test
    void parse_rejects_formatOnWildcard()
    {
        assertThrows(OperandParseException.class, () -> OperandSubstitutor.parse("${*:fmt}"));
    }


    @Test
    void parse_rejects_adjacentWildcards()
    {
        // EC-16 Q-13b: `${*}${*}` has an empty interior fragment => ambiguous digit split.
        assertThrows(OperandParseException.class, () -> OperandSubstitutor.parse("${*}${*}"));
    }


    @Test
    void parse_rejects_adjacentWildcards_interior()
    {
        // Three stars with an empty middle fragment is likewise rejected.
        assertThrows(OperandParseException.class, () -> OperandSubstitutor.parse("A${*}${*}B"));
    }


    @Test
    void parse_rejects_invalidFormatSpec()
    {
        assertThrows(OperandParseException.class, () -> OperandSubstitutor.parse("${VAR:zz}"));
    }

    // ---------------------------------------------------------------------
    // validate
    // ---------------------------------------------------------------------


    @Test
    void validate_scalarAccepts_anyOperator_inEitherPosition()
    {
        ParsedOperand scalar = OperandSubstitutor.parse("AP${APERIOD:%02d}SDT");
        OperandSubstitutor.validate(scalar, "equal_to", Position.NAME);
        OperandSubstitutor.validate(scalar, "equal_to", Position.VALUE);
        OperandSubstitutor.validate(scalar, "var_exists", Position.NAME);
    }


    @Test
    void validate_wildcardInName_acceptsExistsAndNotExists()
    {
        ParsedOperand wild = OperandSubstitutor.parse("PH${*}SDT");
        OperandSubstitutor.validate(wild, "var_exists", Position.NAME);
        OperandSubstitutor.validate(wild, "var_not_exists", Position.NAME);
    }


    @Test
    void validate_wildcardInName_rejectsEqualTo()
    {
        ParsedOperand wild = OperandSubstitutor.parse("PH${*}SDT");
        OperatorMismatchException ex = assertThrows(OperatorMismatchException.class,
                () -> OperandSubstitutor.validate(wild, "equal_to", Position.NAME));
        assertTrue(ex.getMessage().contains("equal_to"), "message names operator");
        assertTrue(ex.getMessage().contains("name"), "message names position");
    }


    @Test
    void validate_wildcardInValue_acceptsContainedByOperators()
    {
        ParsedOperand wild = OperandSubstitutor.parse("ADSL.PH${*}SDT");
        OperandSubstitutor.validate(wild, "is_contained_by", Position.VALUE);
        OperandSubstitutor.validate(wild, "is_not_contained_by", Position.VALUE);
        OperandSubstitutor.validate(wild, "is_contained_by_case_insensitive", Position.VALUE);
    }


    @Test
    void validate_wildcardInValue_rejectsEqualTo()
    {
        ParsedOperand wild = OperandSubstitutor.parse("ADSL.PH${*}SDT");
        OperatorMismatchException ex = assertThrows(OperatorMismatchException.class,
                () -> OperandSubstitutor.validate(wild, "equal_to", Position.VALUE));
        assertTrue(ex.getMessage().contains("equal_to"));
        assertTrue(ex.getMessage().contains("value"));
    }


    @Test
    void validate_wildcardInValue_rejectsNotEqualTo()
    {
        ParsedOperand wild = OperandSubstitutor.parse("ADSL.PH${*}SDT");
        assertThrows(OperatorMismatchException.class,
                () -> OperandSubstitutor.validate(wild, "not_equal_to", Position.VALUE));
    }

    // ---------------------------------------------------------------------
    // substituteScalar
    // ---------------------------------------------------------------------


    @Test
    void substituteScalar_zeroPaddedNumeric()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1", "S1").colLong("APERIOD", 2L, 12L)
                .name("ADAE").build();
        EvaluationContext ctx = EvaluationContext.builder().table(table).build();
        Scalar s = (Scalar) OperandSubstitutor.parse("AP${APERIOD:%02d}SDT");
        assertEquals("AP02SDT", OperandSubstitutor.substituteScalar(s, ctx, 0));
        assertEquals("AP12SDT", OperandSubstitutor.substituteScalar(s, ctx, 1));
    }


    @Test
    void substituteScalar_missingColumn_throws()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1").name("ADAE").build();
        EvaluationContext ctx = EvaluationContext.builder().table(table).build();
        Scalar s = (Scalar) OperandSubstitutor.parse("AP${APERIOD:%02d}SDT");
        SubstitutionException ex = assertThrows(SubstitutionException.class,
                () -> OperandSubstitutor.substituteScalar(s, ctx, 0));
        assertTrue(ex.getMessage().contains("APERIOD"));
    }


    @Test
    void substituteScalar_missingValue_throws()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1").colLong("APERIOD", new Long[]
        {
                null
        }).name("ADAE").build();
        EvaluationContext ctx = EvaluationContext.builder().table(table).build();
        Scalar s = (Scalar) OperandSubstitutor.parse("AP${APERIOD:%02d}SDT");
        assertThrows(SubstitutionException.class,
                () -> OperandSubstitutor.substituteScalar(s, ctx, 0));
    }


    @Test
    void substituteScalar_stringValueAgainstNumericFormat_throws()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1").col("APERIOD", "foo").name("ADAE")
                .build();
        EvaluationContext ctx = EvaluationContext.builder().table(table).build();
        Scalar s = (Scalar) OperandSubstitutor.parse("AP${APERIOD:%02d}SDT");
        assertThrows(SubstitutionException.class,
                () -> OperandSubstitutor.substituteScalar(s, ctx, 0));
    }


    @Test
    void substituteScalar_foreignDatasetPrefix_preserved()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1").colLong("APERIOD", 2L).name("ADAE")
                .build();
        EvaluationContext ctx = EvaluationContext.builder().table(table).build();
        Scalar s = (Scalar) OperandSubstitutor.parse("ADSL.AP${APERIOD:%02d}SDT");
        assertEquals("ADSL.AP02SDT", OperandSubstitutor.substituteScalar(s, ctx, 0));
    }

    // ---------------------------------------------------------------------
    // toColumnPattern
    // ---------------------------------------------------------------------


    @Test
    void toColumnPattern_simpleWildcard_matchesDigits()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1").name("ADAE").build();
        EvaluationContext ctx = EvaluationContext.builder().table(table).build();
        Wildcard w = (Wildcard) OperandSubstitutor.parse("PH${*}SDT");
        Pattern pat = OperandSubstitutor.toColumnPattern(w, ctx, 0);
        assertNotNull(pat);
        assertTrue(pat.matcher("PH1SDT").matches());
        assertTrue(pat.matcher("PH12SDT").matches());
        assertFalse(pat.matcher("PHASDT").matches());
        assertFalse(pat.matcher("PHSDT").matches());
        assertFalse(pat.matcher("PH1XDT").matches());
    }


    @Test
    void toColumnPattern_combinedDriverAndWildcard()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1").colLong("APERIOD", 2L).name("ADAE")
                .build();
        EvaluationContext ctx = EvaluationContext.builder().table(table).build();
        Wildcard w = (Wildcard) OperandSubstitutor.parse("AP${APERIOD:%02d}${*}SDT");
        Pattern pat = OperandSubstitutor.toColumnPattern(w, ctx, 0);
        assertTrue(pat.matcher("AP021SDT").matches());
        assertTrue(pat.matcher("AP025SDT").matches());
        assertFalse(pat.matcher("AP01XSDT").matches());
        assertFalse(pat.matcher("AP01SDT").matches());
    }


    @Test
    void toColumnPattern_twoWildcards_buildsTwoDigitRuns()
    {
        // EC-16: `^TR\d+PG\d+$` — each star is an independent digit run.
        Wildcard w = (Wildcard) OperandSubstitutor.parse("ADSL.TR${*}PG${*}");
        Pattern pat = OperandSubstitutor.toColumnPattern(w, null, 0);
        assertEquals("^\\QTR\\E\\d+\\QPG\\E\\d+$", pat.pattern());
        assertTrue(pat.matcher("TR01PG1").matches());
        assertTrue(pat.matcher("TR12PG34").matches());
        assertFalse(pat.matcher("TRPG1").matches()); // first star needs >=1 digit
        assertFalse(pat.matcher("TR01PG").matches()); // second star needs >=1 digit
        assertFalse(pat.matcher("TR01XPG1").matches()); // non-digit between runs
    }


    @Test
    void toColumnPattern_driverPlusTwoWildcards_perRow()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1").colLong("APERIOD", 2L).name("ADAE")
                .build();
        EvaluationContext ctx = EvaluationContext.builder().table(table).build();
        // Mix a driver segment with two independent wildcard runs.
        Wildcard w = (Wildcard) OperandSubstitutor.parse("P${APERIOD:%02d}${*}S${*}SDT");
        Pattern pat = OperandSubstitutor.toColumnPattern(w, ctx, 0);
        assertTrue(pat.matcher("P021S3SDT").matches());
        assertTrue(pat.matcher("P0212S34SDT").matches());
        assertFalse(pat.matcher("P021SSDT").matches()); // second run needs a digit
        assertFalse(pat.matcher("P011S3SDT").matches()); // driver resolved to 02, not 01
    }
}
