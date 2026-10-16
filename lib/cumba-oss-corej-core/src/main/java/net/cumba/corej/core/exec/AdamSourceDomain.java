package net.cumba.corej.core.exec;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * {@code PLAN-library-template-label-resolution} round-2 ruling R2-1, as
 * {@code PLAN-occds-source-domain-by-ig} D1–D5 restate it after ADaMIG-OCCDS — the two predicates
 * the ADaM core-variable walks ({@code required_variables()}, {@code expected_variables()},
 * {@code get_column_order_from_library()}) apply to an <b>occurrence</b> structure's published
 * names before demanding them of a dataset.
 *
 * <h2>(i) The dataset's SDTM source domains — {@link #domainsOf}</h2>
 *
 * <p>
 * An OCCDS structure publishes its topic and identifier variables as {@code --} templates
 * ({@code --SEQ}, {@code --TERM}: <i>"copied from the SDTM domain XX"</i>), which only the dataset
 * can fill in. ADaMIG-OCCDS v1.1 §3 says where the domain comes from: <i>"The 2-letter prefix of the
 * variable name is the SDTM domain name"</i> — the prefix of the occurrence variables the dataset
 * carries, never its name (§4.2: <i>"ADAE is not a required dataset name"</i>). The evidence is a
 * column named a two-letter prefix plus one of {@link #EVIDENCE_SUFFIXES} — the domain's
 * {@code --SEQ} or its topic variable ({@code AESEQ}, {@code AETERM}, {@code CMTRT}; letter case
 * ignored, the prefix upper-cased) — and the dataset's domains are the set of those prefixes (D1,
 * as narrowed by owner ruling MED-1 (a)), read with the IG's own precedence
 * ({@code PLAN-bugfix-engine-define} BD2 (a), owner 2026-10-15 — ADaMIG-OCCDS v1.1 §3: <i>"--SEQ
 * provides all of the row identifier … The 2-letter prefix of the variable name is the SDTM domain
 * name"</i>, and a multi-domain dataset adds <i>"the --SEQ variable from each domain … or the
 * variables SRCDOM and SRCSEQ"</i>): exactly <b>one</b> {@code --SEQ} prefix and no {@code SRCDOM}
 * ⇒ that is the one domain, whatever topic columns are copied beside it ({@code ADAE} with
 * {@code AESEQ}, {@code AETERM} and a copied {@code EXTRT} is AE); <b>two or more</b> {@code --SEQ}
 * prefixes <b>or</b> an {@code SRCDOM} column ⇒ multi-domain, with every evidence prefix as its
 * domains; no {@code --SEQ} and no {@code SRCDOM} ⇒ the topic prefixes decide (several ⇒
 * multi-domain — the named residual: an {@code ADAE} with no {@code AESEQ} and a copied
 * {@code EXTRT} stays multi-domain, there is no IG anchor to break the tie):
 * </p>
 * <ul>
 * <li><b>one</b> domain — a {@code --} name resolves to it and is demanded, present or not: an
 * {@code ADAE} carrying {@code AETERM} but no {@code AESEQ} is asked for {@code AESEQ} (D2;
 * {@link #singleDomainOf}). Except the class-bound topic templates ({@code PLAN-bugfix-engine-define}
 * BD4 (a) with BD8 (a), owner 2026-10-16): {@code --TERM} is the topic of the Events class only and
 * {@code --TRT} of Interventions only (Findings About uses {@code --TESTCD} / {@code --OBJ}), and
 * the evidence suffix that named the domain tells its class — a domain evidenced by
 * {@code <DD>TRT} is Interventions, so {@code --TERM} is not demanded of it ({@code ADCM} with
 * {@code CMTRT} is not asked for {@code CMTERM}, which SDTM CM does not define); by {@code <DD>TERM}
 * Events, so {@code --TRT} is not; by {@code <DD>SEQ} alone it has no class and both templates are
 * demanded as before ({@link #observationClassOf}, {@link #topicTemplateApplies});</li>
 * <li><b>several</b> — a multi-domain OCCDS, for which the IG offers {@code --SEQ} per domain or
 * {@code SRCDOM} / {@code SRCSEQ}: no {@code --} name is demanded (D3);</li>
 * <li><b>none</b> — the structure came from a {@code def:Class} or the library's declaration, not
 * from the columns: no {@code --} name is demanded (D4), since the engine cannot name the concrete
 * variable, so it cannot say it is absent.</li>
 * </ul>
 * <p>
 * The suffix set (D5, measured 2026-10-04 on our store's {@code adam-occds-1-0} / {@code -1-1},
 * {@code adam-adae-1-0}, {@code adam-nca-1-0} and the TIG's ADaM leg) is the row identifier
 * {@code --SEQ} the IG names plus the occurrence topic variables {@code --TERM} and {@code --TRT}.
 * No ADaMIG (ADSL, BDS), {@code adam-tte}, {@code adam-md} or {@code adam-poppk} name matches it.
 * </p>
 * <p>
 * <b>{@code --DECOD} is not evidence</b> (owner ruling MED-1, 2026-10-04, option (a): a domain
 * needs its {@code --SEQ} or its topic variable, {@code --TERM} / {@code --TRT}; a
 * {@code --DECOD} alone adds no domain). ADSL's {@code DCDECOD} and {@code DSDECOD} are routinely copied onto an
 * occurrence dataset; read as evidence they made an {@code ADAE} multi-domain ({@code {AE, DC}}, so
 * D3 demanded no {@code --} name and a missing {@code AESEQ} went unreported), or, on an
 * {@code ADAE} carrying no AE occurrence variable, a single foreign domain that dropped every
 * carried AE name. Whether a {@code --DECOD} still counts once a {@code --SEQ} / {@code --TERM} /
 * {@code --TRT} of the same domain is present is moot: that column already names the domain, so a
 * {@code DECOD} adds nothing either way — hence the plain suffix set, with no pairing rule. A
 * consequence, recorded: an occurrence dataset whose only occurrence column is a {@code --DECOD}
 * ({@code AEDECOD} with no {@code AETERM}, no {@code AESEQ}) gives no evidence, so its {@code --}
 * names are not demanded (D4) — the H1 rule below still keeps every concrete carried name.
 * </p>
 * <p>
 * {@code --BODSYS} and {@code --CAT} are left out too: a dataset carrying them but none of the
 * three is already missing its required topic variable, and {@code CAT} is a qualifier suffix of
 * every SDTM domain, so a copied grouping column would read as a second domain. Timing suffixes are
 * never evidence ({@code RFSTDTC} would read as {@code RF}). The exact two-letter shape is what
 * keeps the ADaM {@code ASEQ}, {@code SRCSEQ} and {@code RECSEQ} out.
 * </p>
 * <p>
 * ⚠ Deliberately not {@code AdamDataStructureDetector.OCCDS_SUFFIXES} ({@code TERM}, {@code TRT},
 * {@code DECOD}, matched as a bare suffix of any length): that set decides whether a dataset
 * <em>is</em> occurrence-shaped and changing it would move structure detection; this one names a
 * <em>domain</em>, so it needs the exact {@code <DD><suffix>} shape, adds the {@code SEQ}
 * identifier and leaves {@code DECOD} out.
 * </p>
 *
 * <h2>(ii) A published name that carries a domain's prefix — {@link #carriedDomainOf}</h2>
 *
 * <p>
 * The base OCCDS structures mix the AE and CM conventions: {@code adam-occds-1-0} publishes
 * {@code CMTRT} and {@code AESER} as {@code Req} beside the {@code --} templates. Our store's own
 * data says which SDTM variable such a name copies — the published {@code description} opens with
 * the source token {@code <DOMAIN>.<VARIABLE>} ({@code CM.CMTRT}, {@code AE.AESER};
 * {@code XX.--SEQ} for a template, {@code DM.ARM} for an ADSL variable). <b>The predicate:</b> a
 * published variable carries domain {@code DD}'s prefix when its description <em>opens</em> with a
 * source token naming a concrete two-letter domain {@code DD} (never the {@code XX} placeholder)
 * <em>and</em> the published name itself starts with {@code DD}. The second half is what keeps
 * {@code DM.ARM}, {@code DM.STUDYID} and {@code PC.PCSTRESN} (published as {@code VOLUME})
 * demanded: they copy another domain's value, they do not carry its prefix. The token must open the
 * description because a later one is a derivation input, not the variable copied ({@code AESEVN}:
 * <i>"Code AE.AESEV to numeric"</i>; {@code adam-nca-1-0}'s {@code Req} {@code PCRFTDT}: <i>"…
 * Based on PC.PCRFTDTC"</i>, which stays demanded).
 * </p>
 *
 * <p>
 * Measured 2026-10-04 on every ADaM product of the store (and the TIG's ADaM leg): the predicate
 * holds for 15 names of {@code adam-occds-1-0}'s {@code OCCDS} ({@code CMTRT} and {@code AESER} the
 * two {@code Req}; {@code CMDECOD} {@code Cond}; {@code CMCLAS}, {@code CMCLASCD}, {@code CMSTAT},
 * {@code CMINDC}, {@code CMDOSE}, {@code CMDOSFRM}, {@code CMDOSRGM}, {@code CMROUTE},
 * {@code AESEV}, {@code AEREL}, {@code AETOXGR}, {@code AEACN} {@code Perm}); for the four
 * {@code CM} dictionary names of {@code adam-occds-1-1} ({@code CMTRT} {@code Req} in
 * {@code OCCDS}, {@code Not Used} in its {@code AE} subclass) and of the TIG's {@code OCCDS}; for
 * the 22 {@code AE} names of {@code adam-adae-1-0}'s {@code ADAE}; and for {@code PCSEQ}
 * ({@code Cond}) of {@code adam-nca-1-0}. No ADaMIG (1.0–1.3), {@code adam-tte}, {@code adam-md} or
 * {@code adam-poppk} name satisfies it, so ADSL, BDS and TTE walks are unchanged. Such a name is
 * not demanded only when the dataset's occurrence variables give evidence of a <b>different</b>
 * domain ({@link #carriesAnotherDomain}): the domain set is non-empty and does not contain
 * {@code DD} — the <em>same</em> set predicate (i) resolves against (T16: an OCCDS with
 * {@code CMSEQ} and a copied {@code AETERM} is {CM}, so a published {@code AESER} is dropped where
 * it was demanded before the {@code --SEQ} precedence; a multi-domain set keeps every evidenced
 * domain's carried names). An <b>empty</b> set never drops it — the name is concrete, so an {@code ADAE} with no
 * occurrence variable at all (its structure declared by the define) is still asked for
 * {@code AESEQ}, {@code AETERM}, {@code AEDECOD}: a missing required column must not disarm its own
 * requirement (round-2 T2 review H1; {@code PLAN-occds-source-domain-by-ig} review H1).
 * </p>
 */
public final class AdamSourceDomain
{

    /**
     * D5 as narrowed by owner ruling MED-1 (a): the suffixes whose {@code <DD><suffix>} column names
     * the source domain {@code DD} — the row identifier {@code --SEQ} and the topic variables
     * {@code --TERM}, {@code --TRT}; never {@code --DECOD} (see the class javadoc for the
     * measurement, the ruling and why this is not {@code AdamDataStructureDetector.OCCDS_SUFFIXES}).
     */
    static final List<String> EVIDENCE_SUFFIXES = List.of("SEQ", "TERM", "TRT");

    /** A two-letter prefix plus one evidence suffix, the whole column name, letter case ignored. */
    private static final Pattern EVIDENCE = Pattern.compile(
            "^([A-Z]{2})(" + String.join("|", EVIDENCE_SUFFIXES) + ")$",
            Pattern.CASE_INSENSITIVE);

    /** The source token a published description opens with: {@code CM.CMTRT}, {@code XX.--SEQ}. */
    private static final Pattern SOURCE_TOKEN = Pattern.compile("^\\s*([A-Z]{2})\\.\\S");

    /** The domain placeholder of a {@code --} template's source token. */
    private static final String PLACEHOLDER = "XX";

    private AdamSourceDomain()
    {
    }


    /** The {@code SRCDOM} column of a multi-domain OCCDS dataset (ADaMIG-OCCDS v1.1 §3). */
    private static final String SRCDOM = "SRCDOM";

    /** The SDTM observation class whose topic variable is {@code --TERM}. */
    public static final String EVENTS = "EVENTS";

    /** The SDTM observation class whose topic variable is {@code --TRT}. */
    public static final String INTERVENTIONS = "INTERVENTIONS";

    /**
     * The dataset's source domains ({@link #domainsOf}): the domain codes, whether the dataset is
     * multi-domain (D3 — not a set size: {@code AESEQ} + {@code SRCDOM} has one prefix and is
     * multi-domain all the same, confirmation look MED-1), and the evidence suffixes each domain
     * was named by (BD8 (a): the class source).
     *
     * @param domains
     *            the domain codes, sorted
     * @param multi
     *            whether the dataset is multi-domain (D3): no {@code --} name is demanded
     * @param evidence
     *            domain code → the evidence suffixes ({@code SEQ}, {@code TERM}, {@code TRT}) the
     *            dataset carries for it
     */
    public record SourceDomains(SortedSet<String> domains, boolean multi,
            Map<String, SortedSet<String>> evidence)
    {

        /** Defensive, unmodifiable copies. */
        public SourceDomains
        {
            domains = Collections.unmodifiableSortedSet(new TreeSet<>(domains));
            Map<String, SortedSet<String>> copy = new TreeMap<>();
            evidence.forEach((d, e) -> copy.put(d, Collections.unmodifiableSortedSet(new TreeSet<>(e))));
            evidence = Collections.unmodifiableMap(copy);
        }
    }


    /**
     * The dataset's SDTM source domains (D1, with BD2 (a)'s {@code --SEQ} precedence): the
     * upper-cased two-letter prefix of every column named {@code <DD>SEQ}, {@code <DD>TERM} or
     * {@code <DD>TRT} (letter case ignored), read as the class javadoc says — one {@code --SEQ}
     * prefix without {@code SRCDOM} is the one domain; two or more {@code --SEQ} prefixes or an
     * {@code SRCDOM} column make the dataset multi-domain over every evidence prefix; without any
     * {@code --SEQ} the topic prefixes decide. The dataset name is not read, nor a kept SDTM
     * {@code DOMAIN} cell, nor a {@code <DD>DECOD} column (MED-1 (a)).
     *
     * @param aTable
     *            the dataset
     * @return the domains, the multi-domain verdict and the evidence per domain; empty domains when
     *         the dataset carries no occurrence variable
     */
    public static SourceDomains domainsOf(IDataTable aTable)
    {
        Map<String, SortedSet<String>> evidence = new TreeMap<>();
        SortedSet<String> seqPrefixes = new TreeSet<>();
        boolean srcdom = false;
        for (String column : AdamStructureContext.columnNamesOf(aTable.getMetaData()))
        {
            if (column == null)
            {
                continue;
            }
            if (SRCDOM.equalsIgnoreCase(column))
            {
                srcdom = true;
                continue;
            }
            Matcher m = EVIDENCE.matcher(column);
            if (m.matches())
            {
                String domain = m.group(1).toUpperCase(Locale.ROOT);
                String suffix = m.group(2).toUpperCase(Locale.ROOT);
                evidence.computeIfAbsent(domain, d -> new TreeSet<>()).add(suffix);
                if ("SEQ".equals(suffix))
                {
                    seqPrefixes.add(domain);
                }
            }
        }
        if (seqPrefixes.size() == 1 && !srcdom)
        {
            // BD2 (a): the one --SEQ names the one domain; copied topic columns are left out
            String domain = seqPrefixes.first();
            return new SourceDomains(new TreeSet<>(Set.of(domain)), false,
                    Map.of(domain, evidence.get(domain)));
        }
        SortedSet<String> domains = new TreeSet<>(evidence.keySet());
        boolean multi = seqPrefixes.size() >= 2 || srcdom || domains.size() > 1;
        return new SourceDomains(domains, multi, evidence);
    }


    /**
     * The domain a {@code --} name resolves to: the only one of {@code aDomains} when the dataset is
     * not multi-domain (D2), or {@code null} when it is (D3) or when there is none (D4) — then no
     * {@code --} name is demanded.
     *
     * @param aDomains
     *            the dataset's domains ({@link #domainsOf})
     * @return the single domain code ({@code AE}), or {@code null}
     */
    public static @Nullable String singleDomainOf(SourceDomains aDomains)
    {
        return !aDomains.multi() && aDomains.domains().size() == 1 ? aDomains.domains().first()
                : null;
    }


    /**
     * The SDTM observation class a source domain's evidence names (BD8 (a), owner 2026-10-16):
     * {@link #INTERVENTIONS} when the dataset carries {@code <DD>TRT} and no {@code <DD>TERM},
     * {@link #EVENTS} when {@code <DD>TERM} and no {@code <DD>TRT}, else {@code null} — a domain
     * evidenced by {@code <DD>SEQ} alone (or, theoretically, by both topic variables) has no class
     * here and keeps today's answer (both templates demanded: the pinned gap for that case).
     *
     * @param aDomains
     *            the dataset's domains ({@link #domainsOf})
     * @param aDomain
     *            a domain code of the set
     * @return the class, or {@code null}
     */
    public static @Nullable String observationClassOf(SourceDomains aDomains, String aDomain)
    {
        SortedSet<String> suffixes = aDomains.evidence().get(aDomain);
        if (suffixes == null)
        {
            return null;
        }
        boolean trt = suffixes.contains("TRT");
        boolean term = suffixes.contains("TERM");
        if (trt && !term)
        {
            return INTERVENTIONS;
        }
        return term && !trt ? EVENTS : null;
    }


    /**
     * Whether a published {@code --} template applies to a source domain of the given class (BD4
     * (a)): {@code --TERM} is the topic variable of Events only, {@code --TRT} of Interventions
     * only, so the other class's topic template is not demanded of the domain; every other template,
     * and either template of a domain with no class, applies.
     *
     * @param aTemplate
     *            the published name ({@code --TERM})
     * @param aObservationClass
     *            {@link #observationClassOf}, may be {@code null}
     * @return whether the template is resolved and demanded
     */
    public static boolean topicTemplateApplies(String aTemplate, @Nullable String aObservationClass)
    {
        if (aObservationClass == null)
        {
            return true;
        }
        if ("--TERM".equalsIgnoreCase(aTemplate))
        {
            return !INTERVENTIONS.equals(aObservationClass);
        }
        return !"--TRT".equalsIgnoreCase(aTemplate) || !EVENTS.equals(aObservationClass);
    }


    /**
     * Whether a published name carrying domain {@code aCarried}'s prefix ({@link #carriedDomainOf})
     * is evidence of a <b>different</b> domain than the dataset's — predicate (ii)'s drop
     * condition: the dataset's domains name at least one domain, and not {@code aCarried}. With no
     * evidence at all the name stays demanded, so a missing required column cannot disarm its own
     * requirement. The same domain set predicate (i) resolves against (T16).
     *
     * @param aDomains
     *            the dataset's domains ({@link #domainsOf})
     * @param aCarried
     *            the carried domain code ({@code CM})
     * @return {@code true} when the name is to be dropped
     */
    public static boolean carriesAnotherDomain(SourceDomains aDomains, String aCarried)
    {
        return !aDomains.domains().isEmpty() && !aDomains.domains().contains(aCarried);
    }


    /**
     * The SDTM domain whose prefix a published variable carries (predicate (ii) above), or
     * {@code null} when it carries none: no description, no source token, the {@code XX}
     * placeholder, or a name that does not start with the token's domain.
     *
     * @param aPublishedName
     *            the name as the Library publishes it ({@code CMTRT})
     * @param aDescription
     *            the published description attribute, or {@code null}
     * @return the carried domain code ({@code CM}), or {@code null}
     */
    public static @Nullable String carriedDomainOf(String aPublishedName,
            @Nullable String aDescription)
    {
        if (aDescription == null)
        {
            return null;
        }
        Matcher m = SOURCE_TOKEN.matcher(aDescription);
        if (!m.find())
        {
            return null;
        }
        String domain = m.group(1);
        if (PLACEHOLDER.equals(domain)
                || !aPublishedName.toUpperCase(Locale.ROOT).startsWith(domain))
        {
            return null;
        }
        return domain;
    }
}
