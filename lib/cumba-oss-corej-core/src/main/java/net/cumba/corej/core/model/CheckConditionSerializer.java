package net.cumba.corej.core.model;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;

/**
 * Serialises a {@link CheckCondition} tree back to the nested {@code all}/ {@code any}/{@code not}
 * wrapper form used by the CDISC CORE rule JSON (the exact inverse of
 * {@link CheckConditionDeserializer}).
 *
 * <p>
 * Without this serialiser, Lombok-generated accessors would emit {@code CheckConditionAll} as
 * {@code {"conditions":[…]}} which the deserialiser does not recognise as a composite and rejects
 * on the next read.
 * </p>
 */
public class CheckConditionSerializer extends StdSerializer<CheckCondition>
{

    private static final long serialVersionUID = 1L;

    public CheckConditionSerializer()
    {
        super(CheckCondition.class);
    }


    @Override
    public void serialize(CheckCondition aValue, JsonGenerator aGen, SerializerProvider aProvider)
        throws IOException
    {
        if (aValue instanceof CheckConditionAll all)
        {
            aGen.writeStartObject();
            aGen.writeArrayFieldStart("all");
            for (CheckCondition c : all.getConditions())
            {
                serialize(c, aGen, aProvider);
            }
            aGen.writeEndArray();
            aGen.writeEndObject();
        }
        else if (aValue instanceof CheckConditionAny any)
        {
            aGen.writeStartObject();
            aGen.writeArrayFieldStart("any");
            for (CheckCondition c : any.getConditions())
            {
                serialize(c, aGen, aProvider);
            }
            aGen.writeEndArray();
            aGen.writeEndObject();
        }
        else if (aValue instanceof CheckConditionNot not)
        {
            aGen.writeStartObject();
            aGen.writeFieldName("not");
            serialize(not.getCondition(), aGen, aProvider);
            aGen.writeEndObject();
        }
        else if (aValue instanceof CheckConditionExpression expr)
        {
            aGen.writeStartObject();
            aGen.writeStringField("expression", expr.source());
            aGen.writeEndObject();
        }
        else if (aValue == null)
        {
            aGen.writeNull();
        }
        else
        {
            throw new IOException("Unknown CheckCondition type: " + aValue.getClass());
        }
    }


    /**
     * Writes a condition's own keys into the <b>already-open</b> object {@code aGen} is positioned
     * in, i.e. {@link #serialize} minus its enclosing braces.
     *
     * <p>
     * Used by {@link RuleCheckSerializer} for a check level that carries its own {@code Message}:
     * Plan C &#167;3.3 spells that level as the condition's keys <em>plus</em> {@code Message} in
     * one flat object, so the {@code Message} has to become a sibling of the condition's keys
     * rather than wrap them.
     * </p>
     *
     * @param aValue
     *            the condition whose keys to write
     * @param aGen
     *            a generator positioned inside an open object
     * @param aProvider
     *            the serialisation provider
     * @throws IOException
     *             on a write failure
     */
    static void writeUnwrapped(CheckCondition aValue, JsonGenerator aGen,
            SerializerProvider aProvider)
        throws IOException
    {
        switch (aValue)
        {
        case CheckConditionAll all ->
        {
            aGen.writeArrayFieldStart("all");
            for (CheckCondition c : all.getConditions())
            {
                new CheckConditionSerializer().serialize(c, aGen, aProvider);
            }
            aGen.writeEndArray();
        }
        case CheckConditionAny any ->
        {
            aGen.writeArrayFieldStart("any");
            for (CheckCondition c : any.getConditions())
            {
                new CheckConditionSerializer().serialize(c, aGen, aProvider);
            }
            aGen.writeEndArray();
        }
        case CheckConditionNot not ->
        {
            aGen.writeFieldName("not");
            new CheckConditionSerializer().serialize(not.getCondition(), aGen, aProvider);
        }
        case CheckConditionExpression expr -> aGen.writeStringField("expression", expr.source());
        }
    }

}
