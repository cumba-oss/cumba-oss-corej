package net.cumba.cdisc.core.model;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * EC-87 — the admitted spellings of the {@code relation=} kwarg on
 * {@code has_next_corresponding_record} / {@code does_not_have_next_corresponding_record}, shared
 * by every surface that reads it ({@code ExprCompiler}, {@code RulePackageLoader}'s inline
 * validator, {@code CheckToExpr}, {@code ExprLowering}) so the surfaces cannot drift apart.
 *
 * <p>
 * The relation is applied <b>in disjunction with</b> the shipped {@code KeyPart}-identity
 * correspondence, never instead of it: {@code "=="} is identical to omitting the kwarg, and
 * {@code "<="} / {@code ">="} only <em>widen</em> what corresponds. {@code "<"} / {@code ">"} are
 * deliberately not admitted — under the disjunction they would be behaviourally identical to
 * {@code "<="} / {@code ">="} — and {@code "!="} inverts the operator's own meaning (drop the
 * leading {@code not} instead). An unrecognised spelling is a LOAD error on the inline surface,
 * never a silent fallback to identity.
 * </p>
 */
public enum NextRecordRelation
{

    /** The default — {@code KeyPart} identity only; identical to omitting the kwarg. */
    IDENTITY("==", 0),
    /** Identity, or the current cell is strictly earlier/less than the next record's cell. */
    AT_MOST("<=", -1),
    /** Identity, or the current cell is strictly later/greater than the next record's cell. */
    AT_LEAST(">=", 1);

    /** The Check operators (both Q1 twins) that consume {@code relation=}. */
    public static final Set<String> OPERATORS = Set.of("does_not_have_next_corresponding_record",
            "has_next_corresponding_record");

    /** The admitted spellings, in declaration order — for error messages. */
    public static final List<String> SPELLINGS = Arrays.stream(values())
            .map(NextRecordRelation::spelling).toList();

    private final String spelling;

    private final int direction;

    NextRecordRelation(String aSpelling, int aDirection)
    {
        spelling = aSpelling;
        direction = aDirection;
    }


    /** The kwarg spelling ({@code "=="}, {@code "<="}, {@code ">="}). */
    public String spelling()
    {
        return spelling;
    }


    /**
     * The {@code Primitives.compareCells} direction of the comparison arm: {@code -1} = less,
     * {@code 1} = greater, {@code 0} for {@link #IDENTITY} (which has no comparison arm).
     */
    public int direction()
    {
        return direction;
    }


    /** The relation for a spelling, or {@code null} when the spelling is not admitted. */
    public static @Nullable NextRecordRelation fromSpelling(@Nullable String aSpelling)
    {
        for (NextRecordRelation r : values())
        {
            if (r.spelling.equals(aSpelling))
            {
                return r;
            }
        }
        return null;
    }
}
