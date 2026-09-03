package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.cumba.corej.core.exec.OperandSubstitutor.OperandParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Edge-case coverage for {@link OperandSubstitutor}: the null operand check and the format-spec
 * backtrack branches.
 */
class OperandSubstitutorMoreCoverageTest
{

    @Test
    void parse_null_throws()
    {
        OperandParseException ex = assertThrows(OperandParseException.class,
                () -> OperandSubstitutor.parse(null));
        assertNotNull(ex.getMessage());
        assertEquals("operand is null", ex.getMessage());
    }


    @ParameterizedTest(name = "parse({0}) succeeds")
    @ValueSource(strings =
    {
            // Empty string body → parseSegments returns empty list → Scalar with no segments.
            "",
            // Plain literal → Scalar with one Literal segment.
            "AESEQ",
            // %d works on numeric — alternate-flow path that confirms acceptance.
            "X${VAR:%d}Y"
    })
    void parse_succeeds(String input)
    {
        OperandSubstitutor.ParsedOperand parsed = OperandSubstitutor.parse(input);
        assertNotNull(parsed);
    }


    @Test
    void parse_unterminatedPlaceholder_throws()
    {
        assertThrows(OperandParseException.class, () -> OperandSubstitutor.parse("foo${"));
    }


    @Test
    void validate_scalar_alwaysOk()
    {
        OperandSubstitutor.ParsedOperand parsed = OperandSubstitutor.parse("AESEQ");
        // Position.NAME / Position.VALUE both accept Scalar.
        OperandSubstitutor.validate(parsed, "equal_to", OperandSubstitutor.Position.NAME);
        OperandSubstitutor.validate(parsed, "is_contained_by", OperandSubstitutor.Position.VALUE);
    }


    @Test
    void validate_wildcard_namePosition_existsAccepted()
    {
        OperandSubstitutor.ParsedOperand parsed = OperandSubstitutor.parse("${*}");
        // exists / not_exists are OK in name position.
        OperandSubstitutor.validate(parsed, "var_exists", OperandSubstitutor.Position.NAME);
        OperandSubstitutor.validate(parsed, "var_not_exists", OperandSubstitutor.Position.NAME);
    }


    @Test
    void validate_wildcard_namePosition_otherRejected()
    {
        OperandSubstitutor.ParsedOperand parsed = OperandSubstitutor.parse("${*}");
        assertThrows(OperandSubstitutor.OperatorMismatchException.class, () -> OperandSubstitutor
                .validate(parsed, "equal_to", OperandSubstitutor.Position.NAME));
    }


    @Test
    void validate_wildcard_valuePosition_listAwareAccepted()
    {
        OperandSubstitutor.ParsedOperand parsed = OperandSubstitutor.parse("${*}");
        OperandSubstitutor.validate(parsed, "is_contained_by", OperandSubstitutor.Position.VALUE);
        OperandSubstitutor.validate(parsed, "is_not_contained_by",
                OperandSubstitutor.Position.VALUE);
        OperandSubstitutor.validate(parsed, "is_contained_by_case_insensitive",
                OperandSubstitutor.Position.VALUE);
    }


    @Test
    void validate_wildcard_valuePosition_nonListAwareRejected()
    {
        OperandSubstitutor.ParsedOperand parsed = OperandSubstitutor.parse("${*}");
        assertThrows(OperandSubstitutor.OperatorMismatchException.class, () -> OperandSubstitutor
                .validate(parsed, "equal_to", OperandSubstitutor.Position.VALUE));
    }


    @Test
    void validate_wildcard_valuePosition_nullOperator_rejected()
    {
        OperandSubstitutor.ParsedOperand parsed = OperandSubstitutor.parse("${*}");
        assertThrows(OperandSubstitutor.OperatorMismatchException.class,
                () -> OperandSubstitutor.validate(parsed, null, OperandSubstitutor.Position.VALUE));
    }


    @Test
    void operandParseException_message()
    {
        OperandParseException ex = new OperandParseException("boom");
        assertEquals("boom", ex.getMessage());
    }


    @Test
    void substitutionException_message()
    {
        OperandSubstitutor.SubstitutionException ex = new OperandSubstitutor.SubstitutionException(
                "subst boom");
        assertEquals("subst boom", ex.getMessage());
    }


    @Test
    void operatorMismatchException_message()
    {
        OperandSubstitutor.OperatorMismatchException ex = new OperandSubstitutor.OperatorMismatchException(
                "opmiss");
        assertEquals("opmiss", ex.getMessage());
    }
}
