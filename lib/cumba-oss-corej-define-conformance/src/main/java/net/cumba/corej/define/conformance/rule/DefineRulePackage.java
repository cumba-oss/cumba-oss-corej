package net.cumba.corej.define.conformance.rule;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/**
 * One generated Define-XML rule package: every rule of a single {@code (family, version)} pair,
 * keyed by {@code Rule_Id}.
 *
 * <p>
 * This record is the <b>single definition of the package format</b>. The offline generator writes
 * it and {@link RuleRepository} reads it, both through Jackson with the same annotations, so the
 * two cannot drift apart. That matters more than it looks: {@link ConformanceRule}'s own fields
 * carry explicit {@code @JsonProperty} names ({@code Rule_Id}, {@code Applicable_Versions}, …)
 * while everything inside {@link CheckDefinition} uses raw record-component names ({@code kind},
 * {@code className}, {@code target}, …). Anything that re-implements the format by hand, or applies
 * a uniform naming strategy over it, silently breaks custom-check resolution at evaluation time
 * rather than at load.
 * </p>
 *
 * <p>
 * A rule that applies to both Define-XML versions is published in <b>both</b> of its family's
 * packages. That duplication is deliberate and mirrors the CDISC data-rule corpus, where a rule
 * ships in every standard/version package it applies to. It is why exactly one version's package
 * may be loaded at a time — two would collide on {@code Rule_Id}.
 * </p>
 *
 * @param family
 *            which source sheet these rules mirror
 * @param version
 *            the Define-XML version the package is scoped to, e.g. {@code 2.1}
 * @param rules
 *            the rules, keyed by {@code Rule_Id}, in ascending id order
 */
public record DefineRulePackage(@JsonProperty("family") RuleSet family,
        @JsonProperty("version") String version,
        @JsonProperty("rules") SequencedMap<String, ConformanceRule> rules)
{

    public DefineRulePackage
    {
        // Unmodifiable, not merely copied: the defensive copy on the way in was pointless while
        // the accessor handed the same mutable map straight back out (SpotBugs EI_EXPOSE_REP).
        // A package is a value — a caller must not be able to add or drop rules in one.
        //
        // ⚠ Deliberately NOT Map.copyOf, which SpotBugs would recognise and which would let the
        // suppression in spotbugs_ignore.xml go: Map.copyOf is UNORDERED, and this map must stay
        // insertion-ordered. The generator inserts in ascending Rule_Id order and
        // DefineRuleCorpusRoundTripTest pins the serialized bytes, so losing the order would
        // rewrite every shipped package.
        rules = Collections.unmodifiableSequencedMap(
                rules == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rules));
    }


    /** The rules in manifest order, detached from their keys. */
    public List<ConformanceRule> ruleList()
    {
        return List.copyOf(rules.values());
    }


    /** Builds a package from a rule list, keyed and ordered by {@code Rule_Id}. */
    public static DefineRulePackage of(RuleSet aFamily, String aVersion,
            List<ConformanceRule> aRules)
    {
        SequencedMap<String, ConformanceRule> keyed = new LinkedHashMap<>();
        aRules.stream().sorted((a, b) -> a.ruleId().compareTo(b.ruleId()))
                .forEach(r -> keyed.put(r.ruleId(), r));
        return new DefineRulePackage(aFamily, aVersion, keyed);
    }

}
