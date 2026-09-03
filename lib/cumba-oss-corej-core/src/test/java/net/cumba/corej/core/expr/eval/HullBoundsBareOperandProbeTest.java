package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan C phase 5b, step 0 — probe C's sibling for the corpus's <b>actual</b> date-ordering shape:
 * {@code date(X) op <bare operand>} (all 91 order comparisons in the 87-rule family; there are zero
 * {@code date(X) op date(Y)} comparisons at HEAD).
 *
 * <p>
 * Three questions, each measured through the shipped path a rule actually takes —
 * {@code CheckExpressionParser} &rarr; {@code CheckConditionDeserializer}'s lowering &rarr;
 * {@code RuleRunner} — never through a helper:
 * </p>
 * <ol>
 * <li><b>What does {@code date(X) >= BARE} mean today?</b> The {@code date()} tag routes the whole
 * comparison ({@code ExprCompiler.family} falls back over both operands), so the bare operand is
 * coerced through the same {@code IsoDateComparison} machinery as a wrapped one: hull semantics,
 * clipped to the <em>pair's common</em> precision, with the complete-vs-complete fast path
 * comparing cores at the <em>coarser</em> of the two precisions.</li>
 * <li><b>Does &sect;3.5's authored ERROR form reproduce it?</b> The literal spelling
 * {@code earliest_possible(X) >= latest_possible(Y)} carries no type tag, so it compiles to the
 * <b>plain</b> comparison ({@code Primitives.comparison}) — which is numeric-only ("a non-numeric
 * cell &rArr; no violation"). Over ISO date strings it fires on <b>nothing</b>, including rows
 * where today's ERROR fires. The recipe's ERROR leg, as spelled, is vacuous. The tagged repair
 * {@code date(earliest_possible(X)) >= latest_possible(Y)} re-enters the date machinery, but the
 * bounds are rendered at each value's <em>own</em> precision and the fast path then compares them
 * at the <em>coarser bound</em> precision — not at today's pair-common precision — so it over-fires
 * {@code >=}/{@code <=} on a partial-vs-timed pair sharing a boundary day (row R7).</li>
 * <li><b>Is wrapping the bare operand a semantic change?</b> No: {@code date(X) op Y} and
 * {@code date(X) op date(Y)} produce identical verdicts row-for-row — the implicit coercion is
 * already total.</li>
 * </ol>
 *
 * <p>
 * &#9888; {@code HullBoundsCompleteDateProbeTest} (probe C) modelled the authored leg as
 * {@code IsoDateComparison.bound} + raw {@code String.compareTo}. No expressible rule shape
 * evaluates that way: the untagged spelling is numeric (vacuous) and the tagged spelling re-enters
 * {@code IsoDateComparison.fires}. This probe supersedes probe C's model of the authored form;
 * probe C's equal-precision/mixed-precision findings about the two clipping disciplines stand.
 * </p>
 */
@DisplayName("phase 5b step 0 — date(X) op BARE vs the §3.5 hull rewrite, through RuleRunner")
class HullBoundsBareOperandProbeTest
{

    private static final ObjectMapper MAPPER = new YAMLMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * The fixture battery — {@code MHENDTC op RFSTDTC}, CDISC-CG0078's own operand shape folded
     * into one table (the cross-dataset join resolves to the same per-row comparison).
     *
     * <pre>
     * R0  2013-06-15           vs 2012-06-15        complete, a year later
     * R1  2011-06-15           vs 2012-06-15        complete, a year earlier
     * R2  2012-06-15           vs 2012-06-15        complete, equal
     * R3  2012-06-15T10:30:45  vs 2012-06-15        MIXED complete precision, same day
     * R4  2012-06              vs 2012-06-15        month partial vs day — possibly, not definitely
     * R5  2012-07              vs 2012-06-15        month partial, definitely after
     * R6  (blank)              vs 2012-06-15        unpositionable
     * R7  2012-06              vs 2012-06-01T10:30  partial vs TIMED complete, boundary day
     * R8  UNKNOWN              vs 2012-06-15        junk
     * </pre>
     */
    private static IDataTable table()
    {
        return MockTable.of().name("MH")
                .col("ROW", "R0", "R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8")
                .col("MHENDTC", "2013-06-15", "2011-06-15", "2012-06-15", "2012-06-15T10:30:45",
                        "2012-06", "2012-07", "", "2012-06", "UNKNOWN")
                .col("RFSTDTC", "2012-06-15", "2012-06-15", "2012-06-15", "2012-06-15",
                        "2012-06-15", "2012-06-15", "2012-06-15", "2012-06-01T10:30", "2012-06-15")
                .build();
    }


    /**
     * A second battery for the tranche's <b>time-bearing</b> shape ({@code CDISC-SEND-0327} /
     * {@code FDA-SE2280} guard both operands with {@code =~ /.*T[0-9]/}): every value is
     * calendar-complete and carries a time component, at mixed sub-day precisions.
     *
     * <pre>
     * T0  2012-06-01T10:30     vs 2012-06-01T10:30     equal, minute precision
     * T1  2012-06-01T10:29     vs 2012-06-01T10:30     one minute before
     * T2  2012-06-01T10:31     vs 2012-06-01T10:30     one minute after
     * T3  2012-06-01T10:30:45  vs 2012-06-01T10:30     second vs minute, same minute
     * T4  2012-06-01T10        vs 2012-06-01T10:59     hour vs minute, same hour
     * T5  2012-06-01T10:30:44  vs 2012-06-01T10:30:45  second vs second, one apart
     * </pre>
     */
    private static IDataTable timedTable()
    {
        return MockTable.of().name("MH").col("ROW", "T0", "T1", "T2", "T3", "T4", "T5")
                .col("MHENDTC", "2012-06-01T10:30", "2012-06-01T10:29", "2012-06-01T10:31",
                        "2012-06-01T10:30:45", "2012-06-01T10", "2012-06-01T10:30:44")
                .col("RFSTDTC", "2012-06-01T10:30", "2012-06-01T10:30", "2012-06-01T10:30",
                        "2012-06-01T10:30", "2012-06-01T10:59", "2012-06-01T10:30:45")
                .build();
    }


    /**
     * UTC-offset battery — one operand carries an offset, the other does not.
     *
     * <pre>
     * O0  2012-06-01T08:30-02:00  vs 2012-06-01T09:00   UTC instant 10:30, core reads 08:30
     * O1  2012-06-01T10:00+04:00  vs 2012-06-01T08:00   UTC instant 06:00, core reads 10:00
     * </pre>
     */
    private static IDataTable offsetTable()
    {
        return MockTable.of().name("MH").col("ROW", "O0", "O1")
                .col("MHENDTC", "2012-06-01T08:30-02:00", "2012-06-01T10:00+04:00")
                .col("RFSTDTC", "2012-06-01T09:00", "2012-06-01T08:00").build();
    }


    /** Binds a one-expression rule exactly as the loader does and runs it over the R battery. */
    private static List<String> firedRows(String expression)
    {
        return firedRows(expression, table());
    }


    /** Binds a one-expression rule exactly as the loader does and runs it over {@code data}. */
    private static List<String> firedRows(String expression, IDataTable data)
    {
        try
        {
            String yaml = """
                    Core:
                      Id: "P5B-PROBE"
                    Description: "Raise an error when the probe comparison holds."
                    Check:
                      expression: >-
                        %s
                    Outcome:
                      Message: "probe"
                      Output_Variables:
                      - "ROW"
                    """.formatted(expression);
            Rule rule = MAPPER.readValue(yaml, Rule.class);
            RulePackageLoader.deriveOmittedFields(rule);
            rule.setCheckExpr(CheckToExpr.toExpr(rule.getCheck()));
            RuleExecutionResult result = RuleRunner.execute(rule, data);
            return result.getViolations().stream()
                    .map(v -> String.valueOf(v.getValues().get("ROW"))).sorted().toList();
        }
        catch (Exception e)
        {
            throw new AssertionError("probe expression failed to bind/run: " + expression, e);
        }
    }


    @Test
    @DisplayName("Q1+Q3 — the bare operand is fully coerced; wrapping it in date() changes nothing")
    void bareOperandIsCoercedAndWrappingIsANoOp()
    {
        List<String> bare = firedRows("date(MHENDTC) >= RFSTDTC");

        // Today's semantics over the battery: definitely-on-or-after only. R3 fires because the
        // complete-vs-complete fast path compares the CORES at the coarser (day) precision; R7
        // stays silent because the hull path clips both hulls to the pair's common (minute)
        // precision, where lower(2012-06) = …T00:00 < …T10:30.
        assertEquals(List.of("R0", "R2", "R3", "R5"), bare, "date(X) >= BARE, today");

        // Wrapping the bare operand is NOT a semantic change: the date() tag already routes the
        // whole comparison, so the implicit coercion is total.
        assertEquals(bare, firedRows("date(MHENDTC) >= date(RFSTDTC)"),
                "date(X) >= date(Y) must be row-identical to the bare form");

        // The strict form: the boundary rows R2/R3 drop out, at both precisions.
        assertEquals(List.of("R0", "R5"), firedRows("date(MHENDTC) > RFSTDTC"),
                "date(X) > BARE, today");
    }


    @Test
    @DisplayName("⛔ Q2a — §3.5's literal ERROR spelling is VACUOUS: plain >= is numeric-only")
    void planLiteralSpellingFiresOnNothing()
    {
        // The recipe's ERROR leg carries no date()/num() tag, so it compiles to the plain
        // comparison — Primitives.comparison, whose contract is "non-numeric ⇒ no violation".
        // An ISO date string never parses as a double, so the leg cannot fire — not even on R0,
        // a flagrant complete-date violation. It does not reproduce today's ERROR set; it ERASES
        // it. The same holds for the INFO spelling.
        assertEquals(List.of(), firedRows("earliest_possible(MHENDTC) >= latest_possible(RFSTDTC)"),
                "§3.5 ERROR leg, spelled literally");
        assertEquals(List.of(), firedRows("latest_possible(MHENDTC) >= earliest_possible(RFSTDTC)"),
                "§3.5 INFO leg, spelled literally");
    }


    @Test
    @DisplayName("⛔ Q2b — the date()-tagged repair diverges from date() at mixed precision (R7)")
    void taggedRepairOverFiresOnBoundaryDayPairs()
    {
        // date(earliest_possible(X)) >= latest_possible(Y) is the only expressible spelling that
        // compares the bounds as dates. The bounds are rendered at each value's OWN precision
        // (never coarser than a day), so they are always calendar-complete and ALWAYS take the
        // complete-vs-complete fast path — compared at the coarser BOUND precision. Today's hull
        // path instead clips both hulls to the pair's common (finer) precision. On R7 the two
        // disciplines disagree: earliest_possible(2012-06) = 2012-06-01 compared at DAY precision
        // equals latest_possible(2012-06-01T10:30) = 2012-06-01T10:30, so >= FIRES — where today
        // …T00:00 < …T10:30 keeps the row silent. One added ERROR row = ERROR movement.
        assertEquals(List.of("R0", "R2", "R3", "R5", "R7"),
                firedRows("date(earliest_possible(MHENDTC)) >= latest_possible(RFSTDTC)"),
                "tagged hull ERROR leg — R7 is the over-fire");

        // The strict ERROR form happens to agree over this battery (the boundary-day tie is not
        // a strict inequality on either path) — the divergence is orEqual-operator-shaped, and
        // the tranche's rules use >=, <=, < and > alike.
        assertEquals(List.of("R0", "R5"),
                firedRows("date(earliest_possible(MHENDTC)) > latest_possible(RFSTDTC)"),
                "tagged hull ERROR leg, strict: R0/R5 only on this battery");
    }


    @Test
    @DisplayName("Q2c — the tagged INFO leg is a defensible possibly-reading, for the record")
    void taggedInfoLegCharacterised()
    {
        // latest_possible(X) >= earliest_possible(Y), tagged: fires wherever the order is not
        // definitely right — a plausible INFO rung (R4 and R7 are the merely-possible rows).
        // Characterised here so a future re-design starts from a measured baseline; this probe
        // takes no position on whether its boundary-day behaviour (R3 fires, at day precision)
        // is the wanted INFO semantics.
        assertEquals(List.of("R0", "R2", "R3", "R4", "R5", "R7"),
                firedRows("date(latest_possible(MHENDTC)) >= earliest_possible(RFSTDTC)"),
                "tagged hull INFO leg");
    }


    /**
     * The ruling-(a) INFO spelling for one operator: the <em>possibly</em> reading of
     * {@code X op Y}. For {@code >=}/{@code >} that is upper(X) against lower(Y); for
     * {@code <=}/{@code <} it is the mirror, lower(X) against upper(Y). The {@code date()} tag
     * wraps the bound call — the untagged form is numeric-only and vacuous (Q2a above).
     */
    private static String infoLeg(String op)
    {
        return switch (op)
        {
        case ">=", ">" -> "date(latest_possible(MHENDTC)) " + op + " earliest_possible(RFSTDTC)";
        case "<=", "<" -> "date(earliest_possible(MHENDTC)) " + op + " latest_possible(RFSTDTC)";
        default -> throw new AssertionError("unexpected operator: " + op);
        };
    }


    @Test
    @DisplayName("⭐ ruling (a) gate — ERROR ⊆ INFO for every operator the 5b tranche uses")
    void errorLegIsContainedInInfoLegForEveryTrancheOperator()
    {
        // Phase 5b, ruling (a) of 2026-08-26: ERROR = today's predicate verbatim
        // (date(X) op BARE); INFO = the tagged possibly-reading. First-claim (§3.4) is
        // well-formed only when every ERROR row is also an INFO row — the weaker rung must
        // CONTAIN the stricter one. Measured per operator over the R battery, exact sets pinned
        // so a movement in either leg is loud.
        Map<String, List<String>> error = Map.of( //
                ">=", List.of("R0", "R2", "R3", "R5"), //
                ">", List.of("R0", "R5"), //
                "<=", List.of("R1", "R2", "R3"), //
                "<", List.of("R1"));
        Map<String, List<String>> info = Map.of( //
                ">=", List.of("R0", "R2", "R3", "R4", "R5", "R7"), //
                ">", List.of("R0", "R4", "R5", "R7"), //
                "<=", List.of("R1", "R2", "R3", "R4", "R7"), //
                "<", List.of("R1", "R4"));
        for (String op : List.of(">=", ">", "<=", "<"))
        {
            List<String> errorRows = firedRows("date(MHENDTC) " + op + " RFSTDTC");
            List<String> infoRows = firedRows(infoLeg(op));
            assertEquals(error.get(op), errorRows, "ERROR leg, date(X) " + op + " BARE");
            assertEquals(info.get(op), infoRows, "INFO leg for " + op);
            assertTrue(infoRows.containsAll(errorRows), "entailment ERROR ⊆ INFO broken for " + op
                    + ": ERROR=" + errorRows + " INFO=" + infoRows);
        }
    }


    @Test
    @DisplayName("timed-pair shape (SEND-0327 / SE2280) — the INFO leg collapses onto the ERROR leg")
    void timedPairInfoLegCollapsesOntoErrorLeg()
    {
        // CDISC-SEND-0327 / FDA-SE2280 guard BOTH operands with `=~ /.*T[0-9]/`, so every value
        // the comparison sees is calendar-complete with a time component. Such a value's hull
        // collapses at its own precision: bound() renders lower and upper both as the value's own
        // core (clip at the value's own tier), so earliest_possible(X) == core(X) and
        // latest_possible(Y) == core(Y), and the tagged INFO leg re-enters the same
        // complete-vs-complete fast path over the same strings as today's ERROR leg. The INFO
        // rung therefore claims NOTHING under first-claim — which is why 5b does not rewrite
        // those two rules.
        List<String> errorRows = firedRows("date(MHENDTC) <= RFSTDTC", timedTable());
        List<String> infoRows = firedRows(
                "date(earliest_possible(MHENDTC)) <= latest_possible(RFSTDTC)", timedTable());
        assertEquals(List.of("T0", "T1", "T3", "T4", "T5"), errorRows,
                "ERROR leg over the timed battery (T3/T4: coarser-precision tie fires <=)");
        assertEquals(errorRows, infoRows,
                "the INFO leg must be row-identical to the ERROR leg on guarded timed pairs");
    }


    @Test
    @DisplayName("offset-bearing pairs — both legs read the UTC instant, so entailment holds there too")
    void offsetPairsAgreeOnTheUtcInstant()
    {
        // Characterisation (measured 2026-08-26, correcting a code-reading hypothesis): a
        // trailing UTC offset does NOT split the two legs. Both today's leg and the tagged
        // bound leg resolve the offset instant-preserving — O0 (core reads 08:30 <= 09:00 but
        // the UTC instant is 10:30) stays silent on BOTH, O1 (core reads 10:00 > 08:00 but the
        // UTC instant is 06:00) fires on BOTH. So the offset shape is no entailment threat for
        // ruling (a); pinned so a future change to either path's offset handling is loud.
        assertEquals(List.of("O1"), firedRows("date(MHENDTC) <= RFSTDTC", offsetTable()),
                "ERROR leg resolves the offset (UTC 06:00 <= 08:00 fires; 10:30 <= 09:00 not)");
        assertEquals(List.of("O1"),
                firedRows("date(earliest_possible(MHENDTC)) <= latest_possible(RFSTDTC)",
                        offsetTable()),
                "INFO leg resolves the offset identically — ERROR ⊆ INFO holds on offset pairs");
    }

}
