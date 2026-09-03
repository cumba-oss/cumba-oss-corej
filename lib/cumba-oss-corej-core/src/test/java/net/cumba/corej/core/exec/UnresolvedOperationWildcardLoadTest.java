package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Fix #156 — an Operation that parks the {@code --} wildcard in {@code reference}, {@code ordering}
 * or {@code offset} is tagged at load.
 *
 * <p>
 * Those three are the column-naming Operation fields
 * {@code OperationExecutor.resolvePrefixes(Operation, String, String)} copies <b>verbatim</b>: the
 * literal {@code "--SEQ"} reaches {@code DataTableMeta.getColumnIndex}, misses, and the operation
 * quietly produces nothing — or, for {@code offset}, a <em>wrong</em> answer, since
 * {@code evalDateDiffDays} falls back to an offset of {@code 0} when the named column is absent.
 * Nothing downstream distinguishes any of that from clean data, which is the same silence class as
 * {@code validateOperationReferences}, so the finding takes the same load channel: always
 * {@code loadError}. ⚠ There is no {@code Executability}-driven severity split any more — since
 * {@code Fix #159} a rule declaring {@code Executability: "Not Executable"} is <em>parked</em>
 * ({@code RulePackageLoader.removeParkedRules}) and never reaches this gate at all.
 * </p>
 *
 * <p>
 * ⚠ <b>{@code minuend_match} is deliberately outside the guarded set</b> and has its own test
 * below: its {@code --} tokens are resolved <em>per side</em> at evaluation time, so leaving them
 * alone in {@code resolvePrefixes} is the design rather than the gap.
 * </p>
 */
class UnresolvedOperationWildcardLoadTest
{

    /** The load-finding fragment the pass emits. */
    private static final String MARKER = "is not `--`-resolved";

    private static Rule load(String ruleJson) throws IOException
    {
        RulePackage pkg = RulePackageLoader
                .loadFromString("{\"rules\":{\"rule-1\":" + ruleJson + "}}");
        return pkg.getRules().values().iterator().next();
    }


    private static IDataTable table()
    {
        return MockTable.of().col("USUBJID", "S1").col("SESTDTC", "2020-01-01").name("SE").build();
    }

    // -----------------------------------------------------------------------
    // The three guarded fields, declared-Operations surface
    // -----------------------------------------------------------------------


    @Test
    void declaredReferenceWildcard_tagsLoadError_andExecutesAsError() throws IOException
    {
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-UOW-1"},
                  "Executability": "Fully Executable",
                  "Operations": [{"id": "$diff", "operator": "date_diff_days",
                                  "name": "TFDTC", "reference": "--STDTC"}],
                  "Check": {"all": [{"name": "TFDETECT", "operator": "not_equal_to",
                                     "value": "$diff"}]}
                }
                """);
        assertNotNull(rule.getLoadError(), "an executable rule must fail loud");
        assertTrue(rule.getLoadError().contains(MARKER), rule.getLoadError());
        assertTrue(rule.getLoadError().contains("reference=\"--STDTC\""), rule.getLoadError());
        assertTrue(rule.getLoadError().contains("$diff"),
                "the message must name the offending operation, got " + rule.getLoadError());
        assertNull(rule.getLoadWarning(), "the executable case uses the error channel only");

        RuleExecutionResult result = RuleRunner.execute(rule, table());
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus());
        assertEquals(rule.getLoadError(), result.getStatusMessage());
    }


    @Test
    void declaredOrderingWildcard_tagsLoadError() throws IOException
    {
        // is_last_in_group reads `ordering` off the evaluation table: an unresolved "--SEQ" makes
        // evalIsLastInGroup return null on ordIdx < 0, so every row's verdict is simply absent.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-UOW-2"},
                  "Operations": [{"id": "$last", "operator": "is_last_in_group",
                                  "group": ["USUBJID"], "ordering": "--SEQ"}],
                  "Check": {"all": [{"name": "$last", "operator": "equal_to", "value": true}]}
                }
                """);
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains(MARKER), rule.getLoadError());
        assertTrue(rule.getLoadError().contains("ordering=\"--SEQ\""), rule.getLoadError());
    }


    @Test
    void declaredOffsetWildcard_tagsLoadError() throws IOException
    {
        // The worst of the three: evalDateDiffDays cannot parse "--RFDY" as an integer, looks it
        // up as a column, misses, and silently applies an offset of 0 — a wrong number, not a
        // skip. That is the EC-47 failure mode reached by an authoring slip instead of a data gap.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-UOW-3"},
                  "Operations": [{"id": "$diff", "operator": "date_diff_days",
                                  "name": "TFDTC", "reference": "EXSTDTC", "offset": "--RFDY"}],
                  "Check": {"all": [{"name": "TFDETECT", "operator": "not_equal_to",
                                     "value": "$diff"}]}
                }
                """);
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains(MARKER), rule.getLoadError());
        assertTrue(rule.getLoadError().contains("offset=\"--RFDY\""), rule.getLoadError());
    }

    // -----------------------------------------------------------------------
    // The inline surface — a genuinely separate load path
    // -----------------------------------------------------------------------


    @Test
    void inlineOperationOrderingWildcard_tagsLoadError() throws IOException
    {
        // An inline operation never reaches rule.getOperations(), so the declared-list walk alone
        // would miss it entirely — the hazard validateInlineMissingValues documents.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-UOW-4"},
                  "Check": {"expression":
                    "is_last_in_group(group=[USUBJID], ordering=\\"--SEQ\\") == true"}
                }
                """.replaceAll("\\s*\\R\\s*", " "));
        assertTrue(rule.getCheck() instanceof CheckConditionExpression,
                "fixture must exercise the expression arm, got " + rule.getCheck().getClass());
        assertNull(rule.getOperations(), "an inline call declares no Operations entry");
        assertNotNull(rule.getLoadError(),
                "the inline surface must reach the same channel as the declared one");
        assertTrue(rule.getLoadError().contains(MARKER), rule.getLoadError());
        assertTrue(rule.getLoadError().contains("ordering=\"--SEQ\""), rule.getLoadError());
        assertTrue(rule.getLoadError().contains("inline operation is_last_in_group"),
                "the message must say which surface it found, got " + rule.getLoadError());
    }


    @Test
    void inlineOperationInAPrecondition_tagsLoadError() throws IOException
    {
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-UOW-5"},
                  "Precondition": {"expression":
                    "is_last_in_group(group=[USUBJID], ordering=\\"--SEQ\\") == true"},
                  "Check": {"all": [{"name": "SESTDTC", "operator": "non_empty"}]}
                }
                """.replaceAll("\\s*\\R\\s*", " "));
        assertNotNull(rule.getLoadError(), "the Precondition gates the Check, so it is as fatal");
        assertTrue(rule.getLoadError().contains(MARKER), rule.getLoadError());
    }

    // -----------------------------------------------------------------------
    // A rule declaring the gap is parked, not downgraded
    // -----------------------------------------------------------------------


    @Test
    void notExecutableRule_isParkedBeforeThisGateRuns() throws IOException
    {
        // Fix #159: `Executability: "Not Executable"` removes the rule from the package at load, so
        // this gate never sees it and the old severity downgrade had nothing left to downgrade.
        RulePackage pkg = RulePackageLoader.loadFromString("""
                {"rules": {"rule-1": {
                  "Core": {"Id": "TEST-UOW-6"},
                  "Executability": "Not Executable",
                  "Operations": [{"id": "$last", "operator": "is_last_in_group",
                                  "group": ["USUBJID"], "ordering": "--SEQ"}],
                  "Check": {"all": [{"name": "$last", "operator": "equal_to", "value": true}]}
                }}}
                """);
        assertTrue(pkg.getRules().isEmpty(),
                () -> "a Not Executable rule is parked, not warned: " + pkg.getRules().keySet());
    }

    // -----------------------------------------------------------------------
    // What the guard must NOT catch
    // -----------------------------------------------------------------------


    @Test
    void minuendMatchWildcard_isNotCaught() throws IOException
    {
        // EC-18 / P5c: `--SPID` in minuend_match is resolved PER SIDE at evaluation time by
        // buildForeignMinuendResolver — TFSPID on the evaluation row, PMSPID on the matched
        // minuend_domain record (SENDIG §6.3.15.1 Assumption 5). resolvePrefixes leaves it alone
        // BECAUSE that is the design, so guarding it would reject the one shape the field exists
        // for. ⚠ If this test ever goes red, the guard has been widened wrongly — do not "fix" it
        // by editing the rule.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-UOW-7"},
                  "Operations": [{"id": "$diff", "operator": "date_diff_days",
                                  "name": "PMDTC", "reference": "TFSTDTC",
                                  "minuend_domain": "PM",
                                  "minuend_match": ["USUBJID", "--SPID"]}],
                  "Check": {"all": [{"name": "TFDETECT", "operator": "not_equal_to",
                                     "value": "$diff"}]}
                }
                """);
        assertNull(rule.getLoadError(), "minuend_match `--` is resolved per side, not a gap");
        assertNull(rule.getLoadWarning());
    }


    @Test
    void fieldsResolvePrefixesDoesResolve_areNotCaught() throws IOException
    {
        // name / group / dictionary_parent / external_dictionary_term_variable all ARE rewritten
        // by resolvePrefixes (the last two by Fix #125 / EC-36), and shipped rules rely on it —
        // CDISC-CG0562 groups by "--TESTCD", CDISC-CG0460 declares dictionary_parent "--SOC".
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-UOW-8"},
                  "Operations": [{"id": "$n", "operator": "record_count",
                                  "name": "--DTC", "group": ["USUBJID", "--TESTCD"]}],
                  "Check": {"all": [{"name": "$n", "operator": "greater_than", "value": 1}]}
                }
                """);
        assertNull(rule.getLoadError(), "these positions are resolved, so `--` is legal there");
        assertNull(rule.getLoadWarning());
    }


    @Test
    void aNegativeOffsetLiteral_isNotCaught() throws IOException
    {
        // "-1" is a legal integer offset and contains a single hyphen; only the two-character
        // wildcard token is a finding.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-UOW-9"},
                  "Operations": [{"id": "$diff", "operator": "date_diff_days",
                                  "name": "TFDTC", "reference": "EXSTDTC", "offset": "-1"}],
                  "Check": {"all": [{"name": "TFDETECT", "operator": "not_equal_to",
                                     "value": "$diff"}]}
                }
                """);
        assertNull(rule.getLoadError());
        assertNull(rule.getLoadWarning());
    }


    @Test
    void aNativeLeafFunctionSharingTheKwargName_isNotMistakenForAnOperation() throws IOException
    {
        // `has_next_corresponding_record` is a native Check function, not an OperationType, so its
        // `ordering` kwarg must never be read as an Operation field. What this pins is that the
        // pass rebuilds an Operation instead of scanning kwarg names: the naive implementation
        // (report any Call carrying an `ordering`/`reference`/`offset` kwarg whose value holds
        // `--`) turns this red, measured 2026-08-05.
        //
        // ⚠ Two filters reject this input, so neither is provable alone: ExprCompiler
        // .isInlineOperation returns false on a non-OperationType name, AND
        // OperationExpressionParser.fromCall would throw `unknown operation function` on the same
        // name. The former is deliberate belt-and-braces — it keeps `catch (RuntimeException)`
        // from being the load-bearing filter — not something this fixture can isolate.
        //
        // ⚠ This pins the BOUNDARY of the pass, not the correctness of that surface:
        // CheckConditionTransformer.transformLeaf copies a Check leaf's `ordering` (and `within`)
        // verbatim too, so `--` there is an open sibling gap on a different code path — surfaced,
        // not fixed by Fix #156. No shipped rule authors it: of the 29 `ordering=` uses in rules/,
        // 16 are has_next_corresponding_record and 7 empty_within_except_last_row (both native
        // Check functions, both on the leaf path) and 6 is_last_in_group (the operation) — every
        // one a bare column name.
        Rule rule = load(
                """
                        {
                          "Core": {"Id": "TEST-UOW-10"},
                          "Check": {"expression":
                            "has_next_corresponding_record(SEENDTC, SESTDTC, ordering=\\"--SEQ\\", within=USUBJID)"}
                        }
                        """
                        .replaceAll("\\s*\\R\\s*", " "));
        assertTrue(rule.getLoadError() == null || !rule.getLoadError().contains(MARKER),
                "the Operation-field pass must not claim a native leaf function, got "
                        + rule.getLoadError());
    }
}
