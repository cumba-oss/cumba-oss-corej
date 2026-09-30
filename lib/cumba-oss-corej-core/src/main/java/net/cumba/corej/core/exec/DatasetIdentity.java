package net.cumba.corej.core.exec;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * The data-driven <b>identity</b> of a dataset and the {@code --} wildcard prefixes derived from
 * it: its CDISC domain code ({@link #domainPrefix}), the prefix that substitutes {@code --} in a
 * variable name ({@link #variableWildcardPrefix}, EC-36), its unsplit family name
 * ({@link #unsplitNameFromData}), a dataset-name wildcard resolved against it
 * ({@link #resolveWildcard}, Fix #33), a {@code --}-bearing variable template re-resolved per
 * dataset ({@link #resolveTemplate}), the member's SDTM domain ({@link #domainOfDataset}, J7) and
 * its column names ({@link #datasetColumnNames}).
 *
 * <p>
 * Runbook W8 ({@code PLAN-retire-operation-surface}): these statics lived on the retired operation
 * executor, whose operations were the first readers; every reader left is a registry function, the
 * rule runner, the specialiser, the scope machinery or a join resolver, none of which ever touched
 * an operation. Moved verbatim — the javadoc below is the original, its cross-references
 * re-pointed.
 * </p>
 */
public final class DatasetIdentity
{

    private DatasetIdentity()
    {
    }


    /**
     * Replaces the CDISC {@code --} wildcard with the target table's domain prefix. For example,
     * {@code "SUPP--"} becomes {@code "SUPPAE"} when the target table is named {@code "AE"}.
     * <p>
     * Fix #33: when the target table is itself a SUPP/SQAP primary (e.g. {@code "SUPPAE"}), the
     * {@code --} in patterns like {@code "SUPP--"} refers to the parent-domain prefix, not the full
     * primary name. Substituting with the full name would produce {@code "SUPPSUPPAE"} and the
     * downstream resolver would fail. Derive the parent prefix via
     * {@link SplitDatasetUtil#unsplitName(String)} applied to the {@code tableName.substring(4)}.
     * Non-SUPP/SQAP primaries retain the original substitution behaviour.
     * </p>
     */
    static @Nullable String resolveWildcard(@Nullable String domain, IDataTable table)
    {
        if (domain == null || !domain.contains("--"))
        {
            return domain;
        }
        String tableName = table.getMetaData().getName();
        if (tableName == null || tableName.isEmpty())
        {
            return domain;
        }
        String substitutionPrefix = tableName;
        if ((tableName.startsWith("SUPP") || tableName.startsWith("SQAP"))
                && tableName.length() > 4)
        {
            substitutionPrefix = SplitDatasetUtil.unsplitName(tableName.substring(4));
        }
        return domain.replace("--", substitutionPrefix);
    }


    /**
     * Returns the dataset's CDISC domain code (the row-0 {@code DOMAIN} value, else the unsplit
     * table name). This is the dataset's <em>identity</em>, used for {@code --} in an Operation's
     * {@code domain:} and for the injected {@code DOMAIN} value — <b>not</b> the variable-name
     * replacement, which is {@link #variableWildcardPrefix} (EC-36). It formerly claimed to mirror
     * Python's {@code wildcard_replacement}; that claim belongs to the sibling. Prefers the
     * first-row {@code DOMAIN} column value (authoritative for split datasets where the table name
     * carries a suffix); falls back to the unsplit table name via
     * {@link SplitDatasetUtil#unsplitName(String)} (e.g. {@code "LB1"} → {@code "LB"}).
     * <p>
     * Used by {@code --} template re-resolution (Fix #1 {@code variable_count}), library-metadata
     * operations (Fixes #2/#3), RELREC per-row {@code **} resolution (Fix #5), and the Child-match
     * pre-merger (Fix #6). Public (Phase 4, PLAN-extend-expression-engine) so
     * {@link net.cumba.corej.core.exec.DatasetRuleResolver} can derive the prefix for resolving
     * {@code --} placeholders in {@code Scope.Variables} entries at generation time with the same
     * semantics as execution-time resolution.
     * </p>
     *
     * @param table
     *            the dataset (must be non-null)
     * @return the domain prefix, or {@code ""} when no prefix can be derived
     */
    public static String domainPrefix(IDataTable table)
    {
        if (table == null)
        {
            return "";
        }
        String domainVal = firstRowValue(table, "DOMAIN");
        if (domainVal != null)
        {
            return domainVal;
        }
        String name = table.getMetaData().getName();
        if (name == null || name.isEmpty())
        {
            return "";
        }
        return SplitDatasetUtil.unsplitName(name);
    }


    /**
     * Python's {@code SDTMDatasetMetadata.ap_suffix}: the 2-character parent-domain suffix of an
     * Associated Persons dataset ({@code APMH} &rarr; {@code MH}), or {@code ""} when the dataset
     * is not an AP dataset.
     * <p>
     * The predicate mirrors Python's {@code is_ap} + {@code ap_suffix} pair exactly: an
     * {@code APID} column must be present ({@code "APID" in first_record}), the {@code DOMAIN}
     * value must be at least 4 characters and start with {@code AP}, and SUPP/SQ datasets return
     * {@code ""} unconditionally.
     * </p>
     * <p>
     * {@code ScopeClassLadder.classOf} computes the same Python {@code ap_suffix} to inherit an AP
     * dataset's class from its parent domain. The two are deliberately <em>not</em> shared: that
     * one gates on the {@code DOMAIN} <em>column</em> and reads an already-resolved CDISC domain,
     * this one gates on a non-empty row-0 {@code DOMAIN} <em>value</em>. Unifying them would change
     * which class an AP dataset inherits — a {@code Scope.Classes}-wide blast radius unrelated to
     * EC-36. If either is edited, re-check the other.
     * </p>
     */
    static String apSuffixOf(IDataTable table, @Nullable String domainCode)
    {
        if (domainCode == null || domainCode.length() < AP_DOMAIN_MIN_LENGTH
                || !domainCode.toUpperCase(Locale.ROOT).startsWith("AP")
                || isSuppOrSqName(domainCode))
        {
            return "";
        }
        // Python is_ap (non-supp) is exactly `"APID" in first_record`. Java additionally requires
        // the domain code to look like an AP code (the checks above) — a DELIBERATE deviation:
        // upstream chops the leading two characters off ANY >=4-character domain that happens to
        // carry an APID column, so `POOLDEF` + APID yields "OLDEF" there. Recorded in EC-36.
        // APID is looked up as a column name, so ignoring letter case (CIT §1): a SAS-exported
        // aplb carrying apid is an AP dataset like APLB carrying APID.
        return table.getMetaData().getColumnIndex("APID") >= 0 ? domainCode.substring(2) : "";
    }

    /**
     * Minimum {@code DOMAIN} length for an AP suffix to exist — Python's {@code len(domain) >= 4}.
     */
    private static final int AP_DOMAIN_MIN_LENGTH = 4;

    /** SUPP / SQ supplemental-qualifier datasets, by name prefix. */
    private static boolean isSuppOrSqName(String name)
    {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("SUPP") || upper.startsWith("SQ");
    }


    /**
     * Returns the prefix that substitutes {@code --} in a <em>variable name</em>, derived from the
     * caller-supplied {@code domainCode}.
     *
     * <p>
     * <b>The domain code stays the source of truth.</b> For every dataset except AP and SUPP/SQ
     * this returns {@code domainCode} unchanged, so EC-36 alters nothing outside the two families
     * it was scoped to fix. An earlier revision re-derived the prefix from the table's row-0
     * {@code DOMAIN} cell instead; that silently changed the answer for <em>every</em> dataset
     * whose {@code DOMAIN} value disagrees with its identity — precisely the corruption
     * {@code CDISC-CG0413} exists to detect — making rules resolve to columns that cannot exist and
     * report "no finding". It failed <em>open</em>, and
     * {@code SdtmAllRuleTest.CDISC_CG0028_invalid} caught it.
     * </p>
     *
     * <p>
     * ⚑ <b>The citation deliberately departs from the census.</b> This sentence used to name
     * {@code CORE-000015}, and {@code plans/findings/CENSUS-core-rule-disposition.tsv} maps that id
     * to {@code CDISC-CG0088} — so a census-driven rewrite would have written {@code CG0088} here.
     * It would be wrong: {@code CORE-000015} was
     * {@code not var_exists("--PRESP") and var_exists("--OCCUR")}, which has nothing to do with the
     * DOMAIN-versus-dataset-name corruption this paragraph describes. The rule that does is
     * {@code CORE-000598} ({@code prefix(dataset_name, 2) != DOMAIN}), whose census successor is
     * {@code CDISC-CG0413}. The old id was a mis-citation; the census faithfully carries it
     * forward, and the fix is to cite the right rule rather than the mapped one.
     * </p>
     *
     * <p>
     * The two deviations, mirroring Python's {@code wildcard_replacement}
     * ({@code ap_suffix or domain or ""}):
     * </p>
     * <ul>
     * <li><b>AP</b> — an AP dataset carries <em>parent-prefixed</em> variables ({@code APMH} holds
     * {@code MHTERM}, not {@code APMHTERM}), so the replacement is the AP suffix
     * ({@code domainCode.substring(2)}), gated on an {@code APID} column.</li>
     * <li><b>SUPP/SQ</b> — the replacement is {@code ""}, so {@code --QNAM} resolves to
     * {@code QNAM}, the column that actually exists. Contrast job (a): {@code --} in an Operation's
     * {@code domain:} is a <em>dataset-name</em> wildcard and keeps the full code (Fix
     * #59/#33).</li>
     * </ul>
     *
     * <p>
     * Returns {@code null} only when {@code domainCode} is itself {@code null} — a degraded or
     * synthetic context with no domain at all. Callers substitute nothing in that case, exactly as
     * they did before EC-36.
     * </p>
     *
     * @param table
     *            the dataset, consulted only for the {@code APID} column (may be {@code null})
     * @param domainCode
     *            the caller-supplied CDISC domain code — the same value used for job (a)
     * @return the variable-wildcard prefix, or {@code null} when {@code domainCode} is {@code null}
     */
    public static @Nullable String variableWildcardPrefix(@Nullable IDataTable table,
            @Nullable String domainCode)
    {
        if (domainCode == null)
        {
            return null;
        }
        if (isSuppOrSqName(domainCode))
        {
            return "";
        }
        if (table != null)
        {
            String apSuffix = apSuffixOf(table, domainCode);
            if (!apSuffix.isEmpty())
            {
                return apSuffix;
            }
        }
        return domainCode;
    }


    /**
     * Returns the canonical unsplit (base) name of a dataset, mirroring Python's
     * {@code SDTMDatasetMetadata.unsplit_name} — the <em>data-driven</em> split-detection key used
     * by scope matching and split-family dedup. Unlike {@link SplitDatasetUtil#unsplitName(String)}
     * (which guesses the base from the name alone), this reads the dataset's {@code DOMAIN} column
     * (row 0): a dataset named {@code FAAE} carrying {@code DOMAIN=FA} resolves to {@code FA}, so
     * it is correctly recognised as a split of FA. Resolution order, faithful to Python:
     * <ol>
     * <li>row-0 {@code DOMAIN} value, when present and non-empty (Python
     * {@code if self.domain});</li>
     * <li>for SUPP/SQ datasets with no {@code DOMAIN} value, the base is reconstructed as
     * {@code SUPP}/{@code SQ} + the row-0 {@code RDOMAIN} value; when {@code RDOMAIN} is absent the
     * raw name is returned (treated as not-split — this guards against Python's literal
     * {@code "SUPPNone"} quirk for a SUPP dataset with no resolvable parent);</li>
     * <li>otherwise the raw name (Python {@code return self.name}).</li>
     * </ol>
     * The final fallback is the raw name, <em>not</em>
     * {@link SplitDatasetUtil#unsplitName(String)}, so a rows-less / metadata-only dataset is
     * treated as not-split — matching Python, whose {@code unsplit_name} reduces to the name when
     * there is no {@code first_record}.
     *
     * @param table
     *            the dataset (must be non-null)
     * @return the data-driven unsplit name, or {@code ""} when no name is available
     */
    public static String unsplitNameFromData(IDataTable table)
    {
        if (table == null)
        {
            return "";
        }
        String name = table.getMetaData().getName();
        if (name == null)
        {
            name = "";
        }
        String domainVal = firstRowValue(table, "DOMAIN");
        if (domainVal != null)
        {
            return domainVal;
        }
        if (name.startsWith("SUPP") || name.startsWith("SQ"))
        {
            String rdomain = firstRowValue(table, "RDOMAIN");
            if (rdomain != null)
            {
                return (name.startsWith("SUPP") ? "SUPP" : "SQ") + rdomain;
            }
        }
        return name;
    }


    /**
     * Reads the row-0 value of {@code column} as a non-empty string, or {@code null} when the
     * column is absent, the table has no rows, or the value is missing/empty. Shared by
     * {@link #domainPrefix(IDataTable)} and {@link #unsplitNameFromData(IDataTable)} so both read
     * the data the same way.
     *
     * <p>
     * <b>Fix #370</b> widened this from {@code private} to {@code public}: the {@code SUPP--}/
     * {@code SQ--} tier of the {@code ds_*("LIBRARY")} accessor
     * ({@code ExprCompiler#readProviderLevel}) reads {@code RDOMAIN} to substitute the Library
     * {@code SUPPQUAL} label template, and a second row-0 reader is exactly how the
     * {@code DOMAIN}/{@code RDOMAIN} conventions drift apart. One definition, three callers.
     * </p>
     */
    public static @Nullable String firstRowValue(IDataTable table, String column)
    {
        DataTableMeta meta = table.getMetaData();
        int idx = meta.getColumnIndex(column);
        if (idx < 0 || table.getRowCount() <= 0)
        {
            return null;
        }
        IDataValue dv = table.getColumn(idx).getDataValue(0);
        if (dv.isMissingOrInvalid())
        {
            return null;
        }
        String val = dv.getValueAsString();
        return val == null || val.isEmpty() ? null : val;
    }


    /**
     * Re-resolves a {@code --}-bearing template against the given dataset's prefix. Returns the
     * template unchanged when it contains no {@code --}. Used by the study-wide
     * {@code variable_count("--LNKGRP")} function ({@link ScalarMetadataFunctions}), which iterates
     * every dataset and must resolve the template per iterated dataset rather than once at
     * rule-prep time.
     */
    static @Nullable String resolveTemplate(@Nullable String template, IDataTable table)
    {
        if (template == null || !template.contains("--"))
        {
            return template;
        }
        // EC-36: `--` in a variable-name template takes the variable prefix (Python's
        // wildcard_replacement), not the CDISC domain code. Null = unresolvable; the callers
        // already treat a null column name as "nothing to read from this dataset".
        String prefix = variableWildcardPrefix(table, domainPrefix(table));
        return prefix != null ? template.replace("--", prefix) : null;
    }


    /**
     * The dataset's column names in declaration order, in the dataset's own spelling — read by
     * {@code ScopeClassLadder.classOf} (the scope matcher's tier-3 sniff, and through it the SDTM
     * walks' class for a domain the run's IG does not define, {@code PLAN-custom-domain-model-walk}
     * S2), which hands the set to the class sniffer; the sniffer folds the case itself. Public
     * because {@code ScopeClassLadder} lives outside this package.
     */
    public static Set<String> datasetColumnNames(IDataTable table)
    {
        DataTableMeta meta = table.getMetaData();
        int n = meta.getColumnCount();
        Set<String> names = LinkedHashSet.newLinkedHashSet(n);
        for (int i = 0; i < n; i++)
        {
            names.add(meta.getColumn(i).getName());
        }
        return names;
    }


    /**
     * J7: the data-driven SDTM domain of a dataset member, mirroring Python's
     * {@code SDTMDatasetMetadata.domain}. {@code SUPP}/{@code SQ}/{@code RELREC} have no
     * {@code DOMAIN} column (&rarr; {@code null}); an {@code AP--} member's domain is its full
     * 4-char name (e.g. {@code APQS}); otherwise the authoritative source is the {@code DOMAIN}
     * cell ({@link net.cumba.corej.core.metadata.CdiscDomainResolver#cdiscDomainOf}) when the table
     * resolves, else the first two characters of the (unsplit) member name.
     *
     * @param memberName
     *            the dataset member name (e.g. {@code lbch})
     * @param resolver
     *            resolver used to read the table's {@code DOMAIN} cell
     * @return the domain, or {@code null} for a no-domain dataset
     */
    static @Nullable String domainOfDataset(String memberName, DatasetResolver resolver)
    {
        if (memberName == null || memberName.isEmpty())
        {
            return null;
        }
        String n = memberName.toUpperCase(Locale.ROOT);
        if (n.startsWith("SUPP") || n.startsWith("SQ") || n.equals("RELREC"))
        {
            return null;
        }
        if (n.startsWith("AP") && n.length() >= 4)
        {
            return n.substring(0, 4);
        }
        if (n.length() >= 2)
        {
            IDataTable t = resolver.resolve(memberName);
            return t != null ? net.cumba.corej.core.metadata.CdiscDomainResolver.cdiscDomainOf(t)
                    : n.substring(0, 2);
        }
        return n;
    }

}
