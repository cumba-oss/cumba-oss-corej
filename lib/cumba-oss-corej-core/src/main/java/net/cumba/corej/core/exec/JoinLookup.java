package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Common interface for cross-dataset lookup strategies used by Match_Datasets. Implementations
 * include key-based joins ({@link DatasetLookup}) and relationship-based joins
 * ({@link RelrecExpandedLookup}).
 */
public interface JoinLookup
{

    /**
     * Looks up a column value from the joined dataset for the given row in the primary table.
     *
     * @param primaryTable
     *            the primary table being evaluated
     * @param row
     *            the row index in the primary table
     * @param columnName
     *            the column to look up (may include domain prefix)
     * @return the value, or {@code null} if no match or column not found
     */
    @Nullable
    String lookup(IDataTable primaryTable, long row, String columnName);


    /**
     * Looks up every matched child row's value for the given column. Relationship-based lookups
     * (e.g. {@link RelrecExpandedLookup}) return 0 or 1 element; key-based lookups
     * ({@link DatasetLookup}) may return 0..N when the join key is not unique on the child side.
     * <p>
     * Default implementation delegates to {@link #lookup} and wraps the scalar result — non-null
     * values become a singleton list, {@code null} becomes an empty list. Implementations that can
     * return multiple matches per primary row should override.
     * </p>
     *
     * @param primaryTable
     *            the primary table being evaluated
     * @param row
     *            the row index in the primary table
     * @param columnName
     *            the column to look up
     * @return 0..N values (never {@code null}; empty when there is no match)
     */
    default List<String> lookupAll(IDataTable primaryTable, long row, String columnName)
    {
        String v = lookup(primaryTable, row, columnName);
        return v == null ? List.of() : Collections.singletonList(v);
    }


    /**
     * Returns whether the joined column physically exists for the given primary row. Used to
     * distinguish "the column is absent" (omit the output variable, matching Python's merged-frame
     * semantics) from "the column exists but the value is missing" (keep it as a null/empty value).
     * <p>
     * The default returns {@code true} (assume present), preserving the historical behaviour for
     * key-based joins. Relationship lookups whose related domain may lack a wildcard-resolved
     * column (e.g. {@link RelrecExpandedLookup} resolving {@code **TRT} against a parent with no
     * {@code AETRT}) override this.
     * </p>
     *
     * @param primaryTable
     *            the primary table being evaluated
     * @param row
     *            the row index in the primary table
     * @param columnName
     *            the column to test
     * @return {@code true} if the column exists in the joined dataset for this row
     */
    default boolean hasColumn(IDataTable primaryTable, long row, String columnName)
    {
        return true;
    }


    /**
     * The <b>typed</b> sibling of {@link #lookup}: the matched joined cell as an
     * {@link IDataValue}, keeping the joined column's own type instead of rendering it to text.
     *
     * <p>
     * Step B of {@code PLAN-joined-column-typing}. This is an <b>addition</b>, never a change to
     * {@link #lookup}'s signature, and that is deliberate: a signature change would break all
     * implementations and every call site at once, forcing the report-text, cohort-parity and
     * wildcard-collection consumers to move in the same commit as the behaviour, along with the
     * test files that pin them. Adding beside it lets each value reader migrate on its own, green
     * at every step, and leaves the consumers that legitimately want text untouched.
     * </p>
     *
     * <p>
     * ⚑ <b>This form carries NO type expectation and therefore delegates with
     * {@code numericExpected = false}</b> — i.e. it keeps exactly the behaviour every caller had
     * before {@link #lookupValue(IDataTable, long, String, boolean)} existed. It is retained
     * because the joined read is legitimate from places that hold no rule expectation at all
     * (tests, tooling); ⛔ a production value read on the evaluation path must use the four-argument
     * form, or the dotted-parity invariant documented there is silently not applied.
     * </p>
     *
     * @param primaryTable
     *            the primary table being evaluated
     * @param row
     *            the row index in the primary table
     * @param columnName
     *            the column to look up
     * @return the typed value — a real value or a {@link net.cumba.datatable.values.MissingValue},
     *         never {@code null}
     */
    default IDataValue lookupValue(IDataTable primaryTable, long row, String columnName)
    {
        return lookupValue(primaryTable, row, columnName, false);
    }


    /**
     * The <b>expectation-aware</b> form of {@link #lookupValue(IDataTable, long, String)}: the same
     * read plus the one fact this seam previously could not see — whether the RULE evaluating the
     * operand expects a number.
     *
     * <p>
     * ⭐⭐ <b>THE DOTTED-PARITY INVARIANT (owner ruling, 2026-09-18,
     * {@code PLAN-null-free-value-channel} §9c).</b> A joined variable and a primary variable
     * differ in exactly ONE respect — the joined one is written in the dotted form, the primary one
     * in the short form. In every other respect, at every use site, they behave identically, and
     * that holds for <em>available and absent</em> variables alike. An absent PRIMARY column takes
     * its rule-expected default (numeric → {@code MissingValue.MIS}, otherwise {@code ""} — D76,
     * §1b); until this overload existed an absent JOINED column answered an unconditional
     * {@code ""} even where the expression expected a number, so the SAME variable in the SAME
     * expression answered two different things depending on which access form it was written in.
     * That disparity was the defect this overload closes, in all three production implementations
     * at once.
     * </p>
     *
     * <p>
     * ⚑ <b>Why a parameter, and not the context nor the call site.</b> Threading
     * {@code EvaluationContext} through here would hand every implementation and every test double
     * the whole evaluation state — the central-choke-point shape the owner's standing preference
     * rules out. Resolving the default at the CALL SITE cannot work at all: {@code ""} arrives here
     * from THREE causes — the column is absent entirely, an unmatched row on a char column, and a
     * genuinely STORED empty string — which only the implementation can tell apart, so rewriting
     * {@code "" → MIS} outside would corrupt the third and lose exactly the distinction §1b calls
     * dangerous to lose. One boolean is the whole of what each implementation needs.
     * </p>
     *
     * <p>
     * ⚠ <b>The behaviour change is latent, not theoretical.</b> Measured over all 58 packages / 14
     * 937 rules at the time of the fix, ZERO rules record a numeric expectation for ANY dotted
     * name, so no corpus verdict moves today. The first rule that compares a dotted operand to a
     * number gets a {@code MissingValue} operand and therefore a TOTAL comparison (D34 #5-2), where
     * today's {@code ""} is a present-but-unreadable operand that keeps the "no violation" answer.
     * ⭐ That loudness is the intended, correct consequence of a rule genuinely expecting a number —
     * not a regression, and ⛔ deliberately NOT guarded here.
     * </p>
     *
     * <p>
     * ⛔⛔ <b>The default below CANNOT honour the three-way non-value contract, and that is why every
     * production implementation overrides THIS method rather than the three-argument one.</b> The
     * rule ({@link ScalarSemantics#computedMissing()}) is that a value is a real value or a
     * {@code MissingValue} and never {@code null}, and that <em>which</em> non-value is owed
     * depends on the case: an absent column owes its rule-expected default (char {@code ""},
     * numeric {@code MissingValue.MIS}); a present-but-missing cell owes that cell's own missing
     * identity. {@link #lookup}'s {@code String} channel collapses all of "no such column", "no
     * match" and "present but missing" into one {@code null}, so this default can only answer the
     * computed missing {@code MIS} — right in kind, but it loses a supplied identity and reads an
     * absent CHARACTER column as missing where the constant {@code ""} is owed. ⇒ It therefore also
     * <b>ignores {@code numericExpected} on purpose</b>: not knowing which case it is in, an
     * expectation buys it nothing.
     * </p>
     *
     * <p>
     * ⭐ <b>All three production implementations override it</b> and rule those cases properly (D72
     * / D72a-1 / D75a / §9c): {@link DatasetLookup}, {@code KeyMatchExpandedLookup} and
     * {@code RelrecExpandedLookup}. ⚠ The last one deliberately does NOT also override
     * {@link #declaredTypeOf}: it binds a different target table per expanded row, so no single
     * declared type is correct and {@code MISSING} ("unknown") is the honest publication — while
     * the numeric EXPECTATION passed here is a property of the RULE and is constant for the whole
     * vector, so it stays well defined per row. ⛔ So this default is reached by TEST doubles only —
     * keep it, but treat a new production implementation that relies on it as a defect.
     * </p>
     *
     * @param primaryTable
     *            the primary table being evaluated
     * @param row
     *            the row index in the primary table
     * @param columnName
     *            the column to look up
     * @param numericExpected
     *            whether the rule records a NUMERIC expectation for the operand this read serves,
     *            keyed on its FULL DOTTED name (e.g. {@code DM.AGE}) — see
     *            {@code EvaluationContext.getNumericExpectedColumns}. ⚠ Only the absent-column arm
     *            consults it: a present column's cell and an unmatched row already take their type
     *            from the joined metadata, which is the more specific answer.
     * @return the typed value — a real value or a {@link net.cumba.datatable.values.MissingValue},
     *         never {@code null}
     */
    default IDataValue lookupValue(IDataTable primaryTable, long row, String columnName,
            boolean numericExpected)
    {
        String s = lookup(primaryTable, row, columnName);
        return s == null ? ScalarSemantics.computedMissing()
                : DataValueSupport.getAsDataValue(s, DataValueType.STRING);
    }


    /**
     * The typed sibling of {@link #lookupAll}, with the same 0..N contract.
     *
     * @param primaryTable
     *            the primary table being evaluated
     * @param row
     *            the row index in the primary table
     * @param columnName
     *            the column to look up
     * @return 0..N typed values (never {@code null}; empty when there is no match)
     */
    default List<IDataValue> lookupAllValues(IDataTable primaryTable, long row, String columnName)
    {
        List<String> raw = lookupAll(primaryTable, row, columnName);
        List<IDataValue> out = new ArrayList<>(raw.size());
        for (String v : raw)
        {
            out.add(DataValueSupport.getAsDataValue(v, DataValueType.STRING));
        }
        return out;
    }


    /**
     * The joined column's <b>declared</b> type, or {@link DataValueType#MISSING} — meaning
     * <em>unknown</em> — when this lookup cannot say.
     *
     * <p>
     * Decision <b>D1</b>. A dotted reference needs the foreign column's type to publish it, and the
     * obvious route — resolving the foreign table through {@code SplitDomainResolution
     * .resolveTableOrThrow} — was rejected: that method <b>throws</b> on a split domain whose
     * members cannot be unioned, which would convert a silent {@code null} into a rule ERROR on
     * shipped data. It is also unnecessary. The join map is already built through that same
     * resolver, so a dataset reachable here has already resolved successfully; and the lookup
     * already holds the foreign metadata it needs.
     * </p>
     *
     * ⛔⛔ <b>The default is {@link DataValueType#MISSING}, which means "unknown" — NOT
     * {@code STRING}.</b> This distinction is load-bearing and was got wrong once: a default of
     * {@code STRING} makes an <em>unknown</em> type indistinguishable from a <em>character</em>
     * one, and {@code ColumnTypeGate} then errors a perfectly good numeric comparison with "DM.AGE
     * is declared Char". {@code MISSING} maps to {@code null} in {@code ColumnTypeGate
     * .kindOf}, so an unknown type is simply <b>not gated</b> — the same disposition an absent
     * column has (decision D2).
     *
     * @param columnName
     *            the joined column
     * @return the declared type, or {@code MISSING} when unknown or the column is absent
     */
    default DataValueType declaredTypeOf(String columnName)
    {
        return DataValueType.MISSING;
    }


    /**
     * The join-match flag {@code <dataset>._matched_} for one primary row (spec §3.3, D88 /
     * {@code PLAN-join-match-flag.md}): {@code true} iff the row found at least one partner in the
     * joined dataset under this entry's keys.
     *
     * <p>
     * <b>The default THROWS — deliberately.</b> The flag is a <em>join</em> fact, not a column
     * read, and there is no honest generic derivation from the value-lookup methods (an empty
     * {@link #lookupAll} can mean "no partner" or "partner with a missing cell" — conflating those
     * is exactly the {@code empty()} proxy this flag replaces). A silently-wrong default (always
     * {@code true}, or derived from a cell read) would make {@code not D._matched_} quietly
     * mis-fire, so an implementation that has not answered fails LOUD on the rule's ERROR channel
     * instead. Every production implementation answers from the join structure it already holds —
     * {@link DatasetLookup} from its per-primary-row join map, the row-expanded lookups from their
     * bound-row arrays — so the verdict is a by-product of the map the runner already builds (D88
     * §3.3), an O(1) array read per row, never a repeated per-row key lookup at evaluation time.
     * </p>
     *
     * @param primaryTable
     *            the primary (or row-expanded evaluation) table being evaluated
     * @param row
     *            the row index in that table
     * @return whether the row found at least one join partner
     */
    default boolean matchedRow(IDataTable primaryTable, long row)
    {
        throw new UnsupportedOperationException("this JoinLookup (" + getClass().getName()
                + ", dataset " + getDatasetName() + ") does not answer the _matched_ join flag");
    }


    /**
     * Returns the dataset name this lookup was built from.
     */
    String getDatasetName();


    /**
     * The value a dotted read answers when the column is absent from the joined dataset
     * <b>entirely</b> — the one implementation of an arm that had three.
     *
     * <p>
     * ⭐⭐ §9c DOTTED PARITY (owner ruling, 2026-09-18): a joined variable behaves like a first-class
     * primary one in every respect but the dotted access form, so an absent joined column takes the
     * <b>RULE's expected default</b> exactly as an absent primary column does (D72/D76/§1b):
     * numeric → {@code MissingValue.MIS}, otherwise the CONSTANT {@code ""} — a present empty
     * string, never a computed MIS for a char read (the D96a absent-vs-blank regression, where a
     * MIS sorted below every value under the D34 #5 order arm).
     * </p>
     *
     * <p>
     * ⛔ <b>Do not re-derive the flag here and do not read a declared type for it:</b> an absent
     * column HAS no declared type, so the expectation is a property of the RULE and can only arrive
     * from the call site. That is what {@code numericExpected} is.
     * </p>
     *
     * <p>
     * ⚠ {@link ScalarSemantics#computedMissing()} is the engine's single spelling of
     * {@code MissingValue.MIS} and is byte-identical to what
     * {@code ExprCompiler.dottedNotSuppliedDefault} answers for the sibling "no such joined
     * dataset" case — the two must not drift.
     * </p>
     *
     * <p>
     * ⭐ <b>Extracted 2026-09-21</b> ({@code PLAN-join-key-missing-semantics} phase 6b(3)). It had
     * THREE production implementations — {@link DatasetLookup}, {@code KeyMatchExpandedLookup} and
     * {@code RelrecExpandedLookup} — and the third one's own comment recorded the reason to
     * extract: <i>"THIS SITE WAS MISSED TWICE … ⛔ When this arm changes again, change all three or
     * none."</i> Now there is nothing to keep in step.
     * </p>
     *
     * @param numericExpected
     *            whether the rule reads this column as numeric
     * @return the ruled absent-joined-column value
     */
    static IDataValue absentJoinedColumnValue(boolean numericExpected)
    {
        return numericExpected ? ScalarSemantics.computedMissing()
                : DataValueSupport.defaultForType(DataValueType.STRING);
    }

}
