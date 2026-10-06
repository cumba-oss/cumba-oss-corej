package net.cumba.corej.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.authoring.FunctionHelp.Entry;
import net.cumba.corej.authoring.FunctionHelp.Parameter;
import org.junit.jupiter.api.Test;

class FunctionHelpTest
{

    private static final String PAGE = """
            # Function reference

            ## How to read this page

            Two rules hold everywhere.

            ## Text

            ### concat

            `concat(a, b[, c])` → **value**

            Joins two or three values into one string; a missing operand makes the result missing

            | parameter | type | required | description |
            |---|---|---|---|
            | `a` | any | yes | The first value |
            | `b` | any | **yes** | The second value |
            | `c` | any | no | An optional third value, `x\\|y` kept |

            **Example**

            ```yaml
            Check:
              expression: 'concat(STUDYID, "-", USUBJID) != SUBJID'
            ```

            **Missing and empty** — A missing operand makes the result that missing value.
            The identity is kept.

            ### var_exists

            `var_exists(x)` → **boolean**

            True when the named column exists. A negated spelling `var_not_exists(x)` exists; for a
            written name prefer `not var_exists(x)`.

            ### varname

            `varname()` → **value**

            The variable currently iterated.
            """;

    @Test
    void sectionsBecomeEntries()
    {
        FunctionHelp help = FunctionHelp.parse(PAGE);
        assertEquals(List.of("concat", "var_exists", "varname"),
                List.copyOf(help.entries().keySet()));
        Entry concat = help.entry("concat");
        assertNotNull(concat);
        assertEquals("concat(a, b[, c])", concat.signature());
        assertEquals("value", concat.returnType());
        assertEquals("Joins two or three values into one string; a missing operand makes the result"
                + " missing", concat.summary());
        assertEquals(
                List.of(new Parameter("a", "any", true, "The first value"),
                        new Parameter("b", "any", true, "The second value"),
                        new Parameter("c", "any", false, "An optional third value, `x|y` kept")),
                concat.parameters());
        assertEquals(List.of("Example", "Missing and empty"),
                List.copyOf(concat.sections().keySet()));
        assertTrue(concat.sections().get("Example").contains("concat(STUDYID"));
        assertTrue(concat.sections().get("Missing and empty")
                .startsWith("— A missing operand makes the result that missing value."));
    }


    @Test
    void aSectionWithoutATableHasNoParameters()
    {
        FunctionHelp help = FunctionHelp.parse(PAGE);
        Entry varname = help.entry("varname");
        assertNotNull(varname);
        assertEquals(List.of(), varname.parameters());
        assertEquals("varname()", varname.signature());
        assertEquals("The variable currently iterated.", varname.summary());
    }


    @Test
    void anAliasPointsAtItsSection()
    {
        FunctionHelp help = FunctionHelp.parse(PAGE);
        assertNull(help.entry("var_not_exists"));
        assertEquals("var_exists", help.aliasOf("var_not_exists"));
        assertNull(help.aliasOf("concat"), "a section of its own is not an alias");
        assertNull(help.aliasOf("no_such_function"));
        assertFalse(help.entries().isEmpty());
    }


    @Test
    void anEmptyPageHasNoEntries()
    {
        assertEquals(0, FunctionHelp.parse("# nothing here\n").entries().size());
    }
}
