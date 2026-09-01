package net.cumba.cdisc.core.metadata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Codec for list-valued define metadata attributes carried through the string-valued
 * {@code MetadataProvider.getVariableMetadata} channel ({@code Map<String,String>}).
 *
 * <p>
 * Some define attributes are genuinely lists — notably {@code codelist_coded_codes} (every coded
 * code of a variable's define codelist), mirroring the Python reference engine where
 * {@code define_variable_codelist_coded_codes} is a list cell. Rather than widen the provider
 * contract to {@code Map<String,Object>}, a list attribute is JSON-encoded (e.g.
 * {@code ["AE","CM"]}) by the define provider and decoded here when the native accessor
 * materialises the operand. This is the minimal faithful mirror of Python's "this cell is a list"
 * branch.
 * </p>
 *
 * <p>
 * {@link #decode} is tolerant: {@code null}/blank decodes to the empty list, a JSON array to its
 * elements, and any other scalar string to a singleton list — so a provider that exposes a single
 * code as a bare string still behaves like a one-element codelist.
 * </p>
 */
public final class DefineMetadataListCodec
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>()
    {
    };

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>()
    {
    };

    private DefineMetadataListCodec()
    {
    }


    /**
     * JSON-encodes {@code list} as a string array (e.g. {@code ["AE","CM"]}); {@code "[]"} when
     * empty.
     */
    public static String encode(@Nullable List<String> list)
    {
        if (list == null || list.isEmpty())
        {
            return "[]";
        }
        try
        {
            return MAPPER.writeValueAsString(list);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException _)
        {
            // String lists never fail to serialise; fall back to an empty list rather than throw.
            return "[]";
        }
    }


    /**
     * Decodes a JSON string array to a {@code List<String>}. {@code null}/blank ⇒ empty list; a
     * JSON array ⇒ its elements; any other scalar ⇒ a singleton list (tolerant fallback).
     */
    public static List<String> decode(@Nullable String value)
    {
        if (value == null)
        {
            return List.of();
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty())
        {
            return List.of();
        }
        if (trimmed.charAt(0) == '[')
        {
            try
            {
                List<String> parsed = MAPPER.readValue(trimmed, STRING_LIST);
                return parsed != null ? parsed : List.of();
            }
            catch (com.fasterxml.jackson.core.JsonProcessingException _)
            {
                // Not valid JSON after all — fall back to treating it as a scalar singleton.
                return List.of(value);
            }
        }
        return List.of(value);
    }


    /**
     * JSON-encodes {@code map} as a string object (e.g. {@code {"ALB":"Albumin"}}); {@code "{}"}
     * when empty (Fix #123, the {@code codelist_code_decode} attribute).
     *
     * <p>
     * Named {@code encodeStringMap} rather than {@code encodeMap} to keep it distinct from
     * {@link #decode(String)} — in this class "decode" means <i>JSON → List</i>, whereas the
     * Define-XML {@code Decode} it carries is codelist term text.
     * </p>
     *
     * @param map
     *            the map to encode, may be {@code null}
     * @return the JSON object string, never {@code null}
     */
    public static String encodeStringMap(@Nullable Map<String, String> map)
    {
        if (map == null || map.isEmpty())
        {
            return "{}";
        }
        try
        {
            return MAPPER.writeValueAsString(map);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException _)
        {
            // String maps never fail to serialise; fall back to an empty map rather than throw.
            return "{}";
        }
    }


    /**
     * Decodes a JSON string object to a {@code Map<String,String>} (Fix #123). Tolerant, matching
     * {@link #decode(String)}: {@code null}/blank, a non-object scalar, or malformed JSON all yield
     * an <b>empty</b> map, so a missing or corrupt attribute simply means "no decode expectation"
     * rather than an execution error.
     *
     * @param value
     *            the encoded value, may be {@code null}
     * @return the decoded map, never {@code null}
     */
    public static Map<String, String> decodeStringMap(@Nullable String value)
    {
        if (value == null)
        {
            return Map.of();
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{')
        {
            return Map.of();
        }
        try
        {
            Map<String, String> parsed = MAPPER.readValue(trimmed, STRING_MAP);
            return parsed != null ? parsed : Map.of();
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException _)
        {
            return Map.of();
        }
    }

}
