package net.cumba.cdisc.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

@RequiredArgsConstructor
@Getter
public enum CheckOperator
{

    CONTAINS("contains"),
    CONTAINS_CASE_INSENSITIVE("contains_case_insensitive"),
    DATE_EQUAL_TO("date_equal_to"),
    DATE_GREATER_THAN("date_greater_than"),
    DATE_GREATER_THAN_OR_EQUAL_TO("date_greater_than_or_equal_to"),
    DATE_LESS_THAN("date_less_than"),
    DATE_LESS_THAN_OR_EQUAL_TO("date_less_than_or_equal_to"),
    DATE_NOT_EQUAL_TO("date_not_equal_to"),
    DATE_PART_EQUAL_TO("date_part_equal_to"),
    DATE_PART_NOT_EQUAL_TO("date_part_not_equal_to"),
    DOES_NOT_CONTAIN("does_not_contain"),
    DOES_NOT_CONTAIN_CASE_INSENSITIVE("does_not_contain_case_insensitive"),
    DOES_NOT_EQUAL_STRING_PART("does_not_equal_string_part"),
    DOES_NOT_HAVE_NEXT_CORRESPONDING_RECORD("does_not_have_next_corresponding_record"),
    DS_EXISTS("ds_exists"),
    DS_NOT_EXISTS("ds_not_exists"),
    EMPTY("empty"),
    EMPTY_WITHIN_EXCEPT_LAST_ROW("empty_within_except_last_row"),
    ENDS_WITH("ends_with"),
    EQUAL_TO("equal_to"),
    EQUAL_TO_CASE_INSENSITIVE("equal_to_case_insensitive"),
    GREATER_THAN("greater_than"),
    GREATER_THAN_OR_EQUAL_TO("greater_than_or_equal_to"),
    HAS_EQUAL_LENGTH("has_equal_length"),
    HAS_MULTIPLE_VALUES_FOR("has_multiple_values_for"),
    HAS_NOT_EQUAL_LENGTH("has_not_equal_length"),
    HAS_SAME_VALUES("has_same_values"),
    INCONSISTENT_ENUMERATED_COLUMNS("inconsistent_enumerated_columns"),
    INVALID_DATE("invalid_date"),
    INVALID_DURATION("invalid_duration"),
    IS_COMPLETE_DATE("is_complete_date"),
    IS_COMPLETE_DATE_PART("is_complete_date_part"),
    IS_CONTAINED_BY("is_contained_by"),
    IS_CONTAINED_BY_CASE_INSENSITIVE("is_contained_by_case_insensitive"),
    IS_INCOMPLETE_DATE("is_incomplete_date"),
    IS_INCONSISTENT_ACROSS_DATASET("is_inconsistent_across_dataset"),
    IS_INTEGER("is_integer"),
    IS_NOT_COMPLETE_DATE_PART("is_not_complete_date_part"),
    IS_NOT_CONTAINED_BY("is_not_contained_by"),
    IS_NOT_CONTAINED_BY_CASE_INSENSITIVE("is_not_contained_by_case_insensitive"),
    IS_NOT_INTEGER("is_not_integer"),
    IS_NOT_ORDERED_SUBSET_OF("is_not_ordered_subset_of"),
    IS_NOT_UNIQUE_RELATIONSHIP("is_not_unique_relationship"),
    IS_NOT_UNIQUE_SET("is_not_unique_set"),
    IS_UNIQUE_SET("is_unique_set"),
    LESS_THAN("less_than"),
    LESS_THAN_OR_EQUAL_TO("less_than_or_equal_to"),
    LONGER_THAN("longer_than"),
    LONGER_THAN_OR_EQUAL_TO("longer_than_or_equal_to"),
    MATCHES_REGEX("matches_regex"),
    NON_EMPTY("non_empty"),
    NOT_CONTAINS_ALL("not_contains_all"),
    NOT_EQUAL_TO("not_equal_to"),
    NOT_EQUAL_TO_CASE_INSENSITIVE("not_equal_to_case_insensitive"),
    NOT_EQUAL_TO_DIVIDE("not_equal_to_divide"),
    NOT_EQUAL_TO_PCTCHG("not_equal_to_pctchg"),
    NOT_EQUAL_TO_SUBTRACT("not_equal_to_subtract"),
    NOT_MATCHES_REGEX("not_matches_regex"),
    NOT_PREFIX_MATCHES_REGEX("not_prefix_matches_regex"),
    NOT_PRESENT_ON_MULTIPLE_ROWS_WITHIN("not_present_on_multiple_rows_within"),
    NOT_SUFFIX_MATCHES_REGEX("not_suffix_matches_regex"),
    PREFIX_EQUAL_TO("prefix_equal_to"),
    PREFIX_IS_NOT_CONTAINED_BY("prefix_is_not_contained_by"),
    PREFIX_MATCHES_REGEX("prefix_matches_regex"),
    PREFIX_NOT_EQUAL_TO("prefix_not_equal_to"),
    PRESENT_ON_MULTIPLE_ROWS_WITHIN("present_on_multiple_rows_within"),
    SHARES_NO_ELEMENTS_WITH("shares_no_elements_with"),
    SUFFIX_EQUAL_TO("suffix_equal_to"),
    SHORTER_THAN("shorter_than"),
    SHORTER_THAN_OR_EQUAL_TO("shorter_than_or_equal_to"),
    STARTS_WITH("starts_with"),
    SUFFIX_IS_NOT_CONTAINED_BY("suffix_is_not_contained_by"),
    SUFFIX_MATCHES_REGEX("suffix_matches_regex"),
    TARGET_IS_NOT_SORTED_BY("target_is_not_sorted_by"),
    TIME_PART_EQUAL_TO("time_part_equal_to"),
    TIME_PART_NOT_EQUAL_TO("time_part_not_equal_to"),
    VAR_EXISTS("var_exists"),
    VAR_IS_NULL("var_is_null"),
    VAR_NOT_EXISTS("var_not_exists");

    @JsonValue
    private final String jsonValue;

    private static final Map<String, CheckOperator> LOOKUP = Stream.of(values())
            .collect(Collectors.toMap(CheckOperator::getJsonValue, Function.identity()));

    @JsonCreator
    public static @Nullable CheckOperator fromJson(@Nullable String value)
    {
        return LOOKUP.get(value);
    }

}
