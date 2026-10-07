package net.cumba.corej.authoring;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.cumba.corej.authoring.RuleDiagnostic.Document;
import net.cumba.corej.authoring.RuleDiagnostic.Severity;
import net.cumba.corej.authoring.RuleDiagnostic.TextRange;
import net.cumba.corej.authoring.RuleDiagnostic.YamlPath;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleClassifier;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.model.Executability;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.corej.core.model.StandardRef;
import org.jspecify.annotations.Nullable;

/**
 * The per-rule half of corpus assembly, in main scope: an authored check (the {@code rules-src}
 * split-storage YAML) plus its optional docs sidecar become the packaged rule node exactly as
 * {@code RuleCorpusGenerator} emits it, and that node is loaded through {@link RulePackageLoader}
 * so the caller holds the engine's own view of the rule.
 *
 * <h2>One implementation, two consumers</h2>
 *
 * <p>
 * {@code cumba-corej-rules}' {@code RuleStorageAssembler} drives {@link #rebuildRule} over the
 * whole corpus (that is what the corpus gate proves); the rule editor drives {@link #assemble} over
 * one rule at a time. Both paths run the same code, so an editor's verdict on a rule is the gate's
 * verdict ({@code PLAN-rule-authoring-api} §3.1). ⛔ Nothing here may hold a transform the corpus
 * generator does not run, or the reverse.
 * </p>
 *
 * <h2>What assembly is — and is not</h2>
 *
 * <p>
 * Assembly is a pure join of the rule's own fields: the check minus {@code Standards}, plus the
 * {@code Authorities} tree rebuilt from {@code Standards} with the sidecar's {@code Citations}
 * re-attached, plus a <em>validation-only</em> Sensitivity derivation (the derived value is never
 * written; {@code RulePackageLoader.finishLoad} re-derives it at load). The released shape then
 * drops {@code Source} / {@code Source_Proposed} and collapses {@code Authorities}
 * ({@link ReleaseShapeTrimmer}). Provider {@code Requirements} are no longer materialised (owner
 * ruling 2026-09-08). ⚠ Because this code now ships in a jar, a corpus regenerated against a stale
 * installed engine can differ from one regenerated against the current one — see
 * {@link AuthoringProvenance}.
 * </p>
 */
public final class AuthoredRuleAssembler
{

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * The tree reader every authored file goes through — the editor's {@link #assemble} and the
     * corpus tools' {@link #readYamlTree} alike: a token after the first document's root is an
     * error, not a silently dropped second document (pack-2 review 8a-A F4).
     */
    private static final ObjectReader YAML_TREE = YAML.readerFor(JsonNode.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Binds a {@code rules-src} check node to {@link Rule} for derivation. Lenient because the
     * source YAML carries authoring-only blocks ({@code Standards}, {@code Source}) that the
     * runtime model does not model.
     */
    private static final ObjectMapper LENIENT = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * The {@code Executability} value that parks a rule — {@link Executability#NOT_EXECUTABLE}'s
     * authored form. Taken from the enum rather than re-typed, so a rename cannot silently turn the
     * skip into a no-op.
     */
    public static final String NOT_EXECUTABLE = Executability.NOT_EXECUTABLE.getJsonValue();

    /** The placeholder id of a check that carries no {@code Core.Id}. */
    public static final String UNKNOWN_ID = "<unknown>";

    private static final String STANDARDS = "Standards";

    private static final String REFERENCES = "References";

    private static final String CITATIONS = "Citations";

    private static final String ORGANIZATION = "Organization";

    /** The code of a check (or docs sidecar) that holds a second YAML document. */
    public static final String MULTI_DOCUMENT = "MULTI_DOCUMENT";

    /** The code of a {@code Standards} block {@link #buildAuthorities} cannot join. */
    public static final String STANDARDS_SHAPE = "STANDARDS_SHAPE";

    /** The code of a load failure whose root cause is an expression the parser refused. */
    public static final String EXPRESSION_SYNTAX = "EXPRESSION_SYNTAX";

    /** The code of a docs sidecar whose {@code Core} names another rule. */
    public static final String DOC_CORE_MISMATCH = "DOC_CORE_MISMATCH";

    private AuthoredRuleAssembler()
    {
    }

    // ---- the editor's entry point ---------------------------------------------------------


    /**
     * Assemble, package and load one authored rule.
     *
     * <p>
     * Never throws for a defect in the rule: a YAML syntax error, a missing {@code Core.Id}, an
     * underivable {@code Sensitivity}, a parked rule and every per-rule load error / warning come
     * back as {@link RuleDiagnostic}s; the nodes are still produced wherever the input allows.
     * </p>
     *
     * @param aCheckYaml
     *            the check file's text
     * @param aDocYaml
     *            the docs sidecar's text, or {@code null} when the rule has none
     * @param aPackageStandards
     *            the {@code standards} declaration of the package the rule is loaded in
     *            ({@link PackageStandardsMap#forPackage}); empty for a package declaring none
     * @return the assembled rule
     */
    public static AssembledRule assemble(String aCheckYaml, @Nullable String aDocYaml,
            List<StandardRef> aPackageStandards)
    {
        Objects.requireNonNull(aCheckYaml, "check YAML");
        Objects.requireNonNull(aPackageStandards, "package standards");
        List<RuleDiagnostic> diagnostics = new ArrayList<>();
        YamlLocations locations = YamlLocations.index(aCheckYaml);
        if (locations.secondDocument() != null)
        {
            diagnostics.add(multiDocument("check", locations, Document.CHECK));
            return new AssembledRule(UNKNOWN_ID, null, null, null, diagnostics, false);
        }

        JsonNode check = readYaml(aCheckYaml, "YAML_SYNTAX", "check", diagnostics, Document.CHECK);
        if (check == null || !check.isObject())
        {
            if (check != null)
            {
                diagnostics.add(new RuleDiagnostic("NOT_A_MAPPING", Severity.ERROR,
                        "the check file is not a YAML mapping", new YamlPath(List.of()), null));
            }
            return new AssembledRule(UNKNOWN_ID, null, null, null, diagnostics, false);
        }
        String coreId = textOrNull(check.path("Core").path("Id"));
        if (coreId == null)
        {
            YamlPath path = YamlPath.of("Core/Id");
            diagnostics.add(new RuleDiagnostic("CORE_ID_MISSING", Severity.ERROR,
                    "Core.Id is missing", path, locations.rangeOf(path)));
            coreId = UNKNOWN_ID;
        }
        JsonNode doc = readDoc(aDocYaml, coreId, diagnostics);

        boolean parked = isParked(check);
        if (parked)
        {
            YamlPath path = YamlPath.of("Executability");
            diagnostics.add(new RuleDiagnostic("PARKED", Severity.INFO,
                    "parked: Executability \"" + NOT_EXECUTABLE
                            + "\" — the rule is omitted from every package and never loaded",
                    path, locations.rangeOf(path)));
        }
        // A parked rule is dropped by the corpus gate before anything is derived for it, so its
        // Sensitivity is not derived here either (PACK 3 ride-along; the rule editor's analysis in
        // cumba-corej-rule-editor-rest filtered the SENSITIVITY_UNDERIVABLE diagnostic of a parked
        // rule itself, a workaround this makes redundant).
        if (!parked)
        {
            try
            {
                requireDerivableSensitivity(check);
            }
            catch (IllegalStateException e)
            {
                // A rule that cannot be BOUND (a malformed Check) fails the derivation too; that
                // is the load step's finding, reported once there with the expression's position.
                if (!(e.getCause() instanceof JsonProcessingException))
                {
                    YamlPath path = YamlPath.of("Sensitivity");
                    diagnostics.add(new RuleDiagnostic("SENSITIVITY_UNDERIVABLE", Severity.ERROR,
                            String.valueOf(e.getMessage()), path, locations.rangeOf(path)));
                }
            }
        }
        ShapeProblem shape = standardsShapeProblem(check);
        if (shape != null)
        {
            // buildAuthorities cannot join this shape — no node rather than an NPE / CCE out of a
            // method that never throws for a defect in the rule (review 8a-A F2)
            diagnostics.add(new RuleDiagnostic(STANDARDS_SHAPE, Severity.ERROR, shape.message(),
                    shape.path(), locations.rangeOf(shape.path())));
            return new AssembledRule(coreId, null, null, null, diagnostics, parked);
        }
        ObjectNode canonical = assembleNode(check, doc);
        JsonNode packaged = packagedRuleNode(canonical, coreId);

        Rule rule = null;
        if (!parked && !UNKNOWN_ID.equals(coreId))
        {
            try
            {
                // DEBUG, not INFO: here one package is one edit, not one run (editor note)
                RulePackage pkg = RulePackageLoader.loadFromString(
                        packageJson(packaged, coreId, aPackageStandards),
                        System.Logger.Level.DEBUG);
                rule = pkg.getRules() == null ? null : pkg.getRules().get(coreId);
                if (rule == null)
                {
                    diagnostics.add(new RuleDiagnostic("NOT_LOADED", Severity.ERROR,
                            "the loader returned no rule under " + coreId, null, null));
                }
                else
                {
                    diagnostics.addAll(LoadErrorDiagnostics.of(rule, locations));
                }
            }
            catch (IOException | RuntimeException e)
            {
                diagnostics.add(loadFailure(e, coreId, locations));
            }
        }
        return new AssembledRule(coreId, rule, canonical, packaged, diagnostics, parked);
    }


    /**
     * The diagnostic of a package that could not be loaded at all. A bind failure names the rule
     * field it died in (Jackson's reference chain, minus the {@code rules.<id>} prefix) and, when
     * the root cause is an {@link ExpressionException} with a position, the character of the
     * authored expression it points at.
     */
    static RuleDiagnostic loadFailure(Exception aFailure, String aCoreId, YamlLocations aLocations)
    {
        YamlPath path = null;
        if (aFailure instanceof JsonMappingException jme)
        {
            List<String> segments = new ArrayList<>();
            for (JsonMappingException.Reference ref : jme.getPath())
            {
                if (ref.getFieldName() != null)
                {
                    segments.add(ref.getFieldName());
                }
                else if (ref.getIndex() >= 0)
                {
                    // a sequence element (Bindings[2]): kept, so the path reaches its expression
                    segments.add(Integer.toString(ref.getIndex()));
                }
            }
            // rules -> <id> -> <field> ...: the authored path starts after the rule's key.
            int idAt = segments.indexOf(aCoreId);
            if (idAt >= 0 && idAt + 1 < segments.size())
            {
                path = new YamlPath(segments.subList(idAt + 1, segments.size()));
            }
        }
        Throwable root = aFailure;
        while (root.getCause() != null && root.getCause() != root)
        {
            root = root.getCause();
        }
        TextRange range = null;
        if (path != null)
        {
            range = aLocations.rangeOf(path);
            if (root instanceof ExpressionException ee && ee.getPosition() >= 0)
            {
                // The expression leaf is `<path>/expression` (a level-keyed Check nests one
                // deeper) — or the path itself when the reference chain already ends there. The
                // value index maps the offset in every scalar style, or answers null and the key's
                // range stands (review 8a-A F1).
                List<String> segs = path.segments();
                YamlPath leaf = !segs.isEmpty() && "expression".equals(segs.getLast()) ? path
                        : path.child("expression");
                YamlLocations.ValueLocation value = aLocations.valueOf(leaf);
                TextRange at = value == null ? null : value.rangeAt(ee.getPosition());
                if (at != null)
                {
                    range = at;
                }
            }
        }
        String message = root == aFailure || root.getMessage() == null
                ? String.valueOf(aFailure.getMessage())
                : root.getMessage();
        return new RuleDiagnostic(
                root instanceof ExpressionException ? EXPRESSION_SYNTAX : "PACKAGE_LOAD",
                Severity.ERROR, "the packaged rule could not be loaded: " + message, path, range);
    }


    private static @Nullable JsonNode readYaml(String aText, String aCode, String aWhat,
            List<RuleDiagnostic> aDiagnostics, Document aDocument)
    {
        try
        {
            return YAML_TREE.readTree(aText);
        }
        catch (JacksonException e)
        {
            JsonLocation loc = e.getLocation();
            TextRange range = loc == null ? null
                    : TextRange.at(loc.getLineNr(), loc.getColumnNr(), 1);
            aDiagnostics.add(new RuleDiagnostic(aCode, Severity.ERROR,
                    "the " + aWhat + " file is not valid YAML: " + e.getOriginalMessage(), null,
                    range, aDocument));
            return null;
        }
    }


    /**
     * The docs sidecar, read as the corpus reads it: one document, and its {@code Core} naming this
     * rule — the corpus joins a sidecar to the rule its {@code Core} names, so a mismatch would
     * carry these citations elsewhere (editor note, review 8a-A).
     */
    private static @Nullable JsonNode readDoc(@Nullable String aDocYaml, String aCoreId,
            List<RuleDiagnostic> aDiagnostics)
    {
        if (aDocYaml == null)
        {
            return null;
        }
        YamlLocations docLocations = YamlLocations.index(aDocYaml);
        if (docLocations.secondDocument() != null)
        {
            aDiagnostics.add(multiDocument("docs", docLocations, Document.DOCS));
            return null;
        }
        JsonNode doc = readYaml(aDocYaml, "DOC_YAML_SYNTAX", "docs", aDiagnostics, Document.DOCS);
        if (doc != null && !UNKNOWN_ID.equals(aCoreId))
        {
            String docCore = textOrNull(doc.get("Core"));
            if (!aCoreId.equals(docCore))
            {
                YamlPath core = YamlPath.of("Core");
                aDiagnostics.add(new RuleDiagnostic(DOC_CORE_MISMATCH, Severity.ERROR,
                        "the docs sidecar's Core is "
                                + (docCore == null ? "missing" : "\"" + docCore + "\"")
                                + ", not this rule's " + aCoreId
                                + " — the corpus joins a sidecar to the rule its Core names",
                        core, docLocations.rangeOf(core), Document.DOCS));
            }
        }
        return doc;
    }


    private static RuleDiagnostic multiDocument(String aWhat, YamlLocations aLocations,
            Document aDocument)
    {
        return new RuleDiagnostic(MULTI_DOCUMENT, Severity.ERROR, "the " + aWhat
                + " file holds a second YAML document; only one is read, so everything after"
                + " this marker would be dropped silently — remove it or move it to its own file",
                null, aLocations.secondDocument(), aDocument);
    }

    /**
     * Where and why a {@code Standards} block cannot be joined.
     *
     * @param path
     *            the offending node's path
     * @param message
     *            what is wrong
     */
    record ShapeProblem(YamlPath path, String message)
    {
    }

    /**
     * The first shape in a check's {@code Standards} block that {@link #buildAuthorities} cannot
     * join, or {@code null}: a sequence of authorities, each a mapping with an {@code Organization}
     * and a sequence of standards, each a mapping whose optional {@code References} is a sequence
     * of mappings. An absent (or null) block joins to no authorities, as it always did.
     *
     * @param aCheck
     *            the check node
     * @return the problem, or {@code null}
     */
    static @Nullable ShapeProblem standardsShapeProblem(JsonNode aCheck)
    {
        JsonNode standards = aCheck.get(STANDARDS);
        if (standards == null || standards.isNull())
        {
            return null;
        }
        YamlPath top = YamlPath.of(STANDARDS);
        if (!standards.isArray())
        {
            return new ShapeProblem(top, "Standards must be a sequence of authorities");
        }
        for (int i = 0; i < standards.size(); i++)
        {
            JsonNode authority = standards.get(i);
            YamlPath at = top.child(Integer.toString(i));
            if (!authority.isObject())
            {
                return new ShapeProblem(at, "Standards[" + i + "] must be a mapping with "
                        + ORGANIZATION + " and " + STANDARDS);
            }
            if (!authority.has(ORGANIZATION))
            {
                return new ShapeProblem(at, "Standards[" + i + "] has no " + ORGANIZATION);
            }
            JsonNode stds = authority.get(STANDARDS);
            if (stds == null || !stds.isArray())
            {
                return new ShapeProblem(stds == null ? at : at.child(STANDARDS),
                        "Standards[" + i + "]." + STANDARDS + " must be a sequence of standards");
            }
            for (int j = 0; j < stds.size(); j++)
            {
                ShapeProblem p = standardShapeProblem(stds.get(j), at.child(STANDARDS),
                        "Standards[" + i + "].Standards[" + j + "]", j);
                if (p != null)
                {
                    return p;
                }
            }
        }
        return null;
    }


    private static @Nullable ShapeProblem standardShapeProblem(JsonNode aStd, YamlPath aParent,
            String aLabel, int aIndex)
    {
        YamlPath at = aParent.child(Integer.toString(aIndex));
        if (!aStd.isObject())
        {
            return new ShapeProblem(at, aLabel + " must be a mapping");
        }
        JsonNode refs = aStd.get(REFERENCES);
        if (refs == null || refs.isNull())
        {
            return null;
        }
        if (!refs.isArray())
        {
            return new ShapeProblem(at.child(REFERENCES),
                    aLabel + "." + REFERENCES + " must be a sequence");
        }
        for (int k = 0; k < refs.size(); k++)
        {
            if (!refs.get(k).isObject())
            {
                return new ShapeProblem(at.child(REFERENCES).child(Integer.toString(k)),
                        aLabel + "." + REFERENCES + "[" + k + "] must be a mapping");
            }
        }
        return null;
    }


    /** {@link #standardsShapeProblem} as the corpus gate's loud refusal. */
    private static void requireStandardsShape(JsonNode aCheck)
    {
        ShapeProblem p = standardsShapeProblem(aCheck);
        if (p != null)
        {
            throw new IllegalStateException(
                    "Rule " + aCheck.path("Core").path("Id").asText(UNKNOWN_ID) + ": " + p.message()
                            + " (at " + p.path() + ")");
        }
    }


    /**
     * The package document the engine loads for one rule: {@code {"standards": […], "rules": {<id>:
     * <packaged rule>}}}, serialised exactly as a corpus package is (indented, trailing newline).
     *
     * @param aPackagedRule
     *            the released rule node
     * @param aCoreId
     *            its {@code Core.Id}
     * @param aPackageStandards
     *            the package's declared standards; an empty list declares nothing
     * @return the JSON text
     */
    public static String packageJson(JsonNode aPackagedRule, String aCoreId,
            List<StandardRef> aPackageStandards)
    {
        ObjectNode rules = JSON.createObjectNode();
        rules.set(aCoreId, aPackagedRule);
        ObjectNode pkg = JSON.createObjectNode();
        if (!aPackageStandards.isEmpty())
        {
            pkg.set("standards", standardsJson(aPackageStandards));
        }
        pkg.set("rules", rules);
        try
        {
            return serialize(pkg);
        }
        catch (JsonProcessingException e)
        {
            throw new IllegalStateException("cannot serialise the package of " + aCoreId, e);
        }
    }

    // ---- the corpus generator's building blocks ------------------------------------------


    /** True when the check declares {@code Executability: "Not Executable"}. */
    public static boolean isParked(JsonNode aCheck)
    {
        return NOT_EXECUTABLE.equals(textOrNull(aCheck.get("Executability")));
    }


    /**
     * Rebuilds a rule node: check fields minus {@code Standards}, plus the rejoined
     * {@code Authorities}, after the {@code Sensitivity} derive-and-validate gate.
     *
     * @param aCheck
     *            the check node
     * @param aDoc
     *            the docs sidecar node, or {@code null}
     * @return the canonical rule node
     * @throws IllegalStateException
     *             when the {@code Standards} block has a shape the join cannot read
     *             ({@link #standardsShapeProblem}), or {@code Sensitivity} is absent and cannot be
     *             derived — a silent omission would ship an unloadable rule
     */
    public static ObjectNode rebuildRule(JsonNode aCheck, @Nullable JsonNode aDoc)
    {
        requireStandardsShape(aCheck);
        requireDerivableSensitivity(aCheck);
        return assembleNode(aCheck, aDoc);
    }


    /** {@link #rebuildRule} without the Sensitivity gate — the node itself. */
    static ObjectNode assembleNode(JsonNode aCheck, @Nullable JsonNode aDoc)
    {
        ObjectNode rule = JSON.createObjectNode();
        JsonNode standards = aCheck.get(STANDARDS);
        rule.set("Authorities",
                buildAuthorities(standards, aDoc == null ? null : aDoc.get(CITATIONS)));
        // ⚑ PROVIDER REQUIREMENTS ARE NO LONGER MATERIALISED (owner ruling 2026-09-08).
        // Requirements.Library / .Define / .Dictionary DOCUMENT a fact the engine computes
        // (ProviderRequirements); they never declare one, and RulePackageLoader's gate R5 says so
        // in terms: "Omitting it is always legal ... the runtime arms read the derivation
        // regardless". They are passed through exactly like Sensitivity: authored ships, absent
        // stays absent.
        for (Map.Entry<String, JsonNode> field : aCheck.properties())
        {
            if (STANDARDS.equals(field.getKey()))
            {
                continue;
            }
            rule.set(field.getKey(), field.getValue().deepCopy());
        }
        return rule;
    }


    /**
     * A build gate wearing a derivation's clothes: verifies the {@code Sensitivity} a source rule
     * omits is derivable. The derived value is discarded — the shipped corpus carries
     * {@code Sensitivity} only where authored, and {@code RulePackageLoader.finishLoad} re-derives
     * the rest via {@code deriveOmittedFields}. A no-op for a check that authors the field.
     *
     * @param aCheck
     *            the check node
     * @throws IllegalStateException
     *             when the rule body gives no basis for the value
     */
    public static void requireDerivableSensitivity(JsonNode aCheck)
    {
        if (aCheck.hasNonNull("Sensitivity"))
        {
            return;
        }
        Rule rule;
        try
        {
            rule = LENIENT.treeToValue(aCheck, Rule.class);
        }
        catch (JsonProcessingException e)
        {
            throw new IllegalStateException(
                    "cannot bind rule for derivation: " + aCheck.get("Core"), e);
        }
        // Materialise the Bindings before deriving — an external binder must run this itself
        // (RulePackageLoader.normalizeOperations javadoc); without it the classifier derives over
        // a rule with no operations and the gate would judge a different rule than ships.
        RulePackageLoader.materialiseBindings(rule);
        String coreId = aCheck.path("Core").path("Id").asText(UNKNOWN_ID);
        RuleClassifier.Derived<Sensitivity> sensitivity = RuleClassifier.deriveSensitivity(rule);
        if (sensitivity.confidence() == RuleClassifier.Confidence.NONE)
        {
            throw new IllegalStateException("Rule " + coreId + ": Sensitivity is absent and cannot"
                    + " be derived (" + sensitivity.rationale() + ") — author it explicitly");
        }
    }


    /**
     * The released shape of one canonical rule node: the carry-through conversion and
     * {@link ReleaseShapeTrimmer}, run through the same package-level passes the generator runs, so
     * the result is byte-equal to the rule's entry in a generated package.
     *
     * @param aCanonical
     *            the node {@link #rebuildRule} produced
     * @param aCoreId
     *            the rule's id (the key it sits under)
     * @return the released node
     */
    public static JsonNode packagedRuleNode(ObjectNode aCanonical, String aCoreId)
    {
        ObjectNode rules = JSON.createObjectNode();
        rules.set(aCoreId, aCanonical);
        ObjectNode root = JSON.createObjectNode();
        root.set("rules", rules);
        JsonNode converted = RulePackageConverter.convertPackageNode(root,
                new RulePackageConverter.ConversionReport());
        JsonNode released = converted == null ? null : ReleaseShapeTrimmer.trim(converted);
        JsonNode node = released == null ? null : released.path("rules").get(aCoreId);
        if (node == null)
        {
            throw new IllegalStateException("release shaping lost rule " + aCoreId);
        }
        return node;
    }


    /**
     * Re-joins citations onto the {@code Standards} tree, producing the legacy {@code Authorities}.
     *
     * @param aStandards
     *            the check's {@code Standards} block (an {@code Authorities}-shaped array)
     * @param aCitations
     *            the sidecar's {@code Citations} map, keyed by {@link #referenceKeys}
     * @return the {@code Authorities} array
     */
    public static ArrayNode buildAuthorities(@Nullable JsonNode aStandards,
            @Nullable JsonNode aCitations)
    {
        ArrayNode out = JSON.createArrayNode();
        if (aStandards == null || !aStandards.isArray())
        {
            return out;
        }
        List<String> keys = referenceKeys(aStandards);
        int idx = 0;
        for (JsonNode authority : aStandards)
        {
            ObjectNode a = JSON.createObjectNode();
            a.set("Organization", authority.get("Organization").deepCopy());
            ArrayNode stds = JSON.createArrayNode();
            for (JsonNode std : authority.get(STANDARDS))
            {
                ObjectNode s = JSON.createObjectNode();
                for (Map.Entry<String, JsonNode> f : ((ObjectNode) std).properties())
                {
                    if (!REFERENCES.equals(f.getKey()))
                    {
                        s.set(f.getKey(), f.getValue().deepCopy());
                    }
                }
                ArrayNode refs = JSON.createArrayNode();
                JsonNode refList = std.get(REFERENCES);
                if (refList != null)
                {
                    for (JsonNode ref : refList)
                    {
                        ObjectNode r = (ObjectNode) ref.deepCopy();
                        String key = keys.get(idx++);
                        if (aCitations != null && aCitations.has(key))
                        {
                            r.set(CITATIONS, aCitations.get(key).deepCopy());
                        }
                        refs.add(r);
                    }
                }
                s.set(REFERENCES, refs);
                stds.add(s);
            }
            a.set(STANDARDS, stds);
            out.add(a);
        }
        return out;
    }

    /** A {@code (Name, Version)} a check's {@code Standards} block names. */
    public record StandardVersion(String name, String version)
    {
    }

    /**
     * The distinct {@code (Name, Version)} pairs a check's {@code Standards} block names — the
     * standard tuples whose packages the rule is a member of (its family gates the rest).
     *
     * @param aCheck
     *            the check node
     * @return the pairs in authored order, duplicates kept
     */
    public static List<StandardVersion> standardsOf(JsonNode aCheck)
    {
        List<StandardVersion> out = new ArrayList<>();
        JsonNode standards = aCheck.get(STANDARDS);
        if (standards == null)
        {
            return out;
        }
        for (JsonNode authority : standards)
        {
            JsonNode stds = authority.get(STANDARDS);
            if (stds == null)
            {
                continue;
            }
            for (JsonNode std : stds)
            {
                String name = textOrNull(std.get("Name"));
                String version = textOrNull(std.get("Version"));
                if (name != null && version != null)
                {
                    out.add(new StandardVersion(name, version));
                }
            }
        }
        return out;
    }


    /** True iff the check's {@code Standards} block names the given {@code (name, version)}. */
    public static boolean belongs(JsonNode aCheck, String aName, String aVersion)
    {
        for (StandardVersion sv : standardsOf(aCheck))
        {
            if (aName.equals(sv.name()) && aVersion.equals(sv.version()))
            {
                return true;
            }
        }
        return false;
    }

    // ---- reference keys (shared with the docs sidecar) ------------------------------------

    /** One {@code Reference} of an {@code Authorities} tree, under its stable locator key. */
    public record RefCitations(String key, @Nullable JsonNode citations)
    {
    }

    /**
     * The ordered reference locator keys of an {@code Authorities}/{@code Standards} tree, used to
     * re-join doc citations: the keys are identical whether or not the {@code Citations} are
     * present, since the key derives only from organization / standard / rule-identifier.
     *
     * @param aTree
     *            an {@code Authorities}-shaped array (the check file's {@code Standards} block)
     * @return the reference keys in stable walk order
     */
    public static List<String> referenceKeys(@Nullable JsonNode aTree)
    {
        List<String> keys = new ArrayList<>();
        for (RefCitations rc : walkReferences(aTree))
        {
            keys.add(rc.key());
        }
        return keys;
    }


    /**
     * Walks every {@code Reference} of an {@code Authorities} tree in stable order, yielding a
     * deterministic locator key. The key is content-derived (organization, standard,
     * rule-identifier) with a positional {@code #n} tiebreak for the rare duplicate.
     *
     * @param aAuthorities
     *            the tree
     * @return one entry per reference
     */
    public static List<RefCitations> walkReferences(@Nullable JsonNode aAuthorities)
    {
        List<RefCitations> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        if (aAuthorities == null || !aAuthorities.isArray())
        {
            return out;
        }
        for (JsonNode authority : aAuthorities)
        {
            String org = refText(authority.get("Organization"));
            JsonNode stds = authority.get(STANDARDS);
            if (stds == null)
            {
                continue;
            }
            for (JsonNode std : stds)
            {
                JsonNode refList = std.get(REFERENCES);
                if (refList == null)
                {
                    continue;
                }
                for (JsonNode ref : refList)
                {
                    JsonNode rid = ref.get("Rule_Identifier");
                    String base = String.join("|", org, refText(std.get("Name")),
                            refText(std.get("Version")), refText(std.get("Substandard")),
                            rid == null ? "" : refText(rid.get("Id")),
                            rid == null ? "" : refText(rid.get("Version")));
                    int n = seen.merge(base, 1, Integer::sum) - 1;
                    String key = n == 0 ? base : base + "#" + n;
                    out.add(new RefCitations(key, ref.get(CITATIONS)));
                }
            }
        }
        return out;
    }

    // ---- serialisation ------------------------------------------------------------------


    /**
     * Serialises declared standards to the array a package file carries.
     *
     * @param aRefs
     *            the declared standards
     * @return the JSON array node
     */
    public static ArrayNode standardsJson(List<StandardRef> aRefs)
    {
        return JSON.valueToTree(aRefs);
    }


    /**
     * Canonical package serialisation: indented JSON plus a trailing LF.
     *
     * @param aPackage
     *            the package root
     * @return the text
     * @throws JsonProcessingException
     *             on a serialisation failure
     */
    public static String serialize(ObjectNode aPackage) throws JsonProcessingException
    {
        return JSON.writeValueAsString(aPackage) + "\n";
    }


    /**
     * The YAML reader the corpus tools share — strict about a second document, as {@link #assemble}
     * is.
     */
    public static JsonNode readYamlTree(String aText) throws JsonProcessingException
    {
        return YAML_TREE.readTree(aText);
    }


    /** The text of a node, or {@code null} for a missing or explicitly-null node. */
    public static @Nullable String textOrNull(@Nullable JsonNode aNode)
    {
        return aNode == null || aNode.isNull() || aNode.isMissingNode() ? null : aNode.asText();
    }


    /** Empty string for a missing or explicitly-null node, so a key never contains "null". */
    private static String refText(@Nullable JsonNode aNode)
    {
        return aNode == null || aNode.isNull() ? "" : aNode.asText();
    }
}
