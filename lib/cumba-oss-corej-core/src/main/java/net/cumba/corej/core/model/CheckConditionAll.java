package net.cumba.corej.core.model;

import java.util.List;

import lombok.NonNull;
import lombok.Value;

@Value
public class CheckConditionAll implements CheckCondition
{

    @NonNull
    List<CheckCondition> conditions;

}
