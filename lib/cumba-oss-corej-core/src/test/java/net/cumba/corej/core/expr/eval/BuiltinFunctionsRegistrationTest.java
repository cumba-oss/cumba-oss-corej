package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.expr.eval.spi.BuiltinFunctions;
import org.junit.jupiter.api.Test;

/**
 * Closed-set guard over the {@link BuiltinFunctions} SPI provider, mirroring
 * {@code BuiltinRegistryTest.closedSetSize()} for the metadata-operand registry.
 *
 * <p>
 * {@link BuiltinFunctions#functions()} runs exactly once per JVM, from the {@code static}
 * initialiser of {@link FunctionRegistry}. Behavioural tests therefore reach the registrations only
 * indirectly, and a deleted registration is invisible to them unless some test happens to resolve
 * that particular name. Measured: deleting {@code value(fns, "lowcase", lower)} left all 43
 * {@code BuiltinFunctionsTest} cases green, and mutation testing reported 54 surviving "removed
 * call to bool/value" mutants plus a surviving "replaced return value with Collections.emptyList" —
 * i.e. an empty registry passed.
 * </p>
 *
 * <p>
 * This test asserts the exact {@code name/arity-range/kind} set instead, so adding or removing any
 * descriptor — or changing its parameter bounds — is a deliberate, reviewed edit to the list below.
 * Phase 6b (D19a): one descriptor per name; the former arity overloads ({@code substring} 2/3,
 * {@code concat} 2/3, {@code coalesce} 2/3, {@code prefix_matches} 2/3, {@code suffix_matches} 2/3,
 * {@code tuple} 2..6) are single descriptors whose optional parameters span the same range —
 * {@code 2-3} below — and {@code tuple}'s trailing collector is unbounded ({@code 2+}). It calls
 * the provider directly rather than reading {@link FunctionRegistry}, so it is immune to
 * programmatic registrations left behind by other tests.
 * </p>
 */
class BuiltinFunctionsRegistrationTest
{

    /** Every registered descriptor, as {@code name/arity-range/kind}, sorted. */
    private static final List<String> EXPECTED = List.of("abs/1/VALUE", "available/1/BOOLEAN",
            "between/3/BOOLEAN", "ceil/1/VALUE", "char/1/VALUE", "coalesce/2-3/VALUE",
            "colref/1/VALUE", "concat/2-3/VALUE", "contains/2/BOOLEAN", "count/1/VALUE",
            "date_contains/2/BOOLEAN", "date_overlaps/2/BOOLEAN", "day/1/VALUE",
            "dictionary_available/1/BOOLEAN", "does_not_contain/2/BOOLEAN", "dy/2/VALUE",
            // runbook W2b (PLAN-operation-replacements §8): the day count ported from its operation
            "date_diff_days/2/VALUE",
            // wave 3 (PLAN-per-row-functions): the per-row functions ported from their operations
            "dictionary_has_decode/2-3/BOOLEAN", "earliest_possible/1/VALUE", "empty/1/BOOLEAN",
            // PLAN-scalar-date-extremes: the two-value date extremes (SDE D1)
            "earliest_date/2/VALUE", "latest_date/2/VALUE", "ends_with/2/BOOLEAN",
            "equalsIgnoreCase/2/BOOLEAN", "floor/1/VALUE",
            // wave 0 (PLAN-binding-expressions): the list-valued exemplar, ported from the
            // retired GET_CODELIST_ATTRIBUTES operation
            "get_codelist_attributes/3/VALUE", "has_alpha/1/BOOLEAN", "has_digit/1/BOOLEAN",
            // wave 4 (PLAN-list-functions): the 21 dataset-level list functions
            "codelist_terms/2-3/VALUE", "dataset_names/0/VALUE", "define_dataset_names/0/VALUE",
            "define_key_variables/0/VALUE", "define_variable_names/0/VALUE",
            "duplicate_label_variables/0/VALUE", "expected_variables/0/VALUE",
            "get_column_order_from_dataset/0/VALUE", "get_column_order_from_library/0/VALUE",
            "get_dataset_filtered_variables/0-2/VALUE", "get_model_column_order/0/VALUE",
            "get_model_filtered_variables/0-3/VALUE", "get_parent_model_column_order/1/VALUE",
            "minus/2/VALUE", "natural_key_variables/0/VALUE", "required_variables/0/VALUE",
            "split_sibling_length_mismatch/0/VALUE", "standard_domains/0/VALUE",
            "study_domains/0/VALUE", "valid_codelist_dates/0-1/VALUE", "variable_names/0/VALUE",
            "imatches/2/BOOLEAN", "interval_uncertainty_precision_mismatch/1-2/BOOLEAN",
            "invalid_date/1/BOOLEAN", "invalid_duration/1-2/BOOLEAN", "is_complete_date/1/BOOLEAN",
            "is_complete_date_part/1/BOOLEAN", "is_incomplete_date/1/BOOLEAN",
            "is_integer/1/BOOLEAN", "is_missing/1/BOOLEAN", "is_not_complete_date_part/1/BOOLEAN",
            "is_not_integer/1/BOOLEAN", "is_numeric/1/BOOLEAN", "is_partial_date/1/BOOLEAN",
            "is_present/1/BOOLEAN", "is_valid_date/1/BOOLEAN", "is_valid_duration/1/BOOLEAN",
            "is_valid_name/1/BOOLEAN", "is_valid_testcd/1/BOOLEAN", "latest_possible/1/VALUE",
            "len/1/VALUE", "length/1/VALUE", "library_available/0/BOOLEAN", "lowcase/1/VALUE",
            "lower/1/VALUE", "month/1/VALUE", "non_empty/1/BOOLEAN", "normalize_space/1/VALUE",
            "prefix/2/VALUE", "prefix_matches/2-3/BOOLEAN", "present/1/BOOLEAN",
            "referenced_dataset_variables/1/VALUE", "referenced_domain_class/1/VALUE",
            "round/1/VALUE", "row_max/1/VALUE", "size/1/VALUE", "split_by/2/VALUE",
            "starts_with/2/BOOLEAN", "substring/2-3/VALUE", "suffix/2/VALUE",
            "suffix_matches/2-3/BOOLEAN", "time_contains/2/BOOLEAN", "time_overlaps/2/BOOLEAN",
            "trim/1/VALUE", "tuple/2+/VALUE", "upcase/1/VALUE", "upper/1/VALUE",
            // PLAN-dynamic-column-functions §2.1/§2.2 (owner Q2, Q10): the four conversions,
            // registered, and the two formatting functions
            "date/1/VALUE", "find_vars/1/VALUE", "lpad/2-3/VALUE", "num/1/VALUE", "printf/1+/VALUE",
            "str/1/VALUE", "time/1/VALUE", "valid_external_dictionary_code/3-4/BOOLEAN",
            "valid_external_dictionary_code_term_pair/3-4/BOOLEAN",
            "valid_external_dictionary_hierarchy/3-4/BOOLEAN",
            "valid_external_dictionary_value/3-4/BOOLEAN", "value/0/VALUE", "varname/0/VALUE",
            "year/1/VALUE",
            // wave 4b (PLAN-scalar-metadata-functions): the six dataset-level scalar functions
            "column_series_metadata/1-3/VALUE", "cross_dataset_variable_metadata/2/VALUE",
            "dataset_class_from_library/0/VALUE", "domain_is_custom/0/VALUE",
            "extract_metadata/1/VALUE", "variable_count/0-2/VALUE");

    private static Set<String> actual()
    {
        Set<String> out = new TreeSet<>();
        new BuiltinFunctions().functions().forEach(d -> out.add(signature(d)));
        return out;
    }


    private static String signature(FunctionDescriptor d)
    {
        int min = d.minArity();
        int max = d.maxArity();
        String range = max == Integer.MAX_VALUE ? min + "+"
                : min == max ? String.valueOf(min) : min + "-" + max;
        return d.name() + "/" + range + "/" + d.kind();
    }


    @Test
    void registeredDescriptorsAreExactlyTheClosedSet()
    {
        assertEquals(new TreeSet<>(EXPECTED), actual());
    }


    @Test
    void noDuplicateNames()
    {
        // A duplicate name would be silently collapsed by the Set above, and FunctionRegistry
        // treats it as a configuration error at load time (one descriptor per name, D19a).
        assertEquals(EXPECTED.size(), new BuiltinFunctions().functions().size());
    }

}
