package net.cumba.corej.core.expr.eval;

import static net.cumba.corej.core.expr.eval.MetadataLevel.DATA;
import static net.cumba.corej.core.expr.eval.MetadataLevel.DEFINE;
import static net.cumba.corej.core.expr.eval.MetadataLevel.LIBRARY;

import java.util.HashMap;
import java.util.Map;
import net.cumba.corej.core.expr.eval.MetadataNormalizer.Normalization;
import org.jspecify.annotations.Nullable;

/**
 * The metadata accessor functions ({@code var_*} for variable-scope, {@code ds_*} for
 * dataset-scope) and, per attribute, which {@link MetadataLevel}s model it (the support matrix),
 * the provider-map key it reads at the DEFINE / LIBRARY levels, and its {@link Normalization} rule.
 *
 * <p>
 * The DATA level is sourced directly from the table / {@code EvaluationContext} (see
 * {@code ExprCompiler}); the {@link #providerKey} is the key into
 * {@code MetadataProvider.getVariableMetadata(domain, name)} (variable scope) or
 * {@code getDatasetMetadata(domain)} (dataset scope) for the DEFINE / LIBRARY levels.
 * </p>
 *
 * <p>
 * Asking for an attribute at an unsupported level is a {@code RuleDefinitionException} (decision
 * D3): e.g. {@code var_role} has no DATA cell, {@code var_format} has no LIBRARY cell. Supported
 * levels are held as an immutable {@code int} bitmask over {@link MetadataLevel#bit()} so the enum
 * has no mutable state.
 * </p>
 *
 * <p>
 * <b>The default level is {@code DATA}</b> ({@code PLAN-metadata-accessor-default-level} D1,
 * runbook W1a MA-r1): a call with no level — {@code ds_name()}, {@code var_label()},
 * {@code var_label("AETERM")} — reads the DATA level; {@code MetadataOperandMapping} fills the
 * literal in at load. An attribute without a DATA level called without one is a load error naming
 * {@link #supportedLevels()} (D2).
 * </p>
 */
public enum MetadataAttribute
{

    // -- variable scope --------------------------------------------------------
    VAR_NAME("var_name", Scope.VARIABLE, "name", Normalization.RAW, DATA, DEFINE, LIBRARY),
    VAR_LABEL("var_label", Scope.VARIABLE, "label", Normalization.RAW, DATA, DEFINE, LIBRARY),
    /**
     * {@code label_template([x,] "LIBRARY")} — {@code PLAN-library-template-label-resolution} D6
     * (b): the variable's resolved library label <b>template</b>, in the grammar of
     * {@code matches_label_template}. For a literal published name it is the label itself; for an
     * index template ({@code TRTxxP}) the label with the column's digits substituted, plus a
     * trailing {@code " ..."} when the name carries {@code y} or {@code zz} (the IG's descriptive
     * tail, D8); for a {@code *}-stem the published phrase ({@code {Start Date}}). Absent — the
     * accessor answers a missing — for a column the Library does not know, a column spelled like a
     * template ({@code TRTXXP}) and a label the provider could not resolve. LIBRARY only: the
     * template is a product of the Library's naming conventions, which no Define-XML publishes.
     *
     * <p>
     * <b>The stem-selecting form</b> {@code label_template([x,] "LIBRARY", stem="*DT")} (round-2
     * ruling R2-3): the <em>named</em> stem's published template, served when the column's name
     * ends in that stem's suffix and the column is a stem case (no literal or index template of the
     * governing or ADSL structure claims it) — {@code RANDSDT} answers {@code {Date}} for
     * {@code stem="*DT"} and {@code {Start Date}} for {@code stem="*SDT"}, so each stem rule judges
     * every column its suffix matches with its own phrase. A missing otherwise: a library literal
     * ({@code TRTSDT}), a column the stem does not fit, a stem the product does not publish. The
     * {@code stem=} value is a {@code *}-rooted string literal (stage A {@code PARAMETER_TYPE});
     * the provider-map key is {@code LibraryTemplates.stemKey}. A finding of a Check whose
     * {@code label_template} reads all select one stem reports that stem's template as
     * {@code library_variable_label_template}, the phrase the rule judged
     * ({@code MetadataExprScan.labelTemplateStem}, applied by {@code RuleRunner}).
     * </p>
     */
    LABEL_TEMPLATE("label_template", Scope.VARIABLE, "label_template", Normalization.RAW, LIBRARY),
    VAR_TYPE("var_type", Scope.VARIABLE, "simpleDatatype", Normalization.TYPE, DATA, DEFINE, LIBRARY),
    VAR_LENGTH("var_length", Scope.VARIABLE, "length", Normalization.NUMERIC, DATA, DEFINE, LIBRARY),
    VAR_FORMAT("var_format", Scope.VARIABLE, "format", Normalization.RAW, DATA, DEFINE),
    VAR_ROLE("var_role", Scope.VARIABLE, "role", Normalization.ROLE, DEFINE, LIBRARY),
    VAR_CORE("var_core", Scope.VARIABLE, "core", Normalization.CORE, DEFINE, LIBRARY),
    VAR_MANDATORY("var_mandatory", Scope.VARIABLE, "mandatory", Normalization.MANDATORY, DEFINE),
    VAR_CODELIST("var_codelist", Scope.VARIABLE, "codelist", Normalization.RAW, DEFINE, LIBRARY),
    VAR_ORDINAL("var_ordinal", Scope.VARIABLE, "ordinal", Normalization.NUMERIC, DATA, DEFINE, LIBRARY),
    /**
     * Tier-B codelist C-code (R-P3, {@code plans/done/PLAN-native-engine-residuals.md}): the define
     * provider's {@code ccode} key — the same map entry the legacy cascade injects as
     * {@code define_variable_ccode} (CDISC-CG0001). At {@code LIBRARY} the bound codelist's NCI
     * C-code ({@code MetadataKeys.CODELIST_CONCEPT_ID}), materialised by the library provider to
     * back {@code library_variable_ccode} (Java↔Python parity). Populated only by providers that
     * expose it.
     */
    VAR_CCODE("var_ccode", Scope.VARIABLE, "ccode", Normalization.RAW, DEFINE, LIBRARY),
    /**
     * Tier-B coded-codes list (R-P3): each codelist term's C-code. At {@code DEFINE} the define
     * provider's {@code codelist_coded_codes} key; at {@code LIBRARY} the published codelist's term
     * C-codes — the ADaM-facing code accessor (NRI-008 / value-check-against-library plan; library
     * population pending an {@code ICodelistEntry} conceptId getter).
     */
    VAR_CODELIST_CODED_CODES("var_codelist_coded_codes", Scope.VARIABLE, "codelist_coded_codes", Normalization.RAW, DEFINE, LIBRARY),
    /**
     * Coded-values list: each codelist term's submission value. At {@code LIBRARY} the published
     * codelist's terms (backs the value-check-against-library rule, NRI-008); at {@code DEFINE} the
     * define's enumerated coded values (NRI-007 CT-004).
     */
    VAR_CODELIST_CODED_VALUES("var_codelist_coded_values", Scope.VARIABLE, "codelist_coded_values", Normalization.RAW, DEFINE, LIBRARY),
    /**
     * Whether the variable's codelist is extensible. Library-level only; a non-extensible codelist
     * (and only that) makes the value-check-against-library rule fire. Missing / no-codelist ⇒
     * absent ⇒ treated extensible ⇒ no fire (D4).
     */
    VAR_CODELIST_EXTENSIBLE("var_codelist_extensible", Scope.VARIABLE, "codelist_extensible", Normalization.BOOLEAN, LIBRARY),

    /**
     * The subset of the define codelist's coded values the sponsor flagged as extensions
     * ({@code def:ExtendedValue="Yes"}) — the GLOB-CT-005 variant surface
     * ({@code PLAN-coreJ-codelist-conformance} Phase 3). List-valued, DEFINE-only (the library
     * publishes no extensions; extensibility itself is the LIBRARY-level flag above).
     */
    VAR_CODELIST_EXTENDED_VALUES("var_codelist_extended_values", Scope.VARIABLE, "codelist_extended_values", Normalization.RAW, DEFINE),
    /**
     * DEFINE-only Origin Type of the variable (Define-XML {@code Origin/@Type}, with the v2.0
     * {@code ItemDef/@Origin} attribute as fallback). Backs {@code define_variable_origin_type};
     * empty when the variable declares no Origin (E2,
     * {@code plans/done/PLAN-group-b-followups.md}).
     */
    VAR_ORIGIN_TYPE("var_origin_type", Scope.VARIABLE, "origin_type", Normalization.RAW, DEFINE),
    /**
     * DEFINE-only presence flag: whether the variable's {@code ItemDef} carries a Comment (a
     * {@code CommentOID} reference or an inline {@code Comment}). Backs
     * {@code define_variable_has_comment} (E2).
     */
    VAR_HAS_COMMENT("var_has_comment", Scope.VARIABLE, "has_comment", Normalization.BOOLEAN, DEFINE),
    /**
     * DEFINE-only presence flag: whether the variable's {@code ItemDef} binds a user-defined
     * codelist (an {@code ItemDef/CodeListRef}). Backs {@code define_variable_has_codelist}, the
     * guard for the {@code Value Check against Define XML Variable} rule type (EC-19, SD0037):
     * variable-level codelist membership fires only where a codelist is actually bound. Mirrors the
     * Python reference engine's {@code define_variable_has_codelist} ({@code True} iff
     * {@code itemdef.CodeListRef}).
     */
    VAR_HAS_CODELIST("var_has_codelist", Scope.VARIABLE, "has_codelist", Normalization.BOOLEAN, DEFINE),
    /**
     * DEFINE-only presence flag: whether the variable's {@code ItemRef} carries a {@code MethodOID}
     * (a derivation method). Backs {@code define_variable_has_method} (E2).
     */
    VAR_HAS_METHOD("var_has_method", Scope.VARIABLE, "has_method", Normalization.BOOLEAN, DEFINE),
    /**
     * DEFINE-only presence flag: whether the variable's {@code ItemDef} carries a
     * {@code def:ValueListRef} — the variable is documented per value (runbook W1b DV,
     * {@code PLAN-engine-gap-define-vlm-method} §4.1). Missing for a variable the define does not
     * declare.
     */
    VAR_HAS_VALUE_LIST("var_has_value_list", Scope.VARIABLE, "has_value_list", Normalization.BOOLEAN, DEFINE),
    /**
     * DEFINE-only: the variable is documented per value and EVERY value-level entry carries a
     * method — the {@code ItemDef}'s {@code def:ValueListRef} names a {@code def:ValueListDef} with
     * at least one {@code ItemRef}, each with a {@code MethodOID} and a resolvable {@code ItemDef}.
     * {@code false} without a (non-empty, resolvable) value list; missing for a variable the define
     * does not declare. One answer per variable, so a Check stays variable-level under both
     * universes — the per-record {@code vlm_has_method(X)} answers the matched entry of a row
     * ({@code PLAN-stopped-rule-engine-fixes} SR1 / T1).
     */
    VAR_VLM_HAS_METHOD("var_vlm_has_method", Scope.VARIABLE, "vlm_has_method", Normalization.BOOLEAN, DEFINE),
    /**
     * DEFINE-only: every value-level {@code ItemDef} of the variable's value list carries a
     * {@code def:CommentOID}, read as {@link #VAR_HAS_COMMENT} reads the variable level; otherwise
     * as {@link #VAR_VLM_HAS_METHOD}.
     */
    VAR_VLM_HAS_COMMENT("var_vlm_has_comment", Scope.VARIABLE, "vlm_has_comment", Normalization.BOOLEAN, DEFINE),
    /**
     * DEFINE-only: every value-level {@code ItemDef} of the variable's value list has an origin (a
     * non-empty {@code OdmDefineXMLProvider.originType}: the {@code def:Origin/@Type} element or
     * the v2.0 {@code @Origin} attribute); otherwise as {@link #VAR_VLM_HAS_METHOD}.
     */
    VAR_VLM_HAS_ORIGIN("var_vlm_has_origin", Scope.VARIABLE, "vlm_has_origin", Normalization.BOOLEAN, DEFINE),
    /**
     * DEFINE-only: the distinct origin types of the variable's value-level entries, in value-list
     * order — an entry without an origin, or whose value-level {@code ItemDef} does not resolve,
     * contributes {@code ""}. {@code []} without a (non-empty, resolvable) value list, and
     * {@code []} for a variable the define does not declare (a list attribute decodes a null to
     * {@code []}; guard with {@code not empty(var_name("DEFINE"))}). With
     * {@link #VAR_VLM_HAS_ORIGIN} it answers "every value-level origin is X"
     * ({@code PLAN-vlm-origin-value-accessor} VO1).
     */
    VAR_VLM_ORIGIN_TYPES("var_vlm_origin_types", Scope.VARIABLE, "vlm_origin_types", Normalization.RAW, DEFINE),
    /**
     * DEFINE-only external-dictionary name of the variable's bound codelist (Define-XML
     * {@code CodeList/ExternalCodeList/@Dictionary}). Backs
     * {@code define_variable_external_dictionary}; empty when the variable's codelist declares no
     * external dictionary (E2).
     */
    VAR_EXTERNAL_DICTIONARY("var_external_dictionary", Scope.VARIABLE, "external_dictionary", Normalization.RAW, DEFINE),
    /**
     * DEFINE-only external-dictionary version of the variable's bound codelist (Define-XML
     * {@code CodeList/ExternalCodeList/@Version}). Backs
     * {@code define_variable_external_dictionary_version} (E2).
     */
    VAR_EXTERNAL_DICTIONARY_VERSION("var_external_dictionary_version", Scope.VARIABLE, "external_dictionary_version", Normalization.RAW, DEFINE),

    // -- dataset scope ---------------------------------------------------------
    DS_NAME("ds_name", Scope.DATASET, "name", Normalization.RAW, DATA, DEFINE, LIBRARY),
    DS_LABEL("ds_label", Scope.DATASET, "label", Normalization.RAW, DATA, DEFINE, LIBRARY),
    DS_CLASS("ds_class", Scope.DATASET, "className", Normalization.RAW, DATA, DEFINE, LIBRARY),
    /**
     * The dataset's CDISC domain <em>as {@code Scope.Domains} resolves it</em> — the base leg
     * {@code DatasetIdentity.unsplitNameFromData}: the row-0 {@code DOMAIN} cell <em>verbatim</em>
     * when present and non-empty, else {@code SUPP}/{@code SQ} + the row-0 {@code RDOMAIN} for a
     * split supplemental dataset, else the raw dataset name. So {@code AE}&rarr;{@code AE},
     * {@code LBXY(DOMAIN=LB)}&rarr;{@code LB}, {@code SUPPLBHM(RDOMAIN=LB)}&rarr;{@code SUPPLB}, a
     * 0-row {@code AE}&rarr;{@code AE}. An author who writes {@code Scope.Domains.Include: [X]} and
     * {@code dataset_domain == "X"} gets the same answer from both, by construction.
     *
     * <p>
     * ⚠ Because the {@code DOMAIN} cell is returned verbatim, a <em>malformed</em> domain stays
     * visible: {@code DOMAIN=XXX} yields {@code "XXX"}, so a length check still fires. The value is
     * NOT normalised — normalisation belongs to Scope matching, not to the returned value.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>Not a drop-in sibling.</b> Every other member's {@code providerKey} names a real field
     * on the metadata object that {@code ExprCompiler#readDataLevel} reads with a plain getter
     * ({@code case DS_NAME -> meta.getName()}). A domain is not such a field — it is DERIVED from
     * the data, so this member's DATA arm calls {@code unsplitNameFromData} instead. DATA is
     * deliberately the ONLY supported level: neither the sponsor Define-XML nor the CDISC Library
     * publishes a {@code domain} key, so declaring DEFINE / LIBRARY would turn
     * {@code define_dataset_domain} into a silent missing instead of the honest
     * {@code RuleDefinitionException} an unsupported level raises. {@code providerKey} is therefore
     * never read for this member and is present only to satisfy the shared constructor.
     * </p>
     */
    DS_DOMAIN("ds_domain", Scope.DATASET, "domain", Normalization.RAW, DATA),
    DS_STRUCTURE("ds_structure", Scope.DATASET, "datasetStructure", Normalization.RAW, DEFINE, LIBRARY);

    /**
     * Whether the accessor names a variable (two-arg core form) or the dataset (one-arg core form).
     */
    public enum Scope
    {
        VARIABLE, DATASET
    }

    private static final Map<String, MetadataAttribute> BY_FUNCTION = new HashMap<>();

    static
    {
        for (MetadataAttribute a : values())
        {
            BY_FUNCTION.put(a.functionName, a);
        }
    }

    private final String functionName;

    private final Scope scope;

    private final String providerKey;

    private final Normalization normalization;

    /**
     * Immutable bitmask over {@link MetadataLevel#bit()} — keeps the enum free of mutable state.
     */
    private final int levelMask;

    MetadataAttribute(String functionName, Scope scope, String providerKey,
            Normalization normalization, MetadataLevel... supportedLevels)
    {
        this.functionName = functionName;
        this.scope = scope;
        this.providerKey = providerKey;
        this.normalization = normalization;
        int mask = 0;
        for (MetadataLevel level : supportedLevels)
        {
            mask |= level.bit();
        }
        this.levelMask = mask;
    }


    /** The attribute for a function name ({@code "var_label"}, {@code "ds_class"}, …), or null. */
    public static @Nullable MetadataAttribute fromFunction(String name)
    {
        return BY_FUNCTION.get(name);
    }


    public String functionName()
    {
        return functionName;
    }


    public Scope scope()
    {
        return scope;
    }


    /**
     * The {@code getVariableMetadata}/{@code getDatasetMetadata} key at the DEFINE / LIBRARY
     * levels.
     */
    public String providerKey()
    {
        return providerKey;
    }


    public Normalization normalization()
    {
        return normalization;
    }


    public boolean supports(MetadataLevel level)
    {
        return (levelMask & level.bit()) != 0;
    }


    /**
     * The levels this attribute models, in {@link MetadataLevel} order (DATA, DEFINE, LIBRARY) —
     * the support matrix row, rendered by the language surface and by the load error for a
     * level-less call to an attribute without a DATA level.
     */
    public java.util.List<MetadataLevel> supportedLevels()
    {
        java.util.List<MetadataLevel> out = new java.util.ArrayList<>(3);
        for (MetadataLevel level : MetadataLevel.values())
        {
            if (supports(level))
            {
                out.add(level);
            }
        }
        return java.util.Collections.unmodifiableList(out);
    }


    /**
     * Whether this attribute's value is a <em>list</em> (e.g. a codelist's coded codes) rather than
     * a scalar. A list attribute is carried JSON-encoded through the string-valued provider channel
     * ({@link net.cumba.corej.core.metadata.DefineMetadataListCodec}) and materialised by the
     * native accessor as a {@code List<String>}-valued operand, so {@code is_(not_)contained_by}
     * can compare it element-wise — mirroring the Python reference engine's
     * {@code is_column_of_iterables} branch.
     */
    public boolean isList()
    {
        return this == VAR_CODELIST_CODED_CODES || this == VAR_CODELIST_CODED_VALUES
                || this == VAR_CODELIST_EXTENDED_VALUES || this == VAR_VLM_ORIGIN_TYPES;
    }


    /**
     * The load error for a call to an attribute without a {@code DATA} level that names no level
     * ({@code PLAN-metadata-accessor-default-level} D2 / P2): <em>"var_role has no DATA level —
     * write var_role("DEFINE") or var_role("LIBRARY")"</em>; with the spelled name argument when
     * the call carries one. Stage A files it and the compiler raises it defensively.
     *
     * @param spelledName
     *            the call's name argument as the author spelled it, or {@code null} for the
     *            level-only form
     * @return the message
     */
    public String noDataLevelMessage(@Nullable String spelledName)
    {
        StringBuilder sb = new StringBuilder(96);
        sb.append(functionName).append(" has no DATA level — write ");
        boolean first = true;
        for (MetadataLevel level : supportedLevels())
        {
            if (!first)
            {
                sb.append(" or ");
            }
            first = false;
            sb.append(functionName).append('(');
            if (spelledName != null)
            {
                sb.append(spelledName).append(", ");
            }
            sb.append('"').append(level.name()).append("\")");
        }
        return sb.toString();
    }


    /** Normalizes a raw value read for this attribute to its canonical vocabulary (D5). */
    public @Nullable String normalize(@Nullable String value)
    {
        return MetadataNormalizer.normalize(normalization, value);
    }

}
