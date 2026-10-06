package net.cumba.corej.core.exec;

import java.util.List;
import java.util.Objects;
import net.cumba.corej.core.model.JoinType;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.merge.RelrecPartSpec;
import org.jspecify.annotations.Nullable;

/**
 * The forward-RELREC half of the merged evaluation view ({@code PLAN-merged-evaluation-table} W5,
 * §3.6): a keyless {@code Name: RELREC} entry declares that every related domain the study's
 * {@code RELREC} relationships name for the primary's domain is a part of the view, its columns
 * qualified with that domain ({@code PC.PCRFTDTC}, P-14), and that a primary record expands into
 * the cross product of its related records per relationship (MET #17, OD-4, P-17). The pairing, the
 * SEND grouping (OD-3), the RELTYPE cardinality default (OD-5, OD-9) and the join type (MET #21)
 * are the datatable's ({@link RelrecPartSpec}); this class turns the rule's entry into that spec:
 *
 * <ul>
 * <li><b>The entry</b> — {@code Name} equal to {@code RELREC} ignoring case, no {@code Keys}, not
 * {@code Child: true} (a {@code Child} RELREC primary points at its parents through
 * {@link ChildEntries}; a keyed RELREC entry is an ordinary keyed part).</li>
 * <li><b>{@code Join_Type}</b> — the authored value; the loader's {@code inner} stamp where none is
 * authored (S2): a primary record with no relationship is not evaluated under {@code inner}, it is
 * one row with every related column not supplied and {@code RELREC._matched_} false under
 * {@code left}.</li>
 * <li><b>{@code Cardinality}</b> — the authored value overrides every relationship's RELTYPE
 * default (OD-9, with the datatable's WARNING); unauthored, a dataset-level relationship is checked
 * as its RELTYPE states and a record-level one is {@code n:m} (OD-5).</li>
 * <li><b>Every related domain</b> the relationships name keeps its part even when no record of the
 * primary pairs with it ({@code includeUnmatchedDomains}): a declared qualifier reads the
 * not-supplied marker, never an error (P-14, P-1).</li>
 * </ul>
 *
 * <p>
 * The one statement of what a forward RELREC entry is, read by the view build, the loader's gates
 * and the stage-A / stage-B checks (P-14 for the related-domain qualifiers and the
 * {@code _matched_} flags).
 * </p>
 */
public final class RelrecEntries
{

    /** The dataset the forward entry names and the view resolves. */
    public static final String RELREC = "RELREC";

    private RelrecEntries()
    {
    }


    /**
     * Whether an entry is a forward RELREC entry: {@code Name} equal to {@code RELREC} ignoring
     * case, no {@code Keys}, not {@code Child: true}.
     *
     * @param md
     *            the entry, possibly {@code null}
     * @return whether it declares the forward RELREC merge
     */
    public static boolean isForwardEntry(@Nullable MatchDataset md)
    {
        return md != null && md.getName() != null && RELREC.equalsIgnoreCase(md.getName())
                && (md.getKeys() == null || md.getKeys().isEmpty())
                && !Boolean.TRUE.equals(md.getChild());
    }


    /**
     * The rule's forward RELREC entry, or {@code null} when it declares none.
     *
     * @param entries
     *            the rule's {@code Match_Datasets}, possibly {@code null}
     * @return the entry, or {@code null}
     */
    public static @Nullable MatchDataset findForwardRelrec(@Nullable List<MatchDataset> entries)
    {
        if (entries == null)
        {
            return null;
        }
        for (MatchDataset md : entries)
        {
            if (isForwardEntry(md))
            {
                return md;
            }
        }
        return null;
    }


    /**
     * Whether the rule declares a forward RELREC entry.
     *
     * @param entries
     *            the rule's {@code Match_Datasets}, possibly {@code null}
     * @return whether one entry is a forward RELREC entry
     */
    public static boolean hasForwardEntry(@Nullable List<MatchDataset> entries)
    {
        return findForwardRelrec(entries) != null;
    }


    /**
     * The datatable's RELREC part spec of the rule's forward entry, or {@code null} when the rule
     * declares none.
     *
     * @param entries
     *            the rule's {@code Match_Datasets}, possibly {@code null}
     * @return the spec, or {@code null}
     */
    public static @Nullable RelrecPartSpec spec(@Nullable List<MatchDataset> entries)
    {
        MatchDataset md = findForwardRelrec(entries);
        if (md == null)
        {
            return null;
        }
        String jt = md.getJoinType();
        net.cumba.datatable.merge.JoinType joinType = JoinType.isAbsent(jt)
                ? net.cumba.datatable.merge.JoinType.INNER
                : net.cumba.datatable.merge.JoinType.parse(Objects.requireNonNull(jt));
        return RelrecPartSpec.builder().relrecDataset(RELREC).joinType(joinType)
                .cardinality(md.cardinality()).includeUnmatchedDomains(true).build();
    }
}
