package net.cumba.cdisc.core.exec;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.cumba.cdisc.core.metadata.CdiscDomainResolver;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a dataset's <em>record key</em> — the ordered set of columns that identify which row a
 * finding sits on, beyond the {@code USUBJID} / {@code <DOMAIN>SEQ} pair the engine always carries
 * (EC-40).
 *
 * <h2>Why not the {@code Identifier} role</h2>
 *
 * <p>
 * The obvious-looking source — CDISC Library {@code role = Identifier} — is <b>not</b> used, and
 * deliberately so. {@code Role} classifies a variable's purpose within the observation model, not
 * its keyness. The Identifier set for the general observation classes is {@code STUDYID},
 * {@code DOMAIN}, {@code USUBJID}, {@code --SEQ}, {@code --GRPID}, {@code --REFID}, {@code --SPID},
 * {@code --LNKID}, {@code --LNKGRP}, {@code POOLID}: two are constant per dataset (no identifying
 * information at all), two are already carried, and {@code --GRPID} / {@code --LNKID} /
 * {@code --LNKGRP} are deliberately <em>non</em>-unique — they group and link records. Only
 * {@code --SPID} / {@code --REFID} contribute, and they are handled explicitly below. The engine
 * already encodes this view in {@link StandardVariableSelector#NATURAL_KEY_ROLES}, which excludes
 * {@code Identifier} and {@code Topic}.
 *
 * <h2>Tiers</h2>
 *
 * <p>
 * The strongest non-empty tier wins; the winner is recorded on the finding so a consumer knows how
 * far to trust a cross-version alignment:
 * </p>
 * <ol>
 * <li>{@link KeySource#DEFINE_KEY} — the sponsor's own Define-XML key
 * ({@link MetadataProvider#getKeyVariables}, ordered by {@code ItemRef/@KeySequence}).
 * Authoritative and per study.</li>
 * <li>{@link KeySource#STRUCTURAL} — the dataset shapes whose identity is structural rather than
 * sequential ({@code SUPP--} / {@code SQ--}, {@code RELREC}, {@code CO}). These carry no
 * {@code --SEQ} at all, so without this tier such a finding is located by {@code USUBJID}
 * alone.</li>
 * <li>{@link KeySource#NATURAL} — Topic + every {@link StandardVariableSelector#NATURAL_KEY_ROLES}
 * variable present, i.e. the same natural key the engine computes for FDA/PMDA SD1117. Requires
 * Library access and {@link FindingKeyMode#FULL}. SDTM/SEND only: the ADaM library model carries no
 * {@code role}, so an ADaM dataset never reaches this tier.</li>
 * <li>{@link KeySource#SPONSOR_ID} — no tier fired, but the always-append sponsor identifiers did.
 * The weakest key.</li>
 * <li>{@link KeySource#NONE} — nothing beyond the unconditional {@code USUBJID} /
 * {@code <DOMAIN>SEQ}.</li>
 * </ol>
 *
 * <p>
 * Regardless of the winning tier, {@link #ALWAYS_APPEND} columns are appended when present and
 * {@link #ALWAYS_SUBTRACT} plus the resolved sequence column are removed — the engine carries
 * {@code USUBJID} and the sequence on their own fields (duplicating them would double every row),
 * and {@code STUDYID} / {@code DOMAIN} are constant within a data set so they identify nothing.
 * </p>
 *
 * <p>
 * A missing Define / Library provider is never an error here. It degrades the tier and nothing
 * more: unlike the {@code define_key_variables} and {@code natural_key_variables}
 * <em>operations</em> (which SKIP the rule by contract), this is report enrichment and must never
 * change a verdict.
 * </p>
 */
public final class RecordKeyResolver
{

    private static final System.Logger LOGGER = System.getLogger(RecordKeyResolver.class.getName());

    /** {@code USUBJID}, carried unconditionally on its own field and never repeated in the key. */
    private static final String USUBJID = "USUBJID";

    /** ADaM sequence variable, the {@code <DOMAIN>SEQ} fallback (mirrors EC-37 D5b). */
    private static final String ASEQ = "ASEQ";

    /**
     * Columns appended to whatever the winning tier produced, whenever the dataset carries them.
     *
     * <ul>
     * <li>{@code POOLID} — in pooled SEND data {@code USUBJID} is blank and {@code POOLID} is the
     * subject identifier. It is the analogue of {@code USUBJID} rather than a record-key component,
     * so it must survive every tier, not just {@code STRUCTURAL}.</li>
     * <li>{@code --SPID} / {@code --REFID} — the only Identifier-role variables with genuine
     * sponsor-side stability across extracts, which is exactly what cross-version alignment
     * needs.</li>
     * </ul>
     */
    private static final List<String> ALWAYS_APPEND = List.of("POOLID", "--SPID", "--REFID");

    /**
     * Columns removed from every resolved key. {@code USUBJID} and the sequence column (handled
     * separately, since it is domain-derived) are already carried on the violation's own fields.
     * {@code STUDYID} and {@code DOMAIN} are constant within a data set, so they identify nothing —
     * a Define key of {@code [STUDYID, USUBJID, AESEQ]} would otherwise reduce to
     * {@code [STUDYID]}, the same value on every row, while claiming to be an authoritative
     * {@code DEFINE_KEY}.
     */
    private static final List<String> ALWAYS_SUBTRACT = List.of(USUBJID, "STUDYID", "DOMAIN");

    /** {@code SUPP--} / {@code SQ--} record identity (no {@code --SEQ} exists on these). */
    private static final List<String> SUPP_KEY = List.of("RDOMAIN", "IDVAR", "IDVARVAL", "QNAM");

    /** {@code RELREC} record identity. */
    private static final List<String> RELREC_KEY = List.of("RDOMAIN", "IDVAR", "IDVARVAL", "RELID");

    /** {@code CO} (Comments) record identity. */
    private static final List<String> CO_KEY = List.of("RDOMAIN", "IDVAR", "IDVARVAL");

    private RecordKeyResolver()
    {
    }


    /**
     * The {@code NATURAL} tier's filter: Topic plus every natural-key-forming role. Topic is added
     * back here because {@link StandardVariableSelector#NATURAL_KEY_ROLES} deliberately omits it —
     * the {@code natural_key_variables} operation's consuming rule supplies {@code --TESTCD}
     * itself, whereas a record key has to carry it.
     *
     * @param aVarRow
     *            the variable's attribute map.
     * @return {@code true} when the variable belongs in the natural record key.
     */
    private static boolean isNaturalKeyOrTopic(Map<String, String> aVarRow)
    {
        String role = aVarRow.get("role");
        return "Topic".equals(role) || StandardVariableSelector.NATURAL_KEY_ROLES.contains(role);
    }

    /** Which source produced a record key. Ordered strongest-first. */
    public enum KeySource
    {
        /** The sponsor's Define-XML {@code KeySequence} key. */
        DEFINE_KEY,
        /** A structural key for a dataset shape that has no sequence variable. */
        STRUCTURAL,
        /** Topic + natural-key-role variables resolved from the CDISC Library. */
        NATURAL,
        /**
         * Only the {@link #ALWAYS_APPEND} sponsor identifiers resolved — no tier fired. The weakest
         * key: {@code --SPID} / {@code --REFID} are Permissible and not required to be unique, and
         * {@code POOLID} identifies a pool rather than a record. A consumer should treat an
         * alignment built on this as a hint, not a match.
         */
        SPONSOR_ID,
        /** No key beyond the unconditional {@code USUBJID} / {@code <DOMAIN>SEQ}. */
        NONE
    }


    /**
     * One resolved key column: its name and its pre-resolved index in the dataset.
     *
     * @param name
     *            the real column name.
     * @param columnIndex
     *            the column's index in the dataset, resolved once at spec-build time.
     */
    public record KeyColumn(String name, int columnIndex)
    {
    }


    /**
     * A dataset's resolved record key. Built <b>once per rule × dataset</b> and reused for every
     * violating row — the column indices are resolved here precisely so the per-row read never
     * repeats a {@code getColumnIndex} lookup.
     *
     * @param source
     *            the tier that produced {@link #columns}.
     * @param columns
     *            the ordered key columns; empty for {@link #NONE}.
     */
    public record RowKeySpec(KeySource source, List<KeyColumn> columns)
    {

        /**
         * Defensive copy so the spec is genuinely immutable: it is resolved once and then shared
         * across every violating row of a dataset, so a caller mutating the list it passed in would
         * silently change the key of rows already emitted.
         */
        public RowKeySpec
        {
            columns = List.copyOf(columns);
        }

        /** Shared "no key resolved" spec. */
        public static final RowKeySpec NONE = new RowKeySpec(KeySource.NONE, List.of());

        /**
         * Whether this spec resolves any key column.
         *
         * @return {@code true} when no key column was resolved.
         */
        public boolean isEmpty()
        {
            return columns.isEmpty();
        }


        /**
         * The key column names, in order.
         *
         * @return the ordered key column names.
         */
        public List<String> names()
        {
            return columns.stream().map(KeyColumn::name).toList();
        }
    }

    /**
     * Resolves the record key for one dataset.
     *
     * @param aTable
     *            the dataset being validated.
     * @param aDomainName
     *            the dataset's domain name, as {@code EvaluationContext.getDomainName()} reports it
     *            — used to derive the {@code <DOMAIN>SEQ} column that gets subtracted.
     * @param aMode
     *            the configured enrichment mode; {@link FindingKeyMode#OFF} short-circuits.
     * @param aDefineProvider
     *            the Define-XML provider, or {@code null} when no Define was supplied.
     * @param aLibraryProvider
     *            the CDISC Library provider, or {@code null} when unavailable.
     * @param aResolver
     *            cross-domain resolver, passed through to the Library provider.
     * @param aRuleId
     *            the rule id, for logging only.
     * @return the resolved spec; {@link RowKeySpec#NONE} when nothing resolved.
     */
    public static RowKeySpec resolve(@Nullable IDataTable aTable, @Nullable String aDomainName,
            FindingKeyMode aMode, @Nullable MetadataProvider aDefineProvider,
            @Nullable MetadataProvider aLibraryProvider, @Nullable DatasetResolver aResolver,
            @Nullable String aRuleId)
    {
        if (aTable == null || !aMode.isEnabled())
        {
            return RowKeySpec.NONE;
        }
        DataTableMeta meta = aTable.getMetaData();
        if (meta.getColumnCount() == 0)
        {
            return RowKeySpec.NONE;
        }

        KeySource source = KeySource.NONE;
        List<String> names = defineKey(aTable, meta, aDefineProvider);
        if (!names.isEmpty())
        {
            source = KeySource.DEFINE_KEY;
        }
        else
        {
            names = structuralKey(aTable, meta);
            if (!names.isEmpty())
            {
                source = KeySource.STRUCTURAL;
            }
            else if (aMode.allowsNatural() && aLibraryProvider != null)
            {
                // The NATURAL tier deliberately reuses the operations' own selector, so its
                // dataset-column intersection matches natural_key_variables exactly.
                names = present(meta,
                        StandardVariableSelector.select(aLibraryProvider, aTable,
                                aResolver != null ? aResolver : _ -> null,
                                RecordKeyResolver::isNaturalKeyOrTopic));
                if (!names.isEmpty())
                {
                    source = KeySource.NATURAL;
                }
            }
        }

        // Always-append, then always-subtract. Order-preserving and de-duplicated.
        Set<String> ordered = new LinkedHashSet<>(names);
        String prefix = Objects.requireNonNullElse(OperationExecutor.variableWildcardPrefix(aTable,
                OperationExecutor.domainPrefix(aTable)), "");
        List<String> appended = new ArrayList<>(ALWAYS_APPEND.size());
        for (String candidate : ALWAYS_APPEND)
        {
            String resolved = resolveColumn(meta,
                    candidate.contains("--") ? candidate.replace("--", prefix) : candidate);
            if (resolved != null)
            {
                appended.add(resolved);
            }
        }
        ordered.addAll(appended);
        // Always-subtract: the two the engine already carries on their own fields, plus the two
        // that are constant per dataset and therefore carry no identifying information at all
        // (the same reason the Identifier role was rejected as a key source — see the class doc).
        for (String constant : ALWAYS_SUBTRACT)
        {
            ordered.remove(resolveColumn(meta, constant));
        }
        ordered.remove(sequenceColumn(meta, aDomainName));

        if (ordered.isEmpty())
        {
            return RowKeySpec.NONE;
        }
        // A key built only from the always-append sponsor identifiers has no tier behind it; say
        // so rather than reporting the NONE that the empty cascade left behind.
        if (source == KeySource.NONE)
        {
            source = KeySource.SPONSOR_ID;
        }
        List<KeyColumn> keyColumns = new ArrayList<>(ordered.size());
        for (String name : ordered)
        {
            int idx = meta.getColumnIndex(name);
            if (idx >= 0)
            {
                keyColumns.add(new KeyColumn(name, idx));
            }
        }
        if (keyColumns.isEmpty())
        {
            return RowKeySpec.NONE;
        }
        // D7: no cap — the full key is emitted however wide, but never silently, so an unexpected
        // width is visible in the log rather than only in the report size.
        LOGGER.log(Level.INFO, "[{0}] record key ({1}) for {2}: {3}",
                aRuleId != null ? aRuleId : "?", source, meta.getName(),
                keyColumns.stream().map(KeyColumn::name).toList());
        return new RowKeySpec(source, List.copyOf(keyColumns));
    }


    /**
     * Tier 1 — the sponsor Define-XML key, intersected with the dataset's actual columns.
     *
     * <p>
     * Tried under the dataset/member name first and the CDISC domain code second, because
     * {@code OdmDefineXMLProvider} matches an {@code ItemGroupDef} on either its {@code Name} or
     * its {@code Domain}; the second attempt is what lets a split dataset ({@code LBHE},
     * {@code DOMAIN=LB}) find its parent's declared key.
     * </p>
     */
    private static List<String> defineKey(IDataTable aTable, DataTableMeta aMeta,
            @Nullable MetadataProvider aProvider)
    {
        if (aProvider == null)
        {
            return List.of();
        }
        String memberName = aTable.getMetaData().getName();
        List<String> keys = memberName != null && !memberName.isEmpty()
                ? aProvider.getKeyVariables(memberName)
                : List.of();
        if (keys.isEmpty())
        {
            String domain = CdiscDomainResolver.cdiscDomainOf(aTable);
            if (!domain.isEmpty() && !domain.equals(memberName))
            {
                keys = aProvider.getKeyVariables(domain);
            }
        }
        return present(aMeta, keys);
    }


    /**
     * Tier 2 — structural identity for the dataset shapes that carry no sequence variable.
     */
    private static List<String> structuralKey(IDataTable aTable, DataTableMeta aMeta)
    {
        String name = aMeta.getName();
        String domain = CdiscDomainResolver.cdiscDomainOf(aTable);
        String probe = (name != null && !name.isEmpty() ? name : domain).toUpperCase(Locale.ROOT);
        List<String> candidate;
        if (probe.startsWith("SUPP") || probe.startsWith("SQ"))
        {
            candidate = SUPP_KEY;
        }
        else if (probe.startsWith("RELREC"))
        {
            candidate = RELREC_KEY;
        }
        else if ("CO".equals(probe) || "CO".equals(domain))
        {
            candidate = CO_KEY;
        }
        else
        {
            return List.of();
        }
        return present(aMeta, candidate);
    }


    /**
     * The dataset's sequence column, resolved exactly as {@code RuleRunner.readRowIdentity} does
     * ({@code <DOMAIN>SEQ}, else the EC-37 D5b {@code ASEQ} fallback), so the column the engine
     * already carries is never duplicated into the key.
     *
     * @return the sequence column name, or {@code null} when the dataset has none.
     */
    private static @Nullable String sequenceColumn(DataTableMeta aMeta,
            @Nullable String aDomainName)
    {
        String seqName = (aDomainName != null && !aDomainName.isEmpty()
                ? aDomainName.toUpperCase(Locale.ROOT)
                : "") + "SEQ";
        if (!"SEQ".equals(seqName))
        {
            String resolved = resolveColumn(aMeta, seqName);
            if (resolved != null)
            {
                return resolved;
            }
        }
        return resolveColumn(aMeta, ASEQ);
    }


    /**
     * Keeps the candidates the dataset actually carries, preserving candidate order and
     * substituting the data set's own spelling of each column name.
     *
     * <p>
     * Presence is decided by {@link DataTableMeta#getColumnIndex(String)} rather than by a name-set
     * lookup, because that method honours the table's {@code columnNameCaseSensitive} setting
     * (false by default). Using a case-sensitive set here would disagree with
     * {@code RuleRunner.readRowIdentity}, which resolves {@code USUBJID} / {@code <DOMAIN>SEQ} the
     * same way — and a disagreement means the always-subtract misses, so the sequence value would
     * be emitted twice (once as {@code SEQ}, once inside the key).
     * </p>
     */
    private static List<String> present(DataTableMeta aMeta, List<String> aCandidates)
    {
        List<String> out = new ArrayList<>(aCandidates.size());
        for (String c : aCandidates)
        {
            String resolved = c == null ? null : resolveColumn(aMeta, c);
            if (resolved != null)
            {
                out.add(resolved);
            }
        }
        return out;
    }


    /**
     * The data set's own spelling of {@code aName}, or {@code null} when it has no such column.
     */
    private static @Nullable String resolveColumn(DataTableMeta aMeta, String aName)
    {
        int idx = aMeta.getColumnIndex(aName);
        return idx >= 0 ? aMeta.getColumn(idx).getName() : null;
    }


    /**
     * Reads the key values for one row.
     *
     * @param aTable
     *            the dataset.
     * @param aSpec
     *            the spec resolved once for this dataset.
     * @param aRow
     *            the 0-based row index.
     * @return an ordered name to value map; empty when the spec resolves no columns. A missing or
     *         invalid cell yields {@code ""}, matching {@code readRowIdentity}'s handling.
     */
    public static Map<String, String> readRowKeys(IDataTable aTable, RowKeySpec aSpec, long aRow)
    {
        if (aSpec.isEmpty() || aRow < 0)
        {
            return Map.of();
        }
        Map<String, String> out = LinkedHashMap.newLinkedHashMap(aSpec.columns().size());
        for (KeyColumn kc : aSpec.columns())
        {
            IDataValue value = aTable.getColumn(kc.columnIndex()).getDataValue(aRow);
            out.put(kc.name(), value.isMissingOrInvalid() ? "" : value.getValueAsString());
        }
        return out;
    }

}
