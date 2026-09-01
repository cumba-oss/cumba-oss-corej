package net.cumba.cdisc.core.metadata;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Per-dataset ADaM data-structure determination — the Java mirror of the Python engine's
 * {@code base_data_service.get_data_structure}
 * ({@code cdisc_rules_engine/services/data_services/base_data_service.py}):
 *
 * <ol>
 * <li>a dataset named {@code ADSL} (case-insensitive) is the {@link #ADSL SUBJECT LEVEL ANALYSIS
 * DATASET};</li>
 * <li>a dataset carrying any {@link #BDS_INDICATORS BDS indicator column} (exact, case-insensitive
 * name match) is a {@link #BDS BASIC DATA STRUCTURE};</li>
 * <li>a dataset carrying any column whose upper-cased name ends with an {@link #OCCDS_SUFFIXES
 * OCCDS suffix} is an {@link #OCCDS OCCURRENCE DATA STRUCTURE};</li>
 * <li>everything else is {@link #OTHER ADAM OTHER}.</li>
 * </ol>
 *
 * <p>
 * <b>Phase 3a.1</b> layers a device tier on top of that mirror — {@link #detectSpecific} refines
 * the verdict to the matching medical-device token when the dataset carries
 * {@link #DEVICE_IDENTIFIER SPDEVID}. The four-token method above stays the Python mirror; every
 * production caller goes through {@link #detectAll}, which uses the refinement.
 * </p>
 *
 * <p>
 * The result is total (never {@code null}) and standard-agnostic, exactly like the Python function
 * — it feeds the {@code Scope.Data_Structures} gate
 * ({@link net.cumba.cdisc.core.exec.ScopeMatcher#describeDataStructureMismatch}) and the FU-4 "ADAM
 * OTHER" class fallback ({@link MetadataLibraryProvider}). One deliberate deviation from the
 * upstream constant: upstream's {@code bds_indicators} lists {@code ARAMCD}, a known typo of
 * {@code PARAMCD}; the house engines correct it ({@code PARAMCD}, user decision 2026-07-26 — the
 * parity fork's constant carries the same correction).
 * </p>
 *
 * <h2>Fix #179 — a dataset carries a SET of structures, not one</h2>
 *
 * <p>
 * The three medical-device structures are <em>specialisations</em>, not alternatives: a
 * {@link #MEDICAL_DEVICE_BDS MEDICAL DEVICE BASIC DATA STRUCTURE} dataset <b>is</b> a {@link #BDS
 * BASIC DATA STRUCTURE} dataset. {@link #detectAll} therefore returns the token <em>and every
 * supertype</em>, most-specific first ({@code [MEDICAL DEVICE BASIC DATA STRUCTURE,
 * BASIC DATA STRUCTURE]}), and the gate matches the author's list against the whole set. A rule
 * scoped {@code Include:[BASIC DATA STRUCTURE]} therefore covers a device BDS dataset, while a rule
 * scoped {@code Include:[MEDICAL DEVICE BASIC DATA STRUCTURE]} does not cover a plain BDS one —
 * exactly the asymmetry an is-a relation implies. {@code Exclude} is symmetric with {@code Include}
 * by construction: {@code Exclude:[BASIC DATA STRUCTURE]} matches the set's base element and so
 * excludes device BDS datasets too (owner decision 2026-08-08).
 * </p>
 *
 * <p>
 * Before Fix #179 {@link #structureTokenFromDeclaredClass} <em>folded</em> the three variants onto
 * their bases, so the specialised tokens could not be expressed in {@code Scope.Data_Structures} at
 * all — measured 2026-08-08, that made <b>345 of the 1394</b> ADaM scope entries in the shipped
 * corpus inexpressible in the semantically correct field, which is why they live in
 * {@code Scope.Classes} instead. ⚠ Un-folding <em>alone</em> would have been a silent regression:
 * {@link net.cumba.cdisc.core.exec.ScopeMatcher} matches by exact normalised token, so a detector
 * returning only {@code MEDICAL DEVICE BASIC DATA STRUCTURE} would have quietly stopped 78 shipped
 * {@code Data_Structures} entries from covering medical-device datasets. Keeping the base in the
 * set is what makes those 78 safe <em>by construction</em> rather than by a new matcher code path
 * getting it right.
 * </p>
 */
public final class AdamDataStructureDetector
{

    /** Structure token for the subject-level analysis dataset. */
    public static final String ADSL = "SUBJECT LEVEL ANALYSIS DATASET";

    /** Structure token for the basic data structure. */
    public static final String BDS = "BASIC DATA STRUCTURE";

    /** Structure token for the occurrence data structure. */
    public static final String OCCDS = "OCCURRENCE DATA STRUCTURE";

    /** Structure token for a structure-less ADaM dataset. */
    public static final String OTHER = "ADAM OTHER";

    /**
     * Structure token for the medical-device basic data structure — a <em>specialisation</em> of
     * {@link #BDS} (Fix #179: {@link #structureSet} carries both).
     */
    public static final String MEDICAL_DEVICE_BDS = "MEDICAL DEVICE BASIC DATA STRUCTURE";

    /**
     * Structure token for the medical-device occurrence data structure — a <em>specialisation</em>
     * of {@link #OCCDS}.
     */
    public static final String MEDICAL_DEVICE_OCCDS = "MEDICAL DEVICE OCCURRENCE DATA STRUCTURE";

    /**
     * Structure token for the device-level analysis dataset — one record per device, with no
     * BDS/OCCDS shape.
     *
     * <p>
     * ⚠⚠ <b>It has NO supertype</b> (owner decision 2026-08-09, Phase 3a). Before that it was
     * mapped onto {@link #OTHER} in {@link #SUPERTYPES}, for a <em>compatibility</em> reason rather
     * than an is-a one: the column heuristic could not see device datasets, so such a dataset
     * detected as {@code ADAM OTHER} and the mapping kept the declared and heuristic paths
     * agreeing. {@link #detectSpecific} removes that blindness, and with it the mapping's only
     * justification. Compare the two entries that stay — {@link #MEDICAL_DEVICE_BDS} and
     * {@link #MEDICAL_DEVICE_OCCDS} are genuine <em>specialisations</em> of their bases; nothing
     * analogous was ever claimed here. {@code ADAM OTHER} means <em>structure-less</em>, and a
     * device-level analysis dataset is not structure-less — ADaM defines it as a structure.
     * </p>
     *
     * <p>
     * ⚑ Keeping the mapping would not have been inert: rules scoping
     * {@code Classes.Include:[ADAM OTHER]} would, once the corpus migrates to
     * {@code Scope.Data_Structures} (Phase 3b), have started firing on device-level datasets — a
     * scope widening with no author intent behind it.
     *
     * ⚠⚠ <b>CORRECTED 2026-08-28.</b> This paragraph previously said <em>"8 rules scope
     * {@code Classes.Include:[ADAM OTHER]}"</em>. That figure is <b>unsupported</b>: measured over
     * {@code rules-src/checks} and {@code rules/}, <b>ZERO</b> files mention {@code ADAM OTHER} at
     * all. The reasoning above stands on its own — {@code ADAM OTHER} means
     * <em>structure-less</em>, and a device-level analysis dataset is not — but the count was cited
     * as the justification for the decision, so it is corrected rather than quietly dropped.
     * </p>
     */
    public static final String DEVICE_LEVEL_ANALYSIS_DATASET = "DEVICE LEVEL ANALYSIS DATASET";

    /**
     * Structure token for the TIG reference data structure — {@code tig/1-0/adam}'s
     * {@code REFERENDS}, whose published {@code class} is verbatim {@code REFERENCE DATA
     * STRUCTURE}.
     *
     * <p>
     * ⚠⚠ <b>No supertype, and it must NOT fold onto {@link #OTHER}</b> — the same reasoning the
     * owner applied to {@link #DEVICE_LEVEL_ANALYSIS_DATASET} on 2026-08-09: {@code ADAM OTHER}
     * means <em>structure-less</em>, and a reference data structure is not structure-less. Folding
     * it would silently widen the 8 rules scoping {@code Classes.Include:["ADAM OTHER"]} onto
     * reference datasets with no author intent behind it.
     * </p>
     *
     * <p>
     * ⚑ Its absence from {@link #STRUCTURE_TOKENS} was an <b>oversight</b>, not a decision (owner,
     * 2026-08-27): the class is published by CDISC verbatim, so a dataset declaring it was falling
     * through {@link #structureTokenFromDeclaredClass} to the heuristic and landing on
     * {@link #OTHER}, and {@code MetadataLibraryProvider} could not reach {@code REFERENDS} by any
     * token at all. Census 2026-08-27: the token appears in <b>0</b> of the 3&nbsp;813
     * {@code rules-src/checks} rules, so adding it changes no shipped rule's scope — it exists so
     * future rules <em>can</em> be authored against it.
     * </p>
     */
    public static final String REFERENCE_DATA_STRUCTURE = "REFERENCE DATA STRUCTURE";

    /**
     * The structure tokens the detector can produce, and therefore the
     * {@code Scope.Data_Structures} token vocabulary the loader validates against
     * ({@code RulePackageLoader}). Fix #179 added the three medical-device specialisations — before
     * it they folded onto their bases and were not authorable at all;
     * {@link #REFERENCE_DATA_STRUCTURE} was added later still, closing the same kind of gap on the
     * TIG side.
     */
    public static final Set<String> STRUCTURE_TOKENS = Set.of(ADSL, BDS, OCCDS, OTHER,
            MEDICAL_DEVICE_BDS, MEDICAL_DEVICE_OCCDS, DEVICE_LEVEL_ANALYSIS_DATASET,
            REFERENCE_DATA_STRUCTURE);

    /**
     * Fix #179: the structure hierarchy, as <b>data</b> — each specialised token mapped to its
     * immediate supertype. Deliberately not logic inside
     * {@link net.cumba.cdisc.core.exec.ScopeMatcher}: the matcher stays a pure exact-match over a
     * set of tokens, and the is-a relation lives in one place next to the vocabulary it refines.
     * <p>
     * ⚠ {@link #structureSet} walks this map, so a chain deeper than one level would need the walk
     * to iterate. Today every entry's value is a root, which is asserted by
     * {@code AdamDataStructureDetectorTest}.
     * </p>
     * <p>
     * ⚠⚠ <b>{@link #DEVICE_LEVEL_ANALYSIS_DATASET} and {@link #REFERENCE_DATA_STRUCTURE} are
     * deliberately absent</b> — see their javadoc for the full reasoning (owner decisions
     * 2026-08-09 and 2026-08-27). A device-level dataset yields exactly
     * {@code [DEVICE LEVEL ANALYSIS DATASET]}, a reference dataset exactly
     * {@code [REFERENCE DATA STRUCTURE]}.
     * </p>
     */
    private static final Map<String, String> SUPERTYPES = Map.of(MEDICAL_DEVICE_BDS, BDS,
            MEDICAL_DEVICE_OCCDS, OCCDS);

    /**
     * The eight SDTM {@code ItemGroupClass} values that legitimately appear as a {@code def:Class}
     * declaration but are not ADaM data structures (Define-2.1 XSD enumeration). Used by callers to
     * distinguish "declared but not a structure" (expected, silent) from "declared and
     * unrecognised" (worth a WARN).
     */
    public static final Set<String> SDTM_CLASS_TOKENS = Set.of("EVENTS", "FINDINGS",
            "FINDINGS ABOUT", "INTERVENTIONS", "RELATIONSHIP", "SPECIAL PURPOSE", "STUDY REFERENCE",
            "TRIAL DESIGN");

    /**
     * BDS structural indicator columns — exact (case-insensitive) name match. Mirrors the Python
     * {@code bds_indicators} with the {@code ARAMCD}→{@code PARAMCD} typo corrected (see class
     * javadoc).
     */
    static final Set<String> BDS_INDICATORS = Set.of("PARAMCD", "PARAM", "AVAL", "AVALC");

    /**
     * OCCDS structural indicator suffixes — a column whose upper-cased name ends with one of these
     * has an occurrence-structure signal. Derived from the Python {@code occds_indicators}
     * ({@code ["--TERM", "--TRT"]}) with the {@code --} prefix stripped, matched as a suffix
     * exactly as Python's {@code get_data_structure} does.
     * <p>
     * <b>Fix #140 (EC-50) adds {@code DECOD}</b>, the ADaM OCCDS dictionary-coded topic variable.
     * Before it, the only realistic OCCDS carrier in an ADAE dataset was {@code AETERM}, so an AE
     * analysis dataset that happened not to carry it detected as {@link #OTHER ADAM OTHER} and lost
     * every AE-scoped rule. Python's {@code occds_indicators} are unchanged — this is a deliberate
     * Java-only widening, recorded as EC-50.
     * </p>
     * <p>
     * The addition is <em>additive only</em> for {@link #detect}: a BDS indicator still returns
     * early, so it can move a dataset from {@code ADAM OTHER} to {@code OCCDS}, never from BDS. It
     * does, however, widen {@link #hasNoStructureIndicators} and therefore narrows the FU-4
     * {@code ADAM OTHER} class sentinel in {@link MetadataLibraryProvider} — a dataset carrying a
     * coded-term column is no longer "structure-less". That is intended (such a dataset is an OCCDS
     * dataset), and it is why {@code Scope.Classes:["ADAM OTHER"]} rules no longer reach it.
     * </p>
     */
    static final List<String> OCCDS_SUFFIXES = List.of("TERM", "TRT", "DECOD");

    /**
     * Fix #140 (EC-50): dataset-name prefixes that may carry an ADaM data structure. ADaMIG names
     * analysis datasets {@code AD*}; {@code AX*} is the sponsor-extension convention. A dataset
     * outside both is not an ADaM dataset, so no structure is derived from its columns however
     * BDS/OCCDS-shaped they look — without this, a real SDTM {@code AE} dataset (which carries
     * {@code AETERM} and no BDS indicator) would detect as {@link #OCCDS} and satisfy every
     * {@code Subclasses:["ADVERSE EVENT"]} rule.
     * <p>
     * Applies to the <b>heuristic</b> only. A declared Define-XML {@code def:Class} still wins via
     * {@link #detect(String, Collection, String, boolean)} — an explicit declaration outranks a
     * naming convention — and a {@code null} dataset name skips the gate entirely, so the
     * column-only predicate {@link #hasNoStructureIndicators} is unaffected.
     * </p>
     * <p>
     * Java-only: the parity fork's {@code get_data_structure} has no name gate. Java is therefore
     * <em>narrower</em> here. Unobserved on the parity lane — no spec runs an ADaM rule against a
     * non-ADaM-named primary dataset.
     * </p>
     */
    private static final List<String> ADAM_NAME_PREFIXES = List.of("AD", "AX");

    /**
     * <b>Phase 3a.1 — the medical-device discriminator column.</b> {@code SPDEVID} is the sponsor
     * device identifier: the variable that makes a dataset device-level, and the same signal the
     * sibling {@link AdamSubclassDetector} has always used to separate
     * {@code MEDICAL DEVICE TIME-TO-EVENT} from {@code TIME-TO-EVENT}. Deliberately one column and
     * not a set — a second, weaker signal would widen the three device tokens beyond what the one
     * conclusive ADaM device variable supports, and the whole point of {@link #detectSpecific} is
     * that it must be a <em>refinement</em> of an existing verdict, never a reclassification.
     */
    static final String DEVICE_IDENTIFIER = "SPDEVID";

    /**
     * The system property carrying the {@code defineFirst} preference — see
     * {@link #defineFirstPreference()}.
     */
    public static final String DEFINE_FIRST_PROPERTY = "corej.defineFirst";

    private AdamDataStructureDetector()
    {
    }


    /**
     * The process-wide {@code corej.defineFirst} preference — <b>{@code true} by default since Fix
     * #154</b>, which is the single definition every caller of the four-argument
     * {@link #detect(String, Collection, String, boolean) detect} /
     * {@link AdamSubclassDetector#resolve resolve} overloads reads ({@code RuleRunner},
     * {@code RuleGenerator}, the {@code .cdt} suites pre-gate).
     *
     * <p>
     * <b>Why declared-before-inferred.</b> Not merely "a declaration is authority":
     * <em>reclassifying by columns hides a defect</em>. If a sponsor declares {@code BASIC DATA
     * STRUCTURE} and the data carries a popPK signature, a columns-first engine silently applies
     * the popPK rules and thereby masks the fact that the declaration and the data disagree — which
     * is itself a conformance finding. A conformance engine validates data against what the sponsor
     * declared and <em>reports</em> the mismatch; it does not quietly re-declare on the sponsor's
     * behalf. Fix #119 shipped the preference defaulting to {@code false}; Fix #154 (decision
     * D21-remainder, 2026-08-05) flipped it.
     * </p>
     *
     * <p>
     * Note the deliberate asymmetry with {@link Boolean#getBoolean}: the property is
     * <b>opt-out</b>, so anything other than an explicit (case-insensitive, trimmed) {@code false}
     * — including an absent, blank or unparseable value — means define-first.
     * {@code -Dcorej.defineFirst=false} restores the pre-Fix-#154 columns-first behaviour.
     * </p>
     *
     * @return {@code true} unless the property is explicitly set to {@code false}
     */
    public static boolean defineFirstPreference()
    {
        String value = System.getProperty(DEFINE_FIRST_PROPERTY);
        return value == null || !"false".equalsIgnoreCase(value.trim());
    }


    /**
     * Detects the dataset's ADaM data structure from its name and column names. Total — always
     * returns one of the four {@link #STRUCTURE_TOKENS}.
     *
     * @param datasetName
     *            the dataset name (e.g. {@code ADSL}, {@code ADLBC}), or {@code null} when unknown
     * @param columnNames
     *            the dataset's column names (case-preserving; matched case-insensitively)
     * @return the detected structure token, never {@code null}
     */
    public static String detect(@Nullable String datasetName,
            @Nullable Collection<String> columnNames)
    {
        if (datasetName != null && "ADSL".equalsIgnoreCase(datasetName.trim()))
        {
            return ADSL;
        }
        if (datasetName != null && !isAdamDatasetName(datasetName))
        {
            // Fix #140 (EC-50): the column signatures are not standard-specific — a real SDTM AE
            // dataset carries AETERM and no BDS indicator, so the heuristic alone would call it an
            // OCCURRENCE DATA STRUCTURE and any Subclasses:["ADVERSE EVENT"] rule would select it.
            // ADaMIG names analysis datasets AD*/AX*, so a name outside that convention cannot
            // carry an ADaM structure. Gate the HEURISTIC only: the 4-arg overload still lets an
            // explicit Define-XML def:Class declaration through, because a declaration outranks a
            // naming convention.
            return OTHER;
        }
        if (columnNames != null)
        {
            boolean occds = false;
            for (String col : columnNames)
            {
                if (col == null)
                {
                    continue;
                }
                String upper = col.toUpperCase(Locale.ROOT);
                if (BDS_INDICATORS.contains(upper))
                {
                    // BDS indicators win over OCCDS suffixes, mirroring Python's check order.
                    return BDS;
                }
                if (!occds)
                {
                    for (String suffix : OCCDS_SUFFIXES)
                    {
                        if (upper.endsWith(suffix))
                        {
                            occds = true;
                            break;
                        }
                    }
                }
            }
            if (occds)
            {
                return OCCDS;
            }
        }
        return OTHER;
    }


    /**
     * Whether {@code datasetName} follows an ADaM analysis-dataset naming convention
     * ({@link #ADAM_NAME_PREFIXES}), case-insensitively on the trimmed name.
     */
    private static boolean isAdamDatasetName(String datasetName)
    {
        String upper = datasetName.trim().toUpperCase(Locale.ROOT);
        for (String prefix : ADAM_NAME_PREFIXES)
        {
            if (upper.startsWith(prefix))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * <b>Phase 3a.1 — the device-aware column heuristic, and the one the production paths use.</b>
     * Exactly {@link #detect(String, Collection)} refined by the {@link #DEVICE_IDENTIFIER} column:
     * a dataset that carries {@code SPDEVID} is a <em>medical-device</em> dataset, so the verdict
     * is the device specialisation of whatever shape it already had.
     *
     * <table>
     * <caption>the refinement</caption>
     * <tr>
     * <th>{@link #detect(String, Collection)} says</th>
     * <th>+ {@code SPDEVID}</th>
     * </tr>
     * <tr>
     * <td>{@link #BDS}</td>
     * <td>{@link #MEDICAL_DEVICE_BDS}</td>
     * </tr>
     * <tr>
     * <td>{@link #OCCDS}</td>
     * <td>{@link #MEDICAL_DEVICE_OCCDS}</td>
     * </tr>
     * <tr>
     * <td>{@link #OTHER}</td>
     * <td>{@link #DEVICE_LEVEL_ANALYSIS_DATASET}</td>
     * </tr>
     * <tr>
     * <td>{@link #ADSL}</td>
     * <td>{@link #ADSL} — unchanged; ADaM defines no device subject-level structure</td>
     * </tr>
     * </table>
     *
     * <h4>⚠ Why this is a separate method and {@link #detect(String, Collection)} is untouched</h4>
     *
     * <p>
     * The two-argument {@link #detect(String, Collection) detect} is the declared <b>mirror of
     * Python's {@code base_data_service.get_data_structure}</b>, which knows only the four root
     * tokens, and the parity harness ({@code SpecRunner}) calls it as exactly that. It is also what
     * {@link #hasNoStructureIndicators} is defined in terms of — the FU-4 {@code ADAM OTHER}
     * <em>class</em> sentinel, a corpus-visible gate this phase must not move. Refining it in place
     * would have changed both, so the device knowledge is layered on top instead: the mirror stays
     * a mirror, and every production caller reaches the refinement through
     * {@link #detect(String, Collection, String, boolean)} / {@link #detectAll}.
     * </p>
     *
     * <h4>⚠⚠ Additivity — what a dataset may and may not lose</h4>
     *
     * <p>
     * Through {@link #detectAll} the refinement is <b>additive for the two specialisations</b>: a
     * device BDS dataset goes from {@code [BASIC DATA STRUCTURE]} to
     * {@code [MEDICAL DEVICE BASIC DATA STRUCTURE, BASIC DATA STRUCTURE]} and therefore keeps every
     * rule scoped to the base; likewise for OCCDS. The <b>one deliberate exception</b> is the
     * device-level dataset: it goes from {@code [ADAM OTHER]} to
     * {@code [DEVICE LEVEL ANALYSIS DATASET]} and so <em>loses</em> {@code ADAM OTHER}. That is the
     * owner's 2026-08-09 decision (see {@link #DEVICE_LEVEL_ANALYSIS_DATASET}) and it costs
     * nothing: measured across the shipped rule packages, {@code Scope.Data_Structures} uses only
     * {@code BASIC DATA STRUCTURE} (74), {@code OCCURRENCE DATA STRUCTURE} (174) and
     * {@code SUBJECT LEVEL ANALYSIS DATASET} (20) — <b>zero</b> entries name {@code ADAM OTHER}.
     * </p>
     *
     * @param datasetName
     *            the dataset name, or {@code null} when unknown
     * @param columnNames
     *            the dataset's column names (matched case-insensitively)
     * @return the most specific structure the columns support, never {@code null}
     */
    public static String detectSpecific(@Nullable String datasetName,
            @Nullable Collection<String> columnNames)
    {
        String base = detect(datasetName, columnNames);
        // The Fix #140 (EC-50) name gate applies to the refinement too: a non-ADaM-named dataset
        // carries no ADaM structure however device-shaped it looks, so an SDTM device domain
        // (DI/DO/DU/DX, all SPDEVID carriers) must not be promoted out of ADAM OTHER here.
        if (datasetName != null && !isAdamDatasetName(datasetName))
        {
            return base;
        }
        if (!hasDeviceIdentifier(columnNames))
        {
            return base;
        }
        return switch (base)
        {
        case BDS -> MEDICAL_DEVICE_BDS;
        case OCCDS -> MEDICAL_DEVICE_OCCDS;
        case OTHER -> DEVICE_LEVEL_ANALYSIS_DATASET;
        // ADSL (and, defensively, anything a future token adds) is not refined.
        default -> base;
        };
    }


    /** Whether {@code columnNames} carries the {@link #DEVICE_IDENTIFIER} column. */
    private static boolean hasDeviceIdentifier(@Nullable Collection<String> columnNames)
    {
        if (columnNames == null)
        {
            return false;
        }
        for (String col : columnNames)
        {
            if (col != null && DEVICE_IDENTIFIER.equalsIgnoreCase(col.trim()))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Fix #119: maps a <em>declared</em> Define-XML class value onto the structure vocabulary of
     * the {@code Scope.Data_Structures} gate (Q4 resolution, 2026-07-26):
     * <ul>
     * <li>every one of the eight {@link #STRUCTURE_TOKENS} maps <b>verbatim</b>;</li>
     * <li>the eight SDTM {@code ItemGroupClass} values (EVENTS, FINDINGS, …) and anything
     * unrecognised are <b>not</b> data structures — {@code null} (callers fall back to the
     * heuristic).</li>
     * </ul>
     * Comparison is case-insensitive on the trimmed value.
     *
     * <p>
     * <b>Fix #179 removed the fold.</b> This method used to map the three device variants onto
     * their bases ({@code MEDICAL DEVICE BASIC DATA STRUCTURE} → {@code BASIC DATA STRUCTURE}, …),
     * which discarded the sponsor's declaration and made the variants unauthorable. The is-a
     * relation they express is now carried by {@link #structureSet} instead, so nothing is lost:
     * the base token is still in the set, one position later. Callers that need the pre-Fix-#175
     * value can read {@code structureSet(token).getLast()}.
     * </p>
     *
     * @param declaredClass
     *            the declared class value (e.g. from {@code def:Class}), or {@code null}
     * @return the declared structure token, most specific (i.e. exactly as declared), or
     *         {@code null} when the declaration is not an ADaM structure
     */
    public static @Nullable String structureTokenFromDeclaredClass(@Nullable String declaredClass)
    {
        if (declaredClass == null)
        {
            return null;
        }
        String canonical = declaredClass.trim().toUpperCase(Locale.ROOT);
        return STRUCTURE_TOKENS.contains(canonical) ? canonical : null;
    }


    /**
     * Fix #179: {@code structureToken} together with every supertype it specialises,
     * <b>most-specific first</b> — the set a dataset carrying that structure matches against.
     * {@code MEDICAL DEVICE BASIC DATA STRUCTURE} yields
     * {@code [MEDICAL DEVICE BASIC DATA STRUCTURE, BASIC DATA STRUCTURE]}; a root token yields
     * itself alone; an unrecognised token yields itself alone (the gate then simply never matches
     * it, which is the pre-existing closed-vocabulary behaviour).
     *
     * <p>
     * ⚠ The <b>last</b> element is always the root of the chain, and for the three device variants
     * that root is precisely the token the pre-Fix-#175 fold returned. That identity is what makes
     * Fix #179 behaviour-preserving for every rule authored against the four original tokens.
     * </p>
     *
     * @param structureToken
     *            a structure token (typically from {@link #detect} or
     *            {@link #structureTokenFromDeclaredClass})
     * @return an immutable list, never empty, most-specific first
     */
    public static List<String> structureSet(String structureToken)
    {
        String supertype = SUPERTYPES.get(structureToken);
        return supertype == null ? List.of(structureToken) : List.of(structureToken, supertype);
    }


    /**
     * Fix #119: {@link #detect(String, Collection)} with a declared Define-XML class and the
     * {@code corej.defineFirst} preference:
     * <ul>
     * <li>{@code defineFirst} and the declaration folds to a structure token
     * ({@link #structureTokenFromDeclaredClass}) → the declared token wins;</li>
     * <li>otherwise the column/name heuristic decides; when it yields {@link #OTHER} and a
     * recognised declaration exists, the declaration fills in (define as fallback).</li>
     * </ul>
     * Total — always returns one of the {@link #STRUCTURE_TOKENS}, the <b>most specific</b> one
     * that applies. ⚠ Since Fix #179 that can be a medical-device specialisation; the scope gate
     * needs the whole is-a set, so <b>production callers want {@link #detectAll}</b>, not this
     * method.
     *
     * <p>
     * <b>Fix #154</b>: callers pass {@link #defineFirstPreference()}, which is now {@code true} by
     * default — so the first branch is the normal one and the second is the {@code
     * -Dcorej.defineFirst=false} opt-out. The tier order the preference realises is <b>Define-XML →
     * metadata library → local-only</b>; the local-only tier is this method's heuristic, whose own
     * order is {@code ADSL}-by-name (already first, below), then the column signals. There is
     * deliberately no dataset-name tier for BDS/OCCDS here: {@code ADSL} is the only ADaM dataset
     * with a standardised name.
     * </p>
     */
    public static String detect(@Nullable String datasetName,
            @Nullable Collection<String> columnNames, @Nullable String declaredClass,
            boolean defineFirst)
    {
        String declared = structureTokenFromDeclaredClass(declaredClass);
        if (defineFirst && declared != null)
        {
            return declared;
        }
        // Phase 3a.1: the device-aware heuristic, not the bare Python mirror — see detectSpecific
        // for why the two are separate methods. ⚠ Under the -Dcorej.defineFirst=false opt-out a
        // device-level dataset now heuristics to DEVICE LEVEL ANALYSIS DATASET rather than ADAM
        // OTHER, so the declaration no longer fills in for it. That is the documented opt-out
        // behaving as specified — a confident non-OTHER heuristic has always beaten a declaration
        // in columns-first mode, and DEVICE LEVEL ANALYSIS DATASET is now a confident verdict.
        String heuristic = detectSpecific(datasetName, columnNames);
        return OTHER.equals(heuristic) && declared != null ? declared : heuristic;
    }


    /**
     * <b>Fix #179 — the set-valued detector, and the one production callers should use.</b> Exactly
     * {@link #detect(String, Collection, String, boolean)} expanded through {@link #structureSet}:
     * the most specific structure that applies, followed by every supertype it specialises.
     *
     * <p>
     * A medical-device BDS dataset yields {@code [MEDICAL DEVICE BASIC DATA STRUCTURE, BASIC DATA
     * STRUCTURE]}, so it satisfies a rule scoped {@code Include:[BASIC DATA STRUCTURE]}
     * <em>and</em> one scoped {@code Include:[MEDICAL DEVICE BASIC DATA STRUCTURE]}; a plain BDS
     * dataset yields {@code [BASIC DATA STRUCTURE]} and satisfies only the former. Never empty.
     * </p>
     *
     * @param datasetName
     *            the dataset name, or {@code null} when unknown
     * @param columnNames
     *            the dataset's column names (matched case-insensitively)
     * @param declaredClass
     *            the declared Define-XML / library class value, or {@code null}
     * @param defineFirst
     *            the {@link #defineFirstPreference()} value
     * @return the dataset's structure set, most-specific first, never empty
     */
    public static List<String> detectAll(@Nullable String datasetName,
            @Nullable Collection<String> columnNames, @Nullable String declaredClass,
            boolean defineFirst)
    {
        return structureSet(detect(datasetName, columnNames, declaredClass, defineFirst));
    }


    /**
     * Whether {@code columnNames} carries NONE of the BDS/OCCDS structural indicator columns — the
     * positive structure-absence signal gating the FU-4 "ADAM OTHER" class fallback
     * ({@link MetadataLibraryProvider}). Equivalent to
     * {@code !BDS.equals(detect(...)) && !OCCDS.equals(detect(...))} on a non-ADSL name; kept as
     * its own predicate to mirror Python's {@code _has_no_adam_structure_indicators}. An empty or
     * {@code null} column set counts as structure-less.
     *
     * <p>
     * ⚠ <b>Deliberately defined on {@link #detect(String, Collection)}, not on the device-aware
     * {@link #detectSpecific}</b> (Phase 3a.1). Two reasons, and they point the same way. It is the
     * mirror of a Python predicate, so it follows Python's four-token vocabulary; and it gates a
     * <em>class</em> sentinel (⚠ <b>corrected 2026-08-28</b>: previously <em>"corpus-visible today
     * (8 rules scope {@code Classes.Include:[ADAM OTHER]})"</em> — measured, <b>zero</b> corpus
     * files mention {@code ADAM OTHER}; the sentinel is authorable, not currently authored),
     * whereas Phase 3a's acceptance criterion is zero corpus movement. There <em>is</em> a real
     * semantic tension — a device-level analysis dataset is no longer structure-less as far as
     * {@link #detectSpecific} is concerned, yet this predicate still calls it structure-less — and
     * resolving it moves the class gate, so it belongs to the corpus phase (3b), not here.
     * </p>
     */
    public static boolean hasNoStructureIndicators(@Nullable Collection<String> columnNames)
    {
        if (columnNames == null || columnNames.isEmpty())
        {
            return true;
        }
        String detected = detect(null, columnNames);
        return !BDS.equals(detected) && !OCCDS.equals(detected);
    }

}
