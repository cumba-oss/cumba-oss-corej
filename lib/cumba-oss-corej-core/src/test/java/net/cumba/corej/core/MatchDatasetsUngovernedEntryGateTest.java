package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.Test;

/**
 * The two {@code Match_Datasets} load gates added by {@code PLAN-hashed-join-arm-absent-columns}
 * review round 1 (M1, L1), siblings of {@link JoinAsStringValidationTest}'s
 * {@code checkJoinAsStringOnExcludedEntry}:
 *
 * <ul>
 * <li>{@code keep_missings} on an entry the ordinary keyed join does not serve is a load error
 * saying it has no effect — the flag is read by {@code KeyMatchRowExpander.keySpec} only, and the
 * text-carried family's merge and the hashed {@code DatasetLookup} keep a blank key
 * unconditionally;</li>
 * <li>a {@code Child: true} entry must name a concrete dataset or a {@code --}-affixed template — a
 * {@code *}, {@code ${}}, {@code &} or bare {@code --} name is one no merge can select and no
 * stage-A qualifier check can resolve.</li>
 * </ul>
 *
 * <p>
 * ⚑ Both gates are free: zero {@code Match_Datasets} entries in either corpus author
 * {@code keep_missings}, and the 11 {@code Child} entries are {@code AE}, {@code CO},
 * {@code RELREC} and {@code SUPP--} (measured 2026-09-25).
 * </p>
 */
class MatchDatasetsUngovernedEntryGateTest
{

    private static Rule load(String entryJson) throws IOException
    {
        String json = """
                {"rules":{"x":{"Core":{"Id":"T-MDG"},"Sensitivity":"Record",\
                "Match_Datasets":[%s],\
                "Outcome":{"Message":"m","Output_Variables":["USUBJID"]},\
                "Check":{"all":[{"expression": "not empty(USUBJID)"}]}}}}""".formatted(entryJson);
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(rule, "the fixture must bind, or nothing below is measuring anything");
        return rule;
    }


    private static String errorOf(String entryJson) throws IOException
    {
        return String.valueOf(load(entryJson).getLoadError());
    }

    // ---------------------------------------------------------------- M1: keep_missings


    @Test
    void keepMissingsOnAChildEntryIsALoadError() throws IOException
    {
        String error = errorOf(
                "{\"Name\":\"AE\",\"Child\":true,\"Keys\":[\"USUBJID\",\"IDVAR\",\"IDVARVAL\"],"
                        + "\"keep_missings\":false}");
        assertTrue(error.contains("keep_missings has no effect"), error);
        assertTrue(error.contains("Child / RELREC / SUPP--"),
                "the reason names the family: " + error);
    }


    @Test
    void keepMissingsOnASuppNamedOrRelrecEntryIsALoadError() throws IOException
    {
        // The keyed non-Child shapes the expander refuses by NAME reach the hashed DatasetLookup,
        // which never consults the flag — the M1 gap itself.
        assertTrue(errorOf("{\"Name\":\"SUPPAE\",\"Keys\":[\"USUBJID\"],\"keep_missings\":true}")
                .contains("keep_missings has no effect"));
        assertTrue(errorOf("{\"Name\":\"RELREC\",\"Keys\":[\"USUBJID\"],\"keep_missings\":false}")
                .contains("keep_missings has no effect"));
    }


    @Test
    void keepMissingsOnAKeylessEntryIsALoadError() throws IOException
    {
        String error = errorOf("{\"Name\":\"DM\",\"keep_missings\":false}");
        assertTrue(error.contains("keep_missings has no effect"), error);
        assertTrue(error.contains("declares no Keys"),
                "the reason names the keyless half: " + error);
    }


    @Test
    void keepMissingsOnAnOrdinaryKeyedEntryStillLoads() throws IOException
    {
        // ⛔ The control: the flag's one legitimate home. Without this the gate could have banned
        // the flag rather than the combination.
        assertNull(load("{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"],\"keep_missings\":false}")
                .getLoadError());
        assertNull(load("{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"],\"keep_missings\":true}")
                .getLoadError());
    }


    @Test
    void anUngovernedEntryWithoutTheFlagStillLoads() throws IOException
    {
        // The other control: the gate fires on the FLAG, not on the family.
        assertNull(load("{\"Name\":\"AE\",\"Child\":true,\"Keys\":[\"USUBJID\",\"IDVAR\","
                + "\"IDVARVAL\"]}").getLoadError());
        assertNull(load("{\"Name\":\"SUPPAE\",\"Keys\":[\"USUBJID\"]}").getLoadError());
    }

    // ------------------------------------------------------------- L1: Child entry names


    @Test
    void aChildEntryWithAWildcardOrTemplateNameIsALoadError() throws IOException
    {
        for (String name : new String[]
        {
                "*", "AE*", "${DOM}", "&DOM", "--"
        })
        {
            String error = errorOf("{\"Name\":\"" + name
                    + "\",\"Child\":true,\"Keys\":[\"USUBJID\",\"IDVAR\",\"IDVARVAL\"]}");
            assertTrue(error.contains("must name a concrete dataset or a --affixed template"),
                    "'" + name + "' must be refused: " + error);
        }
    }


    @Test
    void aChildEntryWithAConcreteOrAffixedTemplateNameStillLoads() throws IOException
    {
        // The shipped shapes — every one of the 11 Child entries is one of these.
        for (String name : new String[]
        {
                "AE", "CO", "RELREC", "SUPP--", "SQ--"
        })
        {
            assertNull(load("{\"Name\":\"" + name
                    + "\",\"Child\":true,\"Keys\":[\"USUBJID\",\"IDVAR\",\"IDVARVAL\"]}")
                            .getLoadError(),
                    "'" + name + "' must load clean");
        }
    }


    @Test
    void anOrdinaryEntryWithATemplateNameIsNotThisGatesBusiness() throws IOException
    {
        // Control: the name rule is a CHILD rule. An ordinary `--`-named or expansion-template
        // entry is judged by the specialiser and the expansion gates, not here.
        assertNull(load("{\"Name\":\"SUPP--\",\"Keys\":[\"USUBJID\"]}").getLoadError());
    }
}
