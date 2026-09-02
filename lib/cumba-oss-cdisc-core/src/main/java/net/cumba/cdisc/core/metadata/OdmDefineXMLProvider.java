package net.cumba.cdisc.core.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.gen.DefineXMLProvider;
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
import net.cumba.cdisc.define.Study;
import net.cumba.cdisc.define.TranslatedText;
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

    /**
     * The dictionary types the installed store models — the only keys
     * {@link #externalDictionaryVersions()} may emit. Matching strips non-alphanumerics and case
     * ({@code MedDRA}, {@code MED-RT}, {@code WHO Drug} all resolve); anything else is not a
     * version-selection source and is omitted.
     */
    private static final Map<String, String> DICTIONARY_TYPES = Map.of("meddra", "meddra",
            "whodrug", "whodrug", "loinc", "loinc", "medrt", "medrt", "unii", "unii", "snomed",
            "snomed", "neoplasm", "neoplasm");

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
            String type = DICTIONARY_TYPES.get(ecl.getDictionary().replaceAll("[^A-Za-z0-9]", "")
                    .toLowerCase(java.util.Locale.ROOT));
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
     * values read, backing {@code define_variable_codelist_coded_values}.
     */
    private static List<String> codedValues(CodeList cl)
    {
        List<String> values = new ArrayList<>();
        if (cl.getCodeListItems() != null)
        {
            for (CodeListItem it : cl.getCodeListItems())
            {
                if (it.getCodedValue() != null)
                {
                    values.add(it.getCodedValue());
                }
            }
        }
        if (cl.getEnumeratedItems() != null)
        {
            for (EnumeratedItem it : cl.getEnumeratedItems())
            {
                if (it.getCodedValue() != null)
                {
                    values.add(it.getCodedValue());
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
                if (it.getCodedValue() != null && "Yes".equals(it.getExtendedValue()))
                {
                    values.add(it.getCodedValue());
                }
            }
        }
        if (cl.getEnumeratedItems() != null)
        {
            for (EnumeratedItem it : cl.getEnumeratedItems())
            {
                if (it.getCodedValue() != null && "Yes".equals(it.getExtendedValue()))
                {
                    values.add(it.getCodedValue());
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
     * The {@code cumba-oss-cdisc-define} parser's {@code OriginDisambiguationModule} now separates
     * the {@code ItemDef/@Origin} attribute from the {@code <def:Origin Type="…"/>} element, so
     * {@link ItemDef#getOriginElement()} is populated for the element form and its {@code @Type} is
     * read below; the attribute form still resolves via {@link ItemDef#getOrigin()}.
     * </p>
     */
    private static String originType(ItemDef def)
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
    public List<Map<String, String>> getValueLevelMetadata(String datasetName, String variableName)
    {
        return List.of();
    }


    @Override
    public List<Map<String, String>> getWhereClauseConditions(String whereClauseOID)
    {
        return List.of();
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
        return mdv.getItemGroupDefs().stream().map(ItemGroupDef::getName).toList();
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
            if (def != null && def.getName() != null)
            {
                keyVars.add(new KeyVar(seq.intValue(), def.getName()));
            }
        }
        keyVars.sort(java.util.Comparator.comparingInt(KeyVar::sequence));
        return keyVars.stream().map(KeyVar::name).toList();
    }

}
