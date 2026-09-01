package net.cumba.cdisc.core.model;

import java.util.List;

import lombok.NonNull;
import lombok.Value;

@Value
public class CheckConditionAny implements CheckCondition
{

    @NonNull
    List<CheckCondition> conditions;

}
