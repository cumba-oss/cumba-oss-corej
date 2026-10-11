package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.KeyedJoinFixtures;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.expr.typed.StageAChecker;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * ⭐ {@code PLAN-elementwise-list-functions} owner ruling D1-A (runbook W1a E2): the name-list
 * binding {@code $keys: [USUBJID, AESEQ]} is a legal binding of column <b>names</b>, read exactly
 * where the written list of names is read — a splice slot ({@code is_(not_)unique_set}'s members,
 * {@code record_count}'s {@code group=}, {@code values}' columns) — and refused with one hint
 * everywhere else (coordinator decision 9, 2026-10-08). Real loader, real runner, no Mockito.
 */
class NameListBindingTest
{

    private static final String HINT = "holds column names: use it in a name slot (group=";

    /** Rows 1 and 2 share (USUBJID, AESEQ); A / B carry the probed texts of the values test. */
    private static IDataTable ae()
    {
        return RealTables.of("AE").str("USUBJID", "S1", "S1", "S2").lng("AESEQ", 1L, 1L, 2L)
                .str("A", "a", null, "c").str("B", "b", "y", "c").str("AETERM", "t1", "t2", "t3")
                .build();
    }


    private static String esc(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    private static String json(String check, List<String> outputs, String... bindings)
    {
        StringBuilder b = new StringBuilder(
                "{\"Core\":{\"Id\":\"T-NAMELIST\"},\"Sensitivity\":\"Record\"");
        if (bindings.length > 0)
        {
            b.append(",\"Bindings\":[");
            for (int i = 0; i < bindings.length; i += 2)
            {
                b.append(i == 0 ? "" : ",").append("{\"name\":\"").append(bindings[i])
                        .append("\",\"expression\":\"").append(esc(bindings[i + 1])).append("\"}");
            }
            b.append(']');
        }
        if (check != null)
        {
            b.append(",\"Check\":{\"expression\":\"").append(esc(check)).append("\"}");
        }
        b.append(",\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[");
        for (int i = 0; i < outputs.size(); i++)
        {
            b.append(i == 0 ? "" : ",").append('"').append(esc(outputs.get(i))).append('"');
        }
        return b.append("]}}").toString();
    }


    private static Rule load(String ruleJson)
    {
        try
        {
            Rule r = RulePackageLoader
                    .loadFromString(
                            KeyedJoinFixtures.declared("{\"rules\":{\"R\":" + ruleJson + "}}"))
                    .getRules().get("R");
            assertNotNull(r);
            return r;
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }


    /** The fired rows (1-based) of a row-guarded {@code check} on {@link #ae()}. */
    private static List<Long> fired(String check, String... bindings)
    {
        Rule r = load(json("not empty(USUBJID) and (" + check + ")", List.of("USUBJID"), bindings));
        assertNull(r.getLoadError(), r.getLoadError());
        IDataTable t = ae();
        RuleExecutionResult result = RuleRunnerCalls.execute(r, t, RealTables.inventoryOf(t),
                t.getMetaData().getName(), null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> check + " → " + result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    private static String loadError(String check, String... bindings)
    {
        return String.valueOf(load(json(check, List.of("USUBJID"), bindings)).getLoadError());
    }


    private static void assertHint(String what, String error)
    {
        assertTrue(error.contains(HINT), () -> what + " → " + error);
    }

    // ------------------------------------------------------------------ the legal reads


    @Test
    void theBoundNameListSplicesLikeTheWrittenList()
    {
        // the uniqueness members: f($keys) ≡ f([$keys]) ≡ f([USUBJID, AESEQ]) (C5 / D1-A)
        List<Long> written = fired("not is_unique_set([USUBJID, AESEQ])");
        assertEquals(List.of(1L, 2L), written);
        assertEquals(written, fired("not is_unique_set($keys)", "$keys", "[USUBJID, AESEQ]"));
        assertEquals(written, fired("not is_unique_set([$keys])", "$keys", "[USUBJID, AESEQ]"));
        assertEquals(written, fired("not is_unique_set([USUBJID, $k])", "$k", "[AESEQ]"));
        assertEquals(List.of(3L), fired("is_unique_set($keys)", "$keys", "[USUBJID, AESEQ]"));
        // record_count's group=
        assertEquals(fired("record_count(group=[USUBJID, AESEQ]) > 1"),
                fired("record_count(group=$keys) > 1", "$keys", "[USUBJID, AESEQ]"));
        // values' columns: the per-row cells of the named columns
        assertEquals(List.of(2L), fired("\"y\" in values([A, B])"));
        assertEquals(List.of(2L), fired("\"y\" in values($cols)", "$cols", "[A, B]"));
        assertEquals(List.of(3L), fired("\"c\" in values($cols)", "$cols", "[A, B]"));
        assertEquals(List.of(2L), fired("\"y\" in values([$cols])", "$cols", "[A, B]"));
    }


    @Test
    void aPrefixedMemberResolvesAsTheWrittenMemberDoes()
    {
        // --SEQ is specialised to AESEQ inside the binding exactly as inside the written list
        assertEquals(fired("not is_unique_set([USUBJID, --SEQ])"),
                fired("not is_unique_set($keys)", "$keys", "[USUBJID, --SEQ]"));
        assertEquals(List.of(1L, 2L),
                fired("not is_unique_set($keys)", "$keys", "[USUBJID, --SEQ]"));
    }


    @Test
    void theBindingIsTypedAsAListOfColumnReferences()
    {
        Rule r = load(
                json("not is_unique_set($keys)", List.of("USUBJID"), "$keys", "[USUBJID, AESEQ]"));
        assertNull(r.getLoadError(), r.getLoadError());
        ExprType type = StageAChecker.bindingTypes(r).get("$keys");
        assertNotNull(type);
        assertEquals("list<column-reference>", type.describe());
    }

    // ------------------------------------------------------------------ the refusals


    @Test
    void aValuePositionIsALoadErrorWithTheHint()
    {
        String[] keys =
        {
                "$keys", "[USUBJID, AESEQ]"
        };
        assertHint("membership", loadError("AETERM in $keys", keys));
        assertHint("comparison", loadError("$keys == AETERM", keys));
        assertHint("value argument", loadError("not empty(upper($keys))", keys));
        assertHint("computed-name read", loadError("not empty(colref($keys))", keys));
        assertHint("a binding of the binding",
                loadError("not empty($b)", "$keys", "[USUBJID, AESEQ]", "$b", "$keys"));
        // since W1a BS2b every group= splices, and since W1b SK (O16 #3) is_sorted_by's by= too:
        // a name list is the sort keys, each ascending
        assertEquals("null", loadError("not is_sorted_by(AESEQ, by=$keys)", keys));
        assertEquals("null", loadError("max(AESEQ, group=$keys) > 0", keys));
    }


    @Test
    void aReportedNameListBindingIsALoadErrorWithTheHint()
    {
        Rule r = load(json("not is_unique_set($keys)", List.of("USUBJID", "$keys"), "$keys",
                "[USUBJID, AESEQ]"));
        assertHint("Output_Variables", String.valueOf(r.getLoadError()));
    }


    @Test
    void aCheckLessRuleGetsTheLoaderSideJudgement()
    {
        // stage A never walks a Check-less rule's bindings: the loader's seam pass files the same
        // hint — and admits the splice
        Rule refused = load(json(null, List.of("USUBJID"), "$keys", "[USUBJID, AESEQ]", "$m",
                "AETERM in $keys"));
        assertHint("Check-less value read", String.valueOf(refused.getLoadError()));
        Rule admitted = load(json(null, List.of("USUBJID"), "$keys", "[USUBJID, AESEQ]", "$u",
                "not is_unique_set($keys)"));
        assertNull(admitted.getLoadError(), admitted.getLoadError());
    }


    @Test
    void theListHoldsNamesOrPlainLiteralsNeverBoth()
    {
        // C12: a $ inside a list literal stays refused; a mixed list is neither form
        assertNotNull(load(json("not is_unique_set($keys)", List.of("USUBJID"), "$b", "\"AESEQ\"",
                "$keys", "[USUBJID, $b]")).getLoadError(), "a $ member stays refused");
        String mixed = loadError("not is_unique_set($keys)", "$keys", "[USUBJID, \"AESEQ\"]");
        assertTrue(mixed.contains("not both"), mixed);
        // a string-literal list binding is no list of names (runbook W4 E3, D1-B): the load error
        // names the remedy, and the bare names answer as the string list did before
        String strings = loadError("not is_unique_set($keys)", "$keys", "[\"USUBJID\", \"AESEQ\"]");
        assertTrue(strings.contains("write the columns as names: [USUBJID, AESEQ]"), strings);
        assertEquals(List.of(1L, 2L),
                fired("not is_unique_set($keys)", "$keys", "[USUBJID, AESEQ]"));
    }

    // ------------------------------------------------------------------ W1a fold (R5)


    @Test
    void aDoubleDashMemberSplicedUnderDomainIsALoadError()
    {
        // R5 review A-M2 / BS2b MED-3 (coordinator decision: load-time refusal): under domain= a
        // spliced `--` name would resolve against the EVALUATED dataset, not the domain, so the
        // written name list holding one is refused at load as the written member is
        // (GroupedAggregate.columnName) — before, it loaded and only ERRORed at run time
        String inline = loadError("record_count(domain=DM, group=[USUBJID, --SEQ]) > 0");
        assertTrue(inline.contains("does not resolve `--` (--SEQ) under domain="), inline);
        String[] keys =
        {
                "$keys", "[USUBJID, --SEQ]"
        };
        for (String check : List.of("record_count(domain=DM, group=$keys) > 0",
                "max(AESEQ, domain=DM, group=$keys) > 0",
                "record_count(domain=DM, group=[STUDYID, $keys]) > 0",
                "read_value(AESEQ, domain=DM, group=$keys, mode=\"FIRST\") > 0"))
        {
            String error = loadError(check, keys);
            assertTrue(error.contains("does not resolve `--` (--SEQ") && error.contains("$keys")
                    && error.contains("under domain="), () -> check + " → " + error);
        }
        // controls: without domain= the `--` member resolves against the evaluated dataset (the
        // splice is what the name means there); under domain= a list of plain names loads
        assertEquals("null", loadError("max(AESEQ, group=$keys) > 0", keys));
        assertEquals("null",
                loadError("record_count(domain=DM, group=$keys) > 0", "$keys", "[USUBJID]"));
    }


    @Test
    void aDoubleDashMemberSplicedUnderDomainInACheckLessRuleIsTheLoadersFinding()
    {
        // the path stage A never walks: the loader's seam files the same sentence
        Rule checkLess = load(json(null, List.of("USUBJID"), "$keys", "[USUBJID, --SEQ]", "$v",
                "record_count(domain=DM, group=$keys)"));
        String error = String.valueOf(checkLess.getLoadError());
        assertTrue(
                error.contains("does not resolve `--` (--SEQ") && error.contains("the binding $v"),
                error);
        Rule plain = load(json(null, List.of("USUBJID"), "$keys", "[USUBJID]", "$v",
                "record_count(domain=DM, group=$keys)"));
        assertNull(plain.getLoadError(), plain.getLoadError());
    }


    /**
     * A QUALIFIED member of a name list spliced into a {@code group=} under {@code domain=} loads
     * since {@code REG-Q4} (plan §5 Q7: no load gate is left to judge it — D-SRC's kind / join-type
     * refusal is withdrawn for group members, D-REGEX aligned); until then the T2 fold (review
     * L1-L1) refused it at load, on stage A's path and on the loader's seam alike. Still refused: a
     * {@code --} inside a qualified name (it resolves against different datasets on the two sides,
     * as an authored {@code DM.--SEQ} member is refused) and a qualified name spliced into a
     * {@code by=} sort key (a sort key is a column of the ordered table, written bare).
     */
    @Test
    void aQualifiedGroupMemberSplicedUnderDomainLoadsButADashedOneAndASortKeyDoNot()
    {
        for (String member : List.of("DM.USUBJID", "AE.USUBJID"))
        {
            for (String check : List.of("record_count(domain=DM, group=$keys) > 0",
                    "read_value(AESEQ, domain=DM, group=$keys, mode=\"FIRST\") > 0"))
            {
                assertEquals("null", loadError(check, "$keys", "[USUBJID, " + member + "]"),
                        check + " / " + member);
            }
        }
        for (String check : List.of("record_count(domain=DM, group=$keys) > 0",
                "read_value(AESEQ, domain=DM, group=$keys, mode=\"FIRST\") > 0"))
        {
            String error = loadError(check, "$keys", "[USUBJID, DM.--SEQ]");
            assertTrue(error.contains("splices DM.--SEQ") && error.contains("$keys")
                    && error.contains("under domain="), () -> check + " -> " + error);
        }
        // T2 review (lane 1 L3): the spliced binding is a sort key inside a descriptor too —
        // by=[desc($keys)] / by=[asc($keys)] is refused at load like by=$keys, not at run time
        for (String by : List.of("$keys", "[$keys]", "[desc($keys)]", "[asc($keys)]",
                "[AESEQ, desc($keys)]"))
        {
            String sortKey = loadError("not empty(sequence(AESEQ, domain=DM, by=" + by + "))",
                    "$keys", "[DM.USUBJID]");
            assertTrue(
                    sortKey.contains("splices the qualified name DM.USUBJID")
                            && sortKey.contains("by= sort keys"),
                    () -> "by=" + by + " -> " + sortKey);
        }
        // control: without domain= a qualified member is a column of the evaluation table
        assertEquals("null", loadError("max(AESEQ, group=$keys) > 0", "$keys", "[DM.USUBJID]"));
    }


    @Test
    void aPositionalSpliceSlotReadsTheNameListAsItsKeywordDoes()
    {
        // R5 review BS2b MED-1: is_last_in_group binds group= positionally too (bindStrict), so the
        // loader's seams read the slot by parameter index — the positional spelling of a Check-less
        // binding loads exactly as the keyword one does (it was decision 9's value-read hint)
        for (String call : List.of("is_last_in_group(AESEQ, group=$keys)",
                "is_last_in_group(AESEQ, $keys)"))
        {
            Rule r = load(json(null, List.of("USUBJID"), "$keys", "[USUBJID]", "$v", call));
            assertNull(r.getLoadError(), () -> call + " → " + r.getLoadError());
        }
    }
}
