package net.cumba.cdisc.define.conformance.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.cumba.cdisc.define.conformance.ct.CtCodelist;
import net.cumba.cdisc.define.conformance.ct.CtProvider;
import net.cumba.cdisc.define.conformance.library.LibraryProvider;
import net.cumba.cdisc.define.conformance.report.Category;
import net.cumba.cdisc.define.conformance.report.ConformanceFinding;
import net.cumba.cdisc.define.conformance.report.ExecutionStatus;
import net.cumba.cdisc.define.conformance.rule.CheckDefinition;
import net.cumba.cdisc.define.conformance.rule.Condition;
import net.cumba.cdisc.define.conformance.rule.ConformanceRule;
import net.cumba.cdisc.define.conformance.rule.PathResolver;
import net.cumba.cdisc.define.conformance.rule.RegexFormats;
import net.cumba.cdisc.define.conformance.rule.Requires;
import net.cumba.cdisc.define.conformance.rule.RuleSet;
import net.cumba.cdisc.define.conformance.tree.ElementNode;
import org.jspecify.annotations.Nullable;

/**
 * Evaluates declarative rules ({@link ConformanceRule}) against a document
 * ({@link DocumentContext}) — plan §3.3 semantics.
 *
 * <p>
 * Per rule: the version gate, then the {@code Requires} gate (SKIP, plan §3.6), then scope
 * selection by the rule's element selector, then the {@code when} guard per scoped node, then the
 * kind-specific check. Value checks ({@code matches_regex}, {@code one_of}, {@code references})
 * fire only on <b>present</b> values — attribute presence is always a separate rule.
 * </p>
 */
public final class RuleEvaluator
{

    /** The {@code Alias/@Context} marker for NCI c-code aliases. */
    private static final String NCI_EXT_CODE_ID = "nci:ExtCodeID";

    // ConcurrentHashMap: a single RuleEvaluator may be reused across concurrent
    // validate() calls (review-terminal-b N5); custom-check instances are stateless.
    private final Map<String, CustomCheck> customChecks = new ConcurrentHashMap<>();

    public RuleResult evaluate(ConformanceRule aRule, DocumentContext aContext)
    {
        if (!aRule.applicableVersions().contains(aContext.defineVersion()))
        {
            return RuleResult.skipped(aRule, ExecutionStatus.NOT_APPLICABLE_VERSION);
        }
        if (aRule.requires() == Requires.CT && aContext.ctProvider().isEmpty())
        {
            return RuleResult.skipped(aRule, ExecutionStatus.SKIPPED_MISSING_CT);
        }
        if (aRule.requires() == Requires.FOLDER && aContext.submissionFolder().isEmpty())
        {
            return RuleResult.skipped(aRule, ExecutionStatus.SKIPPED_MISSING_FOLDER);
        }
        if (aRule.requires() == Requires.LIBRARY && aContext.libraryProvider().isEmpty())
        {
            return RuleResult.skipped(aRule, ExecutionStatus.SKIPPED_MISSING_LIBRARY);
        }

        List<ElementNode> scoped = selectScope(aRule.element(), aContext);
        Condition guard = aRule.check().when();
        List<ElementNode> guarded = new ArrayList<>();
        for (ElementNode node : scoped)
        {
            if (guard == null || guard.matches(node, aContext.oidResolver()))
            {
                guarded.add(node);
            }
        }

        List<ConformanceFinding> findings = switch (aRule.check())
        {
        case CheckDefinition.Exists c -> presence(aRule, guarded, c.target(), true);
        case CheckDefinition.NotExists c -> presence(aRule, guarded, c.target(), false);
        case CheckDefinition.CardinalityAtMost c -> cardinality(aRule, guarded, c);
        case CheckDefinition.MatchesRegex c -> regex(aRule, guarded, c);
        case CheckDefinition.OneOf c -> oneOf(aRule, guarded, c);
        case CheckDefinition.References c -> references(aRule, guarded, c, aContext);
        case CheckDefinition.UniqueAmongSiblings c -> uniqueAmongSiblings(aRule, guarded, c);
        case CheckDefinition.UniqueInDocument c -> uniqueInDocument(aRule, guarded, c);
        case CheckDefinition.ConsistentAcrossDocument c -> consistent(aRule, guarded, c);
        case CheckDefinition.ReferencedFileExists c -> fileExists(aRule, guarded, c, aContext);
        case CheckDefinition.StylesheetFileExists _ -> stylesheetExists(aRule, guarded, aContext);
        case CheckDefinition.Custom c -> custom(aRule, guarded, c, aContext);
        case CheckDefinition.IsReferenced c -> isReferenced(aRule, guarded, c, aContext);
        case CheckDefinition.Compare c -> compare(aRule, guarded, c, aContext);
        case CheckDefinition.TermInCtCodelist c -> termInCtCodelist(aRule, guarded, c, aContext);
        case CheckDefinition.NciCodeKnown c -> nciCodeKnown(aRule, guarded, c, aContext);
        case CheckDefinition.TermMatchesNciCode _ -> termMatchesNciCode(aRule, guarded, aContext);
        case CheckDefinition.ExtendedValueMarking c -> extendedValueMarking(aRule, guarded, c,
                aContext);
        case CheckDefinition.NciAliasRequired c -> nciAliasRequired(aRule, guarded, c, aContext);
        case CheckDefinition.LibraryDatasetLabelMatches _ -> libraryDatasetLabel(aRule, guarded,
                aContext);
        case CheckDefinition.LibraryVariableLabelMatches _ -> libraryVariableLabel(aRule, guarded,
                aContext);
        case CheckDefinition.LibraryCodelistRefRequired _ -> libraryCodelistRefRequired(aRule,
                guarded, aContext);
        case CheckDefinition.LibraryCodelistCCodeMatches _ -> libraryCodelistCCode(aRule, guarded,
                aContext);
        case CheckDefinition.LibraryQualifierLabelDecode _ -> libraryQualifierDecode(aRule, guarded,
                aContext);
        case CheckDefinition.LibraryCoreMandatory _ -> libraryCoreMandatory(aRule, guarded,
                aContext);
        case CheckDefinition.LibraryCtAliasRequired c -> libraryCtAliasRequired(aRule, guarded, c,
                aContext);
        case CheckDefinition.LibraryStandardVersionKnown c -> libraryStandardVersion(aRule, guarded,
                c, aContext);
        };
        return new RuleResult(aRule, ExecutionStatus.EXECUTED, findings);
    }

    // ------------------------------------------------------------------
    // Scope selection
    // ------------------------------------------------------------------


    /**
     * Nodes matched by the element selector: {@code "Document"}, the {@code "*"} wildcard (every
     * real element), a bare local name, or a parent-qualified {@code "Ancestor/…/Name"} chain
     * matched against immediate parents.
     */
    private static List<ElementNode> selectScope(String aSelector, DocumentContext aContext)
    {
        if (DocumentContext.DOCUMENT_SCOPE.equals(aSelector))
        {
            return List.of(aContext.documentNode());
        }
        if ("*".equals(aSelector))
        {
            List<ElementNode> all = new ArrayList<>(aContext.allNodes());
            all.remove(aContext.documentNode());
            return all;
        }
        String[] segments = aSelector.split("/");
        for (int i = 0; i < segments.length; i++)
        {
            segments[i] = PathResolver.stripPrefix(segments[i]);
        }
        List<ElementNode> out = new ArrayList<>();
        for (ElementNode node : aContext.allNodes())
        {
            if (matchesSelector(node, segments))
            {
                out.add(node);
            }
        }
        return out;
    }


    private static boolean matchesSelector(ElementNode aNode, String[] aSegments)
    {
        if (!aNode.localName().equals(aSegments[aSegments.length - 1]))
        {
            return false;
        }
        ElementNode current = aNode;
        for (int i = aSegments.length - 2; i >= 0; i--)
        {
            Optional<ElementNode> parent = current.parent();
            if (parent.isEmpty() || !parent.get().localName().equals(aSegments[i]))
            {
                return false;
            }
            current = parent.get();
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Kinds
    // ------------------------------------------------------------------


    private static List<ConformanceFinding> presence(ConformanceRule aRule,
            List<ElementNode> aNodes, String aTarget, boolean aMustExist)
    {
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            boolean present = isPresent(node, aTarget);
            if (present != aMustExist)
            {
                findings.add(finding(aRule, node, null));
            }
        }
        return findings;
    }


    private static boolean isPresent(ElementNode aNode, String aTarget)
    {
        if (aTarget.startsWith("@"))
        {
            Optional<String> value = aNode
                    .attribute(PathResolver.stripPrefix(aTarget.substring(1)));
            // An attribute present but empty counts as missing ("must be included and cannot be
            // empty" — the sheets' presence wording).
            return value.isPresent() && !value.get().isBlank();
        }
        return !aNode.children(PathResolver.stripPrefix(aTarget)).isEmpty();
    }


    private static List<ConformanceFinding> cardinality(ConformanceRule aRule,
            List<ElementNode> aNodes, CheckDefinition.CardinalityAtMost aCheck)
    {
        List<ConformanceFinding> findings = new ArrayList<>();
        String target = PathResolver.stripPrefix(aCheck.target());
        for (ElementNode node : aNodes)
        {
            if (node.children(target).size() > aCheck.max())
            {
                findings.add(finding(aRule, node, null));
            }
        }
        return findings;
    }


    private static List<ConformanceFinding> regex(ConformanceRule aRule, List<ElementNode> aNodes,
            CheckDefinition.MatchesRegex aCheck)
    {
        String explicit = aCheck.pattern();
        Pattern pattern = explicit != null ? Pattern.compile(explicit)
                : RegexFormats.byName(Objects.requireNonNull(aCheck.format(),
                        "matches_regex validated to carry pattern xor format"));
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            Optional<String> value = valueOf(node, aCheck.attribute());
            if (value.isPresent() && !pattern.matcher(value.get()).matches())
            {
                findings.add(finding(aRule, node, value.get()));
            }
        }
        return findings;
    }


    private static List<ConformanceFinding> oneOf(ConformanceRule aRule, List<ElementNode> aNodes,
            CheckDefinition.OneOf aCheck)
    {
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            Optional<String> value = valueOf(node, aCheck.attribute());
            if (value.isEmpty())
            {
                continue;
            }
            boolean ok = aCheck.caseInsensitiveOrDefault()
                    ? aCheck.values().stream().anyMatch(v -> v.equalsIgnoreCase(value.get()))
                    : aCheck.values().contains(value.get());
            if (!ok)
            {
                findings.add(finding(aRule, node, value.get()));
            }
        }
        return findings;
    }


    private static List<ConformanceFinding> references(ConformanceRule aRule,
            List<ElementNode> aNodes, CheckDefinition.References aCheck, DocumentContext aContext)
    {
        List<ConformanceFinding> findings = new ArrayList<>();
        String targetElement = PathResolver.stripPrefix(aCheck.targetElement());
        for (ElementNode node : aNodes)
        {
            Optional<String> value = valueOf(node, aCheck.attribute());
            if (value.isPresent() && aContext.oidResolver()
                    .resolve(targetElement, aCheck.targetKeyOrDefault(), value.get()).isEmpty())
            {
                findings.add(finding(aRule, node, value.get()));
            }
        }
        return findings;
    }


    private static List<ConformanceFinding> uniqueAmongSiblings(ConformanceRule aRule,
            List<ElementNode> aNodes, CheckDefinition.UniqueAmongSiblings aCheck)
    {
        // Group scoped nodes by parent identity; flag every occurrence of a value after its first
        // within one group.
        Map<ElementNode, Set<String>> seenByParent = new HashMap<>();
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            Optional<String> value = valueOf(node, aCheck.attribute());
            if (value.isEmpty())
            {
                continue;
            }
            ElementNode parent = node.parent().orElse(node);
            if (!seenByParent.computeIfAbsent(parent, _ -> new HashSet<>()).add(value.get()))
            {
                findings.add(finding(aRule, node, value.get()));
            }
        }
        return findings;
    }


    private static List<ConformanceFinding> uniqueInDocument(ConformanceRule aRule,
            List<ElementNode> aNodes, CheckDefinition.UniqueInDocument aCheck)
    {
        Set<String> seen = new HashSet<>();
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            Optional<String> value = valueOf(node, aCheck.attribute());
            if (value.isPresent() && !seen.add(value.get()))
            {
                findings.add(finding(aRule, node, value.get()));
            }
        }
        return findings;
    }


    private static List<ConformanceFinding> consistent(ConformanceRule aRule,
            List<ElementNode> aNodes, CheckDefinition.ConsistentAcrossDocument aCheck)
    {
        String first = null;
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            List<String> values = PathResolver.values(node, aCheck.path());
            if (values.isEmpty())
            {
                continue;
            }
            String value = values.get(0);
            if (first == null)
            {
                first = value;
            }
            else if (!first.equals(value))
            {
                findings.add(finding(aRule, node, value));
            }
        }
        return findings;
    }


    private static List<ConformanceFinding> fileExists(ConformanceRule aRule,
            List<ElementNode> aNodes, CheckDefinition.ReferencedFileExists aCheck,
            DocumentContext aContext)
    {
        // The Requires gate guarantees the folder is present here.
        Path folder = aContext.submissionFolder().orElseThrow();
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            Optional<String> value = valueOf(node, aCheck.attribute());
            if (value.isEmpty())
            {
                continue;
            }
            if (hrefMissingInFolder(folder, value.get()))
            {
                findings.add(finding(aRule, node, value.get()));
            }
        }
        return findings;
    }


    /**
     * PMDA DD0085: every {@code <?xml-stylesheet?>} href on the context must exist in the
     * submission folder. The hrefs are prolog content (not elements), so the scoped node — the
     * synthetic {@code Document} anchor, when it passed the guard — only carries the findings.
     */
    private static List<ConformanceFinding> stylesheetExists(ConformanceRule aRule,
            List<ElementNode> aNodes, DocumentContext aContext)
    {
        // The Requires gate guarantees the folder is present here.
        Path folder = aContext.submissionFolder().orElseThrow();
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            for (String href : aContext.stylesheetHrefs())
            {
                if (hrefMissingInFolder(folder, href))
                {
                    findings.add(finding(aRule, node, href));
                }
            }
        }
        return findings;
    }


    /**
     * Whether a href-like value does <b>not</b> resolve to an existing file inside the submission
     * folder. A {@code #fragment} is stripped first; a blank remainder is out of reach (presence is
     * a separate rule). A href escaping the folder (absolute or {@code ../}) can never satisfy a
     * referenced-file check — treated as missing rather than probing outside.
     */
    private static boolean hrefMissingInFolder(Path aFolder, String aHref)
    {
        String href = aHref;
        int fragment = href.indexOf('#');
        if (fragment >= 0)
        {
            href = href.substring(0, fragment);
        }
        if (href.isBlank())
        {
            return false;
        }
        Path resolved = aFolder.resolve(href.replace('\\', '/')).normalize();
        return !resolved.startsWith(aFolder.normalize()) || !Files.exists(resolved);
    }


    private static List<ConformanceFinding> isReferenced(ConformanceRule aRule,
            List<ElementNode> aNodes, CheckDefinition.IsReferenced aCheck, DocumentContext aContext)
    {
        // One pass over the document collects every value any referrer descriptor carries.
        Set<String> referenced = new HashSet<>();
        for (ElementNode node : aContext.allNodes())
        {
            for (CheckDefinition.Referrer referrer : aCheck.by())
            {
                String element = referrer.element();
                if (element == null || node.localName().equals(PathResolver.stripPrefix(element)))
                {
                    node.attribute(PathResolver.stripPrefix(referrer.attribute()))
                            .ifPresent(referenced::add);
                }
            }
        }
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            Optional<String> key = node.attribute(aCheck.keyOrDefault());
            if (key.isPresent() && !referenced.contains(key.get()))
            {
                findings.add(finding(aRule, node, key.get()));
            }
        }
        return findings;
    }


    private List<ConformanceFinding> custom(ConformanceRule aRule, List<ElementNode> aNodes,
            CheckDefinition.Custom aCheck, DocumentContext aContext)
    {
        CustomCheck impl = customChecks.computeIfAbsent(aCheck.className(),
                RuleEvaluator::instantiate);
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            if (!impl.satisfied(node, aContext))
            {
                findings.add(finding(aRule, node, null));
            }
        }
        return findings;
    }


    private static List<ConformanceFinding> compare(ConformanceRule aRule, List<ElementNode> aNodes,
            CheckDefinition.Compare aCheck, DocumentContext aContext)
    {
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            List<String> lefts = PathResolver.valuesWithDeref(node, aCheck.left(),
                    aContext.oidResolver());
            List<String> rights = PathResolver.valuesWithDeref(node, aCheck.right(),
                    aContext.oidResolver());
            if (lefts.isEmpty() || rights.isEmpty())
            {
                continue;
            }
            String left = transform(lefts.get(0), aCheck.leftTransform());
            String right = transform(rights.get(0), aCheck.rightTransform());
            boolean ok;
            if ("less_or_equal".equals(aCheck.opOrDefault()))
            {
                Double l = parseNumeric(left);
                Double r = parseNumeric(right);
                ok = l == null || r == null || l <= r;
            }
            else
            {
                ok = aCheck.caseInsensitiveOrDefault() ? left.equalsIgnoreCase(right)
                        : left.equals(right);
            }
            if (!ok)
            {
                findings.add(finding(aRule, node, left + " vs " + right));
            }
        }
        return findings;
    }


    private static String transform(String aValue, @Nullable String aTransform)
    {
        if (!"file-basename".equals(aTransform))
        {
            return aValue;
        }
        String value = aValue.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0)
        {
            value = value.substring(slash + 1);
        }
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }


    private static @Nullable Double parseNumeric(String aValue)
    {
        try
        {
            return Double.valueOf(aValue.trim());
        }
        catch (NumberFormatException _)
        {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // CT-backed kinds (plan §3.3/§3.6) — reached only for rules declaring
    // Requires: ct, so the provider is present; a kind evaluated without one
    // is an authoring error and fails loudly (mirrors fileExists' orElseThrow).
    // ------------------------------------------------------------------


    private static List<ConformanceFinding> termInCtCodelist(ConformanceRule aRule,
            List<ElementNode> aNodes, CheckDefinition.TermInCtCodelist aCheck,
            DocumentContext aContext)
    {
        CtProvider provider = ctProvider(aContext);
        String explicitCCode = aCheck.cCode();
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            Optional<String> value = valueOf(node, aCheck.attributeOrDefault())
                    .filter(v -> !v.isBlank());
            if (value.isEmpty())
            {
                continue;
            }
            Optional<CtCodelist> codelist = explicitCCode != null
                    ? provider.codelistByCCode(explicitCCode)
                    : resolveEnclosingCodelist(node, provider);
            if (codelist.isEmpty())
            {
                continue;
            }
            if (aCheck.nonExtensibleOnlyOrDefault() && codelist.get().extensible())
            {
                continue;
            }
            if (aCheck.exemptExtendedValuesOrDefault() && isExtendedValueMarked(node))
            {
                continue;
            }
            if (!codelist.get().termsBySubmissionValue().containsKey(value.get()))
            {
                findings.add(finding(aRule, node, value.get()));
            }
        }
        return findings;
    }


    private static List<ConformanceFinding> nciCodeKnown(ConformanceRule aRule,
            List<ElementNode> aNodes, CheckDefinition.NciCodeKnown aCheck, DocumentContext aContext)
    {
        CtProvider provider = ctProvider(aContext);
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            // The scoped element is the Alias itself; only nci:ExtCodeID aliases carry a c-code.
            if (!node.attribute("Context").map(NCI_EXT_CODE_ID::equals).orElse(false))
            {
                continue;
            }
            Optional<String> code = node.attribute("Name").filter(v -> !v.isBlank());
            if (code.isEmpty())
            {
                continue;
            }
            if ("codelist".equals(aCheck.level()))
            {
                if (provider.codelistByCCode(code.get()).isEmpty())
                {
                    findings.add(finding(aRule, node, code.get()));
                }
            }
            else
            {
                // Term level: the c-code must be among the term c-codes of the enclosing
                // CodeList's resolved CT codelist; an unresolvable codelist is skipped.
                Optional<CtCodelist> codelist = resolveEnclosingCodelist(node, provider);
                if (codelist.isPresent()
                        && !codelist.get().termsBySubmissionValue().containsValue(code.get()))
                {
                    findings.add(finding(aRule, node, code.get()));
                }
            }
        }
        return findings;
    }


    private static List<ConformanceFinding> termMatchesNciCode(ConformanceRule aRule,
            List<ElementNode> aNodes, DocumentContext aContext)
    {
        CtProvider provider = ctProvider(aContext);
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            Optional<String> code = nciCodeOf(node);
            Optional<String> codedValue = node.attribute("CodedValue").filter(v -> !v.isBlank());
            if (code.isEmpty() || codedValue.isEmpty())
            {
                continue;
            }
            Optional<CtCodelist> codelist = resolveEnclosingCodelist(node, provider);
            if (codelist.isEmpty())
            {
                continue;
            }
            // Submission values the item's c-code stands for; none ⇒ the c-code is not in this
            // codelist, which is DD0034's finding, not this rule's.
            List<String> submissionValues = codelist.get().termsBySubmissionValue().entrySet()
                    .stream().filter(entry -> entry.getValue().equals(code.get()))
                    .map(Map.Entry::getKey).toList();
            if (!submissionValues.isEmpty() && !submissionValues.contains(codedValue.get()))
            {
                findings.add(finding(aRule, node, codedValue.get()));
            }
        }
        return findings;
    }


    private static List<ConformanceFinding> extendedValueMarking(ConformanceRule aRule,
            List<ElementNode> aNodes, CheckDefinition.ExtendedValueMarking aCheck,
            DocumentContext aContext)
    {
        CtProvider provider = ctProvider(aContext);
        boolean required = "required".equals(aCheck.mode());
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            Optional<CtCodelist> codelist = resolveEnclosingCodelist(node, provider);
            if (codelist.isEmpty())
            {
                continue;
            }
            boolean marked = isExtendedValueMarked(node);
            Optional<String> codedValue = node.attribute("CodedValue").filter(v -> !v.isBlank());
            if (required)
            {
                if (codelist.get().extensible() && codedValue.isPresent()
                        && !codelist.get().termsBySubmissionValue().containsKey(codedValue.get())
                        && !marked)
                {
                    findings.add(finding(aRule, node, codedValue.get()));
                }
            }
            else if (!codelist.get().extensible() && marked)
            {
                findings.add(finding(aRule, node, codedValue.orElse(null)));
            }
        }
        return findings;
    }


    private static List<ConformanceFinding> nciAliasRequired(ConformanceRule aRule,
            List<ElementNode> aNodes, CheckDefinition.NciAliasRequired aCheck,
            DocumentContext aContext)
    {
        CtProvider provider = ctProvider(aContext);
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            if (nciCodeOf(node).isPresent())
            {
                continue;
            }
            if ("codelist".equals(aCheck.level()))
            {
                // "Defined in CDISC CT" without the alias that would name it: identified by the
                // CodeList's Name (CtProvider.codelistByName); no name match ⇒ no finding.
                Optional<String> name = node.attribute("Name").filter(v -> !v.isBlank());
                if (name.isPresent() && provider.codelistByName(name.get()).isPresent())
                {
                    findings.add(finding(aRule, node, name.get()));
                }
            }
            else
            {
                Optional<String> codedValue = node.attribute("CodedValue")
                        .filter(v -> !v.isBlank());
                Optional<CtCodelist> codelist = resolveEnclosingCodelist(node, provider);
                if (codedValue.isPresent() && codelist.isPresent()
                        && codelist.get().termsBySubmissionValue().containsKey(codedValue.get()))
                {
                    findings.add(finding(aRule, node, codedValue.get()));
                }
            }
        }
        return findings;
    }


    private static CtProvider ctProvider(DocumentContext aContext)
    {
        return aContext.ctProvider().orElseThrow(
                () -> new IllegalStateException("CT-backed check evaluated without a CtProvider"
                        + " - the rule must declare 'Requires: ct'"));
    }

    // ------------------------------------------------------------------
    // Library-backed kinds (plan define-library-provider) — reached only for
    // rules declaring Requires: library, so the provider is present; a kind
    // evaluated without one is an authoring error and fails loudly.
    // ------------------------------------------------------------------

    /** An implementation-guide standard (Name + Version) a scoped node is governed by. */
    private record IgStandard(String name, String version)
    {

        /**
         * Hyphen-insensitive family test: 2.1 documents spell the standard {@code SDTMIG} /
         * {@code SENDIG} while 2.0's {@code def:StandardName} CT uses {@code SDTM-IG} /
         * {@code SEND-IG} / {@code SEND-IG-AR} / … (see PMDA-DD0021/DD0022). The provider still
         * receives the verbatim document spelling.
         */
        boolean isSdtmigOrSendig()
        {
            String normalised = name.replace("-", "");
            return normalised.startsWith("SDTMIG") || normalised.startsWith("SENDIG");
        }


        /**
         * The looser CDISC-sheet family test ("def:Standard/@Name beginning with 'SDTM' or 'SEND'",
         * CDISC 67) — also admits the model standards, whose lookups the provider simply answers
         * empty for.
         */
        boolean isSdtmOrSendFamily()
        {
            String normalised = name.replace("-", "");
            return normalised.startsWith("SDTM") || normalised.startsWith("SEND");
        }
    }

    /**
     * PMDA DD0136: the scoped ItemGroupDef's English description must equal the governing
     * SDTMIG/SENDIG standard's dataset label.
     */
    private static List<ConformanceFinding> libraryDatasetLabel(ConformanceRule aRule,
            List<ElementNode> aNodes, DocumentContext aContext)
    {
        LibraryProvider library = libraryProvider(aContext);
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            Optional<IgStandard> standard = governingStandard(node, aContext)
                    .filter(IgStandard::isSdtmigOrSendig);
            // The dataset's OWN name, not the Domain-first variable anchor: a SUPP-- or split
            // dataset (Name=SUPPAE/QSCG, Domain=AE/QS) has its own IG label, and comparing it
            // against the parent domain's label would be an Error-severity false positive. A
            // library-unknown Name is the conservative skip.
            Optional<String> dataset = node.attribute("Name").filter(v -> !v.isBlank());
            if (standard.isEmpty() || dataset.isEmpty())
            {
                continue;
            }
            Optional<String> libraryLabel = library.datasetLabel(standard.get().name(),
                    standard.get().version(), dataset.get());
            Optional<String> documentLabel = englishText(node, "Description");
            if (libraryLabel.isPresent() && documentLabel.isPresent()
                    && !documentLabel.get().trim().equals(libraryLabel.get().trim()))
            {
                findings.add(
                        finding(aRule, node, documentLabel.get() + " vs " + libraryLabel.get()));
            }
        }
        return findings;
    }


    /**
     * PMDA DD0137: the ItemDef referenced by the scoped ItemGroupDef/ItemRef must carry the
     * governing SDTMIG/SENDIG standard's variable label as its English description.
     */
    private static List<ConformanceFinding> libraryVariableLabel(ConformanceRule aRule,
            List<ElementNode> aNodes, DocumentContext aContext)
    {
        LibraryProvider library = libraryProvider(aContext);
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            VariableBinding binding = resolveVariable(node, aContext).orElse(null);
            if (binding == null || !binding.standard().isSdtmigOrSendig())
            {
                continue;
            }
            Optional<String> libraryLabel = library.variableLabel(binding.standard().name(),
                    binding.standard().version(), binding.dataset(), binding.variable());
            Optional<String> documentLabel = englishText(binding.itemDef(), "Description");
            if (libraryLabel.isPresent() && documentLabel.isPresent()
                    && !documentLabel.get().trim().equals(libraryLabel.get().trim()))
            {
                findings.add(finding(aRule, node, binding.variable() + ": " + documentLabel.get()
                        + " vs " + libraryLabel.get()));
            }
        }
        return findings;
    }


    /**
     * PMDA DD0124: a variable the library assigns a CT codelist must reference a codelist — the
     * referenced ItemDef needs a CodeListRef child.
     */
    private static List<ConformanceFinding> libraryCodelistRefRequired(ConformanceRule aRule,
            List<ElementNode> aNodes, DocumentContext aContext)
    {
        LibraryProvider library = libraryProvider(aContext);
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            VariableBinding binding = resolveVariable(node, aContext).orElse(null);
            if (binding == null)
            {
                continue;
            }
            boolean requiresCt = library.variableCodelistCCode(binding.standard().name(),
                    binding.standard().version(), binding.dataset(), binding.variable())
                    .isPresent();
            if (requiresCt && binding.itemDef().children("CodeListRef").isEmpty())
            {
                findings.add(finding(aRule, node, binding.variable()));
            }
        }
        return findings;
    }


    /**
     * PMDA DD0118: the nci:ExtCodeID c-code of the codelist a variable references must match the
     * c-code of the codelist the library assigns to that variable. A CodeList without the alias is
     * DD0031's beat and out of reach here.
     */
    private static List<ConformanceFinding> libraryCodelistCCode(ConformanceRule aRule,
            List<ElementNode> aNodes, DocumentContext aContext)
    {
        LibraryProvider library = libraryProvider(aContext);
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            VariableBinding binding = resolveVariable(node, aContext).orElse(null);
            if (binding == null)
            {
                continue;
            }
            Optional<String> libraryCode = library.variableCodelistCCode(binding.standard().name(),
                    binding.standard().version(), binding.dataset(), binding.variable());
            Optional<String> documentCode = binding.itemDef().children("CodeListRef").stream()
                    .findFirst().flatMap(ref -> ref.attribute("CodeListOID"))
                    .flatMap(oid -> aContext.oidResolver().resolve("CodeList", "OID", oid))
                    .flatMap(RuleEvaluator::nciCodeOf);
            if (libraryCode.isPresent() && documentCode.isPresent()
                    && !documentCode.get().equals(libraryCode.get()))
            {
                findings.add(finding(aRule, node, binding.variable() + ": " + documentCode.get()
                        + " vs " + libraryCode.get()));
            }
        }
        return findings;
    }


    /**
     * PMDA DD0116: on the FATESTCD ItemDef's codelist, every CodeListItem whose CodedValue names an
     * SDTM Event/Intervention qualifier fragment must decode (define.xml's carrier of the FATEST
     * value) to that qualifier's label. EnumeratedItems carry no decode and are out of reach.
     */
    private static List<ConformanceFinding> libraryQualifierDecode(ConformanceRule aRule,
            List<ElementNode> aNodes, DocumentContext aContext)
    {
        LibraryProvider library = libraryProvider(aContext);
        Optional<IgStandard> standard = documentIgStandard(aContext);
        if (standard.isEmpty())
        {
            return List.of();
        }
        List<ConformanceFinding> findings = new ArrayList<>();
        // Several FATESTCD ItemDefs (split FA datasets, value-level defs) may share one CodeList;
        // walk each CodeList once so a bad item yields one finding, not one per referrer.
        Set<ElementNode> visited = new HashSet<>();
        for (ElementNode node : aNodes)
        {
            Optional<ElementNode> codeList = node.children("CodeListRef").stream().findFirst()
                    .flatMap(ref -> ref.attribute("CodeListOID"))
                    .flatMap(oid -> aContext.oidResolver().resolve("CodeList", "OID", oid));
            if (codeList.isEmpty() || !visited.add(codeList.get()))
            {
                continue;
            }
            String codeListName = codeList.get().attribute("Name").orElse("");
            for (ElementNode item : codeList.get().children("CodeListItem"))
            {
                Optional<String> coded = item.attribute("CodedValue").filter(v -> !v.isBlank());
                if (coded.isEmpty())
                {
                    continue;
                }
                Optional<String> label = library.qualifierVariableLabel(standard.get().name(),
                        standard.get().version(), coded.get());
                Optional<String> decode = englishText(item, "Decode");
                if (label.isPresent() && decode.isPresent()
                        && !decode.get().trim().equals(label.get().trim()))
                {
                    findings.add(finding(aRule, item, codeListName + " (" + coded.get() + ": "
                            + decode.get() + " vs " + label.get() + ")"));
                }
            }
        }
        return findings;
    }


    /**
     * CDISC 67: an ItemRef whose variable the library designates {@code Core="Req"} (in an
     * SDTM/SEND-family standard) must carry {@code Mandatory="Yes"}. An absent Mandatory is the
     * XSD's beat.
     */
    private static List<ConformanceFinding> libraryCoreMandatory(ConformanceRule aRule,
            List<ElementNode> aNodes, DocumentContext aContext)
    {
        LibraryProvider library = libraryProvider(aContext);
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            VariableBinding binding = resolveVariable(node, aContext).orElse(null);
            if (binding == null || !binding.standard().isSdtmOrSendFamily())
            {
                continue;
            }
            boolean required = library
                    .variableCoreDesignation(binding.standard().name(),
                            binding.standard().version(), binding.dataset(), binding.variable())
                    .filter("Req"::equalsIgnoreCase).isPresent();
            Optional<String> mandatory = node.attribute("Mandatory").filter(v -> !v.isBlank());
            if (required && mandatory.isPresent() && !"Yes".equals(mandatory.get()))
            {
                findings.add(finding(aRule, node, binding.variable()));
            }
        }
        return findings;
    }


    /**
     * CDISC 97/98/99: nci:ExtCodeID alias presence on codelists (and their items) referenced by a
     * variable the library says requires CT. The CT-required CodeList set is computed once from
     * every ItemGroupDef/ItemRef binding in the document.
     */
    private static List<ConformanceFinding> libraryCtAliasRequired(ConformanceRule aRule,
            List<ElementNode> aNodes, CheckDefinition.LibraryCtAliasRequired aCheck,
            DocumentContext aContext)
    {
        Set<ElementNode> ctRequired = ctRequiredCodeLists(aContext);
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            if ("codelist".equals(aCheck.level()))
            {
                if (ctRequired.contains(node) && nciCodeOf(node).isEmpty())
                {
                    findings.add(finding(aRule, node,
                            node.attribute("Name").or(() -> node.attribute("OID")).orElse(null)));
                }
                continue;
            }
            // Item levels: the scoped node is the EnumeratedItem / CodeListItem itself. A
            // def:ExtendedValue-marked item is a declared extension — no CT alias expected.
            boolean inReach = enclosingCodeList(node).filter(ctRequired::contains).isPresent()
                    && node.attribute("ExtendedValue").isEmpty();
            if (inReach && nciCodeOf(node).isEmpty())
            {
                findings.add(finding(aRule, node, node.attribute("CodedValue").orElse(null)));
            }
        }
        return findings;
    }


    /**
     * The CodeList elements referenced (via CodeListRef) by at least one ItemGroupDef/ItemRef-bound
     * variable to which the library assigns a CT codelist — the sheets' "variable that requires
     * CDISC Controlled Terminology according to the standard" (CDISC 97/98/99).
     */
    private static Set<ElementNode> ctRequiredCodeLists(DocumentContext aContext)
    {
        LibraryProvider library = libraryProvider(aContext);
        Set<ElementNode> codeLists = new HashSet<>();
        for (ElementNode node : aContext.allNodes())
        {
            if (!"ItemRef".equals(node.localName())
                    || node.parent().filter(p -> "ItemGroupDef".equals(p.localName())).isEmpty())
            {
                continue;
            }
            VariableBinding binding = resolveVariable(node, aContext).orElse(null);
            if (binding == null || library.variableCodelistCCode(binding.standard().name(),
                    binding.standard().version(), binding.dataset(), binding.variable()).isEmpty())
            {
                continue;
            }
            binding.itemDef().children("CodeListRef").stream().findFirst()
                    .flatMap(ref -> ref.attribute("CodeListOID"))
                    .flatMap(oid -> aContext.oidResolver().resolve("CodeList", "OID", oid))
                    .ifPresent(codeLists::add);
        }
        return codeLists;
    }


    /**
     * CDISC 263: the scoped element's version attribute must be one of the library's published
     * versions for its name attribute; an unknown name is out of reach.
     */
    private static List<ConformanceFinding> libraryStandardVersion(ConformanceRule aRule,
            List<ElementNode> aNodes, CheckDefinition.LibraryStandardVersionKnown aCheck,
            DocumentContext aContext)
    {
        LibraryProvider library = libraryProvider(aContext);
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode node : aNodes)
        {
            Optional<String> name = node.attribute(aCheck.nameAttributeOrDefault())
                    .map(String::trim).filter(v -> !v.isEmpty());
            Optional<String> version = node.attribute(aCheck.versionAttributeOrDefault())
                    .map(String::trim).filter(v -> !v.isEmpty());
            if (name.isEmpty() || version.isEmpty())
            {
                continue;
            }
            List<String> published = library.publishedStandardVersions(name.get());
            if (!published.isEmpty() && !published.contains(version.get()))
            {
                findings.add(finding(aRule, node, name.get() + " " + version.get()));
            }
        }
        return findings;
    }

    /** A resolved ItemRef: its governing standard, dataset name, variable name and ItemDef. */
    private record VariableBinding(IgStandard standard, String dataset, String variable,
            ElementNode itemDef)
    {
    }

    /**
     * Resolves an {@code ItemGroupDef/ItemRef} to its variable binding: the parent's governing
     * standard and dataset name plus the dereferenced ItemDef and its Name. Empty when any link is
     * missing — presence rules own those defects.
     */
    private static Optional<VariableBinding> resolveVariable(ElementNode aItemRef,
            DocumentContext aContext)
    {
        Optional<ElementNode> parent = aItemRef.parent();
        if (parent.isEmpty())
        {
            return Optional.empty();
        }
        Optional<IgStandard> standard = governingStandard(parent.get(), aContext);
        Optional<String> dataset = datasetNameOf(parent.get());
        Optional<ElementNode> itemDef = aItemRef.attribute("ItemOID")
                .flatMap(oid -> aContext.oidResolver().resolve("ItemDef", "OID", oid));
        Optional<String> variable = itemDef.flatMap(def -> def.attribute("Name"))
                .filter(v -> !v.isBlank());
        if (standard.isEmpty() || dataset.isEmpty() || itemDef.isEmpty() || variable.isEmpty())
        {
            return Optional.empty();
        }
        return Optional.of(
                new VariableBinding(standard.get(), dataset.get(), variable.get(), itemDef.get()));
    }


    /**
     * The standard governing an ItemGroupDef: its {@code def:StandardOID} dereferenced to a
     * {@code def:Standard} (2.1), else the enclosing {@code MetaDataVersion}'s
     * {@code def:StandardName}/{@code def:StandardVersion} attributes (2.0).
     */
    private static Optional<IgStandard> governingStandard(ElementNode aItemGroupDef,
            DocumentContext aContext)
    {
        Optional<IgStandard> byOid = aItemGroupDef.attribute("StandardOID")
                .flatMap(oid -> aContext.oidResolver().resolve("Standard", "OID", oid))
                .flatMap(RuleEvaluator::standardOf);
        if (byOid.isPresent())
        {
            return byOid;
        }
        for (ElementNode current = aItemGroupDef;;)
        {
            Optional<ElementNode> parent = current.parent();
            if (parent.isEmpty())
            {
                return Optional.empty();
            }
            if ("MetaDataVersion".equals(parent.get().localName()))
            {
                return standardOf(parent.get(), "StandardName", "StandardVersion");
            }
            current = parent.get();
        }
    }


    /**
     * The document-level IG standard (DD0116, whose FATESTCD ItemDef has no dataset anchor): the
     * first {@code def:Standard} element naming an SDTMIG/SENDIG (2.1), else the first
     * {@code MetaDataVersion} whose 2.0 standard attributes do.
     */
    private static Optional<IgStandard> documentIgStandard(DocumentContext aContext)
    {
        for (ElementNode node : aContext.allNodes())
        {
            Optional<IgStandard> standard = switch (node.localName())
            {
            case "Standard" -> standardOf(node).filter(IgStandard::isSdtmigOrSendig);
            case "MetaDataVersion" -> standardOf(node, "StandardName", "StandardVersion")
                    .filter(IgStandard::isSdtmigOrSendig);
            default -> Optional.empty();
            };
            if (standard.isPresent())
            {
                return standard;
            }
        }
        return Optional.empty();
    }


    /** The {@code def:Standard} element's own Name/Version pair. */
    private static Optional<IgStandard> standardOf(ElementNode aStandard)
    {
        return standardOf(aStandard, "Name", "Version");
    }


    private static Optional<IgStandard> standardOf(ElementNode aNode, String aNameAttribute,
            String aVersionAttribute)
    {
        Optional<String> name = aNode.attribute(aNameAttribute).filter(v -> !v.isBlank());
        Optional<String> version = aNode.attribute(aVersionAttribute).filter(v -> !v.isBlank());
        if (name.isEmpty() || version.isEmpty())
        {
            return Optional.empty();
        }
        return Optional.of(new IgStandard(name.get(), version.get()));
    }


    /**
     * The dataset anchor for VARIABLE lookups on an ItemGroupDef: {@code @Domain}, else
     * {@code @Name}. Domain-first is right here (a SUPP--/split dataset's variables live under the
     * parent domain in the IG) but wrong for the dataset-label kind, which uses the dataset's own
     * {@code @Name}.
     */
    private static Optional<String> datasetNameOf(ElementNode aItemGroupDef)
    {
        return aItemGroupDef.attribute("Domain").filter(v -> !v.isBlank())
                .or(() -> aItemGroupDef.attribute("Name").filter(v -> !v.isBlank()));
    }


    /**
     * The element's English text under a container child ({@code Description}/{@code Decode}): the
     * first {@code TranslatedText} whose {@code xml:lang} is absent, {@code en}, or {@code en-*}.
     */
    private static Optional<String> englishText(ElementNode aElement, String aContainer)
    {
        for (ElementNode container : aElement.children(aContainer))
        {
            for (ElementNode text : container.children("TranslatedText"))
            {
                // BCP-47 language tags are case-insensitive.
                String lang = text.attribute("lang").orElse("").toLowerCase(Locale.ROOT);
                if (lang.isEmpty() || lang.equals("en") || lang.startsWith("en-"))
                {
                    Optional<String> value = text.text().filter(v -> !v.isBlank());
                    if (value.isPresent())
                    {
                        return value;
                    }
                }
            }
        }
        return Optional.empty();
    }


    private static LibraryProvider libraryProvider(DocumentContext aContext)
    {
        return aContext.libraryProvider()
                .orElseThrow(() -> new IllegalStateException(
                        "library-backed check evaluated without a LibraryProvider - the rule must "
                                + "declare 'Requires: library'"));
    }


    /** The nearest enclosing {@code CodeList} ancestor of a node. */
    private static Optional<ElementNode> enclosingCodeList(ElementNode aNode)
    {
        ElementNode current = aNode;
        while (true)
        {
            Optional<ElementNode> parent = current.parent();
            if (parent.isEmpty())
            {
                return Optional.empty();
            }
            if ("CodeList".equals(parent.get().localName()))
            {
                return parent;
            }
            current = parent.get();
        }
    }


    /** The element's own {@code Alias[@Context="nci:ExtCodeID"]/@Name} c-code, if any. */
    private static Optional<String> nciCodeOf(ElementNode aElement)
    {
        for (ElementNode alias : aElement.children("Alias"))
        {
            if (alias.attribute("Context").map(NCI_EXT_CODE_ID::equals).orElse(false))
            {
                Optional<String> name = alias.attribute("Name").filter(v -> !v.isBlank());
                if (name.isPresent())
                {
                    return name;
                }
            }
        }
        return Optional.empty();
    }


    /**
     * The CT codelist the node's enclosing {@code CodeList} refers to via its nci:ExtCodeID alias
     * c-code; empty when there is no enclosing CodeList, no alias, or CT does not know the c-code.
     */
    private static Optional<CtCodelist> resolveEnclosingCodelist(ElementNode aNode,
            CtProvider aProvider)
    {
        return enclosingCodeList(aNode).flatMap(RuleEvaluator::nciCodeOf)
                .flatMap(aProvider::codelistByCCode);
    }


    private static boolean isExtendedValueMarked(ElementNode aNode)
    {
        return aNode.attribute("ExtendedValue").map("Yes"::equals).orElse(false);
    }


    private static CustomCheck instantiate(String aClassName)
    {
        try
        {
            return Class.forName(aClassName).asSubclass(CustomCheck.class).getDeclaredConstructor()
                    .newInstance();
        }
        catch (ReflectiveOperationException | ClassCastException e)
        {
            throw new IllegalStateException("cannot instantiate CustomCheck " + aClassName, e);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------


    /** The checked value on a scoped node: an attribute, or its text content for {@code #text}. */
    private static Optional<String> valueOf(ElementNode aNode, String aAttribute)
    {
        if ("#text".equals(aAttribute))
        {
            return aNode.text();
        }
        return aNode.attribute(PathResolver.stripPrefix(aAttribute));
    }


    private static ConformanceFinding finding(ConformanceRule aRule, ElementNode aNode,
            @Nullable String aValue)
    {
        return ConformanceFinding.builder()//
                .ruleId(aRule.ruleId())//
                .element(aRule.element())//
                .attribute(aRule.attribute())//
                .xpath(aNode.xpath())//
                .message(render(aRule.message(), aValue))//
                .category(categoryOf(aRule))//
                .severity(aRule.effectiveSeverity())//
                .build();
    }


    private static String render(String aTemplate, @Nullable String aValue)
    {
        return aTemplate.replace("${value}", aValue == null ? "" : aValue);
    }


    private static Category categoryOf(ConformanceRule aRule)
    {
        if (aRule.ruleSet() == RuleSet.PMDA)
        {
            return Category.PMDA;
        }
        String sourceType = aRule.sourceType();
        return sourceType != null && sourceType.equalsIgnoreCase("Schema") ? Category.SCHEMA
                : Category.SPECIFICATION;
    }

}
