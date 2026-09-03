package net.cumba.corej.core.metadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lombok.CustomLog;
import org.jspecify.annotations.Nullable;

/**
 * Per-dataset ADaM subclass determination for the {@code Scope.Subclasses} gate — a house design
 * (no engine implements one upstream; the token vocabulary is the Define-XML 2.1
 * {@code ItemGroupSubClass} enumeration). Detection is tiered:
 *
 * <ol>
 * <li><b>Declared</b> — a Define-XML {@code def:SubClass} declaration, when supplied by the caller
 * (see {@code corej.defineFirst} in the callers for precedence);</li>
 * <li><b>Heuristic</b> — column/name signatures, evaluated most-specific first, first match wins
 * (signatures confirmed 2026-07-26):
 * <table>
 * <caption>subclass signatures</caption>
 * <tr>
 * <th>token</th>
 * <th>precondition</th>
 * <th>signature</th>
 * </tr>
 * <tr>
 * <td>{@code MEDICAL DEVICE TIME-TO-EVENT}</td>
 * <td>structure = BDS</td>
 * <td>columns {@code CNSR} + {@link AdamDataStructureDetector#DEVICE_IDENTIFIER SPDEVID}</td>
 * </tr>
 * <tr>
 * <td>{@code TIME-TO-EVENT}</td>
 * <td>structure = BDS</td>
 * <td>column {@code CNSR} (ADaMIG TTE: required)</td>
 * </tr>
 * <tr>
 * <td>{@code POPULATION PHARMACOKINETIC ANALYSIS}</td>
 * <td>structure = BDS</td>
 * <td>a NONMEM control column ({@code EVID} or {@code MDV}) <em>and</em> ≥ 3 of the
 * NONMEM-signature columns {@code DV}, {@code MDV}, {@code AMT}, {@code EVID}, {@code CMT}</td>
 * </tr>
 * <tr>
 * <td>{@code NON-COMPARTMENTAL ANALYSIS}</td>
 * <td>structure = BDS</td>
 * <td>≥ 2 of the relative-time columns {@code NFRLT}, {@code AFRLT}, {@code ARRLT},
 * {@code NRRLT}</td>
 * </tr>
 * <tr>
 * <td>{@code ADVERSE EVENT}</td>
 * <td>structure = OCCDS</td>
 * <td>column {@code AEDECOD} or {@code AETERM}</td>
 * </tr>
 * </table>
 * ({@code POPULATION PHARMACOKINETIC ANALYSIS} is ordered before {@code NON-COMPARTMENTAL ANALYSIS}
 * because ADPPK datasets routinely carry the relative-time columns too.) ⚠ <b>These signatures are
 * NOT mutually exclusive, so "first match wins" makes the order a real tie-break rule</b> — see
 * {@link #detect} for why it is ordered by column <em>specificity</em> rather than by hit count,
 * and for the measured population of datasets that trip the overlap (empty, as of 2026-08-08).
 * <p>
 * <b>Fix #140 (EC-50)</b> hardened three of these signatures and removed the exact dataset-name
 * tests that {@code POPULATION PHARMACOKINETIC ANALYSIS} and {@code NON-COMPARTMENTAL ANALYSIS}
 * used to carry — see {@link #detect} for the rationale. <b>Fix #154 restored the name tests as a
 * separate, strictly lower tier</b> ({@link #detectByName}); {@link #detect} itself is still purely
 * column-driven, and the parity lane still calls only that.
 * </p>
 * </li>
 * <li><b>Name</b> — {@link #detectByName}, Fix #154's last resort: {@code ADPPK*} / {@code ADPPT*}
 * / {@code ADPOPPK*} and {@code ADNCA*} on a BDS dataset, used only when no declaration is
 * available and the columns are silent. Reversal of a Fix #140 decision — the rationale, and why it
 * must not be re-removed, is on {@link #detectByName}.</li>
 * <li><b>None</b> — {@code null}: the dataset has no detectable subclass, the normal case for a
 * plain BDS/OCCDS/ADSL dataset. There is deliberately no synthetic OTHER sentinel — subclasses are
 * an opt-in refinement, not a partition.</li>
 * </ol>
 *
 * <p>
 * {@link #resolve(String, List, Collection, List, boolean)} composes the tiers in their decided
 * order; {@link #detect(List, Collection)} and {@link #detectByName} are each a single tier and are
 * exposed separately so each can be pinned on its own.
 * </p>
 */
@CustomLog
public final class AdamSubclassDetector
{

    /** Subclass token: adverse-event occurrence data (ADAE family). */
    public static final String ADVERSE_EVENT = "ADVERSE EVENT";

    /** Subclass token: medical-device time-to-event (BDS + CNSR + SPDEVID). */
    public static final String MEDICAL_DEVICE_TIME_TO_EVENT = "MEDICAL DEVICE TIME-TO-EVENT";

    /** Subclass token: non-compartmental analysis (ADNCA family). */
    public static final String NON_COMPARTMENTAL_ANALYSIS = "NON-COMPARTMENTAL ANALYSIS";

    /** Subclass token: population-PK analysis (ADPPK family). */
    public static final String POPULATION_PHARMACOKINETIC_ANALYSIS = "POPULATION PHARMACOKINETIC ANALYSIS";

    /** Subclass token: time-to-event (BDS + CNSR). */
    public static final String TIME_TO_EVENT = "TIME-TO-EVENT";

    /** The five subclass tokens (Define-XML 2.1 {@code ItemGroupSubClass} enumeration). */
    public static final Set<String> SUBCLASS_TOKENS = Set.of(ADVERSE_EVENT,
            MEDICAL_DEVICE_TIME_TO_EVENT, NON_COMPARTMENTAL_ANALYSIS,
            POPULATION_PHARMACOKINETIC_ANALYSIS, TIME_TO_EVENT);

    /**
     * <b>Ruling 5 — the subclass axis's is-a relation, as data.</b> Each specialised token mapped
     * to its immediate supertype; the exact analogue of
     * {@code AdamDataStructureDetector.SUPERTYPES} on the structure axis (Fix #179), and kept as
     * data for the same reason: {@link net.cumba.corej.core.exec.ScopeMatcher} stays a pure
     * exact-match over a token set and the hierarchy lives next to the vocabulary it refines.
     *
     * <p>
     * Today exactly one pair: a medical-device time-to-event dataset <em>is</em> a time-to-event
     * dataset, so a rule scoped {@code Subclasses.Include:[TIME-TO-EVENT]} must reach it. The
     * converse does not hold — a plain TTE dataset is not a device one.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>This is NOT the cross-axis fallback.</b> {@link #ADVERSE_EVENT} has no entry here and
     * must not get one: its fallback is a <em>structure</em> (the base {@code OCCDS} behind the
     * {@code AE} specialisation), which {@code MetadataLibraryProvider}'s governing chain supplies
     * on the structure axis. Conflating the two would put a structure token in a subclass set.
     * </p>
     *
     * <p>
     * ⚠ {@link #subclassSet} walks this map one level deep, exactly as {@code structureSet} does; a
     * chain deeper than one level would need the walk to iterate. Every value here is a root, which
     * {@code AdamSubclassDetectorTest} asserts.
     * </p>
     */
    private static final Map<String, String> SUBCLASS_SUPERTYPES = Map
            .of(MEDICAL_DEVICE_TIME_TO_EVENT, TIME_TO_EVENT);

    /**
     * The structure token a dataset must carry for a <em>declared</em> subclass to be believable —
     * the same precondition {@link #detect(List, Collection)} applies to its own column signatures,
     * lifted out so the declared tier can be validated against it.
     *
     * <p>
     * ⚠⚠ <b>Why the declared tier needs this at all.</b> Until Phase 4 of
     * {@code plans/PLAN-metadata-product-selection.md}, {@link #knownDeclaredTokens} accepted a
     * declared {@code def:SubClass} on {@link #SUBCLASS_TOKENS} membership alone, with no structure
     * check — and under the default {@code corej.defineFirst=true} it beats the heuristic. That was
     * harmless while a subclass only gated rules (a bogus declaration cost the dataset some rules).
     * Since Phase 3 the resolved subclass <b>selects the governing data structure and therefore the
     * variable list</b>, so a declared {@code ADVERSE EVENT} on a BDS dataset would silently pick
     * an occurrence structure's variables. The declaration is dropped with a WARN instead.
     * </p>
     */
    private static final Map<String, String> SUBCLASS_STRUCTURE_PRECONDITION = Map.of(//
            ADVERSE_EVENT, AdamDataStructureDetector.OCCDS, //
            MEDICAL_DEVICE_TIME_TO_EVENT, AdamDataStructureDetector.BDS, //
            NON_COMPARTMENTAL_ANALYSIS, AdamDataStructureDetector.BDS, //
            POPULATION_PHARMACOKINETIC_ANALYSIS, AdamDataStructureDetector.BDS, //
            TIME_TO_EVENT, AdamDataStructureDetector.BDS);

    /**
     * Datasets already warned about in {@link #knownDeclaredTokens}, so the message fires once per
     * (dataset, token) rather than once per rule.
     *
     * <p>
     * ⚠ {@link #resolve} runs once per <em>rule</em> per dataset ({@code RuleRunner}), so an
     * un-deduplicated warning would fire dozens of times for a single dataset — the very reason the
     * class javadoc gives for rejecting a runtime ambiguity warning. Bounded at {@link #WARN_CAP}
     * entries so a long-running server cannot accumulate them without limit; past the cap the drop
     * is silent (the first {@value #WARN_CAP} distinct cases have already been reported).
     * </p>
     *
     * <p>
     * ⛔⛔ <b>Phase 11 finding F9 — the latch is JVM-global, and the key is only
     * {@code (dataset, token)}.</b> In the REST server that spans studies: study A validates an
     * {@code ADAE} that mis-declares {@code TIME-TO-EVENT}, the WARN fires into <em>A's</em> run
     * log, and study B's identically-named, identically-mis-declared dataset is then <b>silent</b>
     * — its operator never learns the declaration was dropped. Nothing in {@link #resolve}'s
     * arguments distinguishes one run from another, and threading a run identity through
     * {@code RuleRunner} / {@code AdamStructureContext} into a static detector is exactly the
     * plumbing this class exists without. {@link #resetDeclarationWarnings()} is the cheap fix
     * instead: {@code StudyValidationService.validate} re-arms the latch as a run begins, so no run
     * can be silenced by an earlier one.
     * </p>
     *
     * <p>
     * ⚠ <b>Residual, deliberate:</b> under <em>concurrent</em> runs in one JVM a later run's re-arm
     * can let an earlier, still-executing run repeat a warning it already emitted. That trade is
     * the right way round — a duplicated WARN is visible and harmless, a suppressed one is neither.
     * The {@link #WARN_CAP} bound is unaffected: re-arming can only shrink the set.
     * </p>
     */
    private static final Set<String> WARNED_DECLARATIONS = ConcurrentHashMap.newKeySet();

    /** Maximum number of distinct declared-subclass mismatches reported per JVM. */
    private static final int WARN_CAP = 1024;

    /** NONMEM-signature columns for the popPK heuristic (≥ {@link #POPPK_MIN_HITS} required). */
    private static final Set<String> POPPK_COLUMNS = Set.of("DV", "MDV", "AMT", "EVID", "CMT");

    private static final int POPPK_MIN_HITS = 3;

    /** Relative-time columns for the NCA heuristic (≥ {@link #NCA_MIN_HITS} required). */
    private static final Set<String> NCA_COLUMNS = Set.of("NFRLT", "AFRLT", "ARRLT", "NRRLT");

    private static final int NCA_MIN_HITS = 2;

    /**
     * Fix #154: dataset-name prefixes for the <b>last-resort</b> popPK tier — see
     * {@link #detectByName}. Taken from the {@code Scope.Domains} globs the six popPK rules
     * (CDISC-AD0885–CDISC-AD0890) carried before decision D21 dropped them ({@code "ADPPK*"} /
     * {@code "ADPPT*"}), plus the {@code ADPOPPK} spelling; matched as prefixes, exactly as those
     * globs were.
     */
    private static final List<String> POPPK_NAME_PREFIXES = List.of("ADPPK", "ADPPT", "ADPOPPK");

    /**
     * Fix #154: dataset-name prefixes for the last-resort NCA tier — see {@link #detectByName}.
     * {@code ADNCA} is the name Fix #140 removed and the one the ADaM NCA IG uses.
     */
    private static final List<String> NCA_NAME_PREFIXES = List.of("ADNCA");

    private AdamSubclassDetector()
    {
    }


    /**
     * Detects the dataset's ADaM subclass from its detected data structure and column names.
     * Heuristic tier only — the Define-XML declared tier is applied by {@link #resolve} (which
     * holds the declaration and the {@code corej.defineFirst} preference).
     *
     * <p>
     * <b>Fix #140 (EC-50)</b> — three Java-only deviations from the parity fork's
     * {@code get_subclass}, all made so that ADaM rules can be scoped by subclass instead of by
     * dataset name:
     * </p>
     * <ul>
     * <li>{@link #ADVERSE_EVENT} accepts {@code AEDECOD} <em>or</em> {@code AETERM}. Keying on one
     * of them made a rule that checks for that column's absence unable to fire at all (CDISC-AD0261
     * / CDISC-AD0620). Both signals keep the {@code AE} prefix, so {@code ADMH}
     * ({@code MHTERM}/{@code MHDECOD}) and {@code ADCM} ({@code CMTRT}/{@code CMDECOD}) still
     * detect nothing.</li>
     * <li>{@link #POPULATION_PHARMACOKINETIC_ANALYSIS} requires BDS <em>and</em> a NONMEM control
     * column ({@code EVID}/{@code MDV}) on top of the &ge;3-column signature. {@code DV},
     * {@code AMT} and {@code CMT} are short, generic names, and without this an NCA dataset
     * carrying three of them was classified popPK and lost its own rules. The popPK-before-NCA
     * order is kept: ADPPK datasets routinely carry the relative-time columns too, so reordering
     * would misclassify them.</li>
     * <li><b>No subclass is keyed off a dataset name in <em>this</em> method.</b> The
     * {@code "ADPPK"}/{@code "ADNCA"} name tests and the {@code datasetName} parameter left it in
     * Fix #140. <b>Fix #154 put the name back as a strictly lower tier</b> — {@link #detectByName},
     * consulted by {@link #resolve} only after both the columns and the declaration have come up
     * empty — because Fix #140's justification assumed a declared tier is always available and
     * D21-remainder rejected that assumption. This method stays column-only, so the parity fork's
     * {@code get_subclass} and this one still agree.</li>
     * </ul>
     *
     * <p>
     * <b>Fix #179</b>: {@code detectedStructures} is the dataset's structure <em>set</em>
     * ({@link AdamDataStructureDetector#detectAll}), so the BDS/OCCDS preconditions are
     * <b>contains</b>-checks rather than equality. ⚑ <b>Behaviour is unchanged</b>: before Fix #179
     * a medical-device BDS dataset folded to {@code BASIC DATA STRUCTURE} and {@code bds} was true;
     * with {@code [MEDICAL DEVICE BASIC DATA STRUCTURE, BASIC DATA STRUCTURE]} it still is. This is
     * the only place in the engine that branches on the structure, and it keeps answering
     * identically — which is why Fix #179 is invisible.
     * </p>
     *
     * <p>
     * <b>Phase 3a.1 preserves that invariance.</b> The device-aware column heuristic
     * ({@link AdamDataStructureDetector#detectSpecific}) can now return
     * {@code MEDICAL DEVICE BASIC DATA STRUCTURE} / {@code MEDICAL DEVICE OCCURRENCE DATA
     * STRUCTURE} where it previously returned the base, but both keep their base in the set, so
     * {@code bds} / {@code occds} answer exactly as before. The one heuristic verdict that
     * <em>does</em> change is {@code ADAM OTHER} → {@code DEVICE LEVEL ANALYSIS DATASET}, which has
     * no supertype — and since neither {@code ADAM OTHER} nor {@code DEVICE LEVEL ANALYSIS DATASET}
     * is {@code BDS} or {@code OCCDS}, both flags are {@code false} either way. <b>Every branch
     * below is therefore unchanged for every input.</b>
     * </p>
     *
     * <p>
     * ⚠⚠ <b>Phase 3a.2 deliberately did NOT let a medical-device <em>structure</em> imply
     * {@link #MEDICAL_DEVICE_TIME_TO_EVENT}.</b> It is the obvious next step now that the structure
     * set can say "medical device" — and it was implemented, measured and reverted. A
     * device-variant set would then decide <em>differently</em> from its base token's set for the
     * TTE branch, which is exactly the invariance {@code AdamSubclassDetectorStructureSetTest}
     * exists to pin and the property that makes Fix #179 invisible. It buys nothing to pay for
     * that: {@code TIME-TO-EVENT} and {@code MEDICAL DEVICE TIME-TO-EVENT} are scoped by
     * <b>zero</b> rules in the shipped packages (the only {@code Scope.Subclasses} tokens in use
     * are {@code ADVERSE EVENT} 174, {@code NON-COMPARTMENTAL ANALYSIS} 68 and {@code POPULATION
     * PHARMACOKINETIC ANALYSIS} 6). Subclass is-a semantics are a separate plan — see
     * {@code plans/PLAN-adam-structure-set-valued.md} §7 item 2.
     * </p>
     *
     * <h4>⚠⚠ The branch order is DELIBERATE — and the tie-break is SPECIFICITY, not hit count</h4>
     *
     * <p>
     * The three BDS branches are <b>non-exclusive predicates over the same column set</b>, and the
     * chain is first-match-wins, so source order <em>is</em> the tie-break rule. A dataset carrying
     * {@code CNSR} <em>and</em> the NONMEM signature satisfies branches 1 and 2 at once and is
     * classified {@link #TIME_TO_EVENT}. That is a decision, not an oversight, and it is pinned by
     * {@code AdamSubclassDetectorTest.tteOrderedBeforePopPkAndNca_deliberate} — reorder the
     * branches and that test reddens.
     * </p>
     *
     * <p>
     * ⚑ <b>Why branch 1 outranks branches 2 and 3 despite keying on one column.</b> Evidence here
     * is ranked by how <em>diagnostic</em> a column is, not by how many matched. {@code CNSR} is
     * ADaMIG-required in a time-to-event dataset and appears in no other ADaM structure's
     * signature, so a single hit is near-conclusive. The popPK signature is built from {@code DV},
     * {@code AMT} and {@code CMT} — two- and three-character generic names — which is precisely why
     * branch 2 has to demand an {@code EVID}/{@code MDV} control column on top of three hits (Fix
     * #140, EC-50: without it an NCA dataset carrying the generic trio was classified popPK and
     * lost its own rules). A score-by-hit-count rule would rank that reinforced-but-generic
     * signature <em>above</em> the specific one and invert a correct precedence. The class
     * javadoc's "most-specific first" is therefore literal.
     * </p>
     *
     * <p>
     * Branches 1–3 (BDS) and branch 4 (OCCDS) can never compete:
     * {@link AdamDataStructureDetector#detectAll} is {@code structureSet(detect(…))},
     * {@code detect} returns exactly one token, and no token's supertype chain contains both
     * {@code BDS} and {@code OCCDS}. Only branches 1/2/3 can overlap.
     * </p>
     *
     * <h4>⚠ What the overlap would cost, and why it is nevertheless left alone</h4>
     *
     * <p>
     * <b>{@link #TIME_TO_EVENT} and {@link #MEDICAL_DEVICE_TIME_TO_EVENT} are scoped by ZERO
     * rules.</b> Measured 2026-08-08 by parsing {@code Scope.Subclasses} out of all 3&nbsp;727
     * {@code rules-src/checks} YAMLs: 78 rules carry the gate, all {@code Include}, one token each
     * — {@code ADVERSE EVENT} 55, {@code NON-COMPARTMENTAL ANALYSIS} 17, {@code POPULATION
     * PHARMACOKINETIC ANALYSIS} 6. So if the ambiguous shape ever did occur, branch 1 would return
     * a token nothing matches and the dataset would silently lose its NCA/popPK rules — a
     * silencing, not a mislabelling.
     * </p>
     *
     * <p>
     * ⚑ <b>The shape does not occur in any data available to this project.</b> Measured 2026-08-08
     * over 8&nbsp;197 dataset definitions — 5&nbsp;447 {@code .cdt} fixture datasets, 1&nbsp;694
     * Define-XML {@code ItemGroupDef}s, 958 Dataset-JSON files, 57 XPT members and 41 CSV headers,
     * drawn from {@code /data/testdata}, the whole repository and the parity fork's own test corpus
     * (which contributes 595 of them): <b>0</b> datasets carry {@code CNSR} together with either
     * the popPK or the NCA signature, and <b>0</b> carry {@code CNSR} alongside even <em>one</em>
     * NONMEM or relative-time column. The five {@code CNSR}-bearing datasets are ADTTE/ADLBC shapes
     * with none of those columns. Branches 2 and 3 do not overlap each other in that corpus either
     * — so the popPK-before-NCA order below, though correct, is exercised only by
     * {@code AdamSubclassDetectorTest.popPkOrderedBeforeNca}, never by a fixture.
     * </p>
     *
     * <p>
     * ⚠ And {@link #resolve} shields the shape further: a recognised <em>declared</em> subclass
     * wins outright under the default {@code corej.defineFirst=true} (Fix #154), so the chain only
     * decides when nothing declares. Declaring the subclass in the Define is the real remedy, and
     * it already works. Adding a runtime ambiguity WARNING was considered and rejected:
     * {@code resolve} is called once per <em>rule</em> per dataset ({@code RuleRunner}), so an
     * un-deduplicated warning would fire up to 78 times for a single dataset, and the shape it
     * would report has never been observed. See {@code plans/done/PLAN-adam-subclass-ambiguity.md}
     * for the full adjudication, and its open question on whether a branch that can only ever
     * return an unscoped token should exist at all — that is a corpus/authoring question, not an
     * engine one.
     * </p>
     *
     * @param detectedStructures
     *            the dataset's structure set from {@link AdamDataStructureDetector#detectAll} (may
     *            be empty defensively; the BDS/OCCDS preconditions then fail)
     * @param columnNames
     *            the dataset's column names (matched case-insensitively)
     * @return the detected subclass token, or {@code null} when the dataset has no detectable
     *         subclass
     */
    public static @Nullable String detect(List<String> detectedStructures,
            @Nullable Collection<String> columnNames)
    {
        Set<String> upper = upperColumns(columnNames);
        boolean bds = detectedStructures.contains(AdamDataStructureDetector.BDS);
        boolean occds = detectedStructures.contains(AdamDataStructureDetector.OCCDS);

        if (bds && upper.contains("CNSR"))
        {
            // Phase 3a.2: the device discriminator is now a shared constant on the sibling
            // detector, which learned the same column in 3a.1. ⚠ Behaviour is byte-identical —
            // AdamDataStructureDetector.DEVICE_IDENTIFIER is "SPDEVID", the literal that stood
            // here before. What changes is that a future change to what makes a dataset
            // device-level moves BOTH detectors, instead of leaving this one behind.
            return upper.contains(AdamDataStructureDetector.DEVICE_IDENTIFIER)
                    ? MEDICAL_DEVICE_TIME_TO_EVENT
                    : TIME_TO_EVENT;
        }
        if (bds && (upper.contains("EVID") || upper.contains("MDV"))
                && countHits(upper, POPPK_COLUMNS) >= POPPK_MIN_HITS)
        {
            return POPULATION_PHARMACOKINETIC_ANALYSIS;
        }
        if (bds && countHits(upper, NCA_COLUMNS) >= NCA_MIN_HITS)
        {
            return NON_COMPARTMENTAL_ANALYSIS;
        }
        if (occds && (upper.contains("AEDECOD") || upper.contains("AETERM")))
        {
            return ADVERSE_EVENT;
        }
        return null;
    }


    /**
     * <b>Fix #154 — the last-resort dataset-name tier.</b> Consulted only after the column
     * signatures ({@link #detect(List, Collection)}) <em>and</em> the declared Define-XML / library
     * tier have both come up empty; see {@link #resolve} for the composition.
     *
     * <p>
     * {@code POPULATION PHARMACOKINETIC ANALYSIS} and {@code NON-COMPARTMENTAL ANALYSIS} are
     * recognised from {@link #POPPK_NAME_PREFIXES} / {@link #NCA_NAME_PREFIXES} on a
     * {@link AdamDataStructureDetector#BDS BDS} dataset. No other subclass has a name tier: every
     * other ADaM dataset is sponsor-named.
     * </p>
     *
     * <h4>⚠ This deliberately reverses part of Fix #140 (EC-50) — do not re-remove it</h4>
     *
     * <p>
     * Fix #140 deleted the {@code "ADPPK"} / {@code "ADNCA"} name tests on the principle that
     * <em>"a name test in a detector that exists to replace name scoping is a contradiction"</em>,
     * and routed the uncoverable dataset <em>"through the declared tier instead"</em>. That
     * principle rested on an assumption decision D21-remainder (2026-08-05) explicitly rejects:
     * that a declared tier is always available. It is not — of the 33 Define-XML files in the
     * project's test corpus exactly one carries any ADaM {@code def:Class} declaration at all, and
     * a submission may ship none.
     * </p>
     *
     * <p>
     * The objection does not survive the distinction it conflated: a <em>detector</em> using the
     * name as one signal among several is <b>evidence</b>; a <em>rule</em> scoping itself by name
     * is <b>authority</b>. D21 removed the authority (the {@code Scope.Domains: ["ADPPK*",
     * "ADPPT*"]} facet on the six popPK rules); this restores only the evidence, and only where
     * nothing better exists.
     * </p>
     *
     * <p>
     * <b>⚠ The name tier must stay BELOW the columns</b> — {@link #resolve} guarantees that, and
     * the ordering is load-bearing, not stylistic: {@code ADPPK} datasets routinely carry the
     * relative-time columns too, which is exactly why popPK is ordered <em>before</em> NCA inside
     * {@link #detect(List, Collection)}. A name tier above the columns would flip an
     * {@code ADPPK}-named NCA dataset into popPK and cost it its own rules.
     * </p>
     *
     * <p>
     * Java-only: the parity fork's {@code get_subclass} has no name tier, and the parity lane calls
     * the column-only {@link #detect(List, Collection)} rather than {@link #resolve}, so this tier
     * is invisible there.
     * </p>
     *
     * @param datasetName
     *            the dataset name, or {@code null} when unknown
     * @param detectedStructures
     *            the dataset's structure set ({@link AdamDataStructureDetector#detectAll}); only a
     *            set containing {@link AdamDataStructureDetector#BDS} can produce a hit — Fix #179
     *            makes that a contains-check, so a medical-device BDS dataset qualifies exactly as
     *            it did when the variant folded onto {@code BASIC DATA STRUCTURE}
     * @return the subclass token the name implies, or {@code null}
     */
    public static @Nullable String detectByName(@Nullable String datasetName,
            List<String> detectedStructures)
    {
        if (datasetName == null || !detectedStructures.contains(AdamDataStructureDetector.BDS))
        {
            return null;
        }
        String upper = datasetName.trim().toUpperCase(Locale.ROOT);
        if (startsWithAny(upper, POPPK_NAME_PREFIXES))
        {
            return POPULATION_PHARMACOKINETIC_ANALYSIS;
        }
        if (startsWithAny(upper, NCA_NAME_PREFIXES))
        {
            return NON_COMPARTMENTAL_ANALYSIS;
        }
        return null;
    }


    private static boolean startsWithAny(String upperName, List<String> prefixes)
    {
        for (String prefix : prefixes)
        {
            if (upperName.startsWith(prefix))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Fix #119: subclass resolution with declared Define-XML {@code <def:SubClass>} names and the
     * {@code corej.defineFirst} preference. Equivalent to
     * {@link #resolve(String, List, Collection, List, boolean)} with no dataset name, i.e. with the
     * Fix #154 name tier switched off.
     *
     * @return the resolved subclass tokens, empty when the dataset has none
     */
    public static List<String> resolve(List<String> detectedStructures,
            @Nullable Collection<String> columnNames, @Nullable List<String> declaredSubClasses,
            boolean defineFirst)
    {
        return resolve(null, detectedStructures, columnNames, declaredSubClasses, defineFirst);
    }


    /**
     * Fix #119 + <b>Fix #154</b>: the full tier order for the {@code Scope.Subclasses} gate.
     * Returns the set the gate should match against (a dataset may declare several subclasses; the
     * heuristics contribute at most one):
     *
     * <ol>
     * <li><b>Declared</b> (Define-XML {@code def:SubClass}, else the metadata library — the caller
     * decides which provider supplies {@code declaredSubClasses}): wins outright when
     * {@code defineFirst}, which is the default since Fix #154
     * ({@link AdamDataStructureDetector#defineFirstPreference()}). Unrecognised names are ignored —
     * callers may WARN. ⚠ Since ruling 5's phase a declaration is also <b>validated against
     * {@code detectedStructures}</b> and dropped with a WARN when it names a subclass that cannot
     * apply to this structure; see {@link #SUBCLASS_STRUCTURE_PRECONDITION}.</li>
     * <li><b>Column signals</b> ({@link #detect(List, Collection)}).</li>
     * <li><b>Declared, as a fallback</b> — reached only under {@code -Dcorej.defineFirst=false},
     * where the columns were consulted first and found nothing.</li>
     * <li><b>Dataset name</b> ({@link #detectByName}), the last resort: a local-only signal used
     * when there is no usable declaration and the columns are silent.</li>
     * </ol>
     *
     * <p>
     * <b>Ruling 5 — whichever tier answers, the result is then expanded through
     * {@link #subclassSet}</b>, so a token arrives together with the supertypes it specialises,
     * most-specific first ({@code MEDICAL DEVICE TIME-TO-EVENT} ⇒
     * {@code [MEDICAL DEVICE TIME-TO-EVENT, TIME-TO-EVENT]}). The expansion sits at this method's
     * exit precisely so all three tiers get it: a declared device-TTE subclass must cover exactly
     * the rules the heuristic one covers.
     * </p>
     *
     * @param datasetName
     *            the dataset name for the tier-4 fallback, or {@code null} to disable it
     * @param detectedStructures
     *            the dataset's structure set ({@link AdamDataStructureDetector#detectAll}, Fix
     *            #175)
     * @return the resolved subclass tokens, empty when the dataset has none
     */
    public static List<String> resolve(@Nullable String datasetName,
            List<String> detectedStructures, @Nullable Collection<String> columnNames,
            @Nullable List<String> declaredSubClasses, boolean defineFirst)
    {
        return expandSupertypes(resolveOwnTokens(datasetName, detectedStructures, columnNames,
                declaredSubClasses, defineFirst));
    }


    /**
     * The tier chain of {@link #resolve} <b>before</b> the ruling-5 supertype expansion — i.e. the
     * tokens the dataset's own evidence produces. Split out so the expansion happens exactly once,
     * at {@code resolve}'s exit, for every tier.
     */
    private static List<String> resolveOwnTokens(@Nullable String datasetName,
            List<String> detectedStructures, @Nullable Collection<String> columnNames,
            @Nullable List<String> declaredSubClasses, boolean defineFirst)
    {
        List<String> declared = knownDeclaredTokens(datasetName, detectedStructures,
                declaredSubClasses);
        if (defineFirst && !declared.isEmpty())
        {
            return declared;
        }
        String heuristic = detect(detectedStructures, columnNames);
        if (heuristic != null)
        {
            return List.of(heuristic);
        }
        if (!declared.isEmpty())
        {
            return declared;
        }
        String byName = detectByName(datasetName, detectedStructures);
        return byName != null ? List.of(byName) : List.of();
    }


    /**
     * <b>Ruling 5</b>: each token followed by the supertypes it specialises, most-specific first,
     * duplicates dropped — the subclass-axis analogue of
     * {@code AdamDataStructureDetector.structureSet}.
     *
     * <p>
     * ⚠⚠ Applied at {@link #resolve}'s <b>exit</b>, so it covers all three tiers — declared,
     * heuristic and name. Expanding only the heuristic tier would leave a define-XML
     * {@code def:SubClass="MEDICAL DEVICE TIME-TO-EVENT"} covering fewer rules than the identical
     * dataset with no declaration at all: exactly the declared/heuristic asymmetry Fix #179 removed
     * on the structure axis.
     * </p>
     */
    private static List<String> expandSupertypes(List<String> aTokens)
    {
        if (aTokens.isEmpty())
        {
            return aTokens;
        }
        List<String> out = new ArrayList<>(aTokens.size() + 1);
        for (String token : aTokens)
        {
            for (String each : subclassSet(token))
            {
                if (!out.contains(each))
                {
                    out.add(each);
                }
            }
        }
        return List.copyOf(out);
    }


    /**
     * {@code aSubclassToken} together with every supertype it specialises, <b>most-specific
     * first</b> — the set a dataset carrying that subclass matches against.
     * {@code MEDICAL DEVICE TIME-TO-EVENT} yields
     * {@code [MEDICAL DEVICE TIME-TO-EVENT, TIME-TO-EVENT]}; a root token yields itself alone; an
     * unrecognised token yields itself alone (the gate then simply never matches it, which is the
     * pre-existing closed-vocabulary behaviour).
     *
     * @param aSubclassToken
     *            a subclass token, typically from {@link #detect(List, Collection)}
     * @return an immutable list, never empty, most-specific first
     */
    public static List<String> subclassSet(String aSubclassToken)
    {
        String supertype = SUBCLASS_SUPERTYPES.get(aSubclassToken);
        return supertype == null ? List.of(aSubclassToken) : List.of(aSubclassToken, supertype);
    }


    /**
     * The declared names that are known {@link #SUBCLASS_TOKENS} (canonicalised), in order, and
     * <b>whose {@link #SUBCLASS_STRUCTURE_PRECONDITION} the dataset's detected structure set
     * satisfies</b>. A declaration that fails the structure check is dropped with a one-time WARN:
     * see {@link #SUBCLASS_STRUCTURE_PRECONDITION} for why membership alone stopped being enough.
     *
     * <p>
     * ⚑ Dropping is a fall-through, not a verdict: the heuristic and name tiers still run, so a
     * dataset whose columns really do carry a signature keeps its subclass.
     * </p>
     */
    private static List<String> knownDeclaredTokens(@Nullable String aDatasetName,
            List<String> aDetectedStructures, @Nullable List<String> declaredSubClasses)
    {
        if (declaredSubClasses == null || declaredSubClasses.isEmpty())
        {
            return List.of();
        }
        List<String> known = new ArrayList<>(declaredSubClasses.size());
        for (String declared : declaredSubClasses)
        {
            if (declared == null)
            {
                continue;
            }
            String canonical = declared.trim().toUpperCase(Locale.ROOT);
            if (!SUBCLASS_TOKENS.contains(canonical) || known.contains(canonical))
            {
                continue;
            }
            String required = SUBCLASS_STRUCTURE_PRECONDITION.get(canonical);
            if (required != null && !aDetectedStructures.contains(required))
            {
                warnDeclarationDropped(aDatasetName, canonical, required, aDetectedStructures);
                continue;
            }
            known.add(canonical);
        }
        return List.copyOf(known);
    }


    /**
     * Re-arms the once-per-{@code (dataset, token)} declaration warning, so a run cannot be
     * silenced by a warning an earlier run in the same JVM already consumed (Phase 11 finding F9).
     * Called once as a validation run begins; see {@link #WARNED_DECLARATIONS} for the residual
     * concurrent-runs caveat.
     */
    public static void resetDeclarationWarnings()
    {
        WARNED_DECLARATIONS.clear();
    }


    /** One WARN per (dataset, token); see {@link #WARNED_DECLARATIONS}. */
    private static void warnDeclarationDropped(@Nullable String aDatasetName, String aToken,
            String aRequiredStructure, List<String> aDetectedStructures)
    {
        String key = aDatasetName + "\u0000" + aToken;
        if (WARNED_DECLARATIONS.size() >= WARN_CAP || !WARNED_DECLARATIONS.add(key))
        {
            return;
        }
        LOGGER.log(System.Logger.Level.WARNING,
                "Dataset {0} declares subclass {1}, which applies only to a {2} dataset; its "
                        + "detected structures are {3}. Ignoring the declaration — it would "
                        + "otherwise select that structure''s variable list.",
                aDatasetName, aToken, aRequiredStructure, aDetectedStructures);
    }


    private static Set<String> upperColumns(@Nullable Collection<String> columnNames)
    {
        if (columnNames == null || columnNames.isEmpty())
        {
            return Set.of();
        }
        Set<String> upper = new HashSet<>(columnNames.size());
        for (String col : columnNames)
        {
            if (col != null)
            {
                upper.add(col.toUpperCase(Locale.ROOT));
            }
        }
        return upper;
    }


    private static int countHits(Set<String> upperColumns, Set<String> indicators)
    {
        int hits = 0;
        for (String indicator : indicators)
        {
            if (upperColumns.contains(indicator))
            {
                hits++;
            }
        }
        return hits;
    }

}
