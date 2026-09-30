package net.cumba.corej.core.exec;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.ProviderNeed;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.metadata.CdiscDomainResolver;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * The six dataset-level <b>scalar and metadata</b> functions of runbook wave 4b
 * ({@code PLAN-scalar-metadata-functions} §2.2), ported from their retired operation arms with each
 * arm's own answer and empty / unusable disposition carried verbatim. Every one answers <b>one
 * value for the dataset</b> — a {@code Boolean}, a text, a count, a metadata value, or (for
 * {@link #CROSS_DATASET_VARIABLE_METADATA}) one per-variable map — broadcast to every row through
 * {@code ConstVector.of}, which is why every descriptor is an aggregate
 * ({@code FunctionDescriptor.aggregating()}): computed once per rule × dataset.
 *
 * <p>
 * A dataset-level "no answer" (a metadata key the table does not carry, a Library that classifies
 * nothing) is {@code ConstVector.of(null)} — the {@code MIS} missing value
 * ({@code TypedValue.resolved}), exactly what the retired operations' {@code null} broadcast as.
 * There is no input cell whose identity could be handed through.
 * </p>
 *
 * <p>
 * The static parameters ({@code name}, {@code domain}, {@code name_pattern}, {@code min_length})
 * are read <b>once per call</b>, which is why the compile seam holds them to literals (D-W4b-6): a
 * column bound there would read row 0 and silently stand for every row.
 * </p>
 */
public final class ScalarMetadataFunctions
{

    /** {@code domain_is_custom()}. */
    public static final String DOMAIN_IS_CUSTOM = "domain_is_custom";

    /** {@code dataset_class_from_library()}. */
    public static final String DATASET_CLASS_FROM_LIBRARY = "dataset_class_from_library";

    /** {@code extract_metadata(name)}. */
    public static final String EXTRACT_METADATA = "extract_metadata";

    /** {@code cross_dataset_variable_metadata(name, domain=)}. */
    public static final String CROSS_DATASET_VARIABLE_METADATA = "cross_dataset_variable_metadata";

    /** {@code variable_count(name)} / {@code variable_count(name_pattern=)}. */
    public static final String VARIABLE_COUNT = "variable_count";

    /** {@code column_series_metadata(name, name_pattern=, min_length=)}. */
    public static final String COLUMN_SERIES_METADATA = "column_series_metadata";

    /** The metadata key / template / base-column parameter the ported callables share. */
    public static final String NAME_PARAMETER = "name";

    /** The {@code domain} parameter of {@link #CROSS_DATASET_VARIABLE_METADATA}. */
    public static final String DOMAIN_PARAMETER = "domain";

    /**
     * The column-name regex parameter of {@link #VARIABLE_COUNT} / {@link #COLUMN_SERIES_METADATA}.
     */
    public static final String PATTERN_PARAMETER = "name_pattern";

    /** The {@code min_length} parameter of {@link #COLUMN_SERIES_METADATA}. */
    public static final String MIN_LENGTH_PARAMETER = "min_length";

    /** The {@code domain} value that scans the whole study inventory. */
    public static final String ALL_DATASETS = "*";

    /** The attribute keys {@link #CROSS_DATASET_VARIABLE_METADATA} reads. */
    public static final Set<String> VARIABLE_METADATA_FIELDS = Set.of("label", "data_type",
            "length", "format");

    /** The wave-4b functions, by their authored names. */
    public static final Set<String> FUNCTION_NAMES = Set.of(DOMAIN_IS_CUSTOM,
            DATASET_CLASS_FROM_LIBRARY, EXTRACT_METADATA, CROSS_DATASET_VARIABLE_METADATA,
            VARIABLE_COUNT, COLUMN_SERIES_METADATA);

    private static final System.Logger LOGGER = System
            .getLogger(ScalarMetadataFunctions.class.getName());

    private ScalarMetadataFunctions()
    {
    }


    /**
     * {@code domain_is_custom()} — whether the CDISC Library declares this dataset's domain custom.
     *
     * <p>
     * ⚠ Fix #369 — the <b>NEVER</b> answer-kind (D-W4b-8): {@code false} ("not custom") is a real
     * answer <em>and</em> the value that lets a rule fire, so it is indistinguishable from "could
     * not tell". A Define-XML cannot supply it either — "custom" means "not in the standard", and
     * the standard is what is missing. So a degraded Library is unusable here even when the
     * Define-XML fallback is opted in and answerable.
     * </p>
     *
     * @param run
     *            the evaluation run
     * @param args
     *            no arguments
     * @return the broadcast {@code Boolean}
     */
    public static Vector domainIsCustom(EvalRun run, List<Vector> args)
    {
        MetadataProvider provider = ListFunctionSupport.library(run, DOMAIN_IS_CUSTOM);
        if (provider.isLibraryUnavailable())
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "[{0}] {1}: define fallback engaged but a Define-XML cannot answer this"
                            + " function — rule will be skipped",
                    ListFunctionSupport.ruleId(run), DOMAIN_IS_CUSTOM);
            throw ListFunctionSupport.unusable(DOMAIN_IS_CUSTOM, ProviderNeed.Kind.LIBRARY,
                    "the Define-XML fallback cannot tell a custom domain");
        }
        boolean custom = provider
                .isDomainCustom(CdiscDomainResolver.cdiscDomainOf(run.ctx().getTable()));
        return ConstVector.of(custom);
    }


    /**
     * {@code dataset_class_from_library()} — the Library's class name of this dataset's domain
     * (e.g. {@code "BASIC DATA STRUCTURE"}), missing when the Library holds none. Under the
     * degraded Define-XML opt-in a blank answer is unusable (Fix #369's TEXT answer-kind, condition
     * 3).
     *
     * @param run
     *            the evaluation run
     * @param args
     *            no arguments
     * @return the broadcast class name, or the missing value
     */
    public static Vector datasetClassFromLibrary(EvalRun run, List<Vector> args)
    {
        MetadataProvider provider = ListFunctionSupport.library(run, DATASET_CLASS_FROM_LIBRARY);
        Map<String, String> dsMeta = provider
                .getDatasetMetadata(CdiscDomainResolver.cdiscDomainOf(run.ctx().getTable()));
        String className = dsMeta != null ? dsMeta.get("className") : null;
        if (provider.isLibraryUnavailable() && (className == null || className.isBlank()))
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "[{0}] {1}: define fallback engaged but the Define-XML could not answer this"
                            + " function — rule will be skipped",
                    ListFunctionSupport.ruleId(run), DATASET_CLASS_FROM_LIBRARY);
            throw ListFunctionSupport.unusable(DATASET_CLASS_FROM_LIBRARY,
                    ProviderNeed.Kind.LIBRARY, "the Define-XML fallback could not answer");
        }
        return ConstVector.of(className);
    }


    /**
     * {@code extract_metadata(name)} — one dataset-metadata value of the current table:
     * {@code dataset_name}, {@code dataset_label}, {@code dataset_size} (bytes, a {@code Long}),
     * {@code dataset_location} / {@code filename} (the source-URI basename), or any other key the
     * table's provider publishes ({@code file_format}, …). Missing when the table carries none.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the static {@code name}
     * @return the broadcast value, or the missing value
     */
    public static Vector extractMetadata(EvalRun run, List<Vector> args)
    {
        String key = ListFunctionSupport.staticString(args.get(0));
        if (key == null)
        {
            return ConstVector.of(null);
        }
        DataTableMeta meta = run.ctx().getTable().getMetaData();
        Object value = switch (key)
        {
        case "dataset_name" -> meta.getName();
        case "dataset_label" -> meta.getLabel();
        case "dataset_size" -> meta.getMetaData("dataset_size");
        // Two keys, one value (the source-URI basename): dataset_location is the corpus spelling,
        // filename the intuitive alias. Both resolve identically.
        case "dataset_location", "filename" -> fileNameFromUri(meta.getTableURI());
        default -> meta.getMetaData(key);
        };
        return ConstVector.of(value);
    }


    /**
     * {@code cross_dataset_variable_metadata(name, domain=)} — per variable, one metadata attribute
     * ({@code label}, {@code data_type}, {@code length}, {@code format}) of the same-named variable
     * in another dataset: the named {@code domain}, or with {@code domain="*"} the first dataset of
     * the study (shortest name first, then alphabetical) that carries it, the dataset under
     * evaluation excluded by its <b>name</b>.
     *
     * <p>
     * ⭐ <b>The per-variable shape (D-W4b-1).</b> The answer is ONE value for the dataset — the
     * whole per-variable map, a {@link VariableMetadataResult} — carried in a {@code ConstVector}
     * exactly as wave 0 carries a list (option 2). The per-variable loop projects it onto the
     * variable cursor ({@code RuleRunner.projectVariablesForColumn}) and the broadcast fold
     * declines it ({@code BroadcastFold.operationRefsSafe}), so a rule comparing
     * {@code var_label("DATA") != $x} routes per variable exactly as the retired operation's value
     * did — computed once per execution, not once per variable.
     * </p>
     *
     * <p>
     * A concrete {@code domain} the study does not supply answers the missing value — the retired
     * operation's own MISSING codomain for an absent target (Q17-a); {@code "*"} never does.
     * </p>
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the static {@code name} and {@code domain}
     * @return the broadcast per-variable map, or the missing value
     */
    public static Vector crossDatasetVariableMetadata(EvalRun run, List<Vector> args)
    {
        String field = ListFunctionSupport.staticString(args.get(0));
        String domain = ListFunctionSupport.staticString(args.get(1));
        IDataTable table = run.ctx().getTable();
        DatasetResolver resolver = run.ctx().getDatasetResolver();
        String source = DatasetIdentity.resolveWildcard(domain, table);
        if (source == null || (!ALL_DATASETS.equals(source) && resolver.resolve(source) == null))
        {
            return ConstVector.of(null);
        }
        return ConstVector.of(VariableMetadataResult.build(resolver, source, field,
                table.getMetaData().getName()));
    }


    /**
     * {@code variable_count(…)} — three forms, the first two mutually exclusive (a load error when
     * both are given, D-W4b-4):
     * <ul>
     * <li>{@code variable_count(name_pattern="^.+FL$")} — the columns of this dataset whose names
     * match (case-insensitive, full match; CIT §1);</li>
     * <li>{@code variable_count("--LNKGRP")} — study-wide: the datasets of the inventory whose
     * resolved template column exists, each split family counted once (by its data-driven unsplit
     * name). ⛔ A resolver that cannot enumerate the study is loud (D-W4b-5);</li>
     * <li>{@code variable_count()} — this dataset's column count.</li>
     * </ul>
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the static {@code name} and {@code name_pattern}
     * @return the broadcast {@code Long}
     */
    public static Vector variableCount(EvalRun run, List<Vector> args)
    {
        String template = ListFunctionSupport.staticString(args.get(0));
        String pattern = ListFunctionSupport.staticString(args.get(1));
        IDataTable table = run.ctx().getTable();
        if (pattern != null)
        {
            return ConstVector.of(countByPattern(compile(pattern), table));
        }
        if (template == null)
        {
            return ConstVector.of((long) table.getMetaData().getColumnCount());
        }
        DatasetResolver.WithInventory inventory = ListFunctionSupport.inventory(run,
                VARIABLE_COUNT);
        return ConstVector
                .of(countAcrossInventory(template, inventory, run.ctx().getDatasetResolver()));
    }


    /**
     * {@code column_series_metadata(name, name_pattern=, min_length=)} — E7, numbered column-series
     * completeness / continuation ({@code COVAL1..n}). The members are the columns whose names
     * match {@code name_pattern} (by their trailing integer), plus the optional un-numbered base
     * column {@code name} as suffix 0; {@code true} ("series incomplete", the check fires) when the
     * present suffixes are not contiguous from lowest to highest, or when {@code min_length} is set
     * and a non-terminal member declares a length below it; {@code false} with fewer than two
     * members.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the static {@code name}, {@code name_pattern} and {@code min_length}
     * @return the broadcast {@code Boolean}
     */
    public static Vector columnSeriesMetadata(EvalRun run, List<Vector> args)
    {
        String base = ListFunctionSupport.staticString(args.get(0));
        Pattern compiled = compile(
                java.util.Objects.requireNonNull(ListFunctionSupport.staticString(args.get(1)),
                        "column_series_metadata: name_pattern is required"));
        Integer minLength = staticInteger(args.get(2));
        DataTableMeta meta = run.ctx().getTable().getMetaData();
        int colCount = meta.getColumnCount();
        // suffix -> declared length, keyed by the parsed trailing integer of each matched member.
        Map<Integer, Integer> lengthBySuffix = new TreeMap<>();
        for (int c = 0; c < colCount; c++)
        {
            DataTableColumnMeta colMeta = meta.getColumn(c);
            String name = colMeta.getName();
            Integer suffix = null;
            // The un-numbered base is a column name too: matched ignoring letter case, like the
            // name_pattern members (CIT §1) — a lowercase coval is the base of coval1 / coval2.
            if (base != null && base.equalsIgnoreCase(name))
            {
                suffix = 0;
            }
            else if (compiled.matcher(name).matches())
            {
                suffix = trailingInteger(name);
            }
            if (suffix != null)
            {
                lengthBySuffix.putIfAbsent(suffix, colMeta.getLength());
            }
        }
        return ConstVector.of(seriesIncomplete(lengthBySuffix, minLength));
    }


    /**
     * The column-name regex of {@code name_pattern}: case-insensitive on every surface (owner
     * ruling 2026-09-28, CIT §1). The compile seam has already refused an invalid one at load. One
     * definition for every {@code name_pattern} surface: it delegates to {@link RowMax#compile}, so
     * the column-name functions and {@code row_max} cannot drift apart.
     *
     * @param pattern
     *            the regex
     * @return the compiled pattern
     */
    public static Pattern compile(String pattern)
    {
        return RowMax.compile(pattern);
    }


    private static boolean seriesIncomplete(Map<Integer, Integer> lengthBySuffix,
            @Nullable Integer minLength)
    {
        if (lengthBySuffix.size() < 2)
        {
            return false;
        }
        List<Integer> suffixes = List.copyOf(lengthBySuffix.keySet());
        int lo = suffixes.get(0);
        int hi = suffixes.get(suffixes.size() - 1);
        // Gap detection: the contiguous run [lo..hi] must be fully present.
        if (hi - lo + 1 != lengthBySuffix.size())
        {
            return true;
        }
        // Continuation length: every non-terminal member must reach min_length (when specified).
        if (minLength != null)
        {
            for (Map.Entry<Integer, Integer> e : lengthBySuffix.entrySet())
            {
                if (e.getKey() != hi && e.getValue() < minLength)
                {
                    return true;
                }
            }
        }
        return false;
    }


    private static long countByPattern(Pattern compiled, IDataTable table)
    {
        DataTableMeta meta = table.getMetaData();
        int n = meta.getColumnCount();
        long count = 0;
        for (int i = 0; i < n; i++)
        {
            if (compiled.matcher(meta.getColumn(i).getName()).matches())
            {
                count++;
            }
        }
        return count;
    }


    /**
     * Counts the inventory datasets whose resolved {@code --} template column exists. A split
     * family counts once, keyed by its <em>data-driven</em> unsplit name
     * ({@link DatasetIdentity#unsplitNameFromData}, read from {@code DOMAIN} / {@code RDOMAIN}), so
     * a letter-suffix split like {@code FAAE} / {@code DOMAIN=FA} groups with {@code FACM}. The
     * dataset is resolved before its key is computed, because the key comes from the data.
     */
    private static long countAcrossInventory(String template, DatasetResolver.WithInventory inv,
            DatasetResolver resolver)
    {
        long count = 0;
        Set<String> seenKeys = new LinkedHashSet<>();
        for (String dsName : inv.availableDatasets())
        {
            IDataTable ds = resolver.resolve(dsName);
            if (ds == null || !seenKeys.add(DatasetIdentity.unsplitNameFromData(ds)))
            {
                continue;
            }
            String resolved = DatasetIdentity.resolveTemplate(template, ds);
            if (resolved != null && ds.getMetaData().getColumnIndex(resolved) >= 0)
            {
                count++;
            }
        }
        return count;
    }


    /**
     * The trailing run of ASCII digits in {@code name} as an {@code Integer}, or {@code null} when
     * the name has none ({@code COVAL1} → {@code 1}).
     */
    private static @Nullable Integer trailingInteger(String name)
    {
        int end = name.length();
        int i = end;
        while (i > 0 && Character.isDigit(name.charAt(i - 1)))
        {
            i--;
        }
        if (i == end)
        {
            return null;
        }
        try
        {
            return Integer.valueOf(name.substring(i));
        }
        catch (NumberFormatException _)
        {
            return null;
        }
    }


    /** A static integer argument (an integer literal, guaranteed by the compile seam), or null. */
    private static @Nullable Integer staticInteger(@Nullable Vector arg)
    {
        if (arg == null)
        {
            return null;
        }
        Object value = arg.value(0).resolved();
        return value instanceof Number n ? n.intValue() : null;
    }


    /**
     * Last path segment of a dataset's source URI, percent-decoded, in original casing and
     * including the extension (e.g. {@code "ae.xpt"}); {@code null} when the URI is absent or
     * carries no usable path.
     */
    private static @Nullable String fileNameFromUri(@Nullable URI uri)
    {
        if (uri == null)
        {
            return null;
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty())
        {
            path = uri.getSchemeSpecificPart();
        }
        if (path == null || path.isEmpty())
        {
            return null;
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isEmpty() ? null : name;
    }

}
