package net.cumba.corej.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ExecutabilityHintTest
{

    @Test
    void noArgsAndAccessors()
    {
        ExecutabilityHint h = new ExecutabilityHint();
        assertNull(h.getCategory());
        assertNull(h.getDetail());
        h.setCategory("generated");
        h.setDetail("Requires generation via RuleGenerator.");
        assertEquals("generated", h.getCategory());
        assertEquals("Requires generation via RuleGenerator.", h.getDetail());
    }


    @Test
    void allArgsEqualsHashCodeToString()
    {
        ExecutabilityHint a = new ExecutabilityHint("expanded", "Requires wildcard expansion.");
        ExecutabilityHint b = new ExecutabilityHint("expanded", "Requires wildcard expansion.");
        ExecutabilityHint c = new ExecutabilityHint("not executable", "Cannot run.");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertEquals("expanded", a.getCategory());
        org.junit.jupiter.api.Assertions.assertTrue(a.toString().contains("expanded"));
    }


    @Test
    void jacksonRoundTrip() throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"Category\":\"generated\",\"Detail\":\"Template token(s): `*FL`.\"}";
        ExecutabilityHint h = mapper.readValue(json, ExecutabilityHint.class);
        assertEquals("generated", h.getCategory());
        assertEquals("Template token(s): `*FL`.", h.getDetail());
        // Field names serialise via @JsonProperty (Category/Detail), not the Java field names.
        String out = mapper.writeValueAsString(h);
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("\"Category\""));
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("\"Detail\""));
    }

}
