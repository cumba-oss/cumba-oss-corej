package net.cumba.corej.core.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.cdisc.define.Alias;
import net.cumba.cdisc.define.CodeList;
import net.cumba.cdisc.define.CodeListItem;
import net.cumba.cdisc.define.EnumeratedItem;
import net.cumba.cdisc.define.ExternalCodeList;
import net.cumba.cdisc.define.ItemDef;
import net.cumba.cdisc.define.ItemGroupDef;
import net.cumba.cdisc.define.ItemRef;
import net.cumba.cdisc.define.MetaDataVersion;
import net.cumba.cdisc.define.ODM;
import net.cumba.cdisc.define.Origin;
import net.cumba.cdisc.define.Standard;
import net.cumba.cdisc.define.Study;
import net.cumba.cdisc.define.TranslatedText;
import net.cumba.cdisc.define.ValueListDef;
import net.cumba.corej.core.gen.CtStandardRef;
import net.cumba.corej.core.gen.DefineXMLProvider;
import net.cumba.datatable.dictionary.house.DictionaryTypes;
import org.jspecify.annotations.Nullable;

/**
 * A direct {@link DefineXMLProvider} over the parsed Define-XML object model ({@link ODM}). It
 * reads variable metadata straight from the {@code ItemGroupDef}/{@code ItemDef}/{@code ItemRef}/
 * {@code CodeList}/{@code Alias} structures — the same structures the Python reference engine's
 * {@code base_define_xml_reader} walks — so the {@code define_*} operands (including the codelist
 * {@code ccode} and coded codes) reach the engine <b>without</b> the lossy {@code IMetadataLibrary}
 * (datatable) conversion.
 *
 * <p>
 * Built from a {@code DefineXmlParser}-parsed {@link ODM}; {@code StudyValidationService}
 * constructs one from the run's {@code defineXmlPath} and composes it over the datatable-backed
 * define provider (which still serves dataset-level define metadata). The end-to-end behaviour is
 * covered by {@code DefineXmlDirectAccessE2ETest}.
 * </p>
 */
public final class OdmDefineXMLProvider implements DefineXMLProvider
{

    private static final System.Logger LOGGER = System
            .getLogger(OdmDefineXMLProvider.class.getName());

    private static final String EXT_CODE_ID = "nci:ExtCodeID";

    private final ODM odm;

    public OdmDefineXMLProvider(ODM aOdm)
    {
        this.odm = aOdm;
    }


    private @Nullable MetaDataVersion mdv()
    {
        List<Study> studies = odm.getStudies();
        if (studies == null || studies.isEmpty())
        {
            return null;
        }
        List<MetaDataVersion> versions = studies.get(0).getMetaDataVersions();
        return versions == null || versions.isEmpty() ? null : versions.get(0);
    }


    private @Nullable ItemGroupDef itemGroup(String datasetName)
    {
        MetaDataVersion mdv = mdv();
        if (mdv == null || mdv.getItemGroupDefs() == null)
        {
            return null;
        }
        for (ItemGroupDef igd : mdv.getItemGroupDefs())
        {
            if (datasetName.equals(igd.getName()) || datasetName.equals(igd.getDomain()))
            {
                return igd;
            }
        }
        return null;
    }


    private Map<String, ItemDef> itemDefsByOid()
    {
        MetaDataVersion mdv = mdv();
        Map<String, ItemDef> out = new LinkedHashMap<>();
        if (mdv != null && mdv.getItemDefs() != null)
        {
            for (ItemDef d : mdv.getItemDefs())
            {
                out.put(d.getOid(), d);
            }
        }
        return out;
    }


    private Map<String, ValueListDef> valueListsByOid()
    {
        MetaDataVersion mdv = mdv();
        Map<String, ValueListDef> out = new LinkedHashMap<>();
        if (mdv != null && mdv.getValueListDefs() != null)
        {
            for (ValueListDef vl : mdv.getValueListDefs())
            {
                out.put(vl.getOid(), vl);
            }
        }
        return out;
    }

    /**
     * Whether EVERY value-level entry of a variable carries a method, a comment and an origin — the
     * variable-level reading of Define-XML 2.1 §5.3.12.3 ("each ItemDef element referenced in the
     * corresponding def:ValueListDef includes a def:Origin element"), backing
     * {@code var_vlm_has_method} / {@code var_vlm_has_comment} / {@code var_vlm_has_origin}
     * ({@code PLAN-stopped-rule-engine-fixes} SR1 / T1) — plus the distinct origin types of the
     * entries in value-list order ({@code ""} for an entry without an origin or whose
     * {@code ItemDef} does not resolve), backing {@code var_vlm_origin_types}
     * ({@code PLAN-vlm-origin-value-accessor} VO1) — and the same types of the entries whose
     * {@code ItemRef} carries no {@code MethodOID}, backing
     * {@code var_vlm_origin_types_without_method} ({@code PLAN-vlm-unmethoded-origin-accessor} VU1
     * / T3). {@link #NONE} for a variable without a value list, a value list that does not resolve
     * or holds no entry.
     */
    private record VlmCoverage(boolean method, boolean comment, boolean origin,
            List<String> originTypes, List<String> originTypesWithoutMethod)
    {

        static final VlmCoverage NONE = new VlmCoverage(false, false, false, List.of(), List.of());
    }

    /**
     * The per-value documentation of {@code def}: all three flags hold only when every
     * {@code ItemRef} of the value list carries the item AND names an {@code ItemDef} that resolves
     * — an entry whose value-level {@code ItemDef} is missing from the define counts as
     * undocumented for all three (T1), the conservative direction for the rules that read them.
     * "Without a method" for the type list is read on the {@code ItemRef} alone
     * ({@code PLAN-vlm-unmethoded-origin-accessor} T3 / T4): an entry with a {@code MethodOID}
     * contributes nothing even when its {@code ItemDef} does not resolve, one without contributes
     * its type ({@code ""} when the {@code ItemDef} does not resolve).
     */
    private static VlmCoverage vlmCoverage(ItemDef def, Map<String, ItemDef> defs,
            Map<String, ValueListDef> valueLists)
    {
        ValueListDef vl = def.getValueListRef() == null ? null
                : valueLists.get(def.getValueListRef().getValueListOID());
        if (vl == null || vl.getItemRefs() == null || vl.getItemRefs().isEmpty())
        {
            return VlmCoverage.NONE;
        }
        boolean method = true;
        boolean comment = true;
        boolean origin = true;
        Set<String> originTypes = new LinkedHashSet<>();
        Set<String> withoutMethod = new LinkedHashSet<>();
        for (ItemRef r : vl.getItemRefs())
        {
            ItemDef vd = defs.get(r.getItemOID());
            String type = vd == null ? "" : originType(vd);
            method &= vd != null && r.getMethodOID() != null;
            comment &= vd != null && vd.getCommentOID() != null;
            origin &= !type.isEmpty();
            originTypes.add(type);
            if (r.getMethodOID() == null)
            {
                withoutMethod.add(type);
            }
        }
        return new VlmCoverage(method, comment, origin, List.copyOf(originTypes),
                List.copyOf(withoutMethod));
    }


    @Override
    public Map<String, String> externalDictionaryVersions()
    {
        Map<String, String> out = new LinkedHashMap<>();
        for (CodeList cl : codeListsByOid().values())
        {
            ExternalCodeList ecl = cl.getExternalCodeList();
            if (ecl == null || ecl.getDictionary() == null || ecl.getVersion() == null
                    || ecl.getVersion().isBlank())
            {
                continue;
            }
            // the dictionary types the installed store models — the only keys emitted; matching
            // strips non-alphanumerics and case (MedDRA, MED-RT, WHO Drug all resolve), the
            // shared rule of the format path (DictionaryTypes, PLAN-dictionary-backed-formats DF2)
            String type = DictionaryTypes.typeOf(ecl.getDictionary());
            if (type == null)
            {
                continue;
            }
            // First declaration wins, deterministically (document order). A define declaring two
            // different versions of one dictionary is contradictory; keep the first and say so.
            String previous = out.putIfAbsent(type, ecl.getVersion());
            if (previous != null && !previous.equals(ecl.getVersion()))
            {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Define-XML declares conflicting {0} versions ({1} and {2}); using the "
                                + "first declared, {1}. Override with the CLI version option if "
                                + "that is wrong.",
                        type, previous, ecl.getVersion());
            }
        }
        return out;
    }


    /**
     * PLAN-define-driven-ct-selection.md §4.1 — the {@code def:Standards} entries with
     * {@code Type="CT"}, in document order, each with its derived package id
     * ({@code <publishingset>ct-<version>}, e.g. {@code sdtmct-2023-12-15}).
     *
     * <p>
     * Empty when the document carries no {@code def:Standards} — structurally the Define-XML 2.0
     * case (the element exists only from 2.1; the Python reference engine gates on
     * {@code model_package == "define_2_1"} for the same reason). A CT-typed entry lacking
     * {@code PublishingSet} or {@code Version} cannot name a package and is skipped with a WARNING
     * — a malformed declaration is a property of the <em>document</em> (a Define-XML rule's
     * business, plan §4.4), never a run abort.
     * </p>
     */
    @Override
    public List<CtStandardRef> declaredCtPackages()
    {
        MetaDataVersion mdv = mdv();
        if (mdv == null || mdv.getStandards() == null || mdv.getStandards().getStandards() == null)
        {
            return List.of();
        }
        List<CtStandardRef> out = new ArrayList<>();
        for (Standard std : mdv.getStandards().getStandards())
        {
            if (std == null || std.getType() == null || !"CT".equalsIgnoreCase(std.getType()))
            {
                continue;
            }
            String publishingSet = std.getPublishingSet();
            String version = std.getVersion();
            if (publishingSet == null || publishingSet.isBlank() || version == null
                    || version.isBlank())
            {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Define-XML def:Standard {0} is typed CT but lacks a PublishingSet or "
                                + "Version; it names no CT package and is ignored.",
                        std.getOid());
                continue;
            }
            out.add(CtStandardRef.of(publishingSet, version));
        }
        return List.copyOf(out);
    }


    private Map<String, CodeList> codeListsByOid()
    {
        MetaDataVersion mdv = mdv();
        Map<String, CodeList> out = new LinkedHashMap<>();
        if (mdv != null && mdv.getCodeLists() != null)
        {
            for (CodeList cl : mdv.getCodeLists())
            {
                out.put(cl.getOid(), cl);
            }
        }
        return out;
    }


    private static @Nullable String label(ItemDef def)
    {
        if (def.getDescription() == null || def.getDescription().getTranslatedTexts() == null)
        {
            return null;
        }
        List<TranslatedText> tt = def.getDescription().getTranslatedTexts();
        return tt.isEmpty() ? null : tt.get(0).getValue();
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


    /**
     * Every CodeListItem/EnumeratedItem Alias name — the codelist's coded codes (Python parity).
     */
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


    /**
     * Every CodeListItem/EnumeratedItem submission ({@code CodedValue}) — the codelist's coded
     * values (EC-19, Python parity). The variable-level counterpart of {@code VlmResolver}'s coded
     * values read, backing {@code define_variable_codelist_coded_values}. An item without a
     * {@code CodedValue}, or with a blank one, names no term and is skipped (register
     * {@code NNL §1}; review round 2, MEDIUM-1).
     */
    private static List<String> codedValues(CodeList cl)
    {
        List<String> values = new ArrayList<>();
        if (cl.getCodeListItems() != null)
        {
            for (CodeListItem it : cl.getCodeListItems())
            {
                String coded = it.getCodedValue();
                if (coded != null && !coded.isBlank())
                {
                    values.add(coded);
                }
            }
        }
        if (cl.getEnumeratedItems() != null)
        {
            for (EnumeratedItem it : cl.getEnumeratedItems())
            {
                String coded = it.getCodedValue();
                if (coded != null && !coded.isBlank())
                {
                    values.add(coded);
                }
            }
        }
        return values;
    }


    /**
     * The subset of {@link #codedValues(CodeList)} the sponsor flagged as extensions
     * ({@code def:ExtendedValue="Yes"} on the CodeListItem / EnumeratedItem). Exact-case match —
     * the Define-XML enumeration allows only {@code "Yes"}/{@code "No"}, and the Python reference
     * reader compares {@code == "Yes"} exactly.
     */
    private static List<String> extendedValues(CodeList cl)
    {
        List<String> values = new ArrayList<>();
        if (cl.getCodeListItems() != null)
        {
            for (CodeListItem it : cl.getCodeListItems())
            {
                String coded = it.getCodedValue();
                if (coded != null && !coded.isBlank() && "Yes".equals(it.getExtendedValue()))
                {
                    values.add(coded);
                }
            }
        }
        if (cl.getEnumeratedItems() != null)
        {
            for (EnumeratedItem it : cl.getEnumeratedItems())
            {
                String coded = it.getCodedValue();
                if (coded != null && !coded.isBlank() && "Yes".equals(it.getExtendedValue()))
                {
                    values.add(coded);
                }
            }
        }
        return values;
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


    /**
     * The variable's Origin Type. Both Origin forms now resolve: the Define-XML v2.0/2.1
     * {@code def:Origin/@Type} element attribute (preferred), with the v1.0 {@code ItemDef/@Origin}
     * attribute as fallback. Empty when neither is declared (E2).
     *
     * <p>
     * The {@code corej-cdisc-define} parser's {@code OriginDisambiguationModule} now separates the
     * {@code ItemDef/@Origin} attribute from the {@code <def:Origin Type="…"/>} element, so
     * {@link ItemDef#getOriginElement()} is populated for the element form and its {@code @Type} is
     * read below; the attribute form still resolves via {@link ItemDef#getOrigin()}.
     * </p>
     */
    static String originType(ItemDef def)
    {
        Origin origin = def.getOriginElement();
        if (origin != null && origin.getType() != null)
        {
            return origin.getType();
        }
        return def.getOrigin() != null ? def.getOrigin() : "";
    }


    /** The canonical {@code "true"}/{@code "false"} string for a DEFINE presence flag (E2). */
    private static String boolStr(boolean value)
    {
        return value ? "true" : "false";
    }


    @Override
    public List<Map<String, String>> getVariables(String datasetName)
    {
        ItemGroupDef igd = itemGroup(datasetName);
        if (igd == null || igd.getItemRefs() == null)
        {
            return List.of();
        }
        Map<String, ItemDef> defs = itemDefsByOid();
        Map<String, CodeList> codeLists = codeListsByOid();
        Map<String, ValueListDef> valueLists = valueListsByOid();
        List<Map<String, String>> out = new ArrayList<>();
        for (ItemRef ref : igd.getItemRefs())
        {
            ItemDef def = defs.get(ref.getItemOID());
            if (def == null)
            {
                continue;
            }
            Map<String, String> v = new LinkedHashMap<>();
            put(v, "name", def.getName());
            put(v, "label", label(def));
            put(v, "dataType", def.getDataType());
            put(v, "role", ref.getRole());
            put(v, "mandatory", ref.getMandatory());
            put(v, "orderNumber",
                    ref.getOrderNumber() != null ? ref.getOrderNumber().toString() : null);
            String ccode = "";
            List<String> coded = List.of();
            List<String> codedValues = List.of();
            List<String> extendedValues = List.of();
            Map<String, String> codeDecode = Map.of();
            String externalDictionary = "";
            String externalDictionaryVersion = "";
            // EC-19: has_codelist is true iff the variable's ItemDef binds a codelist
            // (CodeListRef), mirroring the Python engine's `True iff itemdef.CodeListRef`.
            boolean hasCodelist = def.getCodeListRef() != null;
            if (hasCodelist)
            {
                CodeList cl = codeLists.get(def.getCodeListRef().getCodeListOID());
                if (cl != null)
                {
                    put(v, "codelist", cl.getOid());
                    String ext = extCodeId(cl);
                    ccode = ext != null ? ext : "";
                    coded = codedCodes(cl);
                    codedValues = codedValues(cl);
                    extendedValues = extendedValues(cl);
                    codeDecode = CodeListDecodes.codeDecodeMap(cl);
                    ExternalCodeList ecl = cl.getExternalCodeList();
                    if (ecl != null)
                    {
                        externalDictionary = ecl.getDictionary() != null ? ecl.getDictionary() : "";
                        externalDictionaryVersion = ecl.getVersion() != null ? ecl.getVersion()
                                : "";
                    }
                }
            }
            v.put("ccode", ccode);
            v.put("codelist_coded_codes", DefineMetadataListCodec.encode(coded));
            // EC-19: the variable-level ItemDef codelist guard + enumerated coded values.
            v.put("has_codelist", boolStr(hasCodelist));
            v.put("codelist_coded_values", DefineMetadataListCodec.encode(codedValues));
            // GLOB-CT-005 variant: the subset of coded values the sponsor flagged as extensions
            // (def:ExtendedValue="Yes"), backing var_codelist_extended_values("DEFINE").
            v.put("codelist_extended_values", DefineMetadataListCodec.encode(extendedValues));
            // Fix #123: the variable-level CodedValue -> Decode mapping of the ItemDef codelist,
            // backing define_variable_decode_matches. Empty ("{}") for an EnumeratedItem-only or
            // ExternalCodeList codelist, which carry no decodes to compare against.
            v.put("codelist_code_decode", DefineMetadataListCodec.encodeStringMap(codeDecode));
            // E2 DEFINE-only accessors (plans/done/PLAN-group-b-followups.md).
            v.put("origin_type", originType(def));
            v.put("has_comment", boolStr(def.getCommentOID() != null));
            v.put("has_method", boolStr(ref.getMethodOID() != null));
            // runbook W1b DV: the variable is documented per value (def:ValueListRef), backing
            // var_has_value_list("DEFINE")
            v.put("has_value_list", boolStr(def.getValueListRef() != null));
            // PLAN-stopped-rule-engine-fixes SR1: every value-level entry documented, backing
            // var_vlm_has_method / var_vlm_has_comment / var_vlm_has_origin ("DEFINE"). The keys
            // equal the functions' suffixes so a derived define_variable_vlm_has_* entry meets the
            // provider's copy (RuleRunner's "define_variable_" + key).
            VlmCoverage cov = vlmCoverage(def, defs, valueLists);
            v.put("vlm_has_method", boolStr(cov.method()));
            v.put("vlm_has_comment", boolStr(cov.comment()));
            v.put("vlm_has_origin", boolStr(cov.origin()));
            // PLAN-vlm-origin-value-accessor VO1: the value-level origin types, a list attribute
            // (DefineMetadataListCodec), backing var_vlm_origin_types("DEFINE")
            v.put("vlm_origin_types", DefineMetadataListCodec.encode(cov.originTypes()));
            // PLAN-vlm-unmethoded-origin-accessor VU1: the same types of the entries without a
            // method, backing var_vlm_origin_types_without_method("DEFINE")
            v.put("vlm_origin_types_without_method",
                    DefineMetadataListCodec.encode(cov.originTypesWithoutMethod()));
            v.put("external_dictionary", externalDictionary);
            v.put("external_dictionary_version", externalDictionaryVersion);
            out.add(v);
        }
        return out;
    }


    private static void put(Map<String, String> map, String key, @Nullable String value)
    {
        if (value != null)
        {
            map.put(key, value);
        }
    }


    @Override
    public Map<String, String> getDatasetMetadata(String datasetName)
    {
        ItemGroupDef igd = itemGroup(datasetName);
        if (igd == null)
        {
            return Map.of();
        }
        Map<String, String> m = new LinkedHashMap<>();
        put(m, "name", igd.getName());
        put(m, "domain", igd.getDomain());
        return m;
    }


    /** Fix #119: the effective {@code def:Class} (2.1 element preferred over 2.0 attribute). */
    @Override
    public @Nullable String getDeclaredClass(String datasetName)
    {
        ItemGroupDef igd = itemGroup(datasetName);
        return igd != null ? igd.getEffectiveClassName() : null;
    }


    /** Fix #119: the declared Define-XML 2.1 {@code <def:SubClass>} names. */
    @Override
    public List<String> getDeclaredSubClasses(String datasetName)
    {
        ItemGroupDef igd = itemGroup(datasetName);
        return igd != null ? igd.getSubClassNames() : List.of();
    }


    @Override
    public List<Map<String, String>> getCodelistTerms(String codelistOID)
    {
        CodeList cl = codeListsByOid().get(codelistOID);
        if (cl == null)
        {
            return List.of();
        }
        List<Map<String, String>> out = new ArrayList<>();
        if (cl.getCodeListItems() != null)
        {
            for (CodeListItem it : cl.getCodeListItems())
            {
                Map<String, String> t = new LinkedHashMap<>();
                put(t, "codedValue", it.getCodedValue());
                out.add(t);
            }
        }
        return out;
    }


    @Override
    public @Nullable MetaDataVersion metaDataVersion()
    {
        return mdv();
    }


    @Override
    public List<String> getDatasetNames()
    {
        MetaDataVersion mdv = mdv();
        if (mdv == null || mdv.getItemGroupDefs() == null)
        {
            return List.of();
        }
        // A nameless ItemGroupDef names no dataset: it is skipped, exactly as a nameless ItemDef
        // is skipped by getKeyVariables / getColumnOrder (register NNL §1 — an element of a list
        // value is a real value or a MissingValue, never null; a missing member means "no entry",
        // never "" and never null). A BLANK Name="" names nothing either and is skipped the same
        // way (review round 1, S-1) — served, it would be the present "" dataset name.
        return mdv.getItemGroupDefs().stream().map(ItemGroupDef::getName)
                .filter(n -> n != null && !n.isBlank()).toList();
    }


    @Override
    public List<String> getKeyVariables(String datasetName)
    {
        ItemGroupDef igd = itemGroup(datasetName);
        if (igd == null || igd.getItemRefs() == null)
        {
            return List.of();
        }
        Map<String, ItemDef> defs = itemDefsByOid();
        // Collect (KeySequence, variable name) for every ItemRef carrying a @KeySequence and a
        // resolvable ItemDef, then order by the sequence number — the Define-XML key-variable
        // ordering the PMDA SD1152 duplicate check groups on.
        record KeyVar(int sequence, String name)
        {
        }
        List<KeyVar> keyVars = new ArrayList<>();
        for (ItemRef ref : igd.getItemRefs())
        {
            Integer seq = ref.getKeySequence();
            ItemDef def = seq != null ? defs.get(ref.getItemOID()) : null;
            // A nameless or blank-named ItemDef names no key variable and is skipped (register
            // NNL §1; PLAN-no-null-list-elements review round 2, MEDIUM-1), as getColumnOrder
            // and getDatasetNames skip it.
            String name = def != null ? def.getName() : null;
            if (seq != null && name != null && !name.isBlank())
            {
                keyVars.add(new KeyVar(seq, name));
            }
        }
        keyVars.sort(java.util.Comparator.comparingInt(KeyVar::sequence));
        return keyVars.stream().map(KeyVar::name).toList();
    }

}
