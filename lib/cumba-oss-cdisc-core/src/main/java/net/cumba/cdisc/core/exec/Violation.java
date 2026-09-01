package net.cumba.cdisc.core.exec;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Value;
import net.cumba.datatable.report.Severity;
import org.jspecify.annotations.Nullable;

/**
 * One emitted violation. {@link #values} carries only the rule-projected {@code Output_Variables};
 * row-identity columns ({@code USUBJID}, {@code <DOMAIN>SEQ}) live in {@link #usubjid} and
 * {@link #seq} to mirror Python's {@code ValidationErrorEntity}, where USUBJID and SEQ are
 * top-level fields rather than entries in {@code value}. {@code ValidationReportBuilder} re-injects
 * them into the slab values map so {@code JsonReportWriter} keeps reading from the canonical keys;
 * the parity normaliser reads only {@code values} and therefore stays clean.
 */
@Value
@AllArgsConstructor
public class Violation
{

    long row;

    Map<String, String> values;

    /** USUBJID for the row, or {@code null} when not applicable. */
    @Nullable
    String usubjid;

    /** Value of {@code <DOMAIN>SEQ} for the row, or {@code null} when not applicable. */
    @Nullable
    String seq;

    /**
     * EC-40 record key for the row — ordered {@code variable name -> value}, or empty when no key
     * beyond {@link #usubjid} / {@link #seq} was resolved (which is always the case under the
     * default {@code corej.findingKeys=off}).
     *
     * <p>
     * <b>Never merged into {@link #values}.</b> That map is the spec contract — the rulespec
     * suite's {@code ViolationNormaliser} reads it verbatim as {@code output_variables} — so it
     * must stay exactly the rule's projected {@code Output_Variables} and nothing else. The keys
     * ride here for the same reason {@link #usubjid} / {@link #seq} do, and only
     * {@code ValidationReportBuilder} consumes them.
     * </p>
     */
    Map<String, String> keys;

    /**
     * The severity level that <b>claimed</b> this row, or {@code null} when the producing site did
     * not resolve one (in which case the reader falls back to the rule's effective severity).
     *
     * <p>
     * ⛔ <b>Never merged into {@link #values}</b>, for exactly the reason {@link #keys} is not: that
     * map is the rulespec contract, read verbatim as {@code output_variables} by the parity suite's
     * {@code ViolationNormaliser}. The level rides as a sibling field, like {@link #usubjid} and
     * {@link #seq}.
     * </p>
     *
     * <p>
     * ⚑ <b>Why it must live here at all.</b> {@code ValidationReportBuilder} is the wrong place to
     * <em>decide</em> the level: the {@code .cdt} scenario checker
     * {@code ViolationLocationCheck.verify} is handed a {@code RuleExecutionResult} and reads
     * {@code Violation} objects — it never sees a {@code ValidationFinding}. A severity attached
     * only in the report builder would be invisible to all 8 824 scenarios and
     * {@code #expectViolationAt severity=…} could never be asserted.
     * </p>
     *
     * <p>
     * In phase 2 every violation carries the rule's single effective severity. Per-level claiming
     * (Plan C §3.4) arrives in phase 4 and populates this field per row.
     * </p>
     */
    @Nullable
    Severity level;

    /**
     * The <b>finding unit</b> this violation reports on, stamped by the <em>producing</em> site —
     * or {@code null} when the producing site stamped none (every single-level execution, and any
     * external producer).
     *
     * <p>
     * &#9873; <b>Why the producer stamps it.</b> Per-level first-claim (Plan C &#167;3.4 step 4)
     * needs to know when two levels' violations report <em>the same thing</em>. Reconstructing that
     * from the materialised violation is wrong in three measured ways: a grouped rule anchors the
     * group at <em>the level's</em> first flagged row (two levels &rArr; two rows &rArr; the group
     * reported twice); a dataset-broadcast verdict and a row verdict can collide on
     * {@code (0, values)} when the domain has no {@code USUBJID}; and a per-(column, row) finding
     * loses the column when {@code variable_name} is not projected into {@link #values}. Only the
     * site that produced the violation knows which unit it meant, so it says so here.
     * </p>
     *
     * <p>
     * &#9888; Like {@link #keys} and {@link #level}, this rides as a sibling field and is <b>never
     * merged into {@link #values}</b> — that map is the rulespec contract, read verbatim as
     * {@code output_variables} by the parity suite's {@code ViolationNormaliser}.
     * </p>
     */
    @Nullable
    Unit unit;

    /**
     * The finding-unit discriminator (Plan C &#167;3.4 step 4). Each variant identifies one unit of
     * one (rule, dataset) execution; two violations report the same unit iff their stamps are
     * equal. The variants are disjoint types, so a dataset verdict can never collide with a row
     * finding whatever their row indices are.
     */
    public sealed interface Unit
    {

        /** The single dataset-level unit — a broadcast / collapsed verdict on the whole dataset. */
        Unit DATASET = new Dataset();

        /** The whole dataset; at most one per (rule, dataset, level) execution. */
        record Dataset() implements Unit
        {
        }


        /**
         * One data row, identified by its real (uncollapsed) row index.
         *
         * @param row
         *            the real row index
         */
        record Row(long row) implements Unit
        {
        }


        /**
         * One (column, row) — the unit of the per-variable paths. For a metadata finding with no
         * row cursor, {@code row} is the site's row-key convention (0 for DATASET sensitivity, the
         * column/ItemDef index otherwise); within one rule every level uses the same convention, so
         * the stamps compare like with like.
         *
         * @param column
         *            the column (or Define ItemDef) name
         * @param row
         *            the real row index, or the site's row-key convention where no row exists
         */
        record Column(String column, long row) implements Unit
        {
        }


        /**
         * One grouping block, identified by its resolved grouping-key tuple — <b>not</b> by the
         * anchor row, which is a per-level accident (each level anchors at <em>its own</em> first
         * flagged row of the block).
         *
         * @param key
         *            the block's key values in grouping-column order ({@code null} entries for
         *            missing key cells); empty when no grouping column is present and the whole
         *            dataset is one group
         */
        record Group(List<String> key) implements Unit
        {

            /**
             * Defensive, null-element-tolerant copy ({@code List.copyOf} would reject the
             * {@code null} missing-key entries), so the stamp is immutable however the caller built
             * its list.
             */
            public Group
            {
                key = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(key));
            }
        }
    }

    /** Compatibility constructor for sites with no per-row identity. */
    public Violation(long row, Map<String, String> values)
    {
        this(row, values, null, null, Map.of(), null, null);
    }


    /** Compatibility constructor for sites with row identity but no record key. */
    public Violation(long row, Map<String, String> values, @Nullable String usubjid,
            @Nullable String seq)
    {
        this(row, values, usubjid, seq, Map.of(), null, null);
    }


    /** Compatibility constructor for sites that resolve a record key but no level. */
    public Violation(long row, Map<String, String> values, @Nullable String usubjid,
            @Nullable String seq, Map<String, String> keys)
    {
        this(row, values, usubjid, seq, keys, null, null);
    }


    /** Compatibility constructor for sites that resolve a level but stamp no finding unit. */
    public Violation(long row, Map<String, String> values, @Nullable String usubjid,
            @Nullable String seq, Map<String, String> keys, @Nullable Severity level)
    {
        this(row, values, usubjid, seq, keys, level, null);
    }


    public long getRowNumber()
    {
        return row + 1;
    }

}
