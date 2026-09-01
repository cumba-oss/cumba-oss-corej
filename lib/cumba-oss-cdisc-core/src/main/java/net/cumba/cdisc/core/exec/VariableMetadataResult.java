package net.cumba.cdisc.core.exec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Per-variable metadata result from a {@code cross_dataset_variable_metadata} Operation.
 * <p>
 * Similar to {@link GroupedResult} (which resolves per-row), this resolves per-variable. A
 * {@code $variable} holding a {@code VariableMetadataResult} is variable-level: constant across
 * rows but varying per column.
 * {@link net.cumba.cdisc.core.expr.eval.BroadcastFold#operationRefsSafe
 * BroadcastFold.operationRefsSafe} enforces that at runtime — such a reference is broadcast-safe
 * only on the per-variable loop, which projects it onto the per-column cursor.
 * <p>
 * The map keys are variable names (e.g., "ITTFL", "TRTDUR"), and values are the requested metadata
 * field (e.g., the ADSL label for that variable).
 */
public class VariableMetadataResult
{

    private final Map<String, String> variableToValue;

    public VariableMetadataResult(Map<String, String> variableToValue)
    {
        this.variableToValue = variableToValue != null ? variableToValue : Map.of();
    }


    /**
     * Returns the metadata value for the given variable name, or {@code null} if the variable is
     * not present in the source dataset.
     */
    public @Nullable String getForVariable(String variableName)
    {
        return variableToValue.get(variableName);
    }


    /**
     * Builds a {@code VariableMetadataResult} from a dataset's column metadata.
     *
     * @param resolver
     *            the dataset resolver
     * @param domainName
     *            the target dataset name (e.g., "ADSL")
     * @param metadataField
     *            the metadata field to extract: "label", "data_type", "length", "format"
     * @return the result, or an empty result if the dataset is not available
     */
    public static VariableMetadataResult build(DatasetResolver resolver,
            @Nullable String domainName, @Nullable String metadataField)
    {
        return build(resolver, domainName, metadataField, null);
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
                    continue; // first match wins
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
