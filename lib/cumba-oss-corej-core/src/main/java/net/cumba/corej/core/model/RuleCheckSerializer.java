package net.cumba.corej.core.model;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.util.Map;
import java.util.SequencedMap;
import net.cumba.datatable.report.Severity;

/**
 * Writes a rule's {@code Check:} key back out in whichever of Plan C &#167;3.3's two shapes the
 * rule carries: a plain {@link CheckCondition} (all 3 804 shipped rules) or a level map.
 *
 * <p>
 * &#9873; <b>The plain branch is byte-identical to the pre-Plan-C binding</b> — it delegates to the
 * very same {@link CheckConditionSerializer} the {@code Check} field used before the level map
 * existed, so nothing about the shipped corpus's serialisation moves.
 * </p>
 *
 * <p>
 * The level branch writes each level under its UPPER-case name in ladder order, with the level's
 * own {@code Message} — when it declares one — spliced in as a sibling of the condition's keys via
 * {@link CheckConditionSerializer#writeUnwrapped}. The rule's {@code Outcome.Message} is never
 * copied into a level (&#167;3.6).
 * </p>
 */
public class RuleCheckSerializer extends StdSerializer<Object>
{

    private static final long serialVersionUID = 1L;

    private static final CheckConditionSerializer CONDITIONS = new CheckConditionSerializer();

    public RuleCheckSerializer()
    {
        super(Object.class);
    }


    @Override
    public void serialize(Object aValue, JsonGenerator aGen, SerializerProvider aProvider)
        throws IOException
    {
        if (aValue instanceof CheckCondition condition)
        {
            CONDITIONS.serialize(condition, aGen, aProvider);
            return;
        }
        if (aValue instanceof SequencedMap<?, ?> levels)
        {
            aGen.writeStartObject();
            for (Map.Entry<?, ?> e : levels.entrySet())
            {
                Severity level = (Severity) e.getKey();
                LevelCheck value = (LevelCheck) e.getValue();
                aGen.writeFieldName(level.name());
                if (value.message() == null)
                {
                    CONDITIONS.serialize(value.condition(), aGen, aProvider);
                }
                else
                {
                    aGen.writeStartObject();
                    CheckConditionSerializer.writeUnwrapped(value.condition(), aGen, aProvider);
                    aGen.writeStringField(RuleCheckDeserializer.MESSAGE_KEY, value.message());
                    aGen.writeEndObject();
                }
            }
            aGen.writeEndObject();
            return;
        }
        throw new IOException("Unknown Check payload type: " + aValue.getClass());
    }

}
