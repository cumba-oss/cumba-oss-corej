package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Key-based {@code Match_Datasets} row expansion through the merged evaluation view (one row per
 * matched pair, each merged row bound to its matching child) — the retired key-match expander's
 * unit tests, kept as pins of the view since W6 of {@code PLAN-merged-evaluation-table}
 * ({@link ExecCalls#expand} builds the view where the expander's entry point stood). Every fixture
 * states its {@code Join_Type}; an absent one runs as {@code inner}, the S2 default the loader
 * stamps on every loaded rule.
 */
class KeyedPartExpansionTest
{

    private static IDataTable tbl(String name, String[] cols, String[][] rows)
    {
        MockTable mt = MockTable.of();
        for (int c = 0; c < cols.length; c++)
        {
            String[] colVals = new String[rows.length];
            for (int r = 0; r < rows.length; r++)
            {
                colVals[r] = rows[r][c];
            }
            mt.col(cols[c], colVals);
        }
        return mt.name(name).build();
    }


    private static DatasetResolver resolver(Map<String, IDataTable> tables)
    {
        return tables::get;
    }


    private static MatchDataset md(String name, String joinType, String... keys)
    {
        MatchDataset m = new MatchDataset();
        m.setName(name);
        m.setKeys(List.of(keys));
        // Always stated, as the loader stamps it (`inner` where none is authored, S2).
        m.setJoinType(joinType);
        return m;
    }


    /** "primaryRow:childValue" per expanded row for the named join's column. */
    private static List<String> collect(MergedEvaluation.Built exp, String ds, String col)
    {
        IDataTable t = exp.table();
        JoinLookup lk = exp.lookups().get(ds);
        List<String> out = new ArrayList<>();
        for (long i = 0; i < t.getRowCount(); i++)
        {
            out.add(t.getRealRowIndex(i) + ":" + lk.lookup(t, i, col));
        }
        return out;
    }


    @Test
    void noKeyEntriesReturnsNull()
    {
        IDataTable dm = tbl("DM", new String[]
        {
                "USUBJID"
        }, new String[][]
        {
                {
                        "P1"
                }
        });
        assertNull(ExecCalls.expand(dm, List.of(), resolver(Map.of()), "R"));
    }


    @Test
    void oneToManyBindsEachMatchingChild()
    {
        IDataTable dm = tbl("DM", new String[]
        {
                "USUBJID", "DTHFL"
        }, new String[][]
        {
                {
                        "P1", ""
                },
                {
                        "P2", ""
                }
        });
        IDataTable ae = tbl("AE", new String[]
        {
                "USUBJID", "AESDTH"
        }, new String[][]
        {
                {
                        "P1", "N"
                },
                {
                        "P1", "Y"
                },
                {
                        "P2", "N"
                }
        });
        MergedEvaluation.Built exp = ExecCalls.expand(dm, List.of(md("AE", "left", "USUBJID")),
                resolver(Map.of("AE", ae)), "R");
        assertNotNull(exp);
        // P1 -> 2 AE rows (N, Y); P2 -> 1 AE row (N). 3 expanded rows; each binds its own child.
        assertEquals(3, exp.table().getRowCount());
        List<String> got = collect(exp, "AE", "AESDTH");
        assertTrue(got.contains("0:N") && got.contains("0:Y"), got.toString());
        assertTrue(got.contains("1:N"), got.toString());
    }


    @Test
    void explicitInnerDropsUnmatchedPrimary()
    {
        IDataTable dm = tbl("DM", new String[]
        {
                "USUBJID"
        }, new String[][]
        {
                {
                        "P1"
                },
                {
                        "P2"
                }
        });
        IDataTable ae = tbl("AE", new String[]
        {
                "USUBJID", "AESDTH"
        }, new String[][]
        {
                {
                        "P1", "Y"
                }
        });
        MergedEvaluation.Built exp = ExecCalls.expand(dm, List.of(md("AE", "inner", "USUBJID")),
                resolver(Map.of("AE", ae)), "R");
        // explicit inner: P2 (no AE) is dropped.
        assertEquals(1, exp.table().getRowCount());
        assertEquals(List.of("0:Y"), collect(exp, "AE", "AESDTH"));
    }


    @Test
    void explicitLeftKeepsUnmatchedPrimaryWithNullChild()
    {
        IDataTable dm = tbl("DM", new String[]
        {
                "USUBJID"
        }, new String[][]
        {
                {
                        "P1"
                },
                {
                        "P2"
                }
        });
        IDataTable ae = tbl("AE", new String[]
        {
                "USUBJID", "AESDTH"
        }, new String[][]
        {
                {
                        "P1", "Y"
                }
        });
        // explicit left: P2 (no AE) is kept with a null-bound child.
        MergedEvaluation.Built exp = ExecCalls.expand(dm, List.of(md("AE", "left", "USUBJID")),
                resolver(Map.of("AE", ae)), "R");
        assertEquals(2, exp.table().getRowCount());
        List<String> got = collect(exp, "AE", "AESDTH");
        assertTrue(got.contains("0:Y"), got.toString());
        assertTrue(got.contains("1:null"), got.toString());
    }


    @Test
    void childAndRelrecNotExpandable()
    {
        MatchDataset child = md("AE", "left", "USUBJID");
        child.setChild(true);
        MatchDataset relrec = new MatchDataset();
        relrec.setName("RELREC");
        relrec.setKeys(List.of("USUBJID"));
        IDataTable dm = tbl("DM", new String[]
        {
                "USUBJID"
        }, new String[][]
        {
                {
                        "P1"
                }
        });
        assertNull(ExecCalls.expand(dm, List.of(child, relrec), resolver(Map.of()), "R"));
    }


    @Test
    void unresolvedChildSkipsMerge()
    {
        IDataTable dm = tbl("DM", new String[]
        {
                "USUBJID"
        }, new String[][]
        {
                {
                        "P1"
                },
                {
                        "P2"
                }
        });
        // AE not registered -> no part: nothing is merged and no view is built, so the
        // evaluation reads the primary's rows unchanged and AE's columns take the not-supplied
        // default (the retired expander answered an unchanged expansion without an AE lookup).
        MergedEvaluation.Built exp = ExecCalls.expand(dm, List.of(md("AE", "left", "USUBJID")),
                resolver(Map.of()), "R");
        assertNull(exp, "nothing to merge: no view");
    }


    @Test
    void multiJoinSequentialFold()
    {
        IDataTable dm = tbl("DM", new String[]
        {
                "USUBJID"
        }, new String[][]
        {
                {
                        "P1"
                }
        });
        IDataTable ae = tbl("AE", new String[]
        {
                "USUBJID", "AESDTH"
        }, new String[][]
        {
                {
                        "P1", "Y"
                },
                {
                        "P1", "N"
                }
        });
        IDataTable ce = tbl("CE", new String[]
        {
                "USUBJID", "CETERM"
        }, new String[][]
        {
                {
                        "P1", "DEATH"
                }
        });
        MergedEvaluation.Built exp = ExecCalls.expand(dm,
                List.of(md("AE", "left", "USUBJID"), md("CE", "left", "USUBJID")),
                resolver(Map.of("AE", ae, "CE", ce)), "R");
        // P1 x 2 AE x 1 CE = 2 expanded rows; each binds one AE and the one CE.
        assertEquals(2, exp.table().getRowCount());
        assertEquals(List.of("0:DEATH", "0:DEATH"), collect(exp, "CE", "CETERM"));
        List<String> aeVals = collect(exp, "AE", "AESDTH");
        assertTrue(aeVals.contains("0:Y") && aeVals.contains("0:N"), aeVals.toString());
    }


    @Test
    void suppQualifierAndWildcardNotExpandable()
    {
        IDataTable dm = tbl("DM", new String[]
        {
                "USUBJID"
        }, new String[][]
        {
                {
                        "P1"
                }
        });
        // SUPP-- / SQ-- qualifier datasets (Python pivots those) and -- wildcard names are not
        // key-merge-expandable; each, as the sole entry, yields no expansion.
        assertNull(ExecCalls.expand(dm, List.of(md("SUPPAE", "left", "USUBJID")),
                resolver(Map.of()), "R"));
        assertNull(ExecCalls.expand(dm, List.of(md("SQAPSC", "left", "USUBJID")),
                resolver(Map.of()), "R"));
        assertNull(ExecCalls.expand(dm, List.of(md("AE--", "left", "USUBJID")), resolver(Map.of()),
                "R"));
    }


    @Test
    void leftOnlyRowReportsColumnPresentButNull()
    {
        IDataTable dm = tbl("DM", new String[]
        {
                "USUBJID"
        }, new String[][]
        {
                {
                        "P1"
                },
                {
                        "P2"
                }
        });
        IDataTable ae = tbl("AE", new String[]
        {
                "USUBJID", "AESDTH"
        }, new String[][]
        {
                {
                        "P1", "Y"
                }
        });
        MergedEvaluation.Built exp = ExecCalls.expand(dm, List.of(md("AE", "left", "USUBJID")),
                resolver(Map.of("AE", ae)), "R");
        assertNotNull(exp);
        JoinLookup lk = exp.lookups().get("AE");
        IDataTable t = exp.table();
        // The left-joined unmatched primary (P2) keeps the column present (hasColumn true) but
        // resolves it to null — the present-but-null contract absence/empty checks depend on.
        boolean checkedUnmatched = false;
        for (long i = 0; i < t.getRowCount(); i++)
        {
            assertTrue(lk.hasColumn(t, i, "AESDTH"), "child column always reported present");
            if (lk.lookup(t, i, "AESDTH") == null)
            {
                checkedUnmatched = true;
            }
        }
        assertTrue(checkedUnmatched, "expected a left-only unmatched row with a null-bound child");
        // A column absent from the child schema is reported absent.
        assertFalse(lk.hasColumn(t, 0, "NOSUCHCOL"));
    }

    // ---- Fix #358: split-domain union on the key path (231/260 corpus entries) ----


    /**
     * A {@code Name: LB} entry on a submission that ships LB split resolves to the member UNION —
     * rows of either member bind. ⚠ Uses a real {@code WithInventory} fixture ({@link RealTables})
     * — the plain-lambda resolver above cannot reach the union branch, so a split case built on it
     * would be vacuous.
     */
    @Test
    void splitDomainChild_expandsAgainstTheMemberUnion()
    {
        IDataTable primary = RealTables.of("ADLB").str("USUBJID", "U1", "U2", "U3")
                .str("LBSEQ", "1", "9", "7").build();
        IDataTable lbch = RealTables.of("lbch").str("DOMAIN", "LB").str("USUBJID", "U1")
                .str("LBSEQ", "1").str("LBORRES", "res-ch").build();
        IDataTable lbhe = RealTables.of("lbhe").str("DOMAIN", "LB").str("USUBJID", "U2")
                .str("LBSEQ", "9").str("LBORRES", "res-he").build();

        MergedEvaluation.Built exp = ExecCalls.expand(primary,
                List.of(md("LB", "left", "USUBJID", "LBSEQ")), RealTables.inventoryOf(lbch, lbhe),
                "R");
        assertNotNull(exp, "a split LB must expand, not silently skip the merge");
        JoinLookup lk = exp.lookups().get("LB");
        assertNotNull(lk);
        IDataTable t = exp.table();
        assertEquals(3, t.getRowCount());
        assertEquals("res-ch", lk.lookup(t, 0, "LBORRES"), "row bound into lbch");
        assertEquals("res-he", lk.lookup(t, 1, "LBORRES"), "row bound into lbhe");
        assertNull(lk.lookup(t, 2, "LBORRES"), "no member matches -> left-kept, null-bound");
    }


    /** Ruling 1: an un-unionable split propagates as the rule-ERROR exception. */
    @Test
    void splitDomainChild_typeClash_throws()
    {
        IDataTable primary = RealTables.of("ADLB").str("USUBJID", "U1").str("LBSEQ", "1").build();
        IDataTable lbch = RealTables.of("lbch").str("DOMAIN", "LB").str("USUBJID", "U1")
                .lng("LBSTRESN", 1L).str("LBSEQ", "1").build();
        IDataTable lbhe = RealTables.of("lbhe").str("DOMAIN", "LB").str("USUBJID", "U2")
                .str("LBSTRESN", "x").str("LBSEQ", "9").build();
        List<MatchDataset> mds = List.of(md("LB", "left", "USUBJID", "LBSEQ"));
        DatasetResolver inv = RealTables.inventoryOf(lbch, lbhe);
        InvalidJoinedDomainException ex = assertThrows(InvalidJoinedDomainException.class,
                () -> ExecCalls.expand(primary, mds, inv, "R"));
        assertTrue(ex.getMessage().contains("LBSTRESN"), ex.getMessage());
    }


    /**
     * U15 of {@code PLAN-retire-dead-multi-match-lookup} refused an entry that reached the retired
     * expander without a {@code Join_Type}, so a loader bypass could never run as a {@code left}
     * join against the S2 ruling. The merged view keeps that guarantee by answering S2 itself: an
     * absent {@code Join_Type} is {@code inner} — the unmatched primary row is dropped, never kept.
     */
    @Test
    void anAbsentJoinTypeRunsAsInnerNeverLeft()
    {
        IDataTable dm = tbl("DM", new String[]
        {
                "USUBJID"
        }, new String[][]
        {
                {
                        "P1"
                },
                {
                        "P2"
                }
        });
        IDataTable ae = tbl("AE", new String[]
        {
                "USUBJID"
        }, new String[][]
        {
                {
                        "P1"
                }
        });
        MatchDataset bare = new MatchDataset();
        bare.setName("AE");
        bare.setKeys(List.of("USUBJID"));
        MergedEvaluation.Built exp = ExecCalls.expand(dm, List.of(bare), resolver(Map.of("AE", ae)),
                "R");
        assertNotNull(exp);
        assertEquals(1, exp.table().getRowCount(),
                "S2: inner — P2 has no AE partner and is dropped");
        assertEquals(0L, exp.table().getRealRowIndex(0));
    }

}
