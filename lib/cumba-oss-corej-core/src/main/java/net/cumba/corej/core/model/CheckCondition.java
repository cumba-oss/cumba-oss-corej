package net.cumba.corej.core.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

@JsonDeserialize(using = CheckConditionDeserializer.class)
@JsonSerialize(using = CheckConditionSerializer.class)
public sealed interface CheckCondition
        permits
        CheckConditionAll,
        CheckConditionAny,
        CheckConditionNot,
        CheckConditionLeaf,
        CheckConditionConstant,
        CheckConditionExpression
{

}
