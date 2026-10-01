package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Per-variable metadata result of the {@code cross_dataset_variable_metadata(name, domain=)}
 * function ({@link ScalarMetadataFunctions}; an operation until wave 4b), which carries it as its
 * one dataset-level value (D-W4b-1), and the builder behind the {@code var_*(…, dataset=)}
 * accessors.
 * <p>
 * Where a per-row compiled binding resolves per row, this resolves per-variable. A
 * {@code $variable} holding a {@code VariableMetadataResult} is variable-level: constant across
 * rows but varying per column.
 * {@link net.cumba.corej.core.expr.eval.BroadcastFold#operationRefsSafe
 * BroadcastFold.operationRefsSafe} enforces that at runtime — such a reference is broadcast-safe
 * only on the per-variable loop, which projects it onto the per-column cursor.
 * <p>
 * The map keys are variable names (e.g., "ITTFL", "TRTDUR"), and values are the requested metadata
 * field (e.g., the ADSL label for that variable).
 */
public final class VariableMetadataResult
{

    /**
     * Keyed IGNORING letter case ({@code PLAN-qualified-name-uniformity-review} N14, register
     * {@code CIT §1}): a variable name matches a column name whatever its case, as every other
     * column lookup does — the {@code var_*(…, dataset=)} read used to miss a lower-case {@code s}
     * that the bare {@code var_type("S", "DATA")} found. The first spelling of a name wins, which
     * keeps {@link #buildFromAllDatasets}' first-match order across case variants.
     */
    private final Map<String, String> variableToValue;

    public VariableMetadataResult(Map<String, String> variableToValue)
    {
        Map<String, String> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (variableToValue != null)
        {
            variableToValue.forEach(byName::putIfAbsent);
        }
        this.variableToValue = byName;
    }


    /**
     * Returns the metadata value for the given variable name, matched ignoring case, or
     * {@code null} if the variable is not present in the source dataset.
     */
    public @Nullable String getForVariable(@Nullable String variableName)
    {
        return variableName == null ? null : variableToValue.get(variableName);
    }


    /**
     * Builds a {@code VariableMetadataResult} from a dataset's column metadata.
     *
     * @param resolver
     *            the dataset resolver
     * @param domainName
     *            the target dataset name (e.g., "ADSL"), or {@code "*"} to scan all datasets
     * @param metadataField
     *            the metadata field to extract: "label", "data_type", "length", "format"
     * @param primaryDatasetName
     *            the dataset currently under validation; for the {@code "*"} wildcard scan it is
     *            excluded so a variable is never compared against its own label. Ignored for a
     *            concrete {@code domainName}. {@code null} disables the exclusion.
     * @return the result, or an empty result if the dataset is not available
     */
    public static VariableMetadataResult build(DatasetResolver resolver,
            @Nullable String domainName, @Nullable String metadataField,
            @Nullable String primaryDatasetName)
    {
        if (resolver == null || domainName == null)
        {
            return new VariableMetadataResult(Map.of());
        }

        // Wildcard domain: search all available datasets for each variable
        if ("*".equals(domainName))
        {
            return buildFromAllDatasets(resolver, metadataField, primaryDatasetName);
        }

        net.cumba.datatable.IDataTable table = resolver.resolve(domainName);
        if (table == null)
        {
            return new VariableMetadataResult(Map.of());
        }
        return buildFromTable(table, metadataField);
    }


    /**
     * Builds a result by searching all available datasets. For each variable, the first dataset
     * that contains it provides the metadata value. This supports rules that compare ADaM variables
     * against their SDTM originals without knowing the specific source domain.
     * <p>
     * Datasets are visited shortest-name-first, then alphabetically. SDTM domain names (e.g.
     * {@code AE}, {@code VS}, {@code DM}) are shorter than their ADaM counterparts ({@code ADAE},
     * {@code ADVS}, {@code ADSL}), so this deterministic order makes the SDTM copy of a shared
     * variable win the "first match" over any ADaM dataset that also carries it. The
     * {@code primaryDatasetName} (the dataset under validation) is skipped so a variable is never
     * compared against its own label.
     */
    private static VariableMetadataResult buildFromAllDatasets(DatasetResolver resolver,
            @Nullable String metadataField, @Nullable String primaryDatasetName)
    {
        if (!(resolver instanceof DatasetResolver.WithInventory inventory))
        {
            return new VariableMetadataResult(Map.of());
        }
        List<String> ordered = new ArrayList<>(inventory.availableDatasets());
        ordered.sort(
                Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
        Map<String, String> result = new LinkedHashMap<>();
        for (String dsName : ordered)
        {
            if (primaryDatasetName != null && primaryDatasetName.equalsIgnoreCase(dsName))
            {
                continue; // never compare a variable against its own dataset
            }
            net.cumba.datatable.IDataTable table = resolver.resolve(dsName);
            if (table == null)
            {
                continue;
            }
            net.cumba.datatable.DataTableMeta meta = table.getMetaData();
            int colCount = meta.getColumnCount();
            for (int c = 0; c < colCount; c++)
            {
                net.cumba.datatable.DataTableColumnMeta colMeta = meta.getColumn(c);
                String varName = colMeta.getName();
                if (result.containsKey(varName))
                {
                    continue; // first match wins (case variants: the constructor's putIfAbsent)
                }
                String value = extractMetadataField(colMeta, metadataField);
                if (value != null)
                {
                    result.put(varName, value);
                }
            }
        }
        return new VariableMetadataResult(result);
    }


    private static VariableMetadataResult buildFromTable(net.cumba.datatable.IDataTable table,
            @Nullable String metadataField)
    {
        net.cumba.datatable.DataTableMeta meta = table.getMetaData();
        int colCount = meta.getColumnCount();
        Map<String, String> result = new LinkedHashMap<>();
        for (int c = 0; c < colCount; c++)
        {
            net.cumba.datatable.DataTableColumnMeta colMeta = meta.getColumn(c);
            String varName = colMeta.getName();
            String value = extractMetadataField(colMeta, metadataField);
            if (value != null)
            {
                result.put(varName, value);
            }
        }
        return new VariableMetadataResult(result);
    }


    private static @Nullable String extractMetadataField(
            net.cumba.datatable.DataTableColumnMeta colMeta, @Nullable String metadataField)
    {
        return switch (metadataField)
        {
        case "label" -> colMeta.getLabel();
        case "data_type" -> dataTypeString(colMeta);
        case "length" -> colMeta.getLength() > 0 ? String.valueOf(colMeta.getLength()) : null;
        case "format" -> colMeta.getDisplayFormat();
        case null, default -> null;
        };
    }


    private static @Nullable String dataTypeString(net.cumba.datatable.DataTableColumnMeta colMeta)
    {
        if (colMeta.getType() == null)
        {
            return null;
        }
        return colMeta.getType() == net.cumba.datatable.values.DataValueType.STRING ? "Char"
                : "Num";
    }


    @Override
    public String toString()
    {
        return "VariableMetadataResult[" + variableToValue.size() + " variables]";
    }

}
