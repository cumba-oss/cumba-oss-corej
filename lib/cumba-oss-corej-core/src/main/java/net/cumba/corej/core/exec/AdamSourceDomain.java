package net.cumba.corej.core.exec;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
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
 * column named a two-letter prefix plus one of {@link #EVIDENCE_SUFFIXES} ({@code AESEQ},
 * {@code AETERM}, {@code CMTRT}, {@code MHDECOD}; letter case ignored, the prefix upper-cased), and
 * the dataset's domains are the set of those prefixes (D1):
 * </p>
 * <ul>
 * <li><b>one</b> domain — a {@code --} name resolves to it and is demanded, present or not: an
 * {@code ADAE} carrying {@code AETERM} but no {@code AESEQ} is asked for {@code AESEQ} (D2;
 * {@link #singleDomainOf});</li>
 * <li><b>several</b> — a multi-domain OCCDS, for which the IG offers {@code --SEQ} per domain or
 * {@code SRCDOM} / {@code SRCSEQ}: no {@code --} name is demanded (D3);</li>
 * <li><b>none</b> — the structure came from a {@code def:Class} or the library's declaration, not
 * from the columns: no {@code --} name is demanded (D4), since the engine cannot name the concrete
 * variable, so it cannot say it is absent.</li>
 * </ul>
 * <p>
 * The suffix set (D5, measured 2026-10-04 on our store's {@code adam-occds-1-0} / {@code -1-1},
 * {@code adam-adae-1-0}, {@code adam-nca-1-0} and the TIG's ADaM leg) is the row identifier
 * {@code --SEQ} the IG names plus the occurrence topic / dictionary variables {@code --TERM},
 * {@code --TRT} and {@code --DECOD} — the ones every OCCDS product publishes as {@code Req} or
 * {@code Cond}, and the same evidence the structure heuristic reads. No ADaMIG (ADSL, BDS),
 * {@code adam-tte}, {@code adam-md} or {@code adam-poppk} name matches it. {@code --BODSYS} and
 * {@code --CAT} are left out: a dataset carrying them but none of the four is already missing its
 * required topic variable, and {@code CAT} is a qualifier suffix of every SDTM domain, so a copied
 * grouping column would read as a second domain. Timing suffixes are never evidence
 * ({@code RFSTDTC} would read as {@code RF}). The exact two-letter shape is what keeps the ADaM
 * {@code ASEQ}, {@code SRCSEQ} and {@code RECSEQ} out.
 * </p>
 * <p>
 * ⚠ Deliberately not {@code AdamDataStructureDetector.OCCDS_SUFFIXES} ({@code TERM}, {@code TRT},
 * {@code DECOD}, matched as a bare suffix of any length): that set decides whether a dataset
 * <em>is</em> occurrence-shaped and widening it would move structure detection; this one names a
 * <em>domain</em>, so it needs the exact {@code <DD><suffix>} shape and the {@code SEQ} identifier.
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
 * domain ({@link #carriesAnotherDomain}): the evidence set is non-empty and does not contain
 * {@code DD}. An <b>empty</b> set never drops it — the name is concrete, so an {@code ADAE} with no
 * occurrence variable at all (its structure declared by the define) is still asked for
 * {@code AESEQ}, {@code AETERM}, {@code AEDECOD}: a missing required column must not disarm its own
 * requirement (round-2 T2 review H1; {@code PLAN-occds-source-domain-by-ig} review H1).
 * </p>
 */
public final class AdamSourceDomain
{

    /**
     * D5: the suffixes whose {@code <DD><suffix>} column names the source domain {@code DD} — the
     * row identifier {@code --SEQ} and the topic / dictionary variables {@code --TERM},
     * {@code --TRT}, {@code --DECOD} (see the class javadoc for the measurement and for why this is
     * not {@code AdamDataStructureDetector.OCCDS_SUFFIXES}).
     */
    static final List<String> EVIDENCE_SUFFIXES = List.of("SEQ", "TERM", "TRT", "DECOD");

    /** A two-letter prefix plus one evidence suffix, the whole column name, letter case ignored. */
    private static final Pattern EVIDENCE = Pattern.compile(
            "^([A-Z]{2})(?:" + String.join("|", EVIDENCE_SUFFIXES) + ")$",
            Pattern.CASE_INSENSITIVE);

    /** The source token a published description opens with: {@code CM.CMTRT}, {@code XX.--SEQ}. */
    private static final Pattern SOURCE_TOKEN = Pattern.compile("^\\s*([A-Z]{2})\\.\\S");

    /** The domain placeholder of a {@code --} template's source token. */
    private static final String PLACEHOLDER = "XX";

    private AdamSourceDomain()
    {
    }


    /**
     * The dataset's SDTM source domains (D1): the upper-cased two-letter prefix of every column named
     * {@code <DD>SEQ}, {@code <DD>TERM}, {@code <DD>TRT} or {@code <DD>DECOD} (letter case ignored).
     * The dataset name is not read, nor a kept SDTM {@code DOMAIN} cell.
     *
     * @param aTable
     *            the dataset
     * @return the domain codes in sorted order; empty when the dataset carries no occurrence
     *         variable
     */
    public static SortedSet<String> domainsOf(IDataTable aTable)
    {
        SortedSet<String> domains = new TreeSet<>();
        for (String column : AdamStructureContext.columnNamesOf(aTable.getMetaData()))
        {
            if (column == null)
            {
                continue;
            }
            Matcher m = EVIDENCE.matcher(column);
            if (m.matches())
            {
                domains.add(m.group(1).toUpperCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSortedSet(domains);
    }


    /**
     * The domain a {@code --} name resolves to: the only one of {@code aDomains} (D2), or
     * {@code null} when there are several (D3) or none (D4) — then no {@code --} name is demanded.
     *
     * @param aDomains
     *            the dataset's domains ({@link #domainsOf})
     * @return the single domain code ({@code AE}), or {@code null}
     */
    public static @Nullable String singleDomainOf(Set<String> aDomains)
    {
        return aDomains.size() == 1 ? aDomains.iterator().next() : null;
    }


    /**
     * Whether a published name carrying domain {@code aCarried}'s prefix ({@link #carriedDomainOf})
     * is evidence of a <b>different</b> domain than the dataset's — predicate (ii)'s drop
     * condition: the dataset's occurrence variables name at least one domain, and not
     * {@code aCarried}. With no evidence at all the name stays demanded, so a missing required
     * column cannot disarm its own requirement.
     *
     * @param aDomains
     *            the dataset's domains ({@link #domainsOf})
     * @param aCarried
     *            the carried domain code ({@code CM})
     * @return {@code true} when the name is to be dropped
     */
    public static boolean carriesAnotherDomain(Set<String> aDomains, String aCarried)
    {
        return !aDomains.isEmpty() && !aDomains.contains(aCarried);
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
