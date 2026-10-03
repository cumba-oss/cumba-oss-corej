package net.cumba.corej.core.metadata.store.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredDomain;
import net.cumba.corej.core.metadata.store.StoredField;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredScenario;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.metadata.store.StoredVariableSet;
import org.jspecify.annotations.Nullable;

/**
 * The store's <b>errata register</b> (PLAN-store-library-content-errata D1): the corrections the
 * seeder applies to the projected products where the CDISC Library content deviates from the
 * printed standard, or a later version of the same standard corrects it. The register is the
 * resource {@value #RESOURCE}; every entry cites its row of {@code cumba-corej-rules}
 * {@code registers/rule-review-results/CDISC-METADATA-ISSUES.md} (issues {@code #282},
 * {@code #283}, {@code #284}; {@code #285} is deliberately NOT applied). Applied by {@link SeedRun}
 * to the products projected <em>from the source this run</em>, after {@link StoreProjection}'s text
 * normalisation, so every seeding surface — CLI, REST, data browser, OSS twin — writes the same
 * store.
 *
 * <h2>Entry kinds</h2>
 * <ul>
 * <li>{@code set-attribute}: one scalar or list attribute of a variable, CDASH field, variable set
 * or data structure; {@code expected} is the value the source must still carry (as projected and
 * normalised), {@code corrected} replaces it.</li>
 * <li>{@code normalised}: the site must have been changed by {@link StoreTextNormaliser} in this
 * run and now hold {@code corrected}; nothing is written.</li>
 * <li>{@code add-variable}: a class-table root inserted after a named variable, ordinals renumbered
 * (the SDTM 1.5–1.8 domain-specific tables, D2).</li>
 * <li>{@code add-field}: a CDASH IG domain field appended with the ordinal it carries.</li>
 * <li>{@code set-order}: an ADaM structure's variable sets reordered ({@code expected} is the
 * current order, exactly); the sets' existing ordinals are reassigned in the new order.</li>
 * <li>{@code remove-set}: an ADaM variable set removed ({@code expected} is its variable
 * names).</li>
 * <li>{@code move-field}: a CDASH model field moved from a class to a domain.</li>
 * </ul>
 *
 * <h2>Outcomes (D4)</h2> An entry is {@code applied}, {@code stale} — its target exists but the
 * expected value no longer matches (CDISC fixed it upstream, or a rule changed under it): reported,
 * NOT applied, never silently re-applied, a warning on every surface and a failing test until the
 * entry is retired — or {@code unreached}: the product, or the container or variable inside it, is
 * not in this run's source at all (a partial fixture, a source that dropped the product). The
 * real-corpus test asserts every entry applied.
 */
final class StoreErrata
{

    /** The register resource on the class path. */
    static final String RESOURCE = "/metadata/store/store-errata.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> KINDS = Set.of("set-attribute", "normalised", "add-variable",
            "add-field", "set-order", "remove-set", "move-field");

    /** One register entry; the optional members are read per kind. */
    record Entry(String id, String issue, String product, String kind, JsonNode node)
    {

        Map<String, String> where()
        {
            return locator(node.path("where"));
        }


        String attribute()
        {
            return node.path("attribute").asText();
        }


        JsonNode expected()
        {
            return node.path("expected");
        }


        JsonNode corrected()
        {
            return node.path("corrected");
        }


        String authority()
        {
            return node.path("authority").asText();
        }


        private static Map<String, String> locator(JsonNode aWhere)
        {
            Map<String, String> where = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : aWhere.properties())
            {
                where.put(e.getKey(), e.getValue().asText());
            }
            return where;
        }
    }


    /**
     * What applying the register did.
     *
     * @param products
     *            the products with the applied entries written
     * @param applied
     *            the ids applied
     * @param stale
     *            {@code <id>: <reason>} for every entry whose target exists but whose expected
     *            value no longer matches — reported, not applied
     * @param unreached
     *            {@code <id>: <reason>} for every entry whose product or target is not in this
     *            source at all
     */
    record Outcome(Map<String, StoredProduct> products, List<String> applied, List<String> stale,
            List<String> unreached)
    {
    }


    /** A located, rewritable target inside one product. */
    private record Target(String path, List<String> paths, @Nullable Object current,
            Function<UnaryOperator<Object>, StoredProduct> rewrite)
    {

        Target(String aPath, @Nullable Object aCurrent,
                Function<UnaryOperator<Object>, StoredProduct> aRewrite)
        {
            this(aPath, List.of(aPath), aCurrent, aRewrite);
        }
    }


    /** Thrown inside an application to report a stale entry with its reason. */
    private static final class StaleEntry extends Exception
    {

        private static final long serialVersionUID = 1L;

        StaleEntry(String aReason)
        {
            super(aReason);
        }
    }


    /** Thrown inside an application when the entry's container or variable is absent. */
    private static final class Unreached extends Exception
    {

        private static final long serialVersionUID = 1L;

        Unreached(String aReason)
        {
            super(aReason);
        }
    }

    private final List<Entry> entries;

    private StoreErrata(List<Entry> aEntries)
    {
        entries = List.copyOf(aEntries);
    }


    /**
     * Loads the register from the class path.
     *
     * @return the register
     * @throws IllegalStateException
     *             when the resource is missing or malformed — a build defect, never a runtime
     *             condition
     */
    static StoreErrata load()
    {
        try (InputStream in = StoreErrata.class.getResourceAsStream(RESOURCE))
        {
            if (in == null)
            {
                throw new IllegalStateException("errata register missing: " + RESOURCE);
            }
            return parse(MAPPER.readTree(in));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("errata register unreadable: " + RESOURCE, e);
        }
    }


    /**
     * Parses a register document ({@code {"entries": [...]}}).
     *
     * @param aDocument
     *            the document
     * @return the register
     * @throws IllegalStateException
     *             on a duplicate id, an unknown kind or an entry without product or issue
     */
    static StoreErrata parse(JsonNode aDocument)
    {
        List<Entry> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode node : aDocument.path("entries"))
        {
            String id = node.path("id").asText();
            String kind = node.path("kind").asText();
            String product = node.path("product").asText();
            String issue = node.path("issue").asText();
            if (id.isEmpty() || !ids.add(id))
            {
                throw new IllegalStateException("errata register: missing or duplicate id " + id);
            }
            if (!KINDS.contains(kind))
            {
                throw new IllegalStateException("errata register: " + id + " has unknown kind "
                        + kind + " (known: " + KINDS + ")");
            }
            if (product.isEmpty() || !issue.startsWith("#"))
            {
                throw new IllegalStateException(
                        "errata register: " + id + " needs a product and a #issue row");
            }
            entries.add(new Entry(id, issue, product, kind, node));
        }
        return new StoreErrata(entries);
    }


    /**
     * The register's entries in file order.
     *
     * @return the entries
     */
    List<Entry> entries()
    {
        return entries;
    }


    /**
     * Applies the register to the products projected from this run's source.
     *
     * @param aProducts
     *            the products by key (not modified)
     * @param aNormalisedSites
     *            the sites {@link StoreProjection#normalisedSites()} changed this run
     * @return the rewritten products and the per-entry outcomes
     */
    Outcome apply(Map<String, StoredProduct> aProducts, Collection<String> aNormalisedSites)
    {
        Map<String, StoredProduct> products = new TreeMap<>(aProducts);
        Set<String> sites = new HashSet<>(aNormalisedSites);
        List<String> applied = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        List<String> unreached = new ArrayList<>();
        for (Entry entry : entries)
        {
            StoredProduct product = products.get(entry.product());
            if (product == null)
            {
                unreached.add(entry.id() + ": product " + entry.product() + " not in this source");
                continue;
            }
            try
            {
                StoredProduct rewritten = switch (entry.kind())
                {
                case "set-attribute" -> setAttribute(product, entry);
                case "normalised" -> assertNormalised(product, entry, sites);
                case "add-variable" -> addVariable(product, entry);
                case "add-field" -> addField(product, entry);
                case "set-order" -> setOrder(product, entry);
                case "remove-set" -> removeSet(product, entry);
                case "move-field" -> moveField(product, entry);
                default -> throw new IllegalStateException(entry.kind());
                };
                products.put(entry.product(), rewritten);
                applied.add(entry.id());
            }
            catch (StaleEntry e)
            {
                stale.add(entry.id() + ": " + e.getMessage());
            }
            catch (Unreached e)
            {
                unreached.add(entry.id() + ": " + e.getMessage());
            }
        }
        return new Outcome(products, applied, stale, unreached);
    }

    // ------------------------------------------------------------------ the kinds


    private static StoredProduct setAttribute(StoredProduct aProduct, Entry aEntry)
        throws StaleEntry, Unreached
    {
        Target target = locate(aProduct, aEntry.where());
        String attribute = aEntry.attribute();
        Object current = attributeOf(target.current(), attribute);
        Object expected = fromJson(aEntry.expected());
        if (!Objects.equals(current, expected))
        {
            throw new StaleEntry(attribute + " expected " + describe(expected) + " but the source"
                    + " now holds " + describe(current) + " at " + target.path());
        }
        Object corrected = fromJson(aEntry.corrected());
        return target.rewrite().apply(o -> withAttribute(o, attribute, corrected));
    }


    private static StoredProduct assertNormalised(StoredProduct aProduct, Entry aEntry,
            Set<String> aSites)
        throws StaleEntry, Unreached
    {
        Target target = locate(aProduct, aEntry.where());
        String attribute = aEntry.attribute();
        for (String path : target.paths())
        {
            String site = path + "." + attribute;
            if (!aSites.contains(site))
            {
                throw new StaleEntry("the seeder's normalisation no longer changes " + site
                        + " - the source no longer carries the defect");
            }
        }
        Object current = attributeOf(target.current(), attribute);
        Object corrected = fromJson(aEntry.corrected());
        if (!Objects.equals(current, corrected))
        {
            throw new StaleEntry(attribute + " normalised to " + describe(current)
                    + " rather than the registered " + describe(corrected) + " at "
                    + target.path());
        }
        return aProduct;
    }


    private static StoredProduct addVariable(StoredProduct aProduct, Entry aEntry)
        throws StaleEntry, Unreached
    {
        String className = aEntry.where().get("class");
        String after = aEntry.node().path("after").asText();
        JsonNode spec = aEntry.node().path("variable");
        String name = spec.path("name").asText();
        int index = indexOfClass(aProduct, className);
        if (index < 0)
        {
            throw new Unreached("class " + className + " not in " + aProduct.key());
        }
        StoredClass clazz = aProduct.classes().get(index);
        List<StoredVariable> vars = new ArrayList<>(clazz.classVariables());
        int at = -1;
        for (int i = 0; i < vars.size(); i++)
        {
            String n = vars.get(i).name();
            if (name.equals(n))
            {
                throw new StaleEntry(name + " is already in class " + className
                        + " - the source now carries it");
            }
            if (after.equals(n))
            {
                at = i;
            }
            if (!Integer.toString(i + 1).equals(vars.get(i).ordinal()))
            {
                throw new StaleEntry("class " + className + " ordinals are no longer contiguous"
                        + " 1.." + vars.size() + " (" + n + " is " + vars.get(i).ordinal()
                        + "), so the inserted ordinal cannot be derived");
            }
        }
        if (at < 0)
        {
            throw new StaleEntry(
                    "the position variable " + after + " is not in class " + className);
        }
        StoredVariable added = StoredVariable.builder().name(name).label(text(spec, "label"))
                .simpleDatatype(text(spec, "simpleDatatype")).role(text(spec, "role"))
                .roleDescription(text(spec, "roleDescription"))
                .description(text(spec, "description"))
                .usageRestrictions(text(spec, "usageRestrictions")).build();
        vars.add(at + 1, added);
        List<StoredVariable> renumbered = new ArrayList<>(vars.size());
        for (int i = 0; i < vars.size(); i++)
        {
            renumbered.add(
                    withVariableAttribute(vars.get(i), "ordinal", Integer.toString(i + 1), null));
        }
        return withClass(aProduct, index, withClassVariables(clazz, renumbered));
    }


    private static StoredProduct addField(StoredProduct aProduct, Entry aEntry)
        throws StaleEntry, Unreached
    {
        Map<String, String> where = aEntry.where();
        JsonNode spec = aEntry.node().path("field");
        String name = spec.path("name").asText();
        int ci = indexOfClass(aProduct, where.get("class"));
        if (ci < 0)
        {
            throw new Unreached("class " + where.get("class") + " not in " + aProduct.key());
        }
        StoredClass clazz = aProduct.classes().get(ci);
        int di = indexOf(clazz.domains(), StoredDomain::name, where.get("domain"));
        if (di < 0)
        {
            throw new Unreached("domain " + where.get("domain") + " not in class "
                    + where.get("class") + " of " + aProduct.key());
        }
        StoredDomain domain = clazz.domains().get(di);
        if (indexOf(domain.fields(), StoredField::name, name) >= 0)
        {
            throw new StaleEntry(name + " is already a field of domain " + domain.name()
                    + " - the source now carries it");
        }
        StoredField added = StoredField.builder().name(name).ordinal(text(spec, "ordinal"))
                .core(text(spec, "core")).label(text(spec, "label"))
                .definition(text(spec, "definition")).simpleDatatype(text(spec, "simpleDatatype"))
                .completionInstructions(text(spec, "completionInstructions"))
                .implementationNotes(text(spec, "implementationNotes"))
                .mappingInstructions(text(spec, "mappingInstructions")).prompt(text(spec, "prompt"))
                .questionText(text(spec, "questionText")).build();
        List<StoredField> fields = new ArrayList<>(domain.fields());
        fields.add(added);
        List<StoredDomain> domains = new ArrayList<>(clazz.domains());
        domains.set(di, new StoredDomain(domain.name(), domain.label(), domain.ordinal(),
                domain.description(), fields));
        return withClass(aProduct, ci,
                new StoredClass(clazz.name(), clazz.label(), clazz.ordinal(),
                        clazz.classVariables(), clazz.datasets(), clazz.description(), domains,
                        clazz.scenarios(), clazz.cdashModelFields()));
    }


    private static StoredProduct setOrder(StoredProduct aProduct, Entry aEntry)
        throws StaleEntry, Unreached
    {
        String structureName = aEntry.where().get("structure");
        int si = indexOf(aProduct.dataStructures(), StoredDataStructure::name, structureName);
        if (si < 0)
        {
            throw new Unreached("structure " + structureName + " not in " + aProduct.key());
        }
        StoredDataStructure structure = aProduct.dataStructures().get(si);
        List<String> current = structure.variableSets().stream().map(StoredVariableSet::name)
                .toList();
        List<String> expected = stringList(aEntry.expected());
        List<String> corrected = stringList(aEntry.corrected());
        if (!current.equals(expected))
        {
            throw new StaleEntry(
                    "set order expected " + expected + " but the source now holds " + current);
        }
        if (corrected == null || !new HashSet<>(corrected).equals(new HashSet<>(current))
                || corrected.size() != current.size())
        {
            throw new StaleEntry("the corrected order " + corrected
                    + " is not a permutation of the current sets " + current);
        }
        List<String> ordinals = new ArrayList<>();
        for (StoredVariableSet set : structure.variableSets())
        {
            ordinals.add(set.ordinal() == null ? "" : set.ordinal());
        }
        ordinals.sort((a, b) -> Integer.compare(ordinalOf(a), ordinalOf(b)));
        List<StoredVariableSet> reordered = new ArrayList<>();
        for (int i = 0; i < corrected.size(); i++)
        {
            StoredVariableSet set = structure.variableSets().get(
                    indexOf(structure.variableSets(), StoredVariableSet::name, corrected.get(i)));
            String ordinal = ordinals.get(i).isEmpty() ? set.ordinal() : ordinals.get(i);
            reordered.add(new StoredVariableSet(set.name(), set.label(), ordinal, set.variables(),
                    set.description()));
        }
        return withStructure(aProduct, si, withSets(structure, reordered));
    }


    private static StoredProduct removeSet(StoredProduct aProduct, Entry aEntry)
        throws StaleEntry, Unreached
    {
        Map<String, String> where = aEntry.where();
        int si = indexOf(aProduct.dataStructures(), StoredDataStructure::name,
                where.get("structure"));
        if (si < 0)
        {
            throw new Unreached(
                    "structure " + where.get("structure") + " not in " + aProduct.key());
        }
        StoredDataStructure structure = aProduct.dataStructures().get(si);
        int vi = indexOf(structure.variableSets(), StoredVariableSet::name, where.get("set"));
        if (vi < 0)
        {
            throw new Unreached("set " + where.get("set") + " not in structure "
                    + where.get("structure") + " of " + aProduct.key());
        }
        List<String> names = structure.variableSets().get(vi).variables().stream()
                .map(StoredVariable::name).toList();
        List<String> expected = stringList(aEntry.expected());
        if (!names.equals(expected))
        {
            throw new StaleEntry("set " + where.get("set") + " expected the variables " + expected
                    + " but the source now holds " + names);
        }
        List<StoredVariableSet> sets = new ArrayList<>(structure.variableSets());
        sets.remove(vi);
        return withStructure(aProduct, si, withSets(structure, sets));
    }


    private static StoredProduct moveField(StoredProduct aProduct, Entry aEntry)
        throws StaleEntry, Unreached
    {
        String name = aEntry.node().path("field").asText();
        String fromClass = aEntry.node().path("from").path("class").asText();
        String toDomain = aEntry.node().path("to").path("domain").asText();
        boolean create = aEntry.node().path("createDomain").asBoolean(false);
        int ci = indexOfClass(aProduct, fromClass);
        if (ci < 0)
        {
            throw new Unreached("class " + fromClass + " not in " + aProduct.key());
        }
        StoredClass clazz = aProduct.classes().get(ci);
        int fi = indexOf(clazz.cdashModelFields(), StoredField::name, name);
        if (fi < 0)
        {
            throw new StaleEntry(name + " is no longer a field of class " + fromClass);
        }
        int di = indexOf(aProduct.domains(), StoredDomain::name, toDomain);
        if (di < 0 && !create)
        {
            throw new StaleEntry("domain " + toDomain + " is not in " + aProduct.key()
                    + " and the entry does not create it");
        }
        StoredDomain domain = di < 0 ? new StoredDomain(toDomain, null, null, null, List.of())
                : aProduct.domains().get(di);
        if (indexOf(domain.fields(), StoredField::name, name) >= 0)
        {
            throw new StaleEntry(
                    name + " is already a field of domain " + toDomain + " - the source moved it");
        }
        List<StoredField> fromFields = new ArrayList<>(clazz.cdashModelFields());
        StoredField moved = fromFields.remove(fi);
        List<StoredField> toFields = new ArrayList<>(domain.fields());
        toFields.add(moved);
        List<StoredDomain> domains = new ArrayList<>(aProduct.domains());
        StoredDomain rewrittenDomain = new StoredDomain(domain.name(), domain.label(),
                domain.ordinal(), domain.description(), toFields);
        if (di < 0)
        {
            domains.add(rewrittenDomain);
        }
        else
        {
            domains.set(di, rewrittenDomain);
        }
        List<StoredClass> classes = new ArrayList<>(aProduct.classes());
        classes.set(ci,
                new StoredClass(clazz.name(), clazz.label(), clazz.ordinal(),
                        clazz.classVariables(), clazz.datasets(), clazz.description(),
                        clazz.domains(), clazz.scenarios(), fromFields));
        return withDomains(withClasses(aProduct, classes), domains);
    }

    // ------------------------------------------------------------------ locating


    /**
     * Resolves a {@code where} locator to its target: a variable (class / dataset / structure+set),
     * a CDASH field (class+domain / class+scenario / class / domain), a variable set
     * (structure+set) or a data structure. The path mirrors {@link StoreProjection}'s site paths.
     */
    private static Target locate(StoredProduct aProduct, Map<String, String> aWhere)
        throws Unreached
    {
        String key = aProduct.key();
        if (aWhere.containsKey("field"))
        {
            return locateField(aProduct, aWhere);
        }
        if (aWhere.containsKey("structure"))
        {
            int si = indexOf(aProduct.dataStructures(), StoredDataStructure::name,
                    aWhere.get("structure"));
            if (si < 0)
            {
                throw new Unreached("structure " + aWhere.get("structure") + " not in " + key);
            }
            StoredDataStructure structure = aProduct.dataStructures().get(si);
            String path = key + "/dataStructures[" + structure.name() + "]";
            if (!aWhere.containsKey("set"))
            {
                return new Target(path, structure, op -> withStructure(aProduct, si,
                        (StoredDataStructure) op.apply(structure)));
            }
            int vi = indexOf(structure.variableSets(), StoredVariableSet::name, aWhere.get("set"));
            if (vi < 0)
            {
                throw new Unreached("set " + aWhere.get("set") + " not in structure "
                        + structure.name() + " of " + key);
            }
            StoredVariableSet set = structure.variableSets().get(vi);
            String setPath = path + "/variableSets[" + set.name() + "]";
            if (!aWhere.containsKey("variable"))
            {
                return new Target(setPath, set, op ->
                {
                    List<StoredVariableSet> sets = new ArrayList<>(structure.variableSets());
                    sets.set(vi, (StoredVariableSet) op.apply(set));
                    return withStructure(aProduct, si, withSets(structure, sets));
                });
            }
            int xi = indexOf(set.variables(), StoredVariable::name, aWhere.get("variable"));
            if (xi < 0)
            {
                throw new Unreached("variable " + aWhere.get("variable") + " not in set "
                        + set.name() + " of " + key);
            }
            StoredVariable variable = set.variables().get(xi);
            return new Target(setPath + "/variables[" + variable.name() + "]", variable, op ->
            {
                List<StoredVariable> vars = new ArrayList<>(set.variables());
                vars.set(xi, (StoredVariable) op.apply(variable));
                List<StoredVariableSet> sets = new ArrayList<>(structure.variableSets());
                sets.set(vi, new StoredVariableSet(set.name(), set.label(), set.ordinal(), vars,
                        set.description()));
                return withStructure(aProduct, si, withSets(structure, sets));
            });
        }
        if (aWhere.containsKey("dataset"))
        {
            return locateDatasetVariable(aProduct, aWhere.get("dataset"), aWhere.get("variable"));
        }
        if (aWhere.containsKey("class"))
        {
            int ci = indexOfClass(aProduct, aWhere.get("class"));
            if (ci < 0)
            {
                throw new Unreached("class " + aWhere.get("class") + " not in " + key);
            }
            StoredClass clazz = aProduct.classes().get(ci);
            int xi = indexOf(clazz.classVariables(), StoredVariable::name, aWhere.get("variable"));
            if (xi < 0)
            {
                throw new Unreached("variable " + aWhere.get("variable") + " not in class "
                        + clazz.name() + " of " + key);
            }
            StoredVariable variable = clazz.classVariables().get(xi);
            String path = key + "/classes[" + clazz.name() + "]/classVariables[" + variable.name()
                    + "]";
            return new Target(path, variable, op ->
            {
                List<StoredVariable> vars = new ArrayList<>(clazz.classVariables());
                vars.set(xi, (StoredVariable) op.apply(variable));
                return withClass(aProduct, ci, withClassVariables(clazz, vars));
            });
        }
        throw new Unreached("locator " + aWhere + " names no class, dataset, structure or field");
    }


    /**
     * A dataset variable: under the dataset's class and — in the SDTM models, which publish every
     * dataset twice, byte-identical — at the product's top level as well. Every copy is rewritten,
     * so the two views of a model dataset never diverge; the copies must agree before the entry
     * applies.
     */
    private static Target locateDatasetVariable(StoredProduct aProduct, @Nullable String aDataset,
            @Nullable String aVariable)
        throws Unreached
    {
        String key = aProduct.key();
        List<String> paths = new ArrayList<>();
        List<Function<UnaryOperator<Object>, UnaryOperator<StoredProduct>>> rewrites = new ArrayList<>();
        StoredVariable first = null;
        for (int ci = 0; ci < aProduct.classes().size(); ci++)
        {
            StoredClass clazz = aProduct.classes().get(ci);
            int di = indexOf(clazz.datasets(), StoredDataset::name, aDataset);
            if (di < 0)
            {
                continue;
            }
            StoredDataset dataset = clazz.datasets().get(di);
            int xi = indexOf(dataset.variables(), StoredVariable::name, aVariable);
            if (xi < 0)
            {
                throw new Unreached("variable " + aVariable + " not in dataset " + aDataset
                        + " (class " + clazz.name() + ") of " + key);
            }
            StoredVariable variable = dataset.variables().get(xi);
            if (first == null)
            {
                first = variable;
            }
            else if (!first.equals(variable))
            {
                throw new Unreached("the copies of " + aDataset + "." + aVariable + " in " + key
                        + " differ, so one correction cannot serve both");
            }
            paths.add(key + "/classes[" + clazz.name() + "]/datasets[" + dataset.name()
                    + "]/variables[" + variable.name() + "]");
            int classIndex = ci;
            rewrites.add(op -> product ->
            {
                StoredClass c = product.classes().get(classIndex);
                StoredDataset d = c.datasets().get(di);
                List<StoredVariable> vars = new ArrayList<>(d.variables());
                vars.set(xi, (StoredVariable) op.apply(d.variables().get(xi)));
                List<StoredDataset> datasets = new ArrayList<>(c.datasets());
                datasets.set(di, withVariables(d, vars));
                return withClass(product, classIndex,
                        new StoredClass(c.name(), c.label(), c.ordinal(), c.classVariables(),
                                datasets, c.description(), c.domains(), c.scenarios(),
                                c.cdashModelFields()));
            });
        }
        int di = indexOf(aProduct.datasets(), StoredDataset::name, aDataset);
        if (di >= 0)
        {
            StoredDataset dataset = aProduct.datasets().get(di);
            int xi = indexOf(dataset.variables(), StoredVariable::name, aVariable);
            if (xi < 0)
            {
                throw new Unreached(
                        "variable " + aVariable + " not in dataset " + aDataset + " of " + key);
            }
            StoredVariable variable = dataset.variables().get(xi);
            if (first == null)
            {
                first = variable;
            }
            else if (!first.equals(variable))
            {
                throw new Unreached("the copies of " + aDataset + "." + aVariable + " in " + key
                        + " differ, so one correction cannot serve both");
            }
            paths.add(key + "/datasets[" + dataset.name() + "]/variables[" + variable.name() + "]");
            rewrites.add(op -> product ->
            {
                StoredDataset d = product.datasets().get(di);
                List<StoredVariable> vars = new ArrayList<>(d.variables());
                vars.set(xi, (StoredVariable) op.apply(d.variables().get(xi)));
                List<StoredDataset> datasets = new ArrayList<>(product.datasets());
                datasets.set(di, withVariables(d, vars));
                return withDatasets(product, datasets);
            });
        }
        if (first == null)
        {
            throw new Unreached("dataset " + aDataset + " not in " + key);
        }
        return new Target(paths.get(0), paths, first, op ->
        {
            StoredProduct product = aProduct;
            for (Function<UnaryOperator<Object>, UnaryOperator<StoredProduct>> rewrite : rewrites)
            {
                product = rewrite.apply(op).apply(product);
            }
            return product;
        });
    }


    private static Target locateField(StoredProduct aProduct, Map<String, String> aWhere)
        throws Unreached
    {
        String key = aProduct.key();
        String name = aWhere.get("field");
        if (!aWhere.containsKey("class"))
        {
            int di = indexOf(aProduct.domains(), StoredDomain::name, aWhere.get("domain"));
            if (di < 0)
            {
                throw new Unreached("domain " + aWhere.get("domain") + " not in " + key);
            }
            StoredDomain domain = aProduct.domains().get(di);
            int fi = indexOf(domain.fields(), StoredField::name, name);
            if (fi < 0)
            {
                throw new Unreached(
                        "field " + name + " not in domain " + domain.name() + " of " + key);
            }
            StoredField field = domain.fields().get(fi);
            String path = key + "/domains[" + domain.name() + "]/fields[" + field.name() + "]";
            return new Target(path, field, op ->
            {
                List<StoredField> fields = new ArrayList<>(domain.fields());
                fields.set(fi, (StoredField) op.apply(field));
                List<StoredDomain> domains = new ArrayList<>(aProduct.domains());
                domains.set(di, new StoredDomain(domain.name(), domain.label(), domain.ordinal(),
                        domain.description(), fields));
                return withDomains(aProduct, domains);
            });
        }
        int ci = indexOfClass(aProduct, aWhere.get("class"));
        if (ci < 0)
        {
            throw new Unreached("class " + aWhere.get("class") + " not in " + key);
        }
        StoredClass clazz = aProduct.classes().get(ci);
        String classPath = key + "/classes[" + clazz.name() + "]";
        if (aWhere.containsKey("domain"))
        {
            int di = indexOf(clazz.domains(), StoredDomain::name, aWhere.get("domain"));
            if (di < 0)
            {
                throw new Unreached("domain " + aWhere.get("domain") + " not in class "
                        + clazz.name() + " of " + key);
            }
            StoredDomain domain = clazz.domains().get(di);
            int fi = indexOf(domain.fields(), StoredField::name, name);
            if (fi < 0)
            {
                throw new Unreached(
                        "field " + name + " not in domain " + domain.name() + " of " + key);
            }
            StoredField field = domain.fields().get(fi);
            String path = classPath + "/domains[" + domain.name() + "]/fields[" + field.name()
                    + "]";
            return new Target(path, field, op ->
            {
                List<StoredField> fields = new ArrayList<>(domain.fields());
                fields.set(fi, (StoredField) op.apply(field));
                List<StoredDomain> domains = new ArrayList<>(clazz.domains());
                domains.set(di, new StoredDomain(domain.name(), domain.label(), domain.ordinal(),
                        domain.description(), fields));
                return withClass(aProduct, ci,
                        new StoredClass(clazz.name(), clazz.label(), clazz.ordinal(),
                                clazz.classVariables(), clazz.datasets(), clazz.description(),
                                domains, clazz.scenarios(), clazz.cdashModelFields()));
            });
        }
        if (aWhere.containsKey("scenario"))
        {
            int si = indexOf(clazz.scenarios(), StoredScenario::scenario, aWhere.get("scenario"));
            if (si < 0)
            {
                throw new Unreached("scenario " + aWhere.get("scenario") + " not in class "
                        + clazz.name() + " of " + key);
            }
            StoredScenario scenario = clazz.scenarios().get(si);
            int fi = indexOf(scenario.fields(), StoredField::name, name);
            if (fi < 0)
            {
                throw new Unreached(
                        "field " + name + " not in scenario " + scenario.scenario() + " of " + key);
            }
            StoredField field = scenario.fields().get(fi);
            String path = classPath + "/scenarios[" + scenario.scenario() + "]/fields["
                    + field.name() + "]";
            return new Target(path, field, op ->
            {
                List<StoredField> fields = new ArrayList<>(scenario.fields());
                fields.set(fi, (StoredField) op.apply(field));
                List<StoredScenario> scenarios = new ArrayList<>(clazz.scenarios());
                scenarios.set(si, new StoredScenario(scenario.scenario(), scenario.domain(),
                        scenario.domainName(), scenario.ordinal(), fields));
                return withClass(aProduct, ci,
                        new StoredClass(clazz.name(), clazz.label(), clazz.ordinal(),
                                clazz.classVariables(), clazz.datasets(), clazz.description(),
                                clazz.domains(), scenarios, clazz.cdashModelFields()));
            });
        }
        int fi = indexOf(clazz.cdashModelFields(), StoredField::name, name);
        if (fi < 0)
        {
            throw new Unreached("field " + name + " not in class " + clazz.name() + " of " + key);
        }
        StoredField field = clazz.cdashModelFields().get(fi);
        String path = classPath + "/cdashModelFields[" + field.name() + "]";
        return new Target(path, field, op ->
        {
            List<StoredField> fields = new ArrayList<>(clazz.cdashModelFields());
            fields.set(fi, (StoredField) op.apply(field));
            return withClass(aProduct, ci,
                    new StoredClass(clazz.name(), clazz.label(), clazz.ordinal(),
                            clazz.classVariables(), clazz.datasets(), clazz.description(),
                            clazz.domains(), clazz.scenarios(), fields));
        });
    }

    // ------------------------------------------------------------------ attributes


    /** The named attribute of a variable, field, set or structure; {@code null} when absent. */
    static @Nullable Object attributeOf(@Nullable Object aTarget, String aAttribute)
    {
        if (aTarget instanceof StoredVariable v)
        {
            return switch (aAttribute)
            {
            case "name" -> v.name();
            case "label" -> v.label();
            case "ordinal" -> v.ordinal();
            case "core" -> v.core();
            case "role" -> v.role();
            case "simpleDatatype" -> v.simpleDatatype();
            case "description" -> v.description();
            case "describedValueDomain" -> v.describedValueDomain();
            case "roleDescription" -> v.roleDescription();
            case "definition" -> v.definition();
            case "notes" -> v.notes();
            case "examples" -> v.examples();
            case "usageRestrictions" -> v.usageRestrictions();
            case "variableCcode" -> v.variableCcode();
            case "codelistIds" -> v.codelistIds();
            case "codelistSubmissionValues" -> v.codelistSubmissionValues();
            default -> throw new IllegalArgumentException(
                    "not a variable attribute: " + aAttribute);
            };
        }
        if (aTarget instanceof StoredField f)
        {
            return switch (aAttribute)
            {
            case "name" -> f.name();
            case "label" -> f.label();
            case "ordinal" -> f.ordinal();
            case "core" -> f.core();
            case "definition" -> f.definition();
            case "simpleDatatype" -> f.simpleDatatype();
            case "completionInstructions" -> f.completionInstructions();
            case "implementationNotes" -> f.implementationNotes();
            case "mappingInstructions" -> f.mappingInstructions();
            case "prompt" -> f.prompt();
            case "questionText" -> f.questionText();
            case "codelistIds" -> f.codelistIds();
            default -> throw new IllegalArgumentException("not a field attribute: " + aAttribute);
            };
        }
        if (aTarget instanceof StoredVariableSet s)
        {
            return switch (aAttribute)
            {
            case "name" -> s.name();
            case "label" -> s.label();
            case "ordinal" -> s.ordinal();
            case "description" -> s.description();
            default -> throw new IllegalArgumentException("not a set attribute: " + aAttribute);
            };
        }
        if (aTarget instanceof StoredDataStructure d)
        {
            return switch (aAttribute)
            {
            case "label" -> d.label();
            case "description" -> d.description();
            default -> throw new IllegalArgumentException(
                    "not a structure attribute: " + aAttribute);
            };
        }
        throw new IllegalArgumentException("no attributes on " + aTarget);
    }


    @SuppressWarnings("unchecked")
    private static Object withAttribute(Object aTarget, String aAttribute, @Nullable Object aValue)
    {
        String s = aValue instanceof String str ? str : null;
        List<String> l = aValue instanceof List<?> list ? (List<String>) list : null;
        if (aTarget instanceof StoredVariable v)
        {
            return withVariableAttribute(v, aAttribute, s, l);
        }
        if (aTarget instanceof StoredField f)
        {
            return withFieldAttribute(f, aAttribute, s, l);
        }
        if (aTarget instanceof StoredVariableSet set)
        {
            return switch (aAttribute)
            {
            case "name" -> new StoredVariableSet(s, set.label(), set.ordinal(), set.variables(),
                    set.description());
            case "label" -> new StoredVariableSet(set.name(), s, set.ordinal(), set.variables(),
                    set.description());
            case "ordinal" -> new StoredVariableSet(set.name(), set.label(), s, set.variables(),
                    set.description());
            case "description" -> new StoredVariableSet(set.name(), set.label(), set.ordinal(),
                    set.variables(), s);
            default -> throw new IllegalArgumentException("not a set attribute: " + aAttribute);
            };
        }
        if (aTarget instanceof StoredDataStructure d)
        {
            return switch (aAttribute)
            {
            case "label" -> new StoredDataStructure(d.name(), s, d.ordinal(), d.className(),
                    d.subClass(), d.variableSets(), d.description());
            case "description" -> new StoredDataStructure(d.name(), d.label(), d.ordinal(),
                    d.className(), d.subClass(), d.variableSets(), s);
            default -> throw new IllegalArgumentException(
                    "not a structure attribute: " + aAttribute);
            };
        }
        throw new IllegalArgumentException("no attributes on " + aTarget);
    }


    /**
     * The variable with one attribute replaced — through the record's canonical constructor, not
     * the Lombok builder, because a builder type in a signature is invisible to the javadoc gate.
     */
    private static StoredVariable withVariableAttribute(StoredVariable v, String aAttribute,
            @Nullable String s, @Nullable List<String> l)
    {
        String name = v.name();
        String label = v.label();
        String ordinal = v.ordinal();
        String core = v.core();
        String role = v.role();
        String simpleDatatype = v.simpleDatatype();
        String description = v.description();
        String describedValueDomain = v.describedValueDomain();
        List<String> codelistSubmissionValues = v.codelistSubmissionValues();
        String roleDescription = v.roleDescription();
        String definition = v.definition();
        String notes = v.notes();
        String examples = v.examples();
        String usageRestrictions = v.usageRestrictions();
        String variableCcode = v.variableCcode();
        List<String> codelistIds = v.codelistIds();
        switch (aAttribute)
        {
        case "name" -> name = s;
        case "label" -> label = s;
        case "ordinal" -> ordinal = s;
        case "core" -> core = s;
        case "role" -> role = s;
        case "simpleDatatype" -> simpleDatatype = s;
        case "description" -> description = s;
        case "describedValueDomain" -> describedValueDomain = s;
        case "roleDescription" -> roleDescription = s;
        case "definition" -> definition = s;
        case "notes" -> notes = s;
        case "examples" -> examples = s;
        case "usageRestrictions" -> usageRestrictions = s;
        case "variableCcode" -> variableCcode = s;
        case "codelistIds" -> codelistIds = l;
        case "codelistSubmissionValues" -> codelistSubmissionValues = l;
        default -> throw new IllegalArgumentException("not a variable attribute: " + aAttribute);
        }
        return new StoredVariable(name, label, ordinal, core, role, simpleDatatype, description,
                v.valueList(), describedValueDomain, codelistSubmissionValues, roleDescription,
                definition, notes, examples, usageRestrictions, variableCcode, codelistIds);
    }


    private static StoredField withFieldAttribute(StoredField f, String aAttribute,
            @Nullable String s, @Nullable List<String> l)
    {
        String name = f.name();
        String label = f.label();
        String ordinal = f.ordinal();
        String core = f.core();
        String definition = f.definition();
        String simpleDatatype = f.simpleDatatype();
        String completionInstructions = f.completionInstructions();
        String implementationNotes = f.implementationNotes();
        String mappingInstructions = f.mappingInstructions();
        String prompt = f.prompt();
        String questionText = f.questionText();
        List<String> codelistIds = f.codelistIds();
        switch (aAttribute)
        {
        case "name" -> name = s;
        case "label" -> label = s;
        case "ordinal" -> ordinal = s;
        case "core" -> core = s;
        case "definition" -> definition = s;
        case "simpleDatatype" -> simpleDatatype = s;
        case "completionInstructions" -> completionInstructions = s;
        case "implementationNotes" -> implementationNotes = s;
        case "mappingInstructions" -> mappingInstructions = s;
        case "prompt" -> prompt = s;
        case "questionText" -> questionText = s;
        case "codelistIds" -> codelistIds = l;
        default -> throw new IllegalArgumentException("not a field attribute: " + aAttribute);
        }
        return new StoredField(name, label, ordinal, core, definition, simpleDatatype,
                f.codelistSubmissionValues(), completionInstructions, implementationNotes,
                mappingInstructions, prompt, questionText, f.domainSpecific(), codelistIds);
    }

    // ------------------------------------------------------------------ record copies


    private static StoredProduct withClasses(StoredProduct p, List<StoredClass> aClasses)
    {
        return new StoredProduct(p.key(), p.name(), p.label(), p.version(), p.modelHref(),
                p.description(), p.effectiveDate(), p.registrationStatus(), p.source(), aClasses,
                p.datasets(), p.dataStructures(), p.domains());
    }


    private static StoredProduct withDatasets(StoredProduct p, List<StoredDataset> aDatasets)
    {
        return new StoredProduct(p.key(), p.name(), p.label(), p.version(), p.modelHref(),
                p.description(), p.effectiveDate(), p.registrationStatus(), p.source(), p.classes(),
                aDatasets, p.dataStructures(), p.domains());
    }


    private static StoredProduct withStructures(StoredProduct p,
            List<StoredDataStructure> aStructures)
    {
        return new StoredProduct(p.key(), p.name(), p.label(), p.version(), p.modelHref(),
                p.description(), p.effectiveDate(), p.registrationStatus(), p.source(), p.classes(),
                p.datasets(), aStructures, p.domains());
    }


    private static StoredProduct withDomains(StoredProduct p, List<StoredDomain> aDomains)
    {
        return new StoredProduct(p.key(), p.name(), p.label(), p.version(), p.modelHref(),
                p.description(), p.effectiveDate(), p.registrationStatus(), p.source(), p.classes(),
                p.datasets(), p.dataStructures(), aDomains);
    }


    private static StoredProduct withClass(StoredProduct aProduct, int aIndex, StoredClass aClass)
    {
        List<StoredClass> classes = new ArrayList<>(aProduct.classes());
        classes.set(aIndex, aClass);
        return withClasses(aProduct, classes);
    }


    private static StoredProduct withStructure(StoredProduct aProduct, int aIndex,
            StoredDataStructure aStructure)
    {
        List<StoredDataStructure> structures = new ArrayList<>(aProduct.dataStructures());
        structures.set(aIndex, aStructure);
        return withStructures(aProduct, structures);
    }


    private static StoredClass withClassVariables(StoredClass c, List<StoredVariable> aVariables)
    {
        return new StoredClass(c.name(), c.label(), c.ordinal(), aVariables, c.datasets(),
                c.description(), c.domains(), c.scenarios(), c.cdashModelFields());
    }


    private static StoredDataset withVariables(StoredDataset d, List<StoredVariable> aVariables)
    {
        return new StoredDataset(d.name(), d.label(), d.ordinal(), d.datasetStructure(), aVariables,
                d.description(), d.status());
    }


    private static StoredDataStructure withSets(StoredDataStructure d,
            List<StoredVariableSet> aSets)
    {
        return new StoredDataStructure(d.name(), d.label(), d.ordinal(), d.className(),
                d.subClass(), aSets, d.description());
    }

    // ------------------------------------------------------------------ small helpers


    private static int indexOfClass(StoredProduct aProduct, @Nullable String aName)
    {
        return indexOf(aProduct.classes(), StoredClass::name, aName);
    }


    private static <T> int indexOf(List<T> aList, Function<T, @Nullable String> aName,
            @Nullable String aWanted)
    {
        if (aWanted == null)
        {
            return -1;
        }
        for (int i = 0; i < aList.size(); i++)
        {
            if (aWanted.equals(aName.apply(aList.get(i))))
            {
                return i;
            }
        }
        return -1;
    }


    private static int ordinalOf(String aOrdinal)
    {
        try
        {
            return aOrdinal.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(aOrdinal);
        }
        catch (NumberFormatException _)
        {
            return Integer.MAX_VALUE;
        }
    }


    private static @Nullable String text(JsonNode aNode, String aField)
    {
        JsonNode value = aNode.get(aField);
        return value == null || value.isNull() ? null : value.asText();
    }


    private static @Nullable List<String> stringList(JsonNode aNode)
    {
        if (aNode == null || aNode.isNull() || aNode.isMissingNode() || !aNode.isArray())
        {
            return null;
        }
        List<String> out = new ArrayList<>();
        aNode.forEach(n -> out.add(n.asText()));
        return out;
    }


    /** A JSON scalar or string array as the attribute value type ({@code null} for null). */
    private static @Nullable Object fromJson(JsonNode aNode)
    {
        if (aNode == null || aNode.isNull() || aNode.isMissingNode())
        {
            return null;
        }
        if (aNode.isArray())
        {
            return stringList(aNode);
        }
        return aNode.asText();
    }


    private static String describe(@Nullable Object aValue)
    {
        return aValue == null ? "<absent>" : "'" + aValue + "'";
    }
}
