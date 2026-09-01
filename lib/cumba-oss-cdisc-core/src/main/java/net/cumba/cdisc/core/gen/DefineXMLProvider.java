package net.cumba.cdisc.core.gen;

import java.util.List;
import java.util.Map;
import net.cumba.cdisc.define.MetaDataVersion;
import org.jspecify.annotations.Nullable;

/**
 * Provides access to Define-XML metadata for a submission. Optional — when not configured,
 * Define-XML rules are silently skipped.
 * <p>
 * Supports both Define-XML v2.0 and v2.1. Version differences are handled by the implementation.
 * This is a plain interface — no service file resolution. Implementations are created by the caller
 * (e.g., an adapter mapping from {@code net.cumba.cdisc.define} structures).
 * </p>
 */
public interface DefineXMLProvider
{

    /**
     * Returns dataset-level metadata from Define-XML.
     * <p>
     * Expected keys: {@code name}, {@code label}, {@code structure}, {@code class},
     * {@code purpose}, {@code repeating}, {@code isReferenceData}, {@code comment}.
     * </p>
     *
     * @param datasetName
     *            the dataset name (e.g., "DM", "ADSL")
     * @return metadata as key-value pairs, or empty map if not defined
     */
    Map<String, String> getDatasetMetadata(String datasetName);


    /**
     * Fix #119: the dataset's declared {@code def:Class} value (2.1 element form preferred over the
     * 2.0 attribute), verbatim — the "declared" channel of the {@code Scope.Data_Structures}
     * determination under {@code corej.defineFirst}.
     *
     * @param datasetName
     *            the dataset name
     * @return the declared class, or {@code null} when the define declares none
     */
    default @Nullable String getDeclaredClass(String datasetName)
    {
        return null;
    }


    /**
     * Fix #119: the dataset's declared Define-XML 2.1 {@code <def:SubClass>} names, in declaration
     * order; empty when none are declared.
     *
     * @param datasetName
     *            the dataset name
     * @return the declared subclass names
     */
    default List<String> getDeclaredSubClasses(String datasetName)
    {
        return List.of();
    }


    /**
     * Returns variable definitions for a dataset from Define-XML.
     * <p>
     * Each entry contains keys: {@code name}, {@code label}, {@code dataType}, {@code length},
     * {@code significantDigits}, {@code origin}, {@code originType}, {@code role}, {@code codelist}
     * (OID), {@code codelistName}, {@code mandatory}, {@code orderNumber}, {@code ccode} (the
     * variable's codelist NCI {@code ExtCodeID}, or empty when none), and
     * {@code codelist_coded_codes} (a JSON array string of the codelist's coded codes, or
     * {@code "[]"} when none). The last two mirror the Python reference engine's
     * {@code define_variable_ccode} / {@code define_variable_codelist_coded_codes} and back
     * CORE-000929. EC-19 adds {@code has_codelist} ({@code "true"}/{@code "false"} — whether the
     * {@code ItemDef} binds a {@code CodeListRef}) and {@code codelist_coded_values} (a JSON array
     * string of the codelist's enumerated submission values, or {@code "[]"} when none), mirroring
     * the Python {@code define_variable_has_codelist} /
     * {@code define_variable_codelist_coded_values} columns and backing the
     * {@code Value Check against Define XML Variable} rule type (SD0037).
     * </p>
     *
     * @param datasetName
     *            the dataset name
     * @return list of variable metadata maps, or empty list if not defined
     */
    List<Map<String, String>> getVariables(String datasetName);


    /**
     * Returns value-level metadata for a variable in a dataset.
     * <p>
     * Each entry describes metadata for a specific parameter/value subset. Keys:
     * {@code whereClauseOID}, {@code variable}, {@code label}, {@code dataType}, {@code length},
     * {@code codelist}, {@code origin}, {@code significantDigits}.
     * </p>
     * <p>
     * The where-clause conditions are retrieved separately via
     * {@link #getWhereClauseConditions(String)}.
     * </p>
     *
     * @param datasetName
     *            the dataset name
     * @param variableName
     *            the variable name (e.g., "AVAL", "AVALC")
     * @return list of value-level metadata entries, or empty list
     */
    List<Map<String, String>> getValueLevelMetadata(String datasetName, String variableName);


    /**
     * Returns the conditions of a where-clause as a list of range checks. Multiple entries within
     * the returned list are AND-ed.
     * <p>
     * Each entry contains keys: {@code variable} (the ItemOID resolved to a variable name),
     * {@code comparator} (EQ, NE, IN, NOTIN, LT, LE, GT, GE), {@code values} (comma-separated check
     * values).
     * </p>
     *
     * @param whereClauseOID
     *            the where-clause OID
     * @return list of range check conditions, or empty list
     */
    List<Map<String, String>> getWhereClauseConditions(String whereClauseOID);


    /**
     * Returns codelist terms defined in Define-XML.
     * <p>
     * Each entry contains keys: {@code codedValue}, {@code decode}, {@code extensible}
     * (true/false).
     * </p>
     *
     * @param codelistOID
     *            the codelist OID from Define-XML
     * @return list of term entries, or empty list
     */
    List<Map<String, String>> getCodelistTerms(String codelistOID);


    /**
     * Returns the list of all dataset names defined in Define-XML.
     */
    List<String> getDatasetNames();


    /**
     * Returns the key variables for a dataset as defined in Define-XML.
     *
     * @param datasetName
     *            the dataset name
     * @return list of key variable names, or empty list
     */
    List<String> getKeyVariables(String datasetName);


    /**
     * The active {@code MetaDataVersion} of the parsed Define-XML, or {@code null} when this
     * provider is not backed by a parsed ODM model (a datatable- or stub-backed provider).
     * <p>
     * Used to build the per-record value-level metadata resolver
     * ({@code net.cumba.cdisc.core.metadata.VlmResolver}), which reads the
     * {@code ValueListDef}/{@code WhereClauseDef} structures the flat accessors above do not
     * expose.
     * </p>
     *
     * @return the parsed {@code MetaDataVersion}, or {@code null}
     */
    default @Nullable MetaDataVersion metaDataVersion()
    {
        return null;
    }

}
