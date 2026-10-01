package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.cumba.corej.core.exec.DatasetLookup;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.JoinLookup;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.typed.StageAChecker;
import net.cumba.corej.core.expr.typed.StageAFinding;
import net.cumba.corej.core.expr.typed.TypeExpectations;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.CachedDataTableColumn;
import net.cumba.datatable.impl.ColumnCachedDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * ⭐ {@code PLAN-qualified-name-uniformity-review} phase 1 — the PERMANENT differential harness: for
 * every place a column name can be written in a function call, an operator operand or a marker, a
 * qualified name {@code J.X} (a {@code Match_Datasets} join {@code J} carrying the identical column
 * set) must answer exactly as the unqualified {@code X} does on the primary ({@code D72},
 * {@code NF §3a}, {@code UVC unqualified}'s UNIFORMITY ruling). The criteria are the plan's §2.2:
 * C1 (present column, value AND type AND missing identity, cell for cell), C2 (an unmatched join
 * row reads the column's type default), C3 (an absent column reads the authored default, keyed on
 * the full dotted name), C4 (an unqualified name never reaches a join), C5c / C5q (the column / the
 * qualifier matched ignoring case), C7 (the level calculus), C8 (Stage A finding kinds), C9 (the
 * string form resolves like the reference form).
 *
 * <h2>Population guards (memory {@code silent-disarming-guards})</h2>
 * <ul>
 * <li><b>The roster is generated, the probes are hand-written.</b> {@link #TABLE} lists EVERY
 * descriptor parameter of {@link FunctionRegistry#all()} plus the {@link MetadataAttribute}
 * accessors, the two marker-only names of {@link ExprCompiler#comparisonMarkers()} and every
 * {@link Expr.BinOp} operand position, each classified name-bearing ({@code N}) or not ({@code -}),
 * and the table is asserted EQUAL to that roster — per function AND per declared parameter, in
 * order. A new function, a new parameter or a renamed one reds the harness until a row is written
 * ({@link #tableEqualsTheDescriptorRoster()}).</li>
 * <li><b>The exemption list is an exact ratchet.</b> {@link #EXPECTED_RED} names every (parameter,
 * criterion) that differs TODAY, with its finding id; the harness asserts the measured red set
 * equals it — a fix that removes a divergence reds the harness until its row is removed, a new
 * divergence reds it until it is recorded
 * ({@link #everyProbedParameterIsUniformExceptTheRecordedRows()}).</li>
 * <li><b>A permanent non-vacuity control.</b> A deliberately primary-only function is planted
 * through the package-private {@link FunctionRegistry#register} seam and the differential must red
 * on it; the roster guard must red on the unlisted registration
 * ({@link #aPrimaryOnlyStubRedsTheDifferential()},
 * {@link #anUnlistedRegistrationRedsTheRoster()}).</li>
 * <li><b>A probe whose UNQUALIFIED side fails is a probe defect, never a pass</b> — the harness
 * reds on it by name, so a function whose bare spelling stopped compiling cannot turn its row
 * silently green.</li>
 * </ul>
 *
 * <h2>Reading a row of {@link #TABLE}</h2> {@code fn | param | class | spec}. {@code class} is
 * {@code N} (the argument can carry a column name) or {@code -}. {@code spec} for an {@code N} row
 * is {@code B:<expr>} (a boolean probe), {@code V:<expr>} (a value probe) or {@code X:<reason>}
 * (not probed, with the reason — a provider fixture it needs, a selector ruled by the plan's Q2, a
 * cursor that carries no name); a {@code ; S:<expr>} suffix is the string-form twin for C9. In a
 * probe, {@code @S @N @L @D @T @K @G} is the PROBED reference (substituted bare / dotted /
 * case-varied, naming the fixture column of that letter); {@code #S #N #L #D #T #K #G} are other
 * references, always bare; {@code @DS} is the {@code dataset=} kwarg channel of {@code var_*}
 * (empty on the bare side, {@code , dataset="J"} on the qualified side). Dotted probes on the join
 * read the SAME column of {@code J}, so a difference is the engine's, never the data's.
 */
class QualifiedNameUniformityTest
{

    // ------------------------------------------------------------------
    // The hand table — one row per declared parameter (phase 0 census, corej 53ddce0).
    // ------------------------------------------------------------------

    static final String TABLE = """
            abs | x | N | V:abs(@N)
            available | x | - | a provider list accessor, never a column
            between | x | N | B:between(@N, 1, 3)
            between | lo | N | B:between(#N, @N, 10)
            between | hi | N | B:between(#N, 0, @N)
            ceil | x | N | V:ceil(@N)
            char | x | N | V:char(@L)
            coalesce | a | N | V:coalesce(@S, "z")
            coalesce | b | N | V:coalesce("", @S)
            coalesce | c | N | V:coalesce("", "", @S)
            codelist_terms | codelists | - | codelist names (LIBRARY)
            codelist_terms | level | - | static string
            codelist_terms | returntype | - | static string
            colref | x | N | V:colref("@S")
            column_series_metadata | name | N | X:selector — reads the primary only (plan Q2, ruled: closed by find_vars)
            column_series_metadata | name_pattern | N | X:selector — a regex over the primary's columns (D92b, plan Q2)
            column_series_metadata | min_length | - | static number
            concat | a | N | V:concat(@S, #S)
            concat | b | N | V:concat(#S, @S)
            concat | c | N | V:concat(#S, #S, @S)
            contains | x | N | B:contains(@S, "a")
            contains | value | N | B:contains("zz", @S)
            contains_all | source | N | B:contains_all(@S, split_by(#S, ","))
            contains_all | required | N | X:list-valued — a $-binding or list accessor (compileNotContainsAll); no column channel in this slot
            contains_all | keys | N | X:list-valued — keyLiterals over a list-valued binding's key columns; needs a binding fixture (runner level)
            count | x | N | V:count(split_by(@S, ","))
            cross_dataset_variable_metadata | name | - | a metadata FIELD name ("label"), not a column
            cross_dataset_variable_metadata | domain | - | a dataset (plan Q1)
            dataset_class_from_library | - | - | nullary
            dataset_names | - | - | nullary
            date | x | N | V:date(@D)
            date_contains | outer | N | B:date_contains(date(@D), date(#D))
            date_contains | inner | N | B:date_contains(date(#D), date(@D))
            date_diff_days | name | N | V:date_diff_days(@D, #D)
            date_diff_days | reference | N | V:date_diff_days(#D, @D)
            date_overlaps | a | N | B:date_overlaps(date(@D), date(#D))
            date_overlaps | b | N | B:date_overlaps(date(#D), date(@D))
            day | x | N | V:day(@D)
            define_dataset_names | - | - | nullary
            define_key_variables | - | - | nullary
            define_variable_decode_matches | name | - | varname() only (compileDefineVariableDecodeMatches refuses any other argument)
            define_variable_names | - | - | nullary
            dictionary_available | type | - | static string
            dictionary_has_decode | name | N | X:provider — the argument vector is compiled by operandPlan (the probed channel); needs a dictionary fixture
            dictionary_has_decode | external_dictionary_type | - | static string
            dictionary_has_decode | case_sensitive | - | static boolean
            distinct | name | N | V:distinct(@S)
            distinct | domain | - | a dataset (plan Q1)
            distinct | filter | N | V:distinct(#S, filter=(@S == "a"))
            distinct | group | N | V:distinct(#S, group=[@G])
            distinct | keep_missings | - | static boolean
            does_not_contain | x | N | B:does_not_contain(@S, "a")
            does_not_contain | value | N | B:does_not_contain("zz", @S)
            does_not_equal_string_part | name | N | B:does_not_equal_string_part(@S, #S, regex="^a")
            does_not_equal_string_part | value | N | B:does_not_equal_string_part(#S, @S, regex="^a")
            does_not_equal_string_part | regex | - | static regex
            domain_is_custom | - | - | nullary
            ds_exists | name | - | a dataset name
            ds_not_exists | name | - | a dataset name
            duplicate_label_variables | - | - | nullary
            dy | name | N | V:dy(@D, #D)
            dy | reference | N | V:dy(#D, @D)
            earliest_date | a | N | V:earliest_date(date(@D), date("2020-01-10"))
            earliest_date | b | N | V:earliest_date(date("2020-01-10"), date(@D))
            earliest_possible | x | N | V:earliest_possible(@D)
            empty | x | N | B:empty(@S)
            empty_within_except_last_row | name | N | B:empty_within_except_last_row(@S, #G, ordering=#L)
            empty_within_except_last_row | group | N | B:empty_within_except_last_row(#S, @G, ordering=#L)
            empty_within_except_last_row | ordering | N | B:empty_within_except_last_row(#S, #G, ordering=@L)
            empty_within_except_last_row | keep_missings | - | static boolean
            ends_with | x | N | B:ends_with(@S, "a")
            ends_with | value | N | B:ends_with("zz", @S)
            equalsIgnoreCase | a | N | B:equalsIgnoreCase(@S, #S)
            equalsIgnoreCase | b | N | B:equalsIgnoreCase(#S, @S)
            expected_variables | - | - | nullary
            extract_metadata | name | - | a metadata key ("dataset_size"), not a column
            find_vars | entry | N | V:count(find_vars("@S"))
            floor | x | N | V:floor(@N)
            get_codelist_attributes | name | N | X:provider — operandPlan channel; needs a LIBRARY fixture
            get_codelist_attributes | version | N | X:provider — operandPlan channel; needs a LIBRARY fixture
            get_codelist_attributes | ct_attribute | - | static string
            get_column_order_from_dataset | - | - | nullary
            get_column_order_from_library | - | - | nullary
            get_dataset_filtered_variables | key_name | - | a metadata key
            get_dataset_filtered_variables | key_value | - | a metadata value
            get_model_column_order | - | - | nullary
            get_model_filtered_variables | key_name | - | a metadata key
            get_model_filtered_variables | key_value | - | a metadata value
            get_model_filtered_variables | model_class | - | static string
            get_parent_model_column_order | name | N | X:provider — operandPlan channel; needs a LIBRARY fixture
            has_alpha | x | N | B:has_alpha(@S)
            has_digit | x | N | B:has_digit(@D)
            has_equal_length | name | N | B:has_equal_length(@S, 1)
            has_equal_length | length | N | B:has_equal_length(#S, @L)
            has_mixed_emptiness_within_group | name | N | B:has_mixed_emptiness_within_group(@S, group=[#G])
            has_mixed_emptiness_within_group | group | N | B:has_mixed_emptiness_within_group(#S, group=[@G])
            has_mixed_emptiness_within_group | qualifying_any_populated | N | B:has_mixed_emptiness_within_group(#S, group=[#G], qualifying_any_populated=[@S])
            has_mixed_emptiness_within_group | keep_missings | - | static boolean
            has_multiple_values_for | name | N | B:has_multiple_values_for(@S, #K)
            has_multiple_values_for | key | N | B:has_multiple_values_for(#S, @K)
            has_multiple_values_for | within | N | B:has_multiple_values_for(#S, #K, within=@G)
            has_multiple_values_for | keep_missings | - | static boolean
            has_multiple_values_for | include_empty | - | static boolean
            has_next_corresponding_record | name | N | B:not has_next_corresponding_record(@S, #S, within=#G, ordering=#L)
            has_next_corresponding_record | value | N | B:not has_next_corresponding_record(#S, @S, within=#G, ordering=#L)
            has_next_corresponding_record | within | N | B:not has_next_corresponding_record(#S, #S, within=@G, ordering=#L)
            has_next_corresponding_record | ordering | N | B:not has_next_corresponding_record(#S, #S, within=#G, ordering=@L)
            has_next_corresponding_record | relation | - | static string
            has_next_corresponding_record | keep_missings | - | static boolean
            has_not_equal_length | name | N | B:has_not_equal_length(@S, 1)
            has_not_equal_length | length | N | B:has_not_equal_length(#S, @L)
            has_same_values | name | N | B:has_same_values(@S)
            imatches | x | N | B:imatches(@S, /^a/)
            imatches | pattern | - | static regex
            inconsistent_enumerated_columns | name | N | B:inconsistent_enumerated_columns(@S)
            interval_uncertainty_precision_mismatch | name | N | B:interval_uncertainty_precision_mismatch(@D)
            interval_uncertainty_precision_mismatch | delimiter | - | static string
            invalid_date | x | N | B:invalid_date(@D)
            invalid_duration | x | N | B:invalid_duration(@D)
            invalid_duration | negative | - | static boolean
            is_complete_date | x | N | B:is_complete_date(@D)
            is_complete_date_part | x | N | B:is_complete_date_part(@D)
            is_incomplete_date | x | N | B:is_incomplete_date(@D)
            is_inconsistent_across_dataset | name | N | B:is_inconsistent_across_dataset(@S, keys=[#K])
            is_inconsistent_across_dataset | key | N | B:is_inconsistent_across_dataset(#S, @K)
            is_inconsistent_across_dataset | keys | N | B:is_inconsistent_across_dataset(#S, keys=[@K])
            is_inconsistent_across_dataset | include_empty | - | static boolean
            is_inconsistent_across_dataset | keep_missings | - | static boolean
            is_integer | x | N | B:is_integer(@N)
            is_last_in_group | ordering | N | B:is_last_in_group(ordering=@L, group=[#G])
            is_last_in_group | group | N | B:is_last_in_group(ordering=#L, group=[@G])
            is_last_in_group | keep_missings | - | static boolean
            is_missing | x | N | B:is_missing(@S)
            is_not_complete_date_part | x | N | B:is_not_complete_date_part(@D)
            is_not_integer | x | N | B:is_not_integer(@N)
            is_not_ordered_subset_of | name | - | a $-binding holding a list (compileIsNotOrderedSubsetOf), never a column
            is_not_ordered_subset_of | value | - | a $-binding holding a list, never a column
            is_not_unique_relationship | name | N | B:is_not_unique_relationship(@S, #K)
            is_not_unique_relationship | value | N | B:is_not_unique_relationship(#S, @K)
            is_not_unique_relationship | keys | N | B:is_not_unique_relationship(#S, keys=[@K])
            is_not_unique_set | members | N | B:is_not_unique_set([@S, #K])
            is_not_unique_set | regex | - | static regex
            is_not_unique_set | keep_missings | - | static boolean
            is_not_unique_value | name | N | B:is_not_unique_value(@S)
            is_numeric | x | N | B:is_numeric(@N)
            is_ordered_subset_of | name | - | a $-binding holding a list, never a column
            is_ordered_subset_of | value | - | a $-binding holding a list, never a column
            is_partial_date | x | N | B:is_partial_date(@D)
            is_present | x | N | B:is_present(@S)
            is_sorted_by | target | N | B:not is_sorted_by(@L, by=[asc(#L)], within=#G)
            is_sorted_by | by | N | B:not is_sorted_by(#L, by=[asc(@L)], within=#G)
            is_sorted_by | within | N | B:not is_sorted_by(#L, by=[asc(#L)], within=@G)
            is_sorted_by | keep_missings | - | static boolean
            is_unique_relationship | name | N | B:is_unique_relationship(@S, #K)
            is_unique_relationship | value | N | B:is_unique_relationship(#S, @K)
            is_unique_relationship | keys | N | B:is_unique_relationship(#S, keys=[@K])
            is_unique_set | members | N | B:is_unique_set([@S, #K])
            is_unique_set | regex | - | static regex
            is_unique_set | keep_missings | - | static boolean
            is_unique_value | name | N | B:is_unique_value(@S)
            is_valid_date | x | N | B:is_valid_date(@D)
            is_valid_duration | x | N | B:is_valid_duration(@D)
            is_valid_name | x | N | B:is_valid_name(@S)
            is_valid_testcd | x | N | B:is_valid_testcd(@S)
            latest_date | a | N | V:latest_date(date(@D), date("2020-01-10"))
            latest_date | b | N | V:latest_date(date("2020-01-10"), date(@D))
            latest_possible | x | N | V:latest_possible(@D)
            len | x | N | V:len(@S)
            length | x | N | V:length(@S)
            library_available | - | - | nullary
            library_variable_code_pair_matches | name | - | varname() only (compileLibraryCodePairMatches refuses any other argument)
            lowcase | x | N | V:lowcase(@S)
            lower | x | N | V:lower(@S)
            lpad | x | N | V:lpad(@S, 5)
            lpad | width | N | V:lpad(#S, @L)
            lpad | fill | - | static string
            max | name | N | V:max(@N, group=[#G])
            max | domain | - | a dataset (plan Q1)
            max | filter | N | V:max(#N, group=[#G], filter=(@S == "a"))
            max | group | N | V:max(#N, group=[@G])
            max | keep_missings | - | static boolean
            max_date | name | N | V:max_date(@D, group=[#G])
            max_date | domain | - | a dataset (plan Q1)
            max_date | filter | N | V:max_date(#D, group=[#G], filter=(@S == "a"))
            max_date | group | N | V:max_date(#D, group=[@G])
            max_date | keep_missings | - | static boolean
            max_date | missing_values | - | static string
            max_value_length | name | N | V:max_value_length(@S) ; S:max_value_length("@S")
            min_date | name | N | V:min_date(@D, group=[#G])
            min_date | domain | - | a dataset (plan Q1)
            min_date | filter | N | V:min_date(#D, group=[#G], filter=(@S == "a"))
            min_date | group | N | V:min_date(#D, group=[@G])
            min_date | keep_missings | - | static boolean
            min_date | missing_values | - | static string
            minus | value | - | a $-binding holding a list, never a column
            minus | subtract | - | a $-binding holding a list, never a column
            month | x | N | V:month(@D)
            natural_key_variables | - | - | nullary
            non_empty | x | N | B:non_empty(@S)
            normalize_space | x | N | V:normalize_space(@S)
            not_contains_all | source | N | B:not_contains_all(@S, split_by(#S, ","))
            not_contains_all | required | N | X:list-valued — a $-binding or list accessor; no column channel in this slot
            not_contains_all | keys | N | X:list-valued — keyLiterals over a list-valued binding's key columns; needs a binding fixture (runner level)
            num | x | N | V:num(@L)
            prefix | x | N | V:prefix(@S, 1)
            prefix | n | N | V:prefix(#S, @L)
            prefix_matches | x | N | B:prefix_matches(@S, /^a/)
            prefix_matches | pattern | - | static regex
            prefix_matches | n | N | B:prefix_matches(#S, /^a/, @L)
            present | x | N | B:present(@S)
            present_on_multiple_rows_within | name | N | B:present_on_multiple_rows_within(@S, within=#G)
            present_on_multiple_rows_within | within | N | B:present_on_multiple_rows_within(#S, within=@G)
            present_on_multiple_rows_within | keep_missings | - | static boolean
            printf | format | - | static format string
            printf | args | N | V:printf("%s", @S)
            read_value | name | N | V:read_value(@S, domain="J", mode="FIRST", group=[#K])
            read_value | domain | - | a dataset (plan Q1)
            read_value | filter | N | V:read_value(#S, domain="J", mode="FIRST", group=[#K], filter=(@S == "a"))
            read_value | mode | - | static string
            read_value | group | N | V:read_value(#S, domain="J", mode="FIRST", group=[@K])
            read_value | keep_missings | - | static boolean
            record_count | domain | - | a dataset (plan Q1)
            record_count | filter | N | V:record_count(filter=(@S == "a"))
            record_count | group | N | V:record_count(group=[@G])
            record_count | keep_missings | - | static boolean
            record_count | regex | - | static regex
            referenced_dataset_variables | name | N | V:referenced_dataset_variables(@S)
            referenced_domain_class | name | N | X:provider — operandPlan channel; needs a LIBRARY fixture
            required_variables | - | - | nullary
            round | x | N | V:round(@N)
            row_max | name_pattern | N | X:selector — a regex over the primary's columns (D92b, plan Q2, ruled: closed by find_vars)
            shares_elements_with | name | - | a $-binding holding a list (compileSharesNoElementsWith), never a column
            shares_elements_with | value | - | a $-binding holding a list, never a column
            shares_elements_with | keys | N | X:list-valued — keyLiterals over a list-valued binding's key columns; needs a binding fixture (runner level)
            shares_no_elements_with | name | - | a $-binding holding a list, never a column
            shares_no_elements_with | value | - | a $-binding holding a list, never a column
            shares_no_elements_with | keys | N | X:list-valued — keyLiterals over a list-valued binding's key columns; needs a binding fixture (runner level)
            size | x | N | V:size(split_by(@S, ","))
            split_by | x | N | V:split_by(@S, ",")
            split_by | delimiter | - | static string
            split_sibling_length_mismatch | - | - | nullary
            standard_domains | - | - | nullary
            starts_with | x | N | B:starts_with(@S, "a")
            starts_with | value | N | B:starts_with("zz", @S)
            str | x | N | V:str(@N)
            study_domains | - | - | nullary
            substring | x | N | V:substring(@S, 1)
            substring | start | N | V:substring(#S, @L)
            substring | length | N | V:substring(#S, 1, @L)
            suffix | x | N | V:suffix(@S, 1)
            suffix | n | N | V:suffix(#S, @L)
            suffix_matches | x | N | B:suffix_matches(@S, /a$/)
            suffix_matches | pattern | - | static regex
            suffix_matches | n | N | B:suffix_matches(#S, /a$/, @L)
            time | x | N | V:time(@T)
            time_contains | outer | N | B:time_contains(time(@T), time(#T))
            time_contains | inner | N | B:time_contains(time(#T), time(@T))
            time_overlaps | a | N | B:time_overlaps(time(@T), time(#T))
            time_overlaps | b | N | B:time_overlaps(time(#T), time(@T))
            trim | x | N | V:trim(@S)
            tuple | c1 | N | V:tuple(@S, #K)
            tuple | c2 | N | V:tuple(#K, @S)
            tuple | columns | N | V:tuple(#K, #S, @L)
            upcase | x | N | V:upcase(@S)
            upper | x | N | V:upper(@S)
            valid_codelist_dates | ct_package_types | - | static strings
            valid_external_dictionary_code | name | N | X:provider — operandPlan channel; needs a dictionary fixture
            valid_external_dictionary_code | external_dictionary_type | - | static string
            valid_external_dictionary_code | dictionary_term_type | - | static string
            valid_external_dictionary_code | case_sensitive | - | static boolean
            valid_external_dictionary_code_term_pair | name | N | X:provider — operandPlan channel; needs a dictionary fixture
            valid_external_dictionary_code_term_pair | external_dictionary_term_variable | N | X:provider — operandPlan channel; needs a dictionary fixture
            valid_external_dictionary_code_term_pair | external_dictionary_type | - | static string
            valid_external_dictionary_code_term_pair | case_sensitive | - | static boolean
            valid_external_dictionary_hierarchy | name | N | X:provider — operandPlan channel; needs a dictionary fixture
            valid_external_dictionary_hierarchy | dictionary_parent | N | X:provider — operandPlan channel; needs a dictionary fixture
            valid_external_dictionary_hierarchy | external_dictionary_type | - | static string
            valid_external_dictionary_hierarchy | case_sensitive | - | static boolean
            valid_external_dictionary_value | name | N | X:provider — operandPlan channel; needs a dictionary fixture
            valid_external_dictionary_value | external_dictionary_type | - | static string
            valid_external_dictionary_value | dictionary_term_type | - | static string
            valid_external_dictionary_value | case_sensitive | - | static boolean
            value | - | - | nullary (the cursor variable's value)
            var_exists | name | N | B:var_exists(@S) ; S:var_exists("@S")
            var_is_null | name | N | B:var_is_null(@S) ; S:var_is_null("@S")
            var_not_exists | name | N | B:var_not_exists(@S) ; S:var_not_exists("@S")
            variable_count | name | N | X:selector — a template counted across the inventory (D92a, plan Q2)
            variable_count | name_pattern | N | X:selector — a regex over the primary's columns (D92b, plan Q2)
            variable_names | - | - | nullary
            varname | - | - | nullary (the cursor variable's name)
            vlm_codelist_coded_codes | name | N | X:provider — a VlmResolver (Define-XML); a qualified name is refused at load naming the bare one (N4, QualifiedNamePhase3FixesTest)
            vlm_codelist_coded_values | name | N | X:provider — a VlmResolver (Define-XML); qualified: refused at load (N4)
            vlm_codelist_extensible | name | N | X:provider — a VlmResolver (Define-XML); qualified: refused at load (N4)
            vlm_data_type | name | N | X:provider — a VlmResolver (Define-XML); qualified: refused at load (N4)
            vlm_decode_matches | name | N | X:provider — a VlmResolver (Define-XML); qualified: refused at load (N4)
            vlm_has_codelist | name | N | X:provider — a VlmResolver (Define-XML); qualified: refused at load (N4)
            vlm_length | name | N | X:provider — a VlmResolver (Define-XML); qualified: refused at load (N4)
            vlm_mandatory | name | N | X:provider — a VlmResolver (Define-XML); qualified: refused at load (N4)
            vlm_type_conforms | name | N | X:provider — a VlmResolver (Define-XML); qualified: refused at load (N4)
            vlm_value_length | name | N | X:provider — a VlmResolver (Define-XML); qualified: refused at load (N4)
            year | x | N | V:year(@D)
            var_name | name | N | V:var_name("@S", "DATA")
            var_name | level | - | static level
            var_label | name | N | V:var_label("@S", "DATA")
            var_label | level | - | static level
            var_label | dataset | N | V:var_label("#S", "DATA"@DS)
            var_type | name | N | V:var_type("@S", "DATA")
            var_type | level | - | static level
            var_type | dataset | N | V:var_type("#S", "DATA"@DS)
            var_length | name | N | V:var_length("@S", "DATA")
            var_length | level | - | static level
            var_length | dataset | N | V:var_length("#S", "DATA"@DS)
            var_format | name | N | V:var_format("@S", "DATA")
            var_format | level | - | static level
            var_format | dataset | N | V:var_format("#S", "DATA"@DS)
            var_role | name | N | X:provider — DEFINE / LIBRARY level only; the name is a string literal or varname()
            var_role | level | - | static level
            var_core | name | N | X:provider — DEFINE / LIBRARY level only
            var_core | level | - | static level
            var_mandatory | name | N | X:provider — DEFINE level only
            var_mandatory | level | - | static level
            var_codelist | name | N | X:provider — DEFINE / LIBRARY level only
            var_codelist | level | - | static level
            var_ordinal | name | N | V:var_ordinal("@S", "DATA")
            var_ordinal | level | - | static level
            var_ccode | name | N | X:provider — DEFINE / LIBRARY level only
            var_ccode | level | - | static level
            var_codelist_coded_codes | name | N | X:provider — DEFINE / LIBRARY level only
            var_codelist_coded_codes | level | - | static level
            var_codelist_coded_values | name | N | X:provider — DEFINE / LIBRARY level only
            var_codelist_coded_values | level | - | static level
            var_codelist_extensible | name | N | X:provider — LIBRARY level only
            var_codelist_extensible | level | - | static level
            var_codelist_extended_values | name | N | X:provider — DEFINE level only
            var_codelist_extended_values | level | - | static level
            var_origin_type | name | N | X:provider — DEFINE level only
            var_origin_type | level | - | static level
            var_has_comment | name | N | X:provider — DEFINE level only
            var_has_comment | level | - | static level
            var_has_codelist | name | N | X:provider — DEFINE level only
            var_has_codelist | level | - | static level
            var_has_method | name | N | X:provider — DEFINE level only
            var_has_method | level | - | static level
            var_external_dictionary | name | N | X:provider — DEFINE level only
            var_external_dictionary | level | - | static level
            var_external_dictionary_version | name | N | X:provider — DEFINE level only
            var_external_dictionary_version | level | - | static level
            ds_name | name | - | a dataset name
            ds_name | level | - | static level
            ds_label | name | - | a dataset name
            ds_label | level | - | static level
            ds_class | name | - | a dataset name
            ds_class | level | - | static level
            ds_domain | name | - | a dataset name
            ds_domain | level | - | static level
            ds_structure | name | - | a dataset name
            ds_structure | level | - | static level
            date_part | x | N | B:date_part(@D) == "2020-01-01"
            time_part | x | N | B:time_part(@T) == "10:00:00"
            op:EQ | left | N | B:@S == "a"
            op:EQ | right | N | B:"a" == @S
            op:NEQ | left | N | B:@S != "a"
            op:NEQ | right | N | B:"a" != @S
            op:LT | left | N | B:@N < 2
            op:LT | right | N | B:2 < @N
            op:GT | left | N | B:@N > 2
            op:GT | right | N | B:2 > @N
            op:LE | left | N | B:@N <= 2
            op:LE | right | N | B:2 <= @N
            op:GE | left | N | B:@N >= 2
            op:GE | right | N | B:2 >= @N
            op:MATCH | left | N | B:@S =~ /^a/
            op:MATCH | right | - | a regex literal
            op:NMATCH | left | N | B:@S !~ /^a/
            op:NMATCH | right | - | a regex literal
            op:IN | left | N | B:@S in ["a", "b"]
            op:IN | right | N | B:"a" in split_by(@S, ",")
            op:NOT_IN | left | N | B:@S not in ["a", "b"]
            op:NOT_IN | right | N | B:"a" not in split_by(@S, ",")
            op:ADD | left | N | V:@N + 1
            op:ADD | right | N | V:1 + @N
            op:SUB | left | N | V:@N - 1
            op:SUB | right | N | V:10 - @N
            op:MUL | left | N | V:@N * 2
            op:MUL | right | N | V:2 * @N
            op:DIV | left | N | V:@N / 2
            op:DIV | right | N | V:2 / @N
            """;

    /**
     * ⭐ The exemption ratchet — every (parameter, criterion) that differs TODAY, with its finding
     * id. {@code REFUSED} means the qualified spelling is refused at compile time with a message
     * naming the bare spelling (head-start N5: a loud refusal with a stated reason, recorded, not a
     * silent divergence). The harness asserts the measured red set EQUALS this table.
     */
    static final String EXPECTED_RED = """
            contains_all/source | REFUSED | N5
            not_contains_all/source | REFUSED | N5
            distinct/filter | REFUSED | N5
            distinct/group | REFUSED | N5
            distinct/name | REFUSED | N5
            empty_within_except_last_row/group | REFUSED | N5
            empty_within_except_last_row/name | REFUSED | N5
            empty_within_except_last_row/ordering | REFUSED | N5
            has_mixed_emptiness_within_group/group | REFUSED | N5
            has_mixed_emptiness_within_group/name | REFUSED | N5
            has_mixed_emptiness_within_group/qualifying_any_populated | REFUSED | N5
            has_multiple_values_for/key | REFUSED | N5
            has_multiple_values_for/name | REFUSED | N5
            has_multiple_values_for/within | REFUSED | N5
            has_next_corresponding_record/name | REFUSED | N5
            has_next_corresponding_record/ordering | REFUSED | N5
            has_next_corresponding_record/value | REFUSED | N5
            has_next_corresponding_record/within | REFUSED | N5
            has_same_values/name | REFUSED | N5
            inconsistent_enumerated_columns/name | REFUSED | N5
            is_inconsistent_across_dataset/key | REFUSED | N5
            is_inconsistent_across_dataset/keys | REFUSED | N5
            is_inconsistent_across_dataset/name | REFUSED | N5
            is_last_in_group/group | REFUSED | N5
            is_last_in_group/ordering | REFUSED | N5
            is_not_unique_relationship/keys | REFUSED | N5
            is_not_unique_relationship/name | REFUSED | N5
            is_not_unique_relationship/value | REFUSED | N5
            is_not_unique_set/members | REFUSED | N5
            is_not_unique_value/name | REFUSED | N5
            is_sorted_by/by | REFUSED | N5
            is_sorted_by/target | REFUSED | N5
            is_sorted_by/within | REFUSED | N5
            is_unique_relationship/keys | REFUSED | N5
            is_unique_relationship/name | REFUSED | N5
            is_unique_relationship/value | REFUSED | N5
            is_unique_set/members | REFUSED | N5
            is_unique_value/name | REFUSED | N5
            max/filter | REFUSED | N5
            max/group | REFUSED | N5
            max/name | REFUSED | N5
            max_date/filter | REFUSED | N5
            max_date/group | REFUSED | N5
            max_date/name | REFUSED | N5
            min_date/filter | REFUSED | N5
            min_date/group | REFUSED | N5
            min_date/name | REFUSED | N5
            present_on_multiple_rows_within/name | REFUSED | N5
            present_on_multiple_rows_within/within | REFUSED | N5
            read_value/filter | REFUSED | N5
            read_value/group | C3 | N6
            read_value/name | REFUSED | N5
            record_count/filter | REFUSED | N5
            record_count/group | REFUSED | N5
            var_format/name | REFUSED | N13
            var_label/name | REFUSED | N13
            var_length/name | REFUSED | N13
            var_name/name | REFUSED | N13
            var_ordinal/name | REFUSED | N13
            var_type/name | REFUSED | N13
            """;

    /**
     * C5q (the dataset QUALIFIER matched ignoring case) is owner-pending N2 (plan §2.4): every
     * probed parameter whose qualified spelling is not REFUSED is expected RED on C5q, EXCEPT the
     * rows below, which {@code PLAN-dynamic-column-functions} made case-insensitive. An exact set:
     * a site made insensitive by a fix reds the harness here until it is listed.
     */
    static final Set<String> C5Q_INSENSITIVE_TODAY = Set.of("colref/x", "find_vars/entry");

    /**
     * Probes that cannot SEE the qualifier: with the join unreachable the qualified reference reads
     * the absent default, and on this fixture that default answers exactly as the bare spelling
     * does (e.g. {@code is_numeric} is false of {@code ""} and of every S cell). They are neither
     * evidence for nor against N2; listed exactly so a sharpened probe is noticed.
     */
    static final Set<String> C5Q_BLIND_ON_THIS_FIXTURE = Set.of("does_not_equal_string_part/name",
            "does_not_equal_string_part/value", "interval_uncertainty_precision_mismatch/name",
            "invalid_duration/x", "is_valid_duration/x", "prefix_matches/n",
            "referenced_dataset_variables/name", "suffix_matches/n", "time_part/x",
            "var_format/dataset");

    // ------------------------------------------------------------------
    // Table model
    // ------------------------------------------------------------------

    /** One parameter row of {@link #TABLE}. */
    record Row(String fn, String param, boolean name, @Nullable String probe,
            @Nullable String stringTwin, @Nullable String reason)
    {

        String id()
        {
            return fn + "/" + param;
        }


        boolean probed()
        {
            return probe != null;
        }


        boolean bool()
        {
            return probe != null && probe.startsWith("B:");
        }


        String template()
        {
            return java.util.Objects.requireNonNull(probe).substring(2);
        }
    }

    static List<Row> rows()
    {
        List<Row> out = new ArrayList<>();
        for (String line : TABLE.split("\n", -1))
        {
            if (line.isBlank())
            {
                continue;
            }
            String[] f = line.split("\\|", -1);
            assertEquals(4, f.length, "a table row has four cells: " + line);
            String fn = f[0].trim();
            String param = f[1].trim();
            String cls = f[2].trim();
            String spec = f[3].trim();
            assertTrue("N".equals(cls) || "-".equals(cls), "class is N or -: " + line);
            if ("-".equals(cls))
            {
                out.add(new Row(fn, param, false, null, null, spec));
                continue;
            }
            if (spec.startsWith("X:"))
            {
                out.add(new Row(fn, param, true, null, null, spec.substring(2).trim()));
                continue;
            }
            String probe = spec;
            String twin = null;
            int semi = spec.indexOf(" ; S:");
            if (semi >= 0)
            {
                probe = spec.substring(0, semi).trim();
                twin = spec.substring(semi + " ; S:".length()).trim();
            }
            assertTrue(probe.startsWith("B:") || probe.startsWith("V:"),
                    "a probe is B: or V: " + line);
            out.add(new Row(fn, param, true, probe, twin, null));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Roster guards
    // ------------------------------------------------------------------


    /** The roster the table must equal: function name → declared parameter names, in order. */
    static SequencedMap<String, List<String>> roster()
    {
        SequencedMap<String, List<String>> out = new LinkedHashMap<>();
        for (FunctionDescriptor d : FunctionRegistry.all())
        {
            List<String> names = new ArrayList<>();
            for (Parameter p : d.parameters())
            {
                names.add(p.name());
            }
            out.put(d.name(), names.isEmpty() ? List.of("-") : names);
        }
        for (MetadataAttribute m : MetadataAttribute.values())
        {
            List<String> names = new ArrayList<>(List.of("name", "level"));
            if (m == MetadataAttribute.VAR_LABEL || m == MetadataAttribute.VAR_TYPE
                    || m == MetadataAttribute.VAR_LENGTH || m == MetadataAttribute.VAR_FORMAT)
            {
                // ExprCompiler.metadataPlan: dataset= is accepted for exactly these four, at the
                // DATA level (crossDatasetField).
                names.add("dataset");
            }
            out.put(m.functionName(), names);
        }
        for (String marker : ExprCompiler.comparisonMarkers())
        {
            out.putIfAbsent(marker, List.of("x"));
        }
        for (Expr.BinOp op : Expr.BinOp.values())
        {
            out.put("op:" + op.name(), List.of("left", "right"));
        }
        return out;
    }


    @Test
    void tableEqualsTheDescriptorRoster()
    {
        SequencedMap<String, List<String>> expected = roster();
        SequencedMap<String, List<String>> actual = new LinkedHashMap<>();
        for (Row r : rows())
        {
            actual.computeIfAbsent(r.fn(), _ -> new ArrayList<>()).add(r.param());
        }
        assertEquals(new TreeSet<>(expected.keySet()), new TreeSet<>(actual.keySet()),
                "the table's function set must equal registry ∪ MetadataAttribute ∪ markers ∪"
                        + " operators");
        for (Map.Entry<String, List<String>> e : expected.entrySet())
        {
            assertEquals(e.getValue(), actual.get(e.getKey()),
                    "declared parameters of " + e.getKey() + ", in order");
        }
    }


    /** The population pins (phase 0 census at corej 53ddce0) — an edit that empties a side reds. */
    @Test
    void populationIsPinned()
    {
        assertEquals(164, FunctionRegistry.all().size(), "registered descriptors");
        assertEquals(281,
                FunctionRegistry.all().stream().mapToInt(d -> d.parameters().size()).sum(),
                "declared parameters");
        assertEquals(26, MetadataAttribute.values().length, "metadata accessors");
        assertEquals(Set.of("date", "date_part", "num", "time", "time_part"),
                new TreeSet<>(ExprCompiler.comparisonMarkers()), "comparison markers");
        assertEquals(14, Expr.BinOp.values().length, "operators");
        List<Row> rows = rows();
        long probed = rows.stream().filter(Row::probed).count();
        long nameClass = rows.stream().filter(Row::name).count();
        assertEquals(252, nameClass, "name-bearing parameters in the hand table");
        assertEquals(205, probed, "probed parameters in the hand table");
        assertEquals(nameClass - probed, rows.stream().filter(r -> r.name() && !r.probed()).count(),
                "every name-bearing row is probed or carries an X: reason");
    }

    // ------------------------------------------------------------------
    // Fixtures: the primary P and the join J carry the IDENTICAL column set and data.
    // ------------------------------------------------------------------

    /** Row 3 (0-based) is the row the unmatched variant drops from J. */
    private static final int UNMATCHED_ROW = 3;

    private static final Map<Character, String> COLUMN = Map.of('S', "S", 'N', "N", 'L', "L", 'D',
            "D", 'T', "T", 'K', "K", 'G', "G");

    private static final Map<Character, DataValueType> TYPE = Map.of('S', DataValueType.STRING, 'N',
            DataValueType.DOUBLE, 'L', DataValueType.LONG, 'D', DataValueType.STRING, 'T',
            DataValueType.STRING, 'K', DataValueType.STRING, 'G', DataValueType.STRING);

    @SuppressWarnings("ArrayRecordComponent")
    private record Col(String name, DataValueType type, Object[] values)
    {
    }

    private static Col col(char letter, Object... values)
    {
        return new Col(COLUMN.get(letter), TYPE.get(letter), values);
    }


    /**
     * The six-row fixture; {@code except} (0 = none) drops that column, {@code dflt} blanks row 3.
     */
    private static List<Col> columns(char except, char dflt)
    {
        List<Col> all = List.of(col('K', "k1", "k2", "k3", "k4", "k5", "k6"),
                col('S', "a", "b", "", "A", "b", "c"),
                col('N', 1.0, 2.5, MissingValue.MIS_UNKNOWN.asDouble(),
                        MissingValue.MIS_A.asDouble(), 1.0, 3.0),
                col('L', 1L, 2L, null, 3L, 1L, 2L),
                col('D', "2020-01-01", "2020-02-15", "", "2020-01", "2021-13-40",
                        "2020-01-01T10:00:00"),
                col('T', "10:00:00", "11:30", "", "25:00", "10:00:00", "09:15:30"),
                col('G', "g1", "g1", "g2", "g2", "g3", "g3"));
        List<Col> out = new ArrayList<>();
        for (Col c : all)
        {
            if (c.name().charAt(0) == except)
            {
                continue;
            }
            if (c.name().charAt(0) == dflt)
            {
                Object[] v = c.values().clone();
                // The cell an unmatched join row reads (DatasetLookup.lookupValue →
                // DataValueSupport.defaultForType): "" for a character column, MIS for a numeric.
                v[UNMATCHED_ROW] = DataValueSupport.defaultForType(c.type()).getValue();
                out.add(new Col(c.name(), c.type(), v));
                continue;
            }
            out.add(c);
        }
        return out;
    }


    private static IDataTable table(String name, List<Col> cols, boolean dropUnmatchedRow)
    {
        return table(name, cols, dropUnmatchedRow, false);
    }


    /** {@code lowerNames}: the DATA spells its column names in lower case (C5c). */
    private static IDataTable table(String name, List<Col> cols, boolean dropUnmatchedRow,
            boolean lowerNames)
    {
        int rc = cols.get(0).values().length - (dropUnmatchedRow ? 1 : 0);
        CachedDataTableColumn[] cc = new CachedDataTableColumn[cols.size()];
        DataTableColumnMeta[] mm = new DataTableColumnMeta[cols.size()];
        for (int i = 0; i < cols.size(); i++)
        {
            Col c = cols.get(i);
            cc[i] = new CachedDataTableColumn(i, c.type());
            String colName = lowerNames ? c.name().toLowerCase(Locale.ROOT) : c.name();
            mm[i] = DataTableColumnMeta.builder().index(i).name(colName).label(colName)
                    .type(c.type()).length(8).build();
            for (int r = 0; r < c.values().length; r++)
            {
                if (dropUnmatchedRow && r == UNMATCHED_ROW)
                {
                    continue;
                }
                cc[i].addElement(c.values()[r]);
            }
            cc[i].complete();
        }
        return new ColumnCachedDataTable(DataTableMeta.builder().name(name).label(name).columns(mm)
                .rowCount(rc).totalRowCount(rc).build(), cc);
    }


    /** P, or P without column {@code except}, or P with column {@code dflt} defaulted at row 3. */
    private static IDataTable primary(char except, char dflt)
    {
        return table("P", columns(except, dflt), false);
    }


    private static IDataTable joined(char except, boolean dropUnmatchedRow)
    {
        return table("J", columns(except, '\0'), dropUnmatchedRow);
    }


    /**
     * The context of the UNQUALIFIED side: the study inventory holds P and J (an exact-case
     * resolver, so {@code domain="J"} resolves on both sides alike), and no join.
     */
    private static EvaluationContext ctx(IDataTable p, IDataTable j, String exprText)
    {
        return ctx(p, j, false, exprText, "J");
    }


    /** The context of the QUALIFIED side: the inventory plus the join over K. */
    private static EvaluationContext joinedCtx(IDataTable p, IDataTable j, String exprText)
    {
        return ctx(p, j, true, exprText, "J");
    }


    /** {@code joinName}: the name the join AND the inventory register J under. */
    private static EvaluationContext ctx(IDataTable p, IDataTable j, boolean joinIt,
            String exprText, String joinName)
    {
        Set<String> numeric = TypeExpectations.of(List.of(CheckExpressionParser.parse(exprText)))
                .numericDefaultColumns();
        Map<String, IDataTable> byName = new LinkedHashMap<>();
        byName.put("P", p);
        byName.put(joinName, j);
        DatasetResolver resolver = byName::get;
        Map<String, JoinLookup> joins = new LinkedHashMap<>();
        if (joinIt)
        {
            DatasetLookup lk = DatasetLookup.build(joinName, j,
                    List.of(j.getMetaData().getColumn(0).getName()));
            assertNotNull(lk, "the J join over K");
            joins.put(joinName, lk);
        }
        return EvaluationContext.builder().table(p).datasetResolver(resolver).joinedDatasets(joins)
                .numericExpectedColumns(numeric).ruleId("QNU").domainName("P").build();
    }

    // ------------------------------------------------------------------
    // Outcomes
    // ------------------------------------------------------------------

    /** What a probe answered: the verdict bits, the rendered cells, or the error. */
    private record Outcome(String kind, String detail)
    {

        boolean error()
        {
            return "ERROR".equals(kind);
        }


        /** The qualified spelling refused at compile time with a message naming the bare form. */
        boolean refused()
        {
            String d = detail.toLowerCase(Locale.ROOT);
            return error() && (d.contains("written bare") || d.contains("plain column")
                    || d.contains("column reference") || d.contains("write it bare")
                    || d.contains("requires domain=") || d.contains("is not a bare column"));
        }
    }

    private static Outcome evaluate(String exprText, boolean bool, EvaluationContext c)
    {
        try
        {
            Expr expr = CheckExpressionParser.parse(exprText);
            if (bool)
            {
                BitSet bits = NativeExprEvaluator.evaluate(expr, c);
                return new Outcome("BITS", bits.toString());
            }
            Vector v = NativeExprEvaluator.bindingProgram(expr).evaluate(EvalRun.fullRange(c));
            StringBuilder sb = new StringBuilder();
            sb.append("declared=").append(v.declaredType());
            for (int r = 0; r < c.rowCount(); r++)
            {
                TypedValue tv = v.value(r);
                sb.append(" [").append(r).append(':').append(tv.type()).append('|')
                        .append(tv.missing()).append('|')
                        .append(tv.missing() != null ? "" : String.valueOf(tv.resolved()))
                        .append(']');
            }
            return new Outcome("VALUES", sb.toString());
        }
        catch (RuntimeException ex)
        {
            return new Outcome("ERROR", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }


    /** The probed letter of a template ({@code @S} → 'S'); {@code @DS} rows probe the kwarg. */
    private static char probedLetter(String template)
    {
        int at = template.indexOf('@');
        assertTrue(at >= 0, "a probe names its probed reference with @: " + template);
        return template.startsWith("@DS", at) ? 'S' : template.charAt(at + 1);
    }


    /** The template with the probed reference spelled {@code probed} and the others bare. */
    private static String spell(String template, String probed, String datasetKwarg)
    {
        String out = template.replace("@DS", datasetKwarg);
        for (Map.Entry<Character, String> e : COLUMN.entrySet())
        {
            out = out.replace("#" + e.getKey(), e.getValue());
            out = out.replace("@" + e.getKey(), probed);
        }
        return out;
    }


    private static Rule ruleDeclaring(String joinName)
    {
        Rule rule = new Rule();
        MatchDataset md = new MatchDataset();
        md.setName(joinName);
        md.setKeys(List.of("K"));
        rule.setMatchDatasets(List.of(md));
        return rule;
    }


    private static String stageAKinds(String exprText, boolean bool)
    {
        try
        {
            Expr expr = CheckExpressionParser
                    .parse(bool ? exprText : "not empty(" + exprText + ")");
            SequencedMap<Severity, Expr> levels = new LinkedHashMap<>();
            levels.put(Severity.ERROR, expr);
            List<String> kinds = new ArrayList<>();
            for (StageAFinding f : StageAChecker.check(ruleDeclaring("J"), levels).findings())
            {
                kinds.add(f.kind().name());
            }
            kinds.sort(null);
            return kinds.toString();
        }
        catch (RuntimeException ex)
        {
            return "ERROR " + ex.getClass().getSimpleName();
        }
    }


    private static String level(String exprText)
    {
        try
        {
            return String.valueOf(DomainScan.infer(CheckExpressionParser.parse(exprText),
                    BindingDomains.forRule(ruleDeclaring("J"))));
        }
        catch (RuntimeException ex)
        {
            return "ERROR " + ex.getClass().getSimpleName();
        }
    }

    // ------------------------------------------------------------------
    // The differential
    // ------------------------------------------------------------------


    /** The red criteria of one row, in criterion order; {@code REFUSED} alone when N5 applies. */
    static SequencedMap<String, String> redCriteria(Row row)
    {
        SequencedMap<String, String> red = new LinkedHashMap<>();
        String t = row.template();
        boolean bool = row.bool();
        char letter = probedLetter(t);
        boolean kwarg = t.contains("@DS");
        String bareName = COLUMN.get(letter);
        String dottedName = kwarg ? bareName : "J." + bareName;
        String bare = spell(t, bareName, "");
        String dotted = spell(t, dottedName, ", dataset=\"J\"");

        IDataTable p = primary('\0', '\0');
        IDataTable j = joined('\0', false);
        Outcome bareC1 = evaluate(bare, bool, ctx(p, j, bare));
        if (bareC1.error())
        {
            red.put("PROBE", "the unqualified spelling fails: " + bareC1.detail());
            return red;
        }
        Outcome dottedC1 = evaluate(dotted, bool, joinedCtx(p, j, dotted));
        if (dottedC1.refused())
        {
            red.put("REFUSED", dottedC1.detail());
            return red;
        }
        compare(red, "C1", bareC1, dottedC1);
        // C2 — the unmatched row of the join reads the column's type default (D72a-1): the
        // primary carries that default at row 3 on BOTH sides, the join lacks row 3's key.
        IDataTable pDefaulted = primary('\0', letter);
        IDataTable jUnmatched = joined('\0', true);
        compare(red, "C2", evaluate(bare, bool, ctx(pDefaulted, jUnmatched, bare)),
                evaluate(dotted, bool, joinedCtx(pDefaulted, jUnmatched, dotted)));
        // C3 — the column absent on both sides reads the authored default (D76 / NF §3a).
        IDataTable pAbsent = primary(letter, '\0');
        IDataTable jAbsent = joined(letter, false);
        compare(red, "C3", evaluate(bare, bool, ctx(pAbsent, jAbsent, bare)),
                evaluate(dotted, bool, joinedCtx(pAbsent, jAbsent, dotted)));
        // C4 — the bare name never reaches the join (UVC unqualified): the column absent from
        // the primary and present in the joined J reads the same with and without the join.
        compare(red, "C4", evaluate(bare, bool, ctx(pAbsent, j, bare)),
                evaluate(bare, bool, joinedCtx(pAbsent, j, bare)));
        // C5c — the column part matched ignoring case (CIT §1): the DATA spells its column
        // names in lower case, both spellings are written as authored. (A lower-case spelling
        // in the rule is refused by the parser — OperandClassifier — so the case can only come
        // from the data side.)
        IDataTable pLower = table("P", columns('\0', '\0'), false, true);
        IDataTable jLower = table("J", columns('\0', '\0'), false, true);
        compare(red, "C5c", evaluate(bare, bool, ctx(pLower, jLower, bare)),
                evaluate(dotted, bool, joinedCtx(pLower, jLower, dotted)));
        // C5q — the QUALIFIER matched ignoring case (owner-pending N2, plan §2.4): the join is
        // declared / registered under the lower-case name "j", the probe spells "J.".
        compare(red, "C5q", bareC1, evaluate(dotted, bool, ctx(p, j, true, dotted, "j")));
        // C7 — the level calculus sees both spellings alike (D32a).
        if (!level(bare).equals(level(dotted)))
        {
            red.put("C7", level(bare) + " vs " + level(dotted));
        }
        // C8 — Stage A files the same finding kinds for both spellings (D97b).
        String kindsBare = stageAKinds(bare, bool);
        String kindsDotted = stageAKinds(dotted, bool);
        if (!kindsBare.equals(kindsDotted))
        {
            red.put("C8", kindsBare + " vs " + kindsDotted);
        }
        // C9 — the string form resolves like the reference form (UNIFORMITY).
        if (row.stringTwin() != null)
        {
            String twinBare = spell(row.stringTwin(), bareName, "");
            String twinDotted = spell(row.stringTwin(), dottedName, "");
            compare(red, "C9", bareC1, evaluate(twinBare, bool, ctx(p, j, twinBare)));
            compare(red, "C9", dottedC1, evaluate(twinDotted, bool, joinedCtx(p, j, twinDotted)));
        }
        return red;
    }


    private static void compare(Map<String, String> red, String criterion, Outcome bare,
            Outcome dotted)
    {
        if (!bare.equals(dotted))
        {
            red.putIfAbsent(criterion, bare.detail() + "  <>  " + dotted.detail());
        }
    }


    /** The measured red set over the table, as {@code id → criterion → detail}. */
    static SequencedMap<String, SequencedMap<String, String>> measure(List<Row> rows)
    {
        SequencedMap<String, SequencedMap<String, String>> out = new TreeMap<>();
        for (Row row : rows)
        {
            if (!row.probed())
            {
                continue;
            }
            SequencedMap<String, String> red = redCriteria(row);
            if (!red.isEmpty())
            {
                out.put(row.id(), red);
            }
        }
        return out;
    }


    /** {@link #EXPECTED_RED} parsed: {@code id → (criteria, finding)}. */
    static SequencedMap<String, String> expectedRed()
    {
        SequencedMap<String, String> out = new TreeMap<>();
        for (String line : EXPECTED_RED.split("\n", -1))
        {
            if (line.isBlank())
            {
                continue;
            }
            String[] f = line.split("\\|", -1);
            assertEquals(3, f.length, "an exemption row is id | criteria | finding: " + line);
            String prev = out.put(f[0].trim(), f[1].trim() + " → " + f[2].trim());
            assertNull(prev, "one exemption row per parameter: " + line);
        }
        return out;
    }


    @Test
    void everyProbedParameterIsUniformExceptTheRecordedRows()
    {
        List<Row> rows = rows();
        SequencedMap<String, SequencedMap<String, String>> measured = measure(rows);
        // The qualifier-case criterion is owner-pending as a WHOLE (N2): it is asserted
        // separately below, so it is lifted out of the per-row comparison here.
        SequencedMap<String, String> actual = new TreeMap<>();
        StringBuilder details = new StringBuilder();
        for (Map.Entry<String, SequencedMap<String, String>> e : measured.entrySet())
        {
            SequencedMap<String, String> red = new LinkedHashMap<>(e.getValue());
            red.remove("C5q");
            if (red.isEmpty())
            {
                continue;
            }
            assertFalse(red.containsKey("PROBE"), e.getKey() + ": " + red.get("PROBE"));
            actual.put(e.getKey(), String.join(",", red.keySet()));
            details.append('\n').append(e.getKey()).append(" → ").append(red);
        }
        SequencedMap<String, String> expected = new TreeMap<>();
        for (Map.Entry<String, String> e : expectedRed().entrySet())
        {
            expected.put(e.getKey(), e.getValue().substring(0, e.getValue().indexOf(" → ")));
        }
        assertEquals(expected, actual,
                "the measured red set must equal EXPECTED_RED exactly"
                        + " (a fixed row is removed, a new divergence is recorded with its finding)"
                        + details);
    }


    @Test
    void theQualifierCaseRatchetIsExact()
    {
        List<Row> rows = rows();
        SequencedMap<String, SequencedMap<String, String>> measured = measure(rows);
        Set<String> redC5q = new TreeSet<>();
        Set<String> expectedC5q = new TreeSet<>();
        for (Row row : rows)
        {
            if (!row.probed())
            {
                continue;
            }
            SequencedMap<String, String> red = measured.getOrDefault(row.id(),
                    new LinkedHashMap<>());
            if (red.containsKey("REFUSED") || red.containsKey("PROBE"))
            {
                continue;
            }
            if (red.containsKey("C5q"))
            {
                redC5q.add(row.id());
            }
            if (!C5Q_INSENSITIVE_TODAY.contains(row.id())
                    && !C5Q_BLIND_ON_THIS_FIXTURE.contains(row.id()))
            {
                expectedC5q.add(row.id());
            }
        }
        assertEquals(expectedC5q, redC5q, "C5q (qualifier case) reds on every probed row except"
                + " C5Q_INSENSITIVE_TODAY and C5Q_BLIND_ON_THIS_FIXTURE — owner-pending N2; a"
                + " site made case-insensitive is listed in the first, a listed site that"
                + " stopped being insensitive is removed");
        assertTrue(expectedC5q.size() > 100, "the N2 population is not vacuous");
        assertTrue(redC5q.stream().noneMatch(C5Q_INSENSITIVE_TODAY::contains),
                "a site listed as case-insensitive must not red C5q");
    }

    // ------------------------------------------------------------------
    // Permanent controls — the detector must be shown to fire.
    // ------------------------------------------------------------------

    private static final String STUB = "__primary_only_probe__";

    /** A function that resolves its argument's AUTHORED name against the primary table. */
    private static FunctionDescriptor primaryOnlyStub()
    {
        EvalFunction fn = (run, args) ->
        {
            Vector arg = args.get(0);
            String name = arg == null ? null : arg.gatedName();
            if (name == null)
            {
                return ConstVector.of("");
            }
            DataTableMeta meta = run.ctx().getTable().getMetaData();
            int idx = meta.getColumnIndex(name);
            if (idx < 0)
            {
                return ConstVector.of("");
            }
            return new ColumnVector(name, run.ctx().getTable().getColumn(idx),
                    meta.getColumn(idx).getType());
        };
        return new FunctionDescriptor(STUB,
                List.of(Parameter.required("x",
                        net.cumba.corej.core.expr.typed.ExprType.Unknown.UNKNOWN)),
                FunctionKind.VALUE, fn);
    }


    @Test
    void aPrimaryOnlyStubRedsTheDifferential()
    {
        FunctionRegistry.register(primaryOnlyStub());
        try
        {
            Row stub = new Row(STUB, "x", true, "V:" + STUB + "(@S)", null, null);
            SequencedMap<String, String> red = redCriteria(stub);
            assertTrue(red.containsKey("C1"), "a primary-only function must red C1: " + red);
            assertTrue(red.containsKey("C2"), "… and C2: " + red);
            assertFalse(red.containsKey("PROBE"), red.toString());
            // The control is a REAL divergence in the detector's own terms: the measured set
            // over the table plus the stub differs from the table alone by exactly this row.
            List<Row> withStub = new ArrayList<>(rows());
            withStub.add(stub);
            assertTrue(measure(withStub).containsKey(STUB + "/x"));
        }
        finally
        {
            FunctionRegistry.unregister(STUB);
        }
    }


    @Test
    void anUnlistedRegistrationRedsTheRoster()
    {
        FunctionRegistry.register(primaryOnlyStub());
        try
        {
            AssertionError err = assertThrows(AssertionError.class,
                    QualifiedNameUniformityTest::assertTableEqualsRoster);
            assertTrue(err.getMessage().contains(STUB), err.getMessage());
        }
        finally
        {
            FunctionRegistry.unregister(STUB);
        }
        assertTableEqualsRoster();
    }


    private static void assertTableEqualsRoster()
    {
        Set<String> tableFns = new LinkedHashSet<>();
        for (Row r : rows())
        {
            tableFns.add(r.fn());
        }
        assertEquals(new TreeSet<>(roster().keySet()), new TreeSet<>(tableFns));
    }


    /** The bare side of every probe compiles and evaluates — a probe defect is never a pass. */
    @Test
    void everyProbeIsValidOnTheUnqualifiedSide()
    {
        IDataTable p = primary('\0', '\0');
        List<String> invalid = new ArrayList<>();
        int probed = 0;
        for (Row row : rows())
        {
            if (!row.probed())
            {
                continue;
            }
            probed++;
            String bare = spell(row.template(), COLUMN.get(probedLetter(row.template())), "");
            Outcome o = evaluate(bare, row.bool(), ctx(p, joined('\0', false), bare));
            if (o.error())
            {
                invalid.add(row.id() + ": " + o.detail());
            }
        }
        assertEquals(List.of(), invalid);
        assertEquals(205, probed);
    }
}
