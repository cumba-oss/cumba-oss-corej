package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.ProviderNeed;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.metadata.CdiscDomainResolver;
import net.cumba.corej.core.metadata.LibraryVariableAttributes;
import net.cumba.corej.core.metadata.MetadataKeys;
import net.cumba.corej.core.metadata.SdtmObservationClasses;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.ICodeList;
import org.jspecify.annotations.Nullable;

/**
 * The eleven CDISC-Library-backed dataset-level list functions of runbook wave 4
 * ({@code PLAN-list-functions} §2.2), ported from their retired operation arms with each arm's own
 * empty / unusable disposition carried verbatim (D-W4-1, D-W4-9). Every one reads <b>our metadata
 * store</b> through {@link MetadataProvider} — never the pickle corpus nor the web API (owner rule
 * 2026-09-28) — and answers one list broadcast to every row
 * ({@link ListFunctionSupport#broadcast}); a rule whose Library cannot answer SKIPs through
 * {@link net.cumba.corej.core.expr.eval.UnusableProviderAnswerException}, never PASSes over an
 * empty list.
 *
 * <p>
 * The static parameters ({@code codelists}, {@code level}, {@code returntype}, {@code key_name},
 * {@code key_value}, {@code model_class}, {@code ct_package_types}) are read <b>once per call</b>,
 * which is why the compile seam holds them to literals (D-W4-3): a column bound there would read
 * row 0 and silently stand for every row.
 * </p>
 */
public final class LibraryLists
{

    /** {@code required_variables()}. */
    public static final String REQUIRED_VARIABLES = "required_variables";

    /** {@code expected_variables()}. */
    public static final String EXPECTED_VARIABLES = "expected_variables";

    /** {@code get_column_order_from_library()}. */
    public static final String GET_COLUMN_ORDER_FROM_LIBRARY = "get_column_order_from_library";

    /** {@code get_model_column_order()}. */
    public static final String GET_MODEL_COLUMN_ORDER = "get_model_column_order";

    /** {@code variable_names()} (EC-13). */
    public static final String VARIABLE_NAMES = "variable_names";

    /** {@code standard_domains()} (EC-14 layer (i)). */
    public static final String STANDARD_DOMAINS = "standard_domains";

    /** {@code get_dataset_filtered_variables(key_name=, key_value=)}. */
    public static final String GET_DATASET_FILTERED_VARIABLES = "get_dataset_filtered_variables";

    /** {@code natural_key_variables()}. */
    public static final String NATURAL_KEY_VARIABLES = "natural_key_variables";

    /** {@code get_model_filtered_variables(key_name=, key_value=, model_class=)}. */
    public static final String GET_MODEL_FILTERED_VARIABLES = "get_model_filtered_variables";

    /** {@code valid_codelist_dates(ct_package_types=)}. */
    public static final String VALID_CODELIST_DATES = "valid_codelist_dates";

    /** {@code codelist_terms(codelists=, level=, returntype=)}. */
    public static final String CODELIST_TERMS = "codelist_terms";

    /** The {@code key_name} parameter of the two filtered walks. */
    public static final String KEY_NAME_PARAMETER = "key_name";

    /** The {@code key_value} parameter of the two filtered walks. */
    public static final String KEY_VALUE_PARAMETER = "key_value";

    /** The {@code model_class} parameter of {@link #GET_MODEL_FILTERED_VARIABLES}. */
    public static final String MODEL_CLASS_PARAMETER = "model_class";

    /** The {@code codelists} parameter of {@link #CODELIST_TERMS}. */
    public static final String CODELISTS_PARAMETER = "codelists";

    /** The {@code level} parameter of {@link #CODELIST_TERMS}. */
    public static final String LEVEL_PARAMETER = "level";

    /** The {@code returntype} parameter of {@link #CODELIST_TERMS}. */
    public static final String RETURNTYPE_PARAMETER = "returntype";

    /** The {@code ct_package_types} parameter of {@link #VALID_CODELIST_DATES}. */
    public static final String CT_PACKAGE_TYPES_PARAMETER = "ct_package_types";

    /** The {@code level} vocabulary of {@link #CODELIST_TERMS}. */
    public static final Set<String> LEVELS = Set.of("term", "codelist");

    /** The {@code returntype} vocabulary of {@link #CODELIST_TERMS}. */
    public static final Set<String> RETURNTYPES = Set.of("value", "code", "pref_term");

    private static final System.Logger LOGGER = System.getLogger(LibraryLists.class.getName());

    private static final String SDTMCT = "sdtmct";

    /**
     * Maps a CDISC standard (e.g. {@code sdtmig}) to the set of eligible CT-package-type prefixes
     * used to split a package identifier like {@code "sdtmct-2023-10-26"}.
     */
    private static final Map<String, Set<String>> STANDARD_TO_PACKAGE_TYPES = Map.of("sdtmig",
            Set.of(SDTMCT), "sendig", Set.of("sendct"), "cdashig", Set.of("cdashct"), "adamig",
            Set.of(SDTMCT, "adamct"), "usdm", Set.of("ddfct", SDTMCT));

    private LibraryLists()
    {
    }

    // ------------------------------------------------------------------ the core obligations


    /**
     * {@code required_variables()} — the variables the standard obliges this dataset to carry.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            no arguments
     * @return the broadcast list; empty is a legitimate pass
     */
    public static Vector requiredVariables(EvalRun run, List<Vector> args)
    {
        return coreVariables(run, REQUIRED_VARIABLES, false);
    }


    /**
     * {@code expected_variables()} — the Expected variables of this dataset.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            no arguments
     * @return the broadcast list; empty is a legitimate pass
     */
    public static Vector expectedVariables(EvalRun run, List<Vector> args)
    {
        return coreVariables(run, EXPECTED_VARIABLES, true);
    }


    /**
     * The two core-obligation walks (the retired {@code evalCoreVariables}, Fix #368 /
     * {@code PLAN-metadata-product-selection} ruling 2): SDTM's variable model is keyed by domain,
     * ADaM's by data structure. A structure-keyed provider is asked per detected structure token,
     * most specific first, with the dataset's subclass set; published names are substituted against
     * the dataset's real columns ({@link #substituteNamingTemplates}). ⚠ No token resolving is
     * <b>not</b> an empty list: it is the honest reason the rule could not run, so it SKIPs — a
     * structure that resolves and publishes nothing is a legitimate pass.
     */
    private static Vector coreVariables(EvalRun run, String function, boolean expected)
    {
        MetadataProvider provider = ListFunctionSupport.library(run, function);
        IDataTable table = run.ctx().getTable();
        List<String> published;
        if (!provider.supportsStructureKeyedVariables())
        {
            String domain = CdiscDomainResolver.cdiscDomainOf(table);
            published = expected ? provider.getExpectedVariables(domain)
                    : provider.getRequiredVariables(domain);
        }
        else
        {
            MetadataProvider define = run.ctx().getDefineProvider();
            List<String> structures = AdamStructureContext.detectAll(table.getMetaData(), define,
                    provider);
            List<String> subclasses = AdamStructureContext.detectSubclasses(table.getMetaData(),
                    define, provider, structures);
            published = null;
            for (String token : structures)
            {
                List<String> answer = expected
                        ? provider.getExpectedVariablesForStructure(token, subclasses)
                        : provider.getRequiredVariablesForStructure(token, subclasses);
                if (answer != null)
                {
                    published = substituteNamingTemplates(answer, table);
                    break;
                }
            }
            if (published == null)
            {
                List<String> declaredProducts = provider.declaredStructureKeyedProducts();
                LOGGER.log(System.Logger.Level.INFO,
                        "[{0}] {1}: the run''s declared product(s) {2} publish no data structure"
                                + " for dataset {3} (tried {4}, subclasses {5}) — rule will be"
                                + " skipped rather than pass",
                        ListFunctionSupport.ruleId(run), function,
                        declaredProducts.isEmpty() ? "<unknown>" : declaredProducts,
                        table.getMetaData().getName(), structures, subclasses);
                throw ListFunctionSupport.unusable(function, ProviderNeed.Kind.LIBRARY,
                        "the declared product(s) publish no data structure for dataset "
                                + table.getMetaData().getName());
            }
        }
        return ListFunctionSupport.broadcast(function,
                ListFunctionSupport.degradedAnswer(provider, run, function, published));
    }


    /**
     * Substitutes ADaM naming templates in a published variable list against the dataset's actual
     * columns, using the <b>same</b> compiled pattern the scope gate matches with
     * ({@link WildcardExpander#scopeVariableWildcardPattern}), so the two cannot drift. A literal
     * passes through; a template with ≥ 1 matching column contributes the matches; a template with
     * no match contributes <b>the template verbatim</b>, so it is reported as missing — both
     * directions are load-bearing (the {@code TRTxxP}-on-every-ADSL false positive, and the
     * absent-column trap where absence IS the defect).
     */
    static List<String> substituteNamingTemplates(List<String> published, IDataTable table)
    {
        List<String> columns = AdamStructureContext.columnNamesOf(table.getMetaData());
        Set<String> out = new LinkedHashSet<>();
        for (String name : published)
        {
            Pattern pattern = WildcardExpander.scopeVariableWildcardPattern(name);
            if (pattern == null)
            {
                out.add(name);
                continue;
            }
            List<String> matches = columns.stream().filter(c -> pattern.matcher(c).matches())
                    .toList();
            if (matches.isEmpty())
            {
                out.add(name);
            }
            else
            {
                out.addAll(matches);
            }
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ the column orders


    /**
     * {@code get_column_order_from_library()} — the IG's variable order of the dataset's domain
     * (algorithm B). A domain absent from the library's variable model resolves to nothing, and
     * that is a SKIP (J10): an empty order would defeat the rules' own
     * {@code not empty($column_order_from_library)} guard, because the per-row scalar {@code empty}
     * is not list-aware.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            no arguments
     * @return the broadcast list, never empty
     */
    public static Vector columnOrderFromLibrary(EvalRun run, List<Vector> args)
    {
        MetadataProvider provider = ListFunctionSupport.library(run, GET_COLUMN_ORDER_FROM_LIBRARY);
        IDataTable table = run.ctx().getTable();
        List<String> order = provider.getColumnOrder(CdiscDomainResolver.cdiscDomainOf(table));
        if (order == null || order.isEmpty())
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "[{0}] {1} resolved an empty/absent library column order for domain {2}"
                            + " — rule will be skipped",
                    ListFunctionSupport.ruleId(run), GET_COLUMN_ORDER_FROM_LIBRARY,
                    table.getMetaData().getName());
            throw ListFunctionSupport.unusable(GET_COLUMN_ORDER_FROM_LIBRARY,
                    ProviderNeed.Kind.LIBRARY,
                    "no library column order for domain " + table.getMetaData().getName());
        }
        return ListFunctionSupport.broadcast(GET_COLUMN_ORDER_FROM_LIBRARY, order);
    }


    /**
     * {@code get_model_column_order()} — the pure SDTM Model walk of the dataset's class (algorithm
     * A, Fix #42 phase 2: custom-domain class detection, the GENERAL OBSERVATIONS splice, FINDINGS
     * ABOUT merge, AP shimming). {@code null} (no product / degraded) and an empty walk both SKIP
     * (Fix #55): the alternative is the FDA-SD0058 fan-out, one finding per column against an empty
     * allowed set.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            no arguments
     * @return the broadcast list, never empty
     */
    public static Vector modelColumnOrder(EvalRun run, List<Vector> args)
    {
        MetadataProvider provider = ListFunctionSupport.library(run, GET_MODEL_COLUMN_ORDER);
        IDataTable table = run.ctx().getTable();
        List<String> order = provider.getStandardModelVariables(table,
                run.ctx().getDatasetResolver());
        if (order == null || order.isEmpty())
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "[{0}] {1} resolver returned {2} for domain {3} — rule will be skipped",
                    ListFunctionSupport.ruleId(run), GET_MODEL_COLUMN_ORDER,
                    order == null ? "null (no product / degraded mode)" : "empty",
                    table.getMetaData().getName());
            throw ListFunctionSupport.unusable(GET_MODEL_COLUMN_ORDER, ProviderNeed.Kind.LIBRARY,
                    "no model column order for domain " + table.getMetaData().getName());
        }
        return ListFunctionSupport.broadcast(GET_MODEL_COLUMN_ORDER, order);
    }


    /**
     * {@code variable_names()} — EC-13, the union of variable names across every dataset the IG
     * standard defines. An empty / absent enumeration SKIPs: a membership against an empty set
     * would misfire.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            no arguments
     * @return the broadcast list, never empty
     */
    public static Vector variableNames(EvalRun run, List<Vector> args)
    {
        MetadataProvider provider = ListFunctionSupport.library(run, VARIABLE_NAMES);
        List<String> names = provider.getStandardVariableNames();
        if (names == null || names.isEmpty())
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "[{0}] {1} resolved no standard variable names — rule will be skipped",
                    ListFunctionSupport.ruleId(run), VARIABLE_NAMES);
            throw ListFunctionSupport.unusable(VARIABLE_NAMES, ProviderNeed.Kind.LIBRARY,
                    "no standard variable names");
        }
        return ListFunctionSupport.broadcast(VARIABLE_NAMES, names);
    }


    /**
     * {@code standard_domains()} — EC-14 layer (i), the IG's datasets unioned with the SDTM
     * Model's. ⚠ The empty-enumeration SKIP is critical: without it
     * {@code SRCDOM is_not_contained_by $sdtm_domains} fires on every populated SRCDOM in a
     * degraded run.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            no arguments
     * @return the broadcast list, never empty
     */
    public static Vector standardDomains(EvalRun run, List<Vector> args)
    {
        MetadataProvider provider = ListFunctionSupport.library(run, STANDARD_DOMAINS);
        List<String> names = provider.getStandardDatasetNames();
        if (names == null || names.isEmpty())
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "[{0}] {1} resolved no standard domain names — rule will be skipped",
                    ListFunctionSupport.ruleId(run), STANDARD_DOMAINS);
            throw ListFunctionSupport.unusable(STANDARD_DOMAINS, ProviderNeed.Kind.LIBRARY,
                    "no standard domain names");
        }
        return ListFunctionSupport.broadcast(STANDARD_DOMAINS, names);
    }

    // ------------------------------------------------------------------ the filtered walks


    /**
     * {@code get_dataset_filtered_variables(key_name=, key_value=)} — the IG-level variables of the
     * dataset's domain filtered by an attribute and intersected with the dataset's columns
     * ({@link StandardVariableSelector}, shared with {@code natural_key_variables} and the EC-40
     * record-key tier). An empty answer on a working Library is a legitimate empty set.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the bound {@code key_name} and {@code key_value} (either may be absent)
     * @return the broadcast list
     */
    public static Vector datasetFilteredVariables(EvalRun run, List<Vector> args)
    {
        MetadataProvider provider = ListFunctionSupport.library(run,
                GET_DATASET_FILTERED_VARIABLES);
        IDataTable table = run.ctx().getTable();
        String keyName = ListFunctionSupport.staticString(args.get(0));
        String keyValue = ListFunctionSupport.staticString(args.get(1));
        String ruleId = ListFunctionSupport.ruleId(run);
        List<String> selected = StandardVariableSelector.select(provider, table,
                run.ctx().getDatasetResolver(),
                varRow -> keyName == null || Objects.equals(varRow.get(keyName), keyValue),
                rows -> warnUnservedKeyName(keyName, rows, GET_DATASET_FILTERED_VARIABLES, table,
                        ruleId));
        return ListFunctionSupport.broadcast(GET_DATASET_FILTERED_VARIABLES, ListFunctionSupport
                .degradedAnswer(provider, run, GET_DATASET_FILTERED_VARIABLES, selected));
    }


    /**
     * {@code natural_key_variables()} — the dataset's library variables whose role forms a natural
     * key ({@link StandardVariableSelector#NATURAL_KEY_ROLES}), intersected with the dataset's
     * columns.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            no arguments
     * @return the broadcast list
     */
    public static Vector naturalKeyVariables(EvalRun run, List<Vector> args)
    {
        MetadataProvider provider = ListFunctionSupport.library(run, NATURAL_KEY_VARIABLES);
        List<String> selected = StandardVariableSelector.select(provider, run.ctx().getTable(),
                run.ctx().getDatasetResolver(), StandardVariableSelector::isNaturalKeyRole);
        return ListFunctionSupport.broadcast(NATURAL_KEY_VARIABLES,
                ListFunctionSupport.degradedAnswer(provider, run, NATURAL_KEY_VARIABLES, selected));
    }


    /**
     * {@code get_model_filtered_variables(key_name=, key_value=, model_class=)} — the Model-level
     * variables of the dataset's observation class (or, EC-85, of the named {@code model_class},
     * still substituted with the dataset's prefix), filtered by an attribute, <em>not</em>
     * intersected with the dataset's columns. The class split of the retired arm is kept: the
     * own-class walk answers an empty set as a legitimate empty list, while a named class the
     * library cannot serve SKIPs (D-6) — {@code varname() in $x} against nothing could never fire.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the bound {@code key_name}, {@code key_value} and {@code model_class} (each may be
     *            absent)
     * @return the broadcast list
     */
    public static Vector modelFilteredVariables(EvalRun run, List<Vector> args)
    {
        MetadataProvider provider = ListFunctionSupport.library(run, GET_MODEL_FILTERED_VARIABLES);
        IDataTable table = run.ctx().getTable();
        DatasetResolver resolver = run.ctx().getDatasetResolver();
        String keyName = ListFunctionSupport.staticString(args.get(0));
        String keyValue = ListFunctionSupport.staticString(args.get(1));
        String rawClass = ListFunctionSupport.staticString(args.get(2));
        String modelClass = rawClass == null ? null
                : SdtmObservationClasses.normalise(rawClass.trim());
        List<Map<String, String>> source = modelClass != null
                ? provider.getStandardModelVariablesForClass(table, resolver, modelClass)
                : provider.getStandardModelVariablesDetailed(table, resolver);
        boolean fromResolver = source != null;
        if (!fromResolver)
        {
            // Fix #59: CDISC domain code, not member name. EC-85: a class-selecting call falls
            // back to the class-keyed harness map — the domain-keyed one answers a different
            // question.
            source = modelClass != null ? provider.getModelVariablesForClass(modelClass)
                    : provider.getModelVariables(CdiscDomainResolver.cdiscDomainOf(table));
        }
        if (source == null || source.isEmpty())
        {
            if (modelClass == null)
            {
                // Unchanged for the own-class callers: an empty walk is an empty set — except
                // under the degraded define opt-in (Fix #369), where it means "the define could
                // not answer this arm" and must SKIP.
                return ListFunctionSupport.broadcast(GET_MODEL_FILTERED_VARIABLES,
                        ListFunctionSupport.degradedAnswer(provider, run,
                                GET_MODEL_FILTERED_VARIABLES, List.of()));
            }
            LOGGER.log(System.Logger.Level.INFO,
                    "[{0}] {1} resolved no variables for model class {2} — rule will be skipped",
                    ListFunctionSupport.ruleId(run), GET_MODEL_FILTERED_VARIABLES, modelClass);
            throw ListFunctionSupport.unusable(GET_MODEL_FILTERED_VARIABLES,
                    ProviderNeed.Kind.LIBRARY, "no variables for model class " + modelClass);
        }
        // EC-36: variable names -> variable prefix; "" for SUPP, AP suffix for AP.
        String prefix = Objects.requireNonNullElse(
                DatasetIdentity.variableWildcardPrefix(table, DatasetIdentity.domainPrefix(table)),
                "");
        warnUnservedKeyName(keyName, source, GET_MODEL_FILTERED_VARIABLES, table,
                ListFunctionSupport.ruleId(run));
        List<String> out = new ArrayList<>();
        for (Map<String, String> varRow : source)
        {
            if (keyName != null && !Objects.equals(varRow.get(keyName), keyValue))
            {
                continue;
            }
            String name = varRow.get("name");
            if (name == null)
            {
                continue;
            }
            // The resolver pre-substitutes; the legacy fallback does not.
            out.add(!fromResolver && name.contains("--") ? name.replace("--", prefix) : name);
        }
        return ListFunctionSupport.broadcast(GET_MODEL_FILTERED_VARIABLES, ListFunctionSupport
                .degradedAnswer(provider, run, GET_MODEL_FILTERED_VARIABLES, out));
    }


    /**
     * ⭐ The FDA-SD1078 diagnostic (CDW-F1, kept as a WARNING — D-W4-12): a {@code key_name} filter
     * running against variable rows that carry no such key at all can never match, on any data, for
     * any {@code key_value}. The load seam rejects a key <em>no</em> level publishes; it
     * deliberately cannot reject {@code core} on {@code get_model_filtered_variables}, because the
     * Model walk does publish {@code core} for SUPP--/SQ-- and ADaM datasets — servability there is
     * a per-dataset runtime fact, reported here. The verdict is unchanged.
     *
     * @param aKeyName
     *            the declared {@code key_name}; {@code null} means no filter
     * @param aRows
     *            the resolved variable rows the filter is about to run over
     * @param aFunction
     *            the function, which names the metadata level in the message
     * @param aTable
     *            the dataset being validated
     * @param aRuleId
     *            the rule, for the log line
     */
    static void warnUnservedKeyName(@Nullable String aKeyName, List<Map<String, String>> aRows,
            String aFunction, @Nullable IDataTable aTable, @Nullable String aRuleId)
    {
        if (aKeyName == null || aRows.isEmpty()
                || LibraryVariableAttributes.carriedByAny(aRows, aKeyName))
        {
            return;
        }
        LOGGER.log(System.Logger.Level.WARNING,
                "[{0}] {1}: key_name `{2}` is carried by none of the {3} variables resolved for"
                        + " dataset {4} — the filter can never match. This level publishes {5}.",
                aRuleId != null ? aRuleId : "?", aFunction, aKeyName, aRows.size(),
                aTable != null ? aTable.getMetaData().getName() : "?",
                LibraryVariableAttributes.publishedKeys(aRows));
    }

    // ------------------------------------------------------------------ the CT walks


    /**
     * {@code valid_codelist_dates(ct_package_types=)} — the sorted published CT-package dates
     * applicable to the run's standard, or to the named package types. No published package is an
     * empty answer on a working Library.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the bound {@code ct_package_types} (may be absent)
     * @return the broadcast list
     */
    public static Vector validCodelistDates(EvalRun run, List<Vector> args)
    {
        MetadataProvider provider = ListFunctionSupport.library(run, VALID_CODELIST_DATES);
        List<String> packages = provider.getPublishedCtPackages();
        if (packages == null || packages.isEmpty())
        {
            return ListFunctionSupport.broadcast(VALID_CODELIST_DATES, ListFunctionSupport
                    .degradedAnswer(provider, run, VALID_CODELIST_DATES, List.of()));
        }
        Set<String> applicable = applicableCtPackageTypes(
                ListFunctionSupport.staticStrings(args.get(0)), provider);
        TreeSet<String> dates = new TreeSet<>();
        for (String pkg : packages)
        {
            int dash = pkg.indexOf('-');
            if (dash < 0)
            {
                continue;
            }
            if (applicable.contains(pkg.substring(0, dash)))
            {
                dates.add(pkg.substring(dash + 1));
            }
        }
        return ListFunctionSupport.broadcast(VALID_CODELIST_DATES, ListFunctionSupport
                .degradedAnswer(provider, run, VALID_CODELIST_DATES, new ArrayList<>(dates)));
    }


    private static Set<String> applicableCtPackageTypes(List<String> ctPackageTypes,
            MetadataProvider provider)
    {
        if (!ctPackageTypes.isEmpty())
        {
            // Rule-level entries look like "SDTM" / "ADAM" / "CDASH" — each maps to the package
            // type prefix by lowercasing and appending "ct".
            Set<String> set = new LinkedHashSet<>();
            for (String s : ctPackageTypes)
            {
                if (!s.isEmpty())
                {
                    set.add(s.toLowerCase(Locale.ROOT) + "ct");
                }
            }
            return set;
        }
        String std = provider.getStandard();
        if (std == null)
        {
            return Set.of();
        }
        return STANDARD_TO_PACKAGE_TYPES.getOrDefault(std.toLowerCase(Locale.ROOT), Set.of());
    }


    /**
     * {@code codelist_terms(codelists=, level=, returntype=)} — the union, order-preserving and
     * deduplicated, of the named codelists projected onto the {@code (level, returntype)} shape.
     * The six shapes: {@code (term, value)} term submission values · {@code (term, pref_term)} term
     * NCI preferred terms · {@code (term, code)} (the term-level default) NCI concept ids ·
     * {@code (codelist, value)} (the codelist-level default) the codelist's own submission value ·
     * {@code (codelist, pref_term)} · {@code (codelist, code)}. Only {@code (term, value)} is
     * served through {@link MetadataProvider#getCodelistTerms}; every other shape needs
     * {@link MetadataProvider#getCodelist}, whose default is deliberately empty. An empty union —
     * no CT package, an unknown codelist, a shape the provider declines — SKIPs: the downstream
     * {@code X not in $terms} would otherwise flag every row.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the bound {@code codelists} (a static list), {@code level} and {@code returntype}
     *            (may be absent)
     * @return the broadcast list, never empty
     */
    public static Vector codelistTerms(EvalRun run, List<Vector> args)
    {
        MetadataProvider provider = ListFunctionSupport.library(run, CODELIST_TERMS);
        List<String> names = ListFunctionSupport.staticStrings(args.get(0));
        String level = ListFunctionSupport.staticString(args.get(1));
        String returntype = ListFunctionSupport.staticString(args.get(2));
        List<String> values;
        if ("term".equals(level) && "value".equals(returntype))
        {
            // The one shape the base MetadataProvider contract proves via getCodelistTerms — kept
            // on that accessor so every provider (test stubs included) keeps serving it.
            values = names.stream().distinct().flatMap(n -> provider.getCodelistTerms(n).stream())
                    .distinct().toList();
        }
        else
        {
            values = names.stream().distinct()
                    .flatMap(n -> projectCodelist(provider, n, level, returntype))
                    .filter(Objects::nonNull).distinct().toList();
        }
        if (values.isEmpty())
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "[{0}] {1} resolved no codelist terms for {2} — rule will be skipped",
                    ListFunctionSupport.ruleId(run), CODELIST_TERMS, names);
            throw ListFunctionSupport.unusable(CODELIST_TERMS, ProviderNeed.Kind.LIBRARY,
                    "no codelist terms for " + names);
        }
        return ListFunctionSupport.broadcast(CODELIST_TERMS, values);
    }


    /**
     * Projects one codelist onto the requested {@code (level, returntype)} shape via
     * {@link MetadataProvider#getCodelist}. An unknown codelist — or a provider whose
     * {@code getCodelist} declines (the honest default) — contributes an empty stream.
     */
    private static Stream<String> projectCodelist(MetadataProvider provider, String aName,
            @Nullable String aLevel, @Nullable String aReturntype)
    {
        Optional<ICodeList> opt = provider.getCodelist(aName);
        if (opt.isEmpty())
        {
            return Stream.empty();
        }
        ICodeList cl = opt.get();
        if ("codelist".equals(aLevel))
        {
            String value = switch (aReturntype == null ? "value" : aReturntype)
            {
            case "code" -> codelistMetaString(cl, MetadataKeys.CODELIST_CONCEPT_ID);
            case "pref_term" -> codelistMetaString(cl, MetadataKeys.CODELIST_PREFERRED_TERM);
            default ->
            {
                String sv = codelistMetaString(cl, MetadataKeys.CODELIST_SUBMISSION_VALUE);
                yield sv != null ? sv : cl.getName();
            }
            };
            return value == null ? Stream.empty() : Stream.of(value);
        }
        if ("term".equals(aLevel))
        {
            return cl.getEntries().stream()
                    .map(e -> switch (aReturntype == null ? "code" : aReturntype)
                    {
                    case "value" -> e.getCodeValue();
                    // ⚠ getDecodeValue() is the NCI preferred term only because these
                    // codelists are library-sourced. ⚠⚠ blankToNull is load-bearing:
                    // ICodelistEntry declares getDecodeValue() as a plain String, so a builder
                    // with no preferred term substitutes "" — which would survive distinct() as
                    // the single element [""], a NON-EMPTY list, and the empty-union SKIP would
                    // never fire. Absent must degrade honestly here, at the projection.
                    case "pref_term" -> blankToNull(e.getDecodeValue());
                    default -> e.getConceptId();
                    });
        }
        // A level that is neither "codelist" nor "term" contributes nothing (the seam rejects it
        // at load; kept for a provider-side spelling).
        return Stream.empty();
    }


    /** The codelist-level meta value for {@code aKey}, or {@code null} when absent. */
    private static @Nullable String codelistMetaString(ICodeList aCodelist, String aKey)
    {
        return aCodelist.getMetaValue(aKey).map(Object::toString).orElse(null);
    }


    private static @Nullable String blankToNull(@Nullable String aValue)
    {
        return aValue == null || aValue.isBlank() ? null : aValue;
    }

}
