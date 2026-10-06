package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.AbstractDataTableColumn;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.impl.ColumnCachedDataTable;
import net.cumba.datatable.merge.ChildMergeSpec;
import net.cumba.datatable.merge.JoinToken;
import net.cumba.datatable.merge.JoinType;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * The {@code Child: true} half of the merged evaluation view ({@code PLAN-merged-evaluation-table}
 * W4, §3.7, MQ1): a {@code SUPP--} / {@code CO} / {@code RELREC} primary points at its parent
 * record through {@code (RDOMAIN, IDVAR, IDVARVAL)}, and the view adds one part per parent domain,
 * its columns qualified with that domain ({@code AE.AESMIE}), supplied only on the rows whose
 * parent is that domain. This class turns the rule's {@code Child} entry into the datatable's
 * {@link ChildMergeSpec}, with the semantics the bare pre-merge it replaces had:
 *
 * <ul>
 * <li><b>The applicable entry</b> — the {@code Child} entry whose {@code Name} is the primary (a
 * {@code SUPP--} / {@code SQAP--} template matching any {@code SUPP<x>} / {@code SQAP<x>}), else
 * the first {@code Child} entry; its {@code Keys} (the SDTM {@code [USUBJID, IDVAR, IDVARVAL]} when
 * it has none).</li>
 * <li><b>The pointer</b> — taken only when the keys name {@code IDVAR} or {@code IDVARVAL} and the
 * primary carries both columns; the subject key is the declared keys minus the pointer columns
 * ({@code STUDYID} only when declared), compared where both sides carry the column.</li>
 * <li><b>The parent</b> — each row's {@code RDOMAIN}; a primary without an {@code RDOMAIN} column
 * takes the domain its {@code SUPP<x>} / {@code SQAP<x>} name implies, else there is no parent. The
 * entry's {@code Name} never names the parent.</li>
 * <li><b>Every record kept</b> ({@code left}): the pre-merge never dropped a row — a record whose
 * parent is not found reads the not-supplied marker (its type default) in the parent's columns,
 * which is exactly what a referenced-record rule such as {@code CDISC-CG0371} reports. An authored
 * {@code Join_Type} other than {@code left} is a load error
 * ({@code RulePackageLoader.checkChildEntryJoinType}).</li>
 * <li><b>The cardinality</b> — the IG's {@code n:m} unless authored (P-13); an authored value is
 * checked by the datatable (P-16).</li>
 * </ul>
 *
 * <p>
 * <b>The pointer token (J5).</b> The parent record is matched where its {@code IDVAR} column's
 * value equals {@code IDVARVAL} as a join token ({@link JoinToken#normalize}: stripped, a numeric
 * parent column canonicalised — the SAS-padded {@code " 1"} is {@code 1}). A rule compares the two
 * itself ({@code str(IDVARVAL) != str(colref(concat(RDOMAIN, ".", IDVAR)))}), so the evaluated
 * {@code IDVARVAL} is that token on every row whose parent carries the pointer column
 * ({@link #withPointerTokens}) — the value the pre-merge exposed. Only the rows whose token differs
 * from the stored text are carried (none on conformant data, so no copy is made there).
 * </p>
 */
public final class ChildEntries
{

    /** The SDTM pointer keys assumed when a {@code Child} entry omits {@code Keys}. */
    private static final List<String> CANONICAL_CHILD_KEYS = List.of("USUBJID", "IDVAR",
            "IDVARVAL");

    private static final String IDVAR = "IDVAR";

    private static final String IDVARVAL = "IDVARVAL";

    private static final String RDOMAIN = "RDOMAIN";

    private ChildEntries()
    {
    }


    /**
     * Whether any entry is {@code Child: true}.
     *
     * @param entries
     *            the rule's {@code Match_Datasets}, possibly {@code null}
     * @return whether the rule has a {@code Child} entry
     */
    public static boolean hasChildEntry(@Nullable List<MatchDataset> entries)
    {
        if (entries == null)
        {
            return false;
        }
        for (MatchDataset md : entries)
        {
            if (md != null && Boolean.TRUE.equals(md.getChild()))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * The {@link ChildMergeSpec} of the rule's applicable {@code Child} entry on {@code primary},
     * or {@code null} when the rule has none or the primary has no pointer to follow (no
     * {@code IDVAR} / {@code IDVARVAL} column, keys naming neither, or no parent derivable).
     *
     * @param entries
     *            the rule's {@code Match_Datasets}
     * @param primary
     *            the evaluated dataset
     * @return the spec, or {@code null}
     */
    static @Nullable ChildMergeSpec spec(@Nullable List<MatchDataset> entries, IDataTable primary)
    {
        if (entries == null || !hasChildEntry(entries))
        {
            return null;
        }
        DataTableMeta meta = primary.getMetaData();
        if (meta.getColumnIndex(IDVAR) < 0 || meta.getColumnIndex(IDVARVAL) < 0)
        {
            return null;
        }
        MatchDataset entry = applicableEntry(entries, meta.getName());
        List<String> keys = entry == null || entry.getKeys() == null || entry.getKeys().isEmpty()
                ? CANONICAL_CHILD_KEYS
                : entry.getKeys();
        if (!keys.contains(IDVAR) && !keys.contains(IDVARVAL))
        {
            return null;
        }
        List<String> subjectKeys = new ArrayList<>(keys.size());
        for (String k : keys)
        {
            if (!IDVAR.equals(k) && !IDVARVAL.equals(k))
            {
                subjectKeys.add(k);
            }
        }
        String fixedParent = null;
        if (meta.getColumnIndex(RDOMAIN) < 0)
        {
            fixedParent = implicitParent(meta.getName());
            if (fixedParent == null)
            {
                return null;
            }
        }
        return ChildMergeSpec.builder().parentDataset(fixedParent).subjectKeys(subjectKeys)
                .joinType(JoinType.LEFT).cardinality(entry == null ? null : entry.cardinality())
                .build();
    }


    /**
     * The applicable {@code Child} entry: the one naming the primary (a trailing-{@code --}
     * template matching by prefix), else the first {@code Child} entry.
     */
    private static @Nullable MatchDataset applicableEntry(List<MatchDataset> entries,
            @Nullable String primaryName)
    {
        MatchDataset first = null;
        for (MatchDataset md : entries)
        {
            if (md == null || !Boolean.TRUE.equals(md.getChild()))
            {
                continue;
            }
            if (first == null)
            {
                first = md;
            }
            String name = md.getName();
            if (name != null && primaryName != null
                    && (name.equals(primaryName) || (name.endsWith("--")
                            && primaryName.startsWith(name.substring(0, name.length() - 2)))))
            {
                return md;
            }
        }
        return first;
    }


    /**
     * The parent a primary without an {@code RDOMAIN} column implies: {@code SUPPAE} /
     * {@code SQAPAE} → {@code AE} (a split name unsplit), else {@code null}.
     */
    private static @Nullable String implicitParent(@Nullable String name)
    {
        if (name == null || name.length() <= 4
                || !(name.startsWith("SUPP") || name.startsWith("SQAP")))
        {
            return null;
        }
        return SplitDatasetUtil.unsplitName(name.substring(4));
    }


    /**
     * The primary with its {@code IDVARVAL} read as the pointer's join token wherever the row's
     * parent resolves and carries the column {@code IDVAR} names and the token differs from the
     * stored text (J5) — or the primary itself when no row differs. Every other column, and every
     * other row of {@code IDVARVAL}, is the primary's own cell.
     *
     * @param primary
     *            the evaluated dataset
     * @param spec
     *            its {@link #spec}
     * @param parents
     *            resolves a parent domain (split-aware), {@code null} when not supplied
     * @return the primary, or a column-replacing view of it
     */
    static IDataTable withPointerTokens(IDataTable primary, ChildMergeSpec spec,
            net.cumba.datatable.merge.DatasetResolver parents)
    {
        DataTableMeta meta = primary.getMetaData();
        int idvarCol = meta.getColumnIndex(spec.getIdvarColumn());
        int valueCol = meta.getColumnIndex(spec.getIdvarvalColumn());
        String fixed = spec.getParentDataset();
        int rdomainCol = fixed == null ? meta.getColumnIndex(spec.getRdomainColumn()) : -1;
        if (idvarCol < 0 || valueCol < 0 || (fixed == null && rdomainCol < 0))
        {
            return primary;
        }
        int rows = Math.toIntExact(primary.getRowCount());
        Map<String, Optional<IDataTable>> byDomain = new HashMap<>();
        Map<String, Optional<Boolean>> numericByPointer = new HashMap<>();
        int[] changedRows = new int[0];
        String[] tokens = new String[0];
        int changed = 0;
        IDataTableColumn values = primary.getColumn(valueCol);
        for (int r = 0; r < rows; r++)
        {
            IDataValue raw = values.getDataValue(r);
            if (raw.isMissingOrInvalid())
            {
                continue;
            }
            String domain = fixed != null ? fixed : text(primary, rdomainCol, r);
            String idvar = text(primary, idvarCol, r);
            if (domain == null || idvar == null)
            {
                continue;
            }
            String key = domain.toUpperCase(Locale.ROOT) + "\0" + idvar.toUpperCase(Locale.ROOT);
            Boolean numeric = numericByPointer
                    .computeIfAbsent(key, _ -> pointerKind(byDomain, parents, domain, idvar))
                    .orElse(null);
            if (numeric == null)
            {
                continue;
            }
            String text = raw.getValueAsString();
            String token = JoinToken.normalize(text, numeric);
            if (token != null && !token.equals(text))
            {
                if (changed == changedRows.length)
                {
                    changedRows = Arrays.copyOf(changedRows, Math.max(8, changed * 2));
                    tokens = Arrays.copyOf(tokens, changedRows.length);
                }
                changedRows[changed] = r;
                tokens[changed] = token;
                changed++;
            }
        }
        if (changed == 0)
        {
            return primary;
        }
        int columns = meta.getColumnCount();
        IDataTableColumn[] cols = new IDataTableColumn[columns];
        for (int c = 0; c < columns; c++)
        {
            cols[c] = c == valueCol
                    ? new TokenColumn(c, values, Arrays.copyOf(changedRows, changed),
                            Arrays.copyOf(tokens, changed))
                    : primary.getColumn(c);
        }
        return new ColumnCachedDataTable(meta, cols);
    }


    /** Whether a parent's pointer column is numeric; empty when the parent or column is absent. */
    private static Optional<Boolean> pointerKind(Map<String, Optional<IDataTable>> byDomain,
            net.cumba.datatable.merge.DatasetResolver parents, String domain, String idvar)
    {
        IDataTable parent = byDomain.computeIfAbsent(domain.toUpperCase(Locale.ROOT),
                _ -> Optional.ofNullable(parents.resolve(domain))).orElse(null);
        if (parent == null)
        {
            return Optional.empty();
        }
        int col = parent.getMetaData().getColumnIndex(idvar);
        if (col < 0)
        {
            return Optional.empty();
        }
        DataValueType t = parent.getMetaData().getColumn(col).getType();
        return Optional.of(t == DataValueType.LONG || t == DataValueType.DOUBLE);
    }


    /** A present, non-blank cell's stripped text, else {@code null}. */
    private static @Nullable String text(IDataTable table, int col, long row)
    {
        IDataValue dv = table.getColumn(col).getDataValue(row);
        if (dv.isMissingOrInvalid())
        {
            return null;
        }
        String v = dv.getValueAsString();
        return v == null || v.isBlank() ? null : v.strip();
    }

    /**
     * {@code IDVARVAL} with the rows whose join token differs from the stored text replaced by the
     * token (ascending {@code rows}, binary-searched); every other row is the original cell.
     */
    private static final class TokenColumn extends AbstractDataTableColumn
    {

        private final IDataTableColumn original;

        private final int[] rows;

        private final String[] tokens;

        TokenColumn(int aIndex, IDataTableColumn aOriginal, int[] aRows, String[] aTokens)
        {
            super(aIndex);
            original = aOriginal;
            rows = aRows;
            tokens = aTokens;
        }


        @Override
        public long getRowCount()
        {
            return original.getRowCount();
        }


        private int at(long aRow)
        {
            return aRow > Integer.MAX_VALUE ? -1 : Arrays.binarySearch(rows, (int) aRow);
        }


        @Override
        public @Nullable Object getValue(long aRow)
        {
            int i = at(aRow);
            return i >= 0 ? tokens[i] : original.getValue(aRow);
        }


        @Override
        public IDataValue getDataValue(long aRow)
        {
            int i = at(aRow);
            return i >= 0 ? DataValueSupport.getAsDataValue(tokens[i], DataValueType.STRING)
                    : original.getDataValue(aRow);
        }
    }
}
