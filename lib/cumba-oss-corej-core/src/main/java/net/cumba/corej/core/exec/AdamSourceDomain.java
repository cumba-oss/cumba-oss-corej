package net.cumba.corej.core.exec;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * {@code PLAN-library-template-label-resolution} round-2 ruling R2-1 — the two predicates the ADaM
 * core-variable walks ({@code required_variables()}, {@code expected_variables()},
 * {@code get_column_order_from_library()}) apply to an <b>occurrence</b> structure's published
 * names before demanding them of a dataset.
 *
 * <h2>(i) The dataset's SDTM source domain — {@link #of}</h2>
 *
 * <p>
 * An OCCDS structure publishes its topic and identifier variables as {@code --} templates
 * ({@code --SEQ}, {@code --TERM}: <i>"copied from the SDTM domain XX"</i>), which only the dataset
 * can fill in. The ADaM naming convention {@code AD<domain>} ({@code ADAE} → {@code AE},
 * {@code ADCM} → {@code CM}) gives the candidate, and the dataset <b>confirms</b> it by carrying
 * the domain's sequence variable {@code <domain>SEQ} — the one SDTM identifier every occurrence
 * dataset copies, and the column a {@code --SEQ} template resolves to. With no confirmed domain a
 * {@code --} name is <b>not demanded</b> (never reported missing as {@code --SEQ}): the engine
 * cannot name the concrete variable, so it cannot say it is absent.
 * </p>
 *
 * <h2>(ii) A published name that carries another domain's prefix — {@link #carriedDomainOf}</h2>
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
 * demanded only of a dataset whose confirmed source domain is {@code DD}; of a dataset of another
 * domain, or of one with no confirmed domain, it is not — as in (i), a demand the engine cannot tie
 * to the dataset is no demand.
 * </p>
 */
public final class AdamSourceDomain
{

    /** {@code AD} + a two-letter SDTM domain code, the ADaM naming convention for a copy. */
    private static final Pattern AD_DOMAIN_NAME = Pattern.compile("^AD([A-Z]{2})");

    /** The source token a published description opens with: {@code CM.CMTRT}, {@code XX.--SEQ}. */
    private static final Pattern SOURCE_TOKEN = Pattern.compile("^\\s*([A-Z]{2})\\.\\S");

    /** The domain placeholder of a {@code --} template's source token. */
    private static final String PLACEHOLDER = "XX";

    private static final String SEQ = "SEQ";

    private AdamSourceDomain()
    {
    }


    /**
     * The dataset's confirmed SDTM source domain: the two letters after {@code AD} in its
     * <b>name</b> (upper-cased), confirmed by a column named {@code <domain>SEQ} (letter case
     * ignored); {@code null} when the name is not {@code AD<domain>} or the sequence column is
     * absent. The name, never a {@code DOMAIN} cell: an ADaM dataset that keeps SDTM's
     * {@code DOMAIN} column would otherwise answer {@code AE} for {@code ADAE} and match no
     * {@code AD<domain>} name.
     *
     * @param aTable
     *            the dataset
     * @return the confirmed domain code ({@code AE}), or {@code null}
     */
    public static @Nullable String of(IDataTable aTable)
    {
        DataTableMeta meta = aTable.getMetaData();
        String datasetName = meta.getName();
        if (datasetName == null)
        {
            return null;
        }
        Matcher m = AD_DOMAIN_NAME.matcher(datasetName.toUpperCase(Locale.ROOT));
        if (!m.find())
        {
            return null;
        }
        String domain = m.group(1);
        String seq = domain + SEQ;
        for (String column : AdamStructureContext.columnNamesOf(meta))
        {
            if (seq.equalsIgnoreCase(column))
            {
                return domain;
            }
        }
        return null;
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
