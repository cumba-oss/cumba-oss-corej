package net.cumba.corej.core.model;

import lombok.Value;

@Value
public class CheckConditionNot implements CheckCondition
{

    CheckCondition condition;

}
