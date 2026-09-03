package net.cumba.corej.core.metadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import net.cumba.cdisc.define.Alias;
import net.cumba.cdisc.define.CheckValue;
import net.cumba.cdisc.define.CodeList;
import net.cumba.cdisc.define.CodeListItem;
import net.cumba.cdisc.define.Decode;
import net.cumba.cdisc.define.EnumeratedItem;
import net.cumba.cdisc.define.ItemDef;
import net.cumba.cdisc.define.ItemGroupDef;
import net.cumba.cdisc.define.ItemRef;
import net.cumba.cdisc.define.MetaDataVersion;
import net.cumba.cdisc.define.RangeCheck;
import net.cumba.cdisc.define.ValueListDef;
import net.cumba.cdisc.define.WhereClauseDef;
import net.cumba.cdisc.define.WhereClauseRef;
import org.jspecify.annotations.Nullable;

/**
 * Per-record Define-XML <b>value-level metadata (VLM)</b> resolver. For a given (domain, variable,
 * record) it selects the applicable value-level {@link ItemDef} by navigating
 * {@code ItemDef.valueListRef -> ValueListDef.itemRefs -> ItemRef.whereClauseRefs ->
 * WhereClauseDef.rangeChecks} and evaluating the where-clause predicate against the record.
 *
 * <p>
 * The metadata surfaced by the matched {@link ItemDef} (data type / length / mandatory / codelist
 * coded values &amp; codes / codelist C-code) is what the {@code vlm_*(varname())} expression
 * accessors read (the {@code Value Check against Define XML VLM} rule type).
 * </p>
 *
 * <h2>Semantics (grounded in {@code plans/done/PLAN-engine-vlm.md} §5.3)</h2>
 * <ul>
 * <li>A {@link WhereClauseDef} with multiple {@link RangeCheck}s is a logical <b>AND</b> — every
 * range check must hold (this matches the Define-XML model; the Python reference engine is patched
 * to the same behaviour, previously reading {@code RangeCheck[0]} only).</li>
 * <li>Comparators: {@code EQ, NE, IN, NOTIN} (string membership) and {@code LT, LE, GT, GE}
 * (numeric-aware, falling back to lexical compare for non-numeric operands).</li>
 * <li>When more than one {@link ItemRef} matches a record, the <b>first by {@code OrderNumber}</b>
 * is chosen (well-formed defines have mutually-exclusive where-clauses).</li>
 * <li>A {@code null} record cell never satisfies a range check.</li>
 * </ul>
 *
 * <p>
 * The resolver is immutable and thread-safe after construction. Structural inconsistencies in the
 * define (dangling {@code WhereClauseRef} / {@code RangeCheck.ItemOID}, or an ItemRef with no
 * where-clause) are collected once at {@link #from(MetaDataVersion)} time and exposed via
 * {@link #structuralWarnings()} for the define-load diagnostic channel; they never abort
 * evaluation.
 * </p>
 */
public final class VlmResolver
{

    private static final String EXT_CODE_ID = "nci:ExtCodeID";

    /**
     * The metadata a matched value-level {@link ItemDef} contributes to the {@code vlm_*}
     * accessors.
     */
    public record VlmMatch(@Nullable String dataType, @Nullable Integer length,
            @Nullable String mandatory, @Nullable String codeListOid, List<String> codedValues,
            List<String> codedCodes, List<String> decodes, @Nullable String codelistCCode,
            Map<String, String> codeDecodeMap)
    {

        /** Defensively copies the collection components so the record is genuinely immutable. */
        public VlmMatch
        {
            codedValues = List.copyOf(codedValues);
            codedCodes = List.copyOf(codedCodes);
            decodes = List.copyOf(decodes);
            codeDecodeMap = Map.copyOf(codeDecodeMap);
        }
    }

    private final Map<String, ItemDef> itemDefsByOid;

    private final Map<String, WhereClauseDef> whereClausesByOid;

    private final Map<String, CodeList> codeListsByOid;

    /** domain-key (ItemGroupDef name and/or domain) -&gt; variable name -&gt; its ValueListDef. */
    private final Map<String, Map<String, ValueListDef>> valueListByVar;

    private final List<String> structuralWarnings;

    private VlmResolver(Map<String, ItemDef> itemDefsByOid,
            Map<String, WhereClauseDef> whereClausesByOid, Map<String, CodeList> codeListsByOid,
            Map<String, Map<String, ValueListDef>> valueListByVar, List<String> structuralWarnings)
    {
        this.itemDefsByOid = itemDefsByOid;
        this.whereClausesByOid = whereClausesByOid;
        this.codeListsByOid = codeListsByOid;
        this.valueListByVar = valueListByVar;
        this.structuralWarnings = structuralWarnings;
    }


    /**
     * Builds a resolver from a parsed {@link MetaDataVersion}, or returns {@code null} when the
     * model carries no value-level metadata (no {@code ValueListDef}s) so callers can leave
     * {@code EvaluationContext.vlmResolver} unset (VLM rules then SKIP via the DEFINE provider
     * gate, exactly as when no Define-XML is supplied).
     */
    public static @Nullable VlmResolver from(@Nullable MetaDataVersion mdv)
    {
        if (mdv == null || mdv.getValueListDefs() == null || mdv.getValueListDefs().isEmpty())
        {
            return null;
        }
        Map<String, ItemDef> itemDefs = index(mdv.getItemDefs(), ItemDef::getOid);
        Map<String, WhereClauseDef> whereClauses = index(mdv.getWhereClauseDefs(),
                WhereClauseDef::getOid);
        Map<String, CodeList> codeLists = index(mdv.getCodeLists(), CodeList::getOid);
        Map<String, ValueListDef> valueLists = index(mdv.getValueListDefs(), ValueListDef::getOid);

        Map<String, Map<String, ValueListDef>> byVar = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        if (mdv.getItemGroupDefs() != null)
        {
            for (ItemGroupDef igd : mdv.getItemGroupDefs())
            {
                if (igd.getItemRefs() == null)
                {
                    continue;
                }
                Map<String, ValueListDef> vars = new LinkedHashMap<>();
                for (ItemRef ref : igd.getItemRefs())
                {
                    ItemDef def = itemDefs.get(ref.getItemOID());
                    if (def == null || def.getValueListRef() == null || def.getName() == null)
                    {
                        continue;
                    }
                    ValueListDef vl = valueLists.get(def.getValueListRef().getValueListOID());
                    if (vl != null)
                    {
                        vars.put(def.getName(), vl);
                    }
                }
                if (!vars.isEmpty())
                {
                    putDomainKey(byVar, igd.getName(), vars);
                    putDomainKey(byVar, igd.getDomain(), vars);
                }
            }
        }
        validateStructure(valueLists.values(), whereClauses, itemDefs, warnings);
        return new VlmResolver(itemDefs, whereClauses, codeLists, byVar, List.copyOf(warnings));
    }


    /**
     * Resolves the value-level metadata applicable to a record of {@code variable} in
     * {@code domain}.
     *
     * @param domain
     *            the dataset name / domain being evaluated
     * @param variable
     *            the target variable (e.g. {@code LBSTRESC})
     * @param cell
     *            reads the record's value of an arbitrary variable name (the where-clause
     *            operands); returns {@code null} for an absent column
     * @return the matched value-level metadata, or {@code null} when no where-clause matches (⇒ the
     *         {@code vlm_*} accessor yields {@code null} ⇒ the predicate does not fire)
     */
    public @Nullable VlmMatch resolve(@Nullable String domain, @Nullable String variable,
            Function<String, @Nullable String> cell)
    {
        if (domain == null || variable == null)
        {
            return null;
        }
        Map<String, ValueListDef> vars = valueListByVar.get(domain);
        ValueListDef vl = vars == null ? null : vars.get(variable);
        if (vl == null || vl.getItemRefs() == null)
        {
            return null;
        }
        return vl.getItemRefs().stream()
                .sorted(Comparator.comparingInt(
                        r -> r.getOrderNumber() == null ? Integer.MAX_VALUE : r.getOrderNumber()))
                .filter(ref -> matches(ref, cell)).findFirst().map(this::toMatch).orElse(null);
    }


    /**
     * The structural-integrity warnings discovered at construction (dangling refs, missing
     * clauses).
     */
    public List<String> structuralWarnings()
    {
        return structuralWarnings;
    }


    private boolean matches(ItemRef ref, Function<String, @Nullable String> cell)
    {
        if (ref.getWhereClauseRefs() == null || ref.getWhereClauseRefs().isEmpty())
        {
            return false;
        }
        for (WhereClauseRef wcr : ref.getWhereClauseRefs())
        {
            WhereClauseDef wc = whereClausesByOid.get(wcr.getWhereClauseOID());
            if (wc == null || wc.getRangeChecks() == null)
            {
                return false;
            }
            for (RangeCheck rc : wc.getRangeChecks())
            {
                ItemDef condVar = itemDefsByOid.get(rc.getItemOID());
                String col = condVar == null ? null : condVar.getName();
                String actual = col == null ? null : cell.apply(col);
                if (!compare(rc.getComparator(), actual, values(rc.getCheckValues())))
                {
                    return false;
                }
            }
        }
        return true;
    }


    private VlmMatch toMatch(ItemRef ref)
    {
        ItemDef vd = itemDefsByOid.get(ref.getItemOID());
        String dataType = vd == null ? null : vd.getDataType();
        Integer length = vd == null ? null : vd.getLength();
        List<String> codedValues = List.of();
        List<String> codedCodes = List.of();
        List<String> decodes = List.of();
        Map<String, String> codeDecodeMap = Map.of();
        String codeListOid = null;
        String cCode = null;
        CodeList cl = vd != null && vd.getCodeListRef() != null
                ? codeListsByOid.get(vd.getCodeListRef().getCodeListOID())
                : null;
        if (cl != null)
        {
            codeListOid = cl.getOid();
            codedValues = codedValues(cl);
            codedCodes = codedCodes(cl);
            decodes = decodes(cl);
            codeDecodeMap = codeDecodeMap(cl);
            cCode = extCodeId(cl);
        }
        return new VlmMatch(dataType, length, ref.getMandatory(), codeListOid, codedValues,
                codedCodes, decodes, cCode, codeDecodeMap);
    }


    /**
     * The value-level codelist's coded value → decode mapping, built from {@code CodeListItem}s
     * only (each carries both a {@code CodedValue} and a {@code Decode}) so the pair is always
     * aligned — the source for the CT2003/CT2006 code/decode pairing check.
     */
    private static Map<String, String> codeDecodeMap(CodeList cl)
    {
        // Fix #123: shared with OdmDefineXMLProvider's variable-level reading so the value-level
        // and variable-level code/decode expectations can never drift apart.
        return CodeListDecodes.codeDecodeMap(cl);
    }


    private static boolean compare(@Nullable String comparator, @Nullable String actual,
            List<String> checkValues)
    {
        if (actual == null || comparator == null)
        {
            return false;
        }
        return switch (comparator.trim().toUpperCase(Locale.ROOT))
        {
        case "EQ" -> !checkValues.isEmpty() && actual.equals(checkValues.get(0));
        case "NE" -> !checkValues.isEmpty() && !actual.equals(checkValues.get(0));
        case "IN" -> checkValues.contains(actual);
        case "NOTIN" -> !checkValues.contains(actual);
        // An ordering comparator with no bound (a structurally-broken RangeCheck) never matches.
        case "LT" -> !checkValues.isEmpty() && numericAware(actual, checkValues) < 0;
        case "LE" -> !checkValues.isEmpty() && numericAware(actual, checkValues) <= 0;
        case "GT" -> !checkValues.isEmpty() && numericAware(actual, checkValues) > 0;
        case "GE" -> !checkValues.isEmpty() && numericAware(actual, checkValues) >= 0;
        default -> false;
        };
    }


    /**
     * Compares {@code actual} to the first check value numerically when both parse, else lexically.
     * The caller guards against an empty {@code checkValues}.
     */
    private static int numericAware(String actual, List<String> checkValues)
    {
        String bound = checkValues.get(0);
        Double a = parseDouble(actual);
        Double b = parseDouble(bound);
        if (a != null && b != null)
        {
            return Double.compare(a, b);
        }
        return actual.compareTo(bound);
    }


    private static @Nullable Double parseDouble(@Nullable String s)
    {
        if (s == null || s.isBlank())
        {
            return null;
        }
        try
        {
            return Double.valueOf(s.trim());
        }
        catch (NumberFormatException _)
        {
            return null;
        }
    }


    private static List<String> values(@Nullable List<CheckValue> checkValues)
    {
        if (checkValues == null)
        {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (CheckValue cv : checkValues)
        {
            if (cv.getValue() != null)
            {
                out.add(cv.getValue());
            }
        }
        return out;
    }


    private static List<String> codedValues(CodeList cl)
    {
        List<String> out = new ArrayList<>();
        if (cl.getCodeListItems() != null)
        {
            for (CodeListItem it : cl.getCodeListItems())
            {
                if (it.getCodedValue() != null)
                {
                    out.add(it.getCodedValue());
                }
            }
        }
        if (cl.getEnumeratedItems() != null)
        {
            for (EnumeratedItem it : cl.getEnumeratedItems())
            {
                if (it.getCodedValue() != null)
                {
                    out.add(it.getCodedValue());
                }
            }
        }
        return out;
    }


    private static List<String> codedCodes(CodeList cl)
    {
        List<String> codes = new ArrayList<>();
        if (cl.getCodeListItems() != null)
        {
            for (CodeListItem it : cl.getCodeListItems())
            {
                addAliasNames(codes, it.getAliases());
            }
        }
        if (cl.getEnumeratedItems() != null)
        {
            for (EnumeratedItem it : cl.getEnumeratedItems())
            {
                addAliasNames(codes, it.getAliases());
            }
        }
        return codes;
    }


    private static List<String> decodes(CodeList cl)
    {
        List<String> out = new ArrayList<>();
        if (cl.getCodeListItems() != null)
        {
            for (CodeListItem it : cl.getCodeListItems())
            {
                String d = decode(it.getDecode());
                if (d != null)
                {
                    out.add(d);
                }
            }
        }
        return out;
    }


    private static @Nullable String decode(@Nullable Decode decode)
    {
        return CodeListDecodes.decodeText(decode);
    }


    private static void addAliasNames(List<String> codes, @Nullable List<Alias> aliases)
    {
        if (aliases != null)
        {
            for (Alias a : aliases)
            {
                if (a.getName() != null)
                {
                    codes.add(a.getName());
                }
            }
        }
    }


    private static @Nullable String extCodeId(CodeList cl)
    {
        if (cl.getAliases() == null)
        {
            return null;
        }
        for (Alias a : cl.getAliases())
        {
            if (EXT_CODE_ID.equals(a.getContext()))
            {
                return a.getName();
            }
        }
        return null;
    }


    private static <T> Map<String, T> index(@Nullable List<T> items, Function<T, String> key)
    {
        Map<String, T> out = new LinkedHashMap<>();
        if (items != null)
        {
            for (T item : items)
            {
                String k = key.apply(item);
                if (k != null)
                {
                    out.put(k, item);
                }
            }
        }
        return out;
    }


    private static void putDomainKey(Map<String, Map<String, ValueListDef>> byVar,
            @Nullable String domainKey, Map<String, ValueListDef> vars)
    {
        if (domainKey != null)
        {
            byVar.computeIfAbsent(domainKey, _ -> new LinkedHashMap<>()).putAll(vars);
        }
    }


    private static void validateStructure(Iterable<ValueListDef> valueLists,
            Map<String, WhereClauseDef> whereClauses, Map<String, ItemDef> itemDefs,
            List<String> warnings)
    {
        for (ValueListDef vl : valueLists)
        {
            if (vl.getItemRefs() == null)
            {
                continue;
            }
            for (ItemRef ref : vl.getItemRefs())
            {
                if (ref.getWhereClauseRefs() == null || ref.getWhereClauseRefs().isEmpty())
                {
                    warnings.add("ValueListDef " + vl.getOid() + " ItemRef " + ref.getItemOID()
                            + " has no WhereClauseRef; it can never match a record");
                    continue;
                }
                for (WhereClauseRef wcr : ref.getWhereClauseRefs())
                {
                    WhereClauseDef wc = whereClauses.get(wcr.getWhereClauseOID());
                    if (wc == null)
                    {
                        warnings.add("dangling WhereClauseRef " + wcr.getWhereClauseOID()
                                + " in ValueListDef " + vl.getOid());
                        continue;
                    }
                    if (wc.getRangeChecks() != null)
                    {
                        for (RangeCheck rc : wc.getRangeChecks())
                        {
                            if (!itemDefs.containsKey(rc.getItemOID()))
                            {
                                warnings.add("dangling RangeCheck ItemOID " + rc.getItemOID()
                                        + " in WhereClauseDef " + wc.getOid());
                            }
                        }
                    }
                }
            }
        }
    }

}
