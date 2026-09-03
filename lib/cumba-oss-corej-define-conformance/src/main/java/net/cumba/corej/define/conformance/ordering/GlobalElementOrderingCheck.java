package net.cumba.corej.define.conformance.ordering;

import static java.util.Map.entry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.define.conformance.report.Category;
import net.cumba.corej.define.conformance.report.ConformanceFinding;
import net.cumba.corej.define.conformance.report.Severity;
import net.cumba.corej.define.conformance.tree.ElementNode;

/**
 * The global element-ordering checker (plan §5) — a hand-written Java check, deliberately NOT a
 * YAML rule: ordering is a whole-document property of sibling sequences, not a per-element
 * predicate the declarative DSL can express.
 *
 * <p>
 * Input is the {@link ElementNode} tree (built by
 * {@code ElementNodeBuilder.build(DefineDomIo.parse(...))}), whose {@link ElementNode#children()}
 * is true source-document order — the property this check observes. Pass the document root (the
 * {@code ODM} node) to {@link #check(ElementNode)}.
 * </p>
 *
 * <h2>Canonical order table</h2>
 * <p>
 * {@link #CANONICAL_CHILD_ORDER} maps a parent element's bare local name to its children's bare
 * local names in XML-Schema sequence order, derived from the vendored schemas under
 * {@code src/main/resources/xsd/define-2-1/} (see the field's per-entry citations). Children whose
 * local name is not in the parent's list — vendor extensions, ARM's
 * {@code arm:AnalysisResultDisplays}, anything unknown — are <b>ignored as anchors</b>: they never
 * raise the running maximum rank and are never reported, so legal schema extensions cannot cause
 * false positives. Parents without a table entry are not checked at all.
 * </p>
 *
 * <h2>Define-XML 2.0 vs 2.1</h2>
 * <p>
 * One table serves both versions, so {@code check} takes no version argument. The foundation schema
 * ({@code ODM1-3-2-foundation.xsd}) is byte-identical in the vendored {@code define-2-0} and
 * {@code define-2-1} trees, and every 2.0-vs-2.1 content-model difference among the covered parents
 * is <b>presence-only</b>, never relative order:
 * </p>
 * <ul>
 * <li>{@code MetaDataVersion}: 2.0's pre-Include extension group lacks {@code def:Standards}
 * (define-2-0 {@code define-extension.xsd} l.31-43 vs define-2-1 l.39-48); the remaining children
 * keep the same relative order.</li>
 * <li>{@code ItemGroupDef}: 2.0's extension group lacks {@code def:Class} (an attribute in 2.0;
 * define-2-0 l.69-74 vs define-2-1 l.74-80).</li>
 * <li>{@code CodeListItem} / {@code EnumeratedItem}: only 2.1 appends a trailing
 * {@code Description} (define-2-1 {@code define-extension.xsd} l.146-163).</li>
 * <li>{@code def:ValueListDef}: only 2.1 has the leading optional {@code Description} (define-2-1
 * {@code define-ns.xsd} l.184-200 vs define-2-0 l.115-121).</li>
 * </ul>
 * <p>
 * The 2.1-superset table therefore yields identical verdicts on schema-legal 2.0 documents (the
 * 2.1-only children simply never occur there).
 * </p>
 *
 * <h2>Algorithm and emission</h2>
 * <p>
 * For each parent with a table entry, walk the children in source order tracking the maximum rank
 * seen; a known child whose rank is <em>less</em> than that maximum is out of order. Findings are
 * emitted <b>once per offending child</b> (not once per parent), naming the child that appears
 * after a higher-ranked sibling — so for {@code Alias} written before {@code Decode}, the reported
 * child is {@code Decode}. Members of an {@code xs:choice} ({@code CodeListItem} /
 * {@code ExternalCodeList} / {@code EnumeratedItem} under {@code CodeList}; {@code CheckValue} /
 * {@code FormalExpression} under {@code RangeCheck}) share one rank, so their mutual order is never
 * flagged. Repeated same-rank children (lists) are always fine.
 * </p>
 *
 * <p>
 * <b>Dual-id emission</b> (plan §5 step 3): every defect yields TWO findings — CDISC sheet row 1 as
 * {@code DEFINE-XML-0001} ({@link Category#SCHEMA}, {@link Severity#ERROR}) and PMDA sheet
 * {@code DD0008} as {@code PMDA-DD0008} ({@link Category#PMDA}, {@link Severity#ERROR}, sheet
 * message "Element in wrong position within Define.xml") — so both rule sets' report coverage stays
 * complete. Both findings carry the parent's xpath in {@code xpath()} and the offending child's
 * local name in {@code element()}, and both messages repeat that location detail.
 * </p>
 */
public final class GlobalElementOrderingCheck
{

    /** CDISC Define-XML v2.1 conformance sheet row 1 ("ALL elements", Source Type Schema). */
    public static final String CDISC_RULE_ID = "DEFINE-XML-0001";

    /** PMDA Validation Rules v6.0, Define-XML sheet id DD0008 (applies to 2.0 and 2.1). */
    public static final String PMDA_RULE_ID = "PMDA-DD0008";

    /**
     * Parent bare local name → child bare local names in schema sequence order.
     *
     * <p>
     * Citations reference the vendored {@code src/main/resources/xsd/define-2-1/} files:
     * {@code foundation} = {@code cdisc-odm-1.3.2/ODM1-3-2-foundation.xsd}, {@code ext} =
     * {@code cdisc-define-2.1/define-extension.xsd} (which splices {@code def:} elements into the
     * foundation's extension-group slots), {@code ns} = {@code cdisc-define-2.1/define-ns.xsd}.
     * </p>
     *
     * <p>
     * NOTE {@code Step20To21.MDV_ORDER} (in {@code lib/cumba-oss-cdisc-define}, package-private)
     * keeps a partial table for the same purpose, but its values do NOT match the XSD — it lists
     * {@code Include}/{@code Protocol} before {@code def:Standards}, whereas the foundation places
     * the {@code MetaDataVersionPreIncludeElementExtension} group (which the 2.1 extension fills
     * with {@code Standards..WhereClauseDef}) BEFORE {@code Include} (foundation l.1532-1547), and
     * it lists a {@code Description} child the schema does not define for {@code MetaDataVersion}
     * (Description is an MDV <em>attribute</em>, foundation l.589-592). The values below are
     * therefore re-derived from the XSDs rather than copied.
     * </p>
     */
    // @formatter:off
    static final Map<String, List<String>> CANONICAL_CHILD_ORDER = Map.ofEntries(
            // foundation l.2270-2283 (ODM element declaration)
            entry("ODM", List.of(
                    "Study", "AdminData", "ReferenceData", "ClinicalData", "Association",
                    "Signature")),
            // foundation l.1490-1499
            entry("Study", List.of("GlobalVariables", "BasicDefinitions", "MetaDataVersion")),
            // foundation l.1500-1508
            entry("GlobalVariables", List.of("StudyName", "StudyDescription", "ProtocolName")),
            // foundation l.1532-1550; pre-Include slot filled by ext l.39-48
            // (Standards..WhereClauseDef), trailing slot by ext l.50-56 (CommentDef, leaf)
            entry("MetaDataVersion", List.of(
                    "Standards", "AnnotatedCRF", "SupplementalDoc", "ValueListDef",
                    "WhereClauseDef", "Include", "Protocol", "StudyEventDef", "FormDef",
                    "ItemGroupDef", "ItemDef", "CodeList", "ImputationMethod", "Presentation",
                    "ConditionDef", "MethodDef", "CommentDef", "leaf")),
            // foundation l.1552-1561
            entry("Protocol", List.of("Description", "StudyEventRef", "Alias")),
            // foundation l.1588-1597; trailing slot filled by ext l.74-80 (Class, leaf)
            entry("ItemGroupDef", List.of("Description", "ItemRef", "Alias", "Class", "leaf")),
            // foundation l.1375-1382; extension slot filled by ext l.92-97 (WhereClauseRef)
            entry("ItemRef", List.of("WhereClauseRef")),
            // foundation l.1598-1611; trailing slot filled by ext l.109-116 (Origin, ValueListRef)
            entry("ItemDef", List.of(
                    "Description", "Question", "ExternalQuestion", "MeasurementUnitRef",
                    "RangeCheck", "CodeListRef", "Role", "Alias", "Origin", "ValueListRef")),
            // foundation l.1620-1632; CheckValue|FormalExpression is an xs:choice (same rank)
            entry("RangeCheck", List.of(
                    "CheckValue", "FormalExpression", "MeasurementUnitRef", "ErrorMessage")),
            // foundation l.1640-1653; the three item kinds are an xs:choice (same rank)
            entry("CodeList", List.of(
                    "Description", "CodeListItem", "ExternalCodeList", "EnumeratedItem", "Alias")),
            // foundation l.1654-1662; trailing slot filled by ext l.146-151 (Description, 2.1)
            entry("CodeListItem", List.of("Decode", "Alias", "Description")),
            // foundation l.1885-1893; trailing slot filled by ext l.158-163 (Description, 2.1)
            entry("EnumeratedItem", List.of("Alias", "Description")),
            // foundation l.1663-1669
            entry("Decode", List.of("TranslatedText")),
            // foundation l.1900-1906
            entry("Description", List.of("TranslatedText")),
            // foundation l.1943-1952; trailing slot filled by ext l.169-174 (DocumentRef)
            entry("MethodDef", List.of("Description", "FormalExpression", "Alias", "DocumentRef")),
            // ns l.372-388 (def:CommentDef)
            entry("CommentDef", List.of("Description", "DocumentRef")),
            // ns l.220-238 (def:WhereClauseDef)
            entry("WhereClauseDef", List.of("RangeCheck")),
            // ns l.184-200 (def:ValueListDef; Description is 2.1-only, see class javadoc)
            entry("ValueListDef", List.of("Description", "ItemRef")),
            // ns l.245-264 (def:Origin)
            entry("Origin", List.of("Description", "DocumentRef")),
            // ns l.60-73 (def:DocumentRef)
            entry("DocumentRef", List.of("PDFPageRef")),
            // ns l.128-138 (def:AnnotatedCRF)
            entry("AnnotatedCRF", List.of("DocumentRef")),
            // ns l.149-159 (def:SupplementalDoc)
            entry("SupplementalDoc", List.of("DocumentRef")),
            // ns l.116-125 (def:Standards)
            entry("Standards", List.of("Standard")),
            // ns l.322-333 (def:Class, 2.1-only element)
            entry("Class", List.of("SubClass")),
            // ns l.395-411 (def:leaf)
            entry("leaf", List.of("title")));
    // @formatter:on

    /**
     * {@code xs:choice} member groups: exactly one branch may legally occur, so their mutual order
     * carries no meaning — members share the choice's rank (the group's smallest table index).
     * Applied per-parent, only where all members appear in that parent's list.
     */
    private static final List<Set<String>> SAME_RANK_GROUPS = List.of(
            Set.of("CodeListItem", "ExternalCodeList", "EnumeratedItem"),
            Set.of("CheckValue", "FormalExpression"));

    /** Parent local name → (child local name → rank), with choice members collapsed to one rank. */
    private static final Map<String, Map<String, Integer>> RANKS = buildRanks();

    private static Map<String, Map<String, Integer>> buildRanks()
    {
        Map<String, Map<String, Integer>> out = new HashMap<>();
        for (Map.Entry<String, List<String>> parent : CANONICAL_CHILD_ORDER.entrySet())
        {
            List<String> order = parent.getValue();
            Map<String, Integer> ranks = new HashMap<>();
            for (int i = 0; i < order.size(); i++)
            {
                ranks.put(order.get(i), i);
            }
            for (Set<String> group : SAME_RANK_GROUPS)
            {
                if (ranks.keySet().containsAll(group))
                {
                    int min = group.stream()
                            .mapToInt(name -> ranks.getOrDefault(name, Integer.MAX_VALUE)).min()
                            .orElseThrow();
                    for (String name : group)
                    {
                        ranks.put(name, min);
                    }
                }
            }
            out.put(parent.getKey(), Map.copyOf(ranks));
        }
        return Map.copyOf(out);
    }


    /**
     * Checks every element under (and including) {@code aRoot} against the canonical child-order
     * table and returns the findings — two per out-of-order child (see class javadoc), in document
     * order, empty when the document is fully in order.
     *
     * @param aRoot
     *            the document root (the {@code ODM} node of an {@code ElementNodeBuilder}-built
     *            tree); any subtree node is also accepted and checked recursively
     */
    public List<ConformanceFinding> check(ElementNode aRoot)
    {
        List<ConformanceFinding> findings = new ArrayList<>();
        for (ElementNode parent : aRoot.selfAndDescendants())
        {
            Map<String, Integer> ranks = RANKS.get(parent.localName());
            if (ranks == null)
            {
                continue; // parent not in the table — not checked
            }
            int maxRank = -1;
            for (ElementNode child : parent.children())
            {
                Integer rank = ranks.get(child.localName());
                if (rank == null)
                {
                    continue; // unknown / vendor-extension child — never an anchor
                }
                if (rank < maxRank)
                {
                    emit(findings, parent, child);
                }
                else
                {
                    maxRank = rank;
                }
            }
        }
        return findings;
    }


    private static void emit(List<ConformanceFinding> aOut, ElementNode aParent, ElementNode aChild)
    {
        String location = "Element " + aChild.localName() + " is out of the canonical order under "
                + aParent.localName() + " (" + aParent.xpath() + ")";
        aOut.add(ConformanceFinding.builder() //
                .ruleId(CDISC_RULE_ID) //
                .element(aChild.localName()) //
                .xpath(aParent.xpath()) //
                .message(location) //
                .category(Category.SCHEMA) //
                .severity(Severity.ERROR) //
                .build());
        aOut.add(ConformanceFinding.builder() //
                .ruleId(PMDA_RULE_ID) //
                .element(aChild.localName()) //
                .xpath(aParent.xpath()) //
                .message("Element in wrong position within Define.xml: " + location) //
                .category(Category.PMDA) //
                .severity(Severity.ERROR) //
                .build());
    }

}
