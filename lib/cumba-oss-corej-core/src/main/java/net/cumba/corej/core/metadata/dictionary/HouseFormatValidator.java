package net.cumba.corej.core.metadata.dictionary;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates a house-format dictionary document before it is written or shipped.
 *
 * <p>
 * Three independent checks, answering three different questions:
 * </p>
 *
 * <ol>
 * <li>{@link #caseContractViolations} — <b>is it self-consistent?</b> The preferred-case contract,
 * lifted here from test scope so the installer can hold its own output to the same standard the
 * shipped fixtures are held to. This is the authority; the test now calls it rather than keeping a
 * private copy that could drift.</li>
 * <li>{@link #completenessViolations} — <b>does it actually answer anything?</b> The clause the
 * case contract structurally cannot provide.</li>
 * <li>{@link #cardinalityViolations} — <b>is it plausibly a whole release?</b> The clause
 * completeness structurally cannot provide: a {@code pt.asc} truncated to 200 of 27 000 rows by an
 * interrupted unzip satisfies every presence clause and installs — after which the 51 MedDRA rules
 * fire on ~99&nbsp;% of conformant rows. Unlike the first two checks this one is a
 * <em>heuristic</em> over hand-calibrated release sizes, not an exact invariant, so it is
 * <b>advisory</b>: the installer surfaces each violation as a loud {@link InstallReport} warning
 * (and the entry counts in {@code SOURCES.md}) rather than refusing the install, which — with no
 * override mechanism — could block a legitimately leaner future or regional release outright.</li>
 * </ol>
 *
 * <p>
 * <b>Why the second check has to exist.</b> Every loop of the case contract is driven by the
 * sections the document happens to contain, so a document with <em>no</em> sections — or a
 * misspelled one, or {@code "levels": {}} — yields <b>zero violations</b>. It is a consistency
 * audit, and an empty file is trivially consistent. A converter bug that emitted nothing would sail
 * through it. Completeness asks the other question: does this dictionary carry the levels, pair
 * registries and attribute maps the shipped corpus actually reads for its type?
 * </p>
 */
public final class HouseFormatValidator
{

    /**
     * What each dictionary type must carry for the rules that read it to answer.
     *
     * @param levels
     *            level names the corpus names in {@code dictionary_term_type}
     * @param pairs
     *            {@code pairs} registries that must be present and non-empty
     * @param attributes
     *            {@code attributes} maps that must be present and non-empty
     * @param needsHierarchy
     *            whether a non-empty {@code hierarchy} is required
     * @param floors
     *            minimum entry counts per section (keyed {@code levels.PT},
     *            {@code attributes.neoplasm}, …) — deliberately loose bounds a whole release always
     *            clears but a truncated raw file cannot; see {@link #cardinalityViolations} for the
     *            calibration
     */
    public record Requirement(Set<String> levels, Set<String> pairs, Set<String> attributes,
            boolean needsHierarchy, Map<String, Integer> floors)
    {

        /**
         * Immutable defensive copies, so neither a caller-retained collection nor one returned by
         * an accessor can mutate the requirement after construction (SpotBugs
         * {@code EI_EXPOSE_REP}).
         */
        public Requirement
        {
            levels = Set.copyOf(levels);
            pairs = Set.copyOf(pairs);
            attributes = Set.copyOf(attributes);
            floors = Map.copyOf(floors);
        }
    }

    /**
     * The census of what the 98 shipped dictionary rules demand, by type. Derived from the rule
     * corpus, not from the dummy fixtures — see {@code dictionaries/README.md} §1.3.
     *
     * <p>
     * ⚠ This describes a <b>real, installed</b> dictionary. The checked-in dummy fixtures
     * deliberately do not satisfy it — they are minimal test data. Completeness is therefore
     * applied to installer output and to a shipped bundle, never to the fixtures.
     * </p>
     */
    public static final Map<String, Requirement> CORPUS_REQUIREMENTS = corpusRequirements();

    private HouseFormatValidator()
    {
    }


    private static Map<String, Requirement> corpusRequirements()
    {
        Map<String, Requirement> m = new LinkedHashMap<>();
        m.put("meddra",
                new Requirement(
                        Set.of("PT", "PTCD", "LLT", "LLTCD", "HLT", "HLTCD", "HLGT", "HLGTCD",
                                "SOC", "SOCCD"),
                        Set.of(), Set.of(), true, Map.of("levels.PT", 10_000)));
        m.put("unii", new Requirement(Set.of("UNII", "SRS"), Set.of("unii"), Set.of(), false,
                Map.of("levels.UNII", 100_000)));
        m.put("snomed", new Requirement(Set.of("SNOMED", "SNOMEDCD"), Set.of("snomed"), Set.of(),
                false, Map.of("levels.SNOMEDCD", 1_000)));
        m.put("medrt", new Requirement(Set.of("MEDRT", "MEDRTCD"), Set.of("medrt"), Set.of(), false,
                Map.of("levels.MEDRTCD", 1_000)));
        // WHODrug carries no floor: no licensed distribution is available to calibrate one
        // defensibly, and a wrong floor would block every licensee's install.
        m.put("whodrug", new Requirement(Set.of("PT", "ATC", "ATCCD"), Set.of("whodrug"), Set.of(),
                false, Map.of()));
        // LOINC's pairs carry the display names the licence's incorporation clause requires; no
        // rule reads them, so this requirement is a compliance guard, not a functional one.
        m.put("loinc", new Requirement(Set.of("LOINC"), Set.of("loinc"), Set.of(), false,
                Map.of("levels.LOINC", 50_000)));
        m.put("neoplasm", new Requirement(Set.of(), Set.of(), Set.of("neoplasm"), false,
                Map.of("attributes.neoplasm", 250)));
        return Map.copyOf(m);
    }


    /**
     * The two <b>blocking</b> checks, in the order an installer should apply them. The advisory
     * third check, {@link #cardinalityViolations}, is deliberately not included — see the class
     * comment; the installer applies it separately as warnings.
     */
    public static List<String> validate(String aDict, JsonNode aRoot)
    {
        List<String> out = new ArrayList<>(caseContractViolations(aDict, aRoot));
        out.addAll(completenessViolations(aDict, aRoot));
        return out;
    }


    /**
     * Whether the document carries what its type's rules read.
     *
     * <p>
     * ⛔ The requirement is keyed off <b>the caller's declared type</b> ({@code aDict}), never the
     * document's own {@code type} field: every converter deliberately emits no {@code type} (the
     * installer stamps it), so keying off the artefact would make the check silently vacuous for
     * any caller that forgot the stamp — the population must come from the caller, not from the
     * thing under audit. A {@code type} field that <em>disagrees</em> with the caller is a
     * violation in its own right. An unknown caller type yields no requirement violations — a
     * bundle may legitimately carry a dictionary this build's corpus does not use.
     * </p>
     */
    public static List<String> completenessViolations(String aDict, JsonNode aRoot)
    {
        List<String> out = new ArrayList<>();
        String declared = aRoot.hasNonNull("type") ? aRoot.get("type").asText() : "";
        if (!declared.isEmpty() && !declared.equalsIgnoreCase(aDict))
        {
            out.add(aDict + ": the document declares type '" + declared
                    + "' but is being validated as '" + aDict
                    + "' — the stamp and the artefact disagree");
        }
        Requirement req = CORPUS_REQUIREMENTS.get(aDict.toLowerCase(Locale.ROOT));
        if (req == null)
        {
            return out;
        }
        for (String level : sorted(req.levels()))
        {
            if (!hasEntries(aRoot.path("levels").path(level)))
            {
                out.add(aDict + ": levels[" + level + "] is missing or empty — the rules that read "
                        + "it would answer false on every row");
            }
        }
        for (String registry : sorted(req.pairs()))
        {
            if (!hasEntries(aRoot.path("pairs").path(registry)))
            {
                out.add(aDict + ": pairs[" + registry + "] is missing or empty — its code/decode "
                        + "rules would report noViolation vacuously");
            }
        }
        for (String attribute : sorted(req.attributes()))
        {
            if (!hasEntries(aRoot.path("attributes").path(attribute)))
            {
                out.add(aDict + ": attributes[" + attribute + "] is missing or empty");
            }
        }
        if (req.needsHierarchy() && !hasEntries(aRoot.path("hierarchy")))
        {
            out.add(aDict + ": hierarchy is missing or empty — the hierarchy rules could never "
                    + "fire");
        }
        return out;
    }


    /**
     * Whether the required sections are plausibly a <b>whole release</b>, not a truncated one.
     *
     * <p>
     * {@code hasEntries} cannot tell a complete {@code pt.asc} from one cut short at row 200 by an
     * interrupted unzip — and the truncated one, once installed, makes every membership rule fire
     * on almost every conformant row. The floors are deliberately loose: a small fraction of what
     * the smallest lawful current release carries, so a legitimately leaner future or regional
     * release still installs while an obviously partial file is refused. Calibration (release sizes
     * as of 2026): MedDRA 27.0 ≈ 25 000 PTs → floor 10 000; UNII 4 Aug 2026 = 171 912 records →
     * floor 100 000; LOINC 2.80 ≈ 104 000 codes → floor 50 000; SNOMED GPS (the smallest lawful
     * distribution) ≈ 4 600 concepts → floor 1 000; MED-RT 2026 = 3 695 rows → floor 1 000; SEND CT
     * 2026-03-27 = 310 neoplasm terms → floor 250 (140 BENIGN + 170 MALIGNANT; a file missing
     * either class fails). WHODrug has no floor — no licensed distribution is available to
     * calibrate one.
     * </p>
     *
     * <p>
     * Each violation names the actual entry count, so the operator can see {@code levels.PT: 200}
     * and recognise the truncation instead of hunting for it. <b>Advisory, not blocking</b> — the
     * installer turns each violation into an {@link InstallReport} warning and still writes the
     * store; a heuristic bound must never hard-refuse a lawful release it was not calibrated on.
     * </p>
     */
    public static List<String> cardinalityViolations(String aDict, JsonNode aRoot)
    {
        List<String> out = new ArrayList<>();
        Requirement req = CORPUS_REQUIREMENTS.get(aDict.toLowerCase(Locale.ROOT));
        if (req == null)
        {
            return out;
        }
        for (Map.Entry<String, Integer> floor : req.floors().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList())
        {
            JsonNode section = aRoot;
            for (String step : floor.getKey().split("\\.", -1))
            {
                section = section.path(step);
            }
            int count = section.isObject() ? section.size() : 0;
            if (count < floor.getValue())
            {
                out.add(aDict + ": " + floor.getKey() + ": " + count + " entries — a whole "
                        + aDict.toLowerCase(Locale.ROOT) + " release carries at least "
                        + floor.getValue()
                        + "; the raw distribution is likely truncated or partial");
            }
        }
        return out;
    }


    private static List<String> sorted(Set<String> aSet)
    {
        return aSet.stream().sorted().toList();
    }


    private static boolean hasEntries(JsonNode aNode)
    {
        return aNode.isObject() && !aNode.isEmpty();
    }


    /**
     * The preferred-case contract, in three clauses:
     *
     * <ol>
     * <li><b>levels</b> — each key is exactly the case-fold of its own value, and a term has ONE
     * preferred form <em>within each level</em>. Two levels may legitimately disagree on case —
     * WHODrug writes drug names upper-case but ATC texts mixed-case, so {@code PT}'s
     * {@code IBUPROFEN} and {@code ATC}'s {@code ibuprofen} must coexist. The engine never crosses
     * levels ({@code isValidTerm}/{@code caseMatches} consult only the level named by
     * {@code dictionary_term_type}, and all 98 rules name one), so a cross-level clause would be a
     * validator-only invariant with no engine backing — and it made WHODrug uninstallable for every
     * licensee (owner ruling).</li>
     * <li><b>hierarchy</b> — every key and every ancestor resolves to a level term, written exactly
     * as some level publishes it;</li>
     * <li><b>pairs / attributes</b> — any code, decode, key or value that <em>is</em> a level term
     * is written as some level publishes it (a decode that is not a term of this dictionary — the
     * NEOPLASM {@code BENIGN}/{@code MALIGNANT} classes, a LOINC long name — is
     * unconstrained).</li>
     * </ol>
     *
     * @return one message per deviation; empty when the document is consistent
     */
    public static List<String> caseContractViolations(String aDict, JsonNode aRoot)
    {
        List<String> out = new ArrayList<>();
        Map<String, Set<String>> forms = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> level : fields(aRoot.path("levels")))
        {
            Map<String, String> levelPreferred = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : fields(level.getValue()))
            {
                String key = e.getKey();
                String pref = e.getValue().asText();
                if (!key.equals(upper(pref)))
                {
                    out.add(aDict + ": levels[" + level.getKey() + "] key '" + key
                            + "' is not the case-fold of its preferred form '" + pref + "'");
                }
                String prior = levelPreferred.putIfAbsent(upper(pref), pref);
                if (prior != null && !prior.equals(pref))
                {
                    out.add(aDict + ": levels[" + level.getKey() + "] carries two preferred forms "
                            + "for term '" + upper(pref) + "': '" + prior + "' and '" + pref + "'");
                }
                forms.computeIfAbsent(upper(pref), _ -> new LinkedHashSet<>()).add(pref);
            }
        }
        for (Map.Entry<String, JsonNode> e : fields(aRoot.path("hierarchy")))
        {
            checkTerm(out, aDict, "hierarchy key", forms, e.getKey(), true);
            for (JsonNode ancestor : e.getValue())
            {
                checkTerm(out, aDict, "hierarchy ancestor of '" + e.getKey() + "'", forms,
                        ancestor.asText(), true);
            }
        }
        for (String section : new String[]
        {
                "pairs", "attributes"
        })
        {
            for (Map.Entry<String, JsonNode> map : fields(aRoot.path(section)))
            {
                for (Map.Entry<String, JsonNode> e : fields(map.getValue()))
                {
                    String where = section + "[" + map.getKey() + "] ";
                    checkTerm(out, aDict, where + "key", forms, e.getKey(), false);
                    checkTerm(out, aDict, where + "value", forms, e.getValue().asText(), false);
                }
            }
        }
        return out;
    }


    private static void checkTerm(List<String> aOut, String aDict, String aWhere,
            Map<String, Set<String>> aForms, String aText, boolean aMustResolve)
    {
        if (aText.isEmpty())
        {
            return;
        }
        Set<String> forms = aForms.get(upper(aText));
        if (forms == null)
        {
            if (aMustResolve)
            {
                aOut.add(aDict + ": " + aWhere + " '" + aText + "' is not a term in any level");
            }
            return;
        }
        if (!forms.contains(aText))
        {
            String published = forms.size() == 1
                    ? "the levels' preferred form '" + forms.iterator().next() + "'"
                    : "any of the levels' preferred forms " + forms;
            aOut.add(aDict + ": " + aWhere + " '" + aText + "' is not written in " + published);
        }
    }


    private static Iterable<Map.Entry<String, JsonNode>> fields(JsonNode aNode)
    {
        return aNode.isObject() ? aNode.properties()
                : Collections.<String, JsonNode> emptyMap().entrySet();
    }


    private static String upper(String aText)
    {
        return aText.toUpperCase(Locale.ROOT);
    }

}
