package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionExpression;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * Phase 4b — native-only authoring: an {@code {"expression": …}} Check using the {@code var_*} /
 * {@code ds_*} accessors with an arbitrary-literal name has no legacy lowering, so it is kept as a
 * {@link CheckConditionExpression} and retained as a native {@code checkExpr}. A definitionally
 * invalid accessor ({@code var_role} at the DATA level) is filed as a load error.
 */
class NativeExpressionAuthoringTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void arbitraryLiteralExpressionDeserializesAsNativeOnly() throws Exception
    {
        CheckCondition c = MAPPER.readValue(
                "{\"expression\": \"var_label(\\\"AESTDTC\\\", \\\"DEFINE\\\") == \\\"Start Date\\\"\"}",
                CheckCondition.class);
        assertInstanceOf(CheckConditionExpression.class, c);
    }


    private static Rule loadSingle(String expression) throws Exception
    {
        String pkg = "{\"rules\": {\"R1\": {" + "\"Core\": {\"Id\": \"CORE-NATIVE-AUTH\"}," + ""
                + "\"Sensitivity\": \"Dataset\"," + "\"Check\": {\"expression\": \""
                + expression.replace("\"", "\\\"") + "\"},"
                + "\"Outcome\": {\"Message\": \"native authoring test\"}}}}";
        RulePackage loaded = RulePackageLoader.loadFromString(pkg);
        return loaded.getRules().values().iterator().next();
    }


    @Test
    void validNativeAccessorRuleRetainsCheckExpr() throws Exception
    {
        Rule r = loadSingle("var_label(\"AESTDTC\", \"DEFINE\") == \"Start Date\"");
        assertNull(r.getLoadError(), "a valid var_* accessor is not a load error");
        assertNotNull(r.getCheckExpr(), "native-only accessor rule retains a checkExpr");
        assertInstanceOf(CheckConditionExpression.class, r.getCheck());
    }


    @Test
    void invalidAccessorLevelIsFiledAsLoadError() throws Exception
    {
        // var_role has no DATA cell -> RuleDefinitionException at compile -> load error -> ERROR.
        Rule r = loadSingle("var_role(\"AETERM\", \"DATA\") == \"Identifier\"");
        assertNotNull(r.getLoadError(), "var_role at the DATA level is a rule definition error");
    }
}
