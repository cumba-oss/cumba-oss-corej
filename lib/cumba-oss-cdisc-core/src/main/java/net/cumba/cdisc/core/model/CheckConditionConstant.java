package net.cumba.cdisc.core.model;

import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * A constant boolean condition used during partial evaluation and simplification of Check condition
 * trees. Not serialized — only used as an intermediate representation within the engine.
 */
@JsonSerialize(using = JsonSerializer.None.class)
public record CheckConditionConstant(boolean value) implements CheckCondition
{

    public static final CheckConditionConstant TRUE = new CheckConditionConstant(true);

    public static final CheckConditionConstant FALSE = new CheckConditionConstant(false);
}
