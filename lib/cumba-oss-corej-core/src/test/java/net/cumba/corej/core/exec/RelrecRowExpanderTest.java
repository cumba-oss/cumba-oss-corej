package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RelrecRowExpander} — forward RELREC row expansion (dataset-level +
 * one-to-many), mirroring the Python engine's {@code merge_relrec_datasets}.
 */
class RelrecRowExpanderTest
{

    /** Builds an in-memory table (column-major) via the shared {@link MockTable} test helper. */
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


    private static MatchDataset forwardRelrec()
    {
        MatchDataset md = new MatchDataset();
        md.setName("RELREC");
        return md;
    }


    /** Collects "primaryRow:value" for every expanded row, for order-insensitive assertions. */
    private static List<String> collect(RelrecRowExpander.RelrecExpansion exp, String col)
    {
        List<String> out = new ArrayList<>();
        IDataTable t = exp.table();
        for (long i = 0; i < t.getRowCount(); i++)
        {
            out.add(t.getRealRowIndex(i) + ":" + exp.lookup().lookup(t, i, col));
        }
        return out;
    }

    // ---- detection ----


    @Test
    void noForwardRelrecReturnsNull()
    {
        IDataTable ae = tbl("AE", new String[]
        {
                "USUBJID"
        }, new String[][]
        {
                {
                        "P1"
                }
        });
        assertNull(RelrecRowExpander.expand(ae, List.of(), resolver(Map.of()), "R"));
    }


    @Test
    void childAndKeyedRelrecAreNotForwardJoins()
    {
        MatchDataset child = new MatchDataset();
        child.setName("RELREC");
        child.setChild(true);
        child.setKeys(List.of("USUBJID", "IDVAR", "IDVARVAL"));
        MatchDataset keyed = new MatchDataset();
        keyed.setName("RELREC");
        keyed.setKeys(List.of("USUBJID", "IDVAR", "IDVARVAL"));
        assertNull(RelrecRowExpander.findForwardRelrec(List.of(child, keyed)));
        assertNotNull(RelrecRowExpander.findForwardRelrec(List.of(child, forwardRelrec())));
    }

    // ---- dataset-level one-to-many (AE primary -> FA), with inner-join exclusion ----


    @Test
    void datasetLevelOneToManyWithInnerJoinExclusion()
    {
        IDataTable ae = tbl("AE", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "AESEQ", "AELNKID", "AETERM"
        }, new String[][]
        {
                {
                        "S1", "AE", "P1", "1", "L1", "REACTION"
                },
                {
                        "S1", "AE", "P1", "2", "L2", "FATIGUE"
                },
                {
                        "S1", "AE", "P1", "3", "L9", "HEADACHE"
                }
        }); // L9 has no FA -> excluded
        IDataTable fa = tbl("FA", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "FASEQ", "FALNKGRP", "FAOBJ"
        }, new String[][]
        {
                {
                        "S1", "FA", "P1", "1", "L1", "ERYTHEMA"
                },
                {
                        "S1", "FA", "P1", "2", "L1", "PAIN"
                },
                {
                        "S1", "FA", "P1", "3", "L1", "EDEMA"
                },
                {
                        "S1", "FA", "P1", "4", "L2", "OTHER"
                }
        });
        IDataTable relrec = tbl("RELREC", new String[]
        {
                "STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "RELTYPE", "RELID"
        }, new String[][]
        {
                {
                        "S1", "AE", "", "AELNKID", "", "ONE", "AEFA"
                },
                {
                        "S1", "FA", "", "FALNKGRP", "", "MANY", "AEFA"
                }
        });
        Map<String, IDataTable> t = new HashMap<>();
        t.put("RELREC", relrec);
        t.put("FA", fa);

        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(ae,
                List.of(forwardRelrec()), resolver(t), "R");
        assertNotNull(exp);
        // AE row 0 (L1) -> FA rows 0,1,2 ; AE row 1 (L2) -> FA row 3 ; AE row 2 (L9) excluded.
        assertEquals(4, exp.table().getRowCount());
        List<String> got = collect(exp, "FAOBJ");
        assertTrue(got.contains("0:ERYTHEMA"));
        assertTrue(got.contains("0:PAIN"));
        assertTrue(got.contains("0:EDEMA"));
        assertTrue(got.contains("1:OTHER"));
        // inner-join exclusion: unmatched AE row 2 never appears.
        assertFalse(got.stream().anyMatch(s -> s.startsWith("2:")));
        // ** wildcard resolves to FA's domain prefix.
        assertEquals("ERYTHEMA", exp.lookup().lookup(exp.table(), 0, "**OBJ"));
    }

    // ---- record-level 1:1 (CM primary -> FA via IDVARVAL) ----


    @Test
    void recordLevelOneToOne()
    {
        IDataTable cm = tbl("CM", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "CMSEQ", "CMTRT"
        }, new String[][]
        {
                {
                        "S1", "CM", "P1", "1", "ASPIRIN"
                },
                {
                        "S1", "CM", "P1", "2", "MYSTERY"
                }
        });
        IDataTable fa = tbl("FA", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "FASEQ", "FAOBJ"
        }, new String[][]
        {
                {
                        "S1", "FA", "P1", "1", "ASPIRIN"
                },
                {
                        "S1", "FA", "P1", "2", "ASPIRIN"
                }
        });
        IDataTable relrec = tbl("RELREC", new String[]
        {
                "STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "RELID"
        }, new String[][]
        {
                {
                        "S1", "CM", "P1", "CMSEQ", "1", "R1"
                },
                {
                        "S1", "FA", "P1", "FASEQ", "1", "R1"
                },
                {
                        "S1", "CM", "P1", "CMSEQ", "2", "R2"
                },
                {
                        "S1", "FA", "P1", "FASEQ", "2", "R2"
                }
        });
        Map<String, IDataTable> t = new HashMap<>();
        t.put("RELREC", relrec);
        t.put("FA", fa);

        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(cm,
                List.of(forwardRelrec()), resolver(t), "R");
        assertEquals(2, exp.table().getRowCount());
        List<String> got = collect(exp, "FAOBJ");
        assertEquals(List.of("0:ASPIRIN", "1:ASPIRIN"), got);
        assertEquals(0, exp.table().getRealRowIndex(0));
        assertEquals(1, exp.table().getRealRowIndex(1));
    }

    // ---- record-level USUBJID isolation (relrec USUBJID must restrict the subject) ----


    @Test
    void recordLevelRestrictsToRelrecSubject()
    {
        // Both P1 and P2 have CMSEQ=1 / FASEQ=1; the relrec row is for P1 only.
        IDataTable cm = tbl("CM", new String[]
        {
                "STUDYID", "USUBJID", "CMSEQ", "CMTRT"
        }, new String[][]
        {
                {
                        "S1", "P1", "1", "A"
                },
                {
                        "S1", "P2", "1", "B"
                }
        });
        IDataTable fa = tbl("FA", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "FASEQ", "FAOBJ"
        }, new String[][]
        {
                {
                        "S1", "FA", "P1", "1", "P1OBJ"
                },
                {
                        "S1", "FA", "P2", "1", "P2OBJ"
                }
        });
        IDataTable relrec = tbl("RELREC", new String[]
        {
                "STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "RELID"
        }, new String[][]
        {
                {
                        "S1", "CM", "P1", "CMSEQ", "1", "R1"
                },
                {
                        "S1", "FA", "P1", "FASEQ", "1", "R1"
                }
        });
        Map<String, IDataTable> t = new HashMap<>();
        t.put("RELREC", relrec);
        t.put("FA", fa);

        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(cm,
                List.of(forwardRelrec()), resolver(t), "R");
        // Only P1 (primary row 0) relates; P2 must NOT be pulled in.
        assertEquals(List.of("0:P1OBJ"), collect(exp, "FAOBJ"));
    }

    // ---- multi-target domain in one RELID group (AE -> FA and DS) ----


    @Test
    void multiTargetDomainGroup()
    {
        IDataTable ae = tbl("AE", new String[]
        {
                "STUDYID", "USUBJID", "AELNKID"
        }, new String[][]
        {
                {
                        "S1", "P1", "L1"
                }
        });
        IDataTable fa = tbl("FA", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "FALNKGRP", "FAOBJ"
        }, new String[][]
        {
                {
                        "S1", "FA", "P1", "L1", "O1"
                },
                {
                        "S1", "FA", "P1", "L1", "O2"
                }
        });
        IDataTable ds = tbl("DS", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "DSLNKID", "DSTERM"
        }, new String[][]
        {
                {
                        "S1", "DS", "P1", "L1", "DEATH"
                }
        });
        IDataTable relrec = tbl("RELREC", new String[]
        {
                "STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "RELID"
        }, new String[][]
        {
                {
                        "S1", "AE", "", "AELNKID", "", "G1"
                },
                {
                        "S1", "FA", "", "FALNKGRP", "", "G1"
                },
                {
                        "S1", "DS", "", "DSLNKID", "", "G1"
                }
        });
        Map<String, IDataTable> t = new HashMap<>();
        t.put("RELREC", relrec);
        t.put("FA", fa);
        t.put("DS", ds);

        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(ae,
                List.of(forwardRelrec()), resolver(t), "R");
        assertEquals(3, exp.table().getRowCount()); // 2 FA + 1 DS
        List<String> fas = collect(exp, "FAOBJ");
        List<String> dss = collect(exp, "DSTERM");
        assertTrue(fas.contains("0:O1") && fas.contains("0:O2"));
        assertTrue(dss.contains("0:DEATH"));
    }

    // ---- dedup: same (primaryRow,target) from two RELID groups collapses ----


    @Test
    void duplicateTriplesAreDeduped()
    {
        IDataTable ae = tbl("AE", new String[]
        {
                "STUDYID", "USUBJID", "AELNKID"
        }, new String[][]
        {
                {
                        "S1", "P1", "L1"
                }
        });
        IDataTable fa = tbl("FA", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "FALNKGRP", "FAOBJ"
        }, new String[][]
        {
                {
                        "S1", "FA", "P1", "L1", "O1"
                }
        });
        IDataTable relrec = tbl("RELREC", new String[]
        {
                "STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "RELID"
        }, new String[][]
        {
                {
                        "S1", "AE", "", "AELNKID", "", "G1"
                },
                {
                        "S1", "FA", "", "FALNKGRP", "", "G1"
                },
                {
                        "S1", "AE", "", "AELNKID", "", "G2"
                },
                {
                        "S1", "FA", "", "FALNKGRP", "", "G2"
                }
        });
        Map<String, IDataTable> t = new HashMap<>();
        t.put("RELREC", relrec);
        t.put("FA", fa);

        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(ae,
                List.of(forwardRelrec()), resolver(t), "R");
        assertEquals(1, exp.table().getRowCount());
    }

    // ---- edge cases: empty expansions ----


    @Test
    void relrecAbsentYieldsEmptyExpansion()
    {
        IDataTable ae = tbl("AE", new String[]
        {
                "STUDYID", "USUBJID", "AELNKID"
        }, new String[][]
        {
                {
                        "S1", "P1", "L1"
                }
        });
        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(ae,
                List.of(forwardRelrec()), resolver(Map.of()), "R"); // resolver returns null RELREC
        assertNotNull(exp);
        assertEquals(0, exp.table().getRowCount());
    }


    @Test
    void relrecMissingRequiredColumnsYieldsEmptyExpansion()
    {
        IDataTable ae = tbl("AE", new String[]
        {
                "STUDYID", "USUBJID", "AELNKID"
        }, new String[][]
        {
                {
                        "S1", "P1", "L1"
                }
        });
        IDataTable relrec = tbl("RELREC", new String[]
        {
                "STUDYID", "USUBJID"
        }, // no RDOMAIN/IDVAR...
                new String[][]
                {
                        {
                                "S1", ""
                        }
                });
        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(ae,
                List.of(forwardRelrec()), resolver(Map.of("RELREC", relrec)), "R");
        assertEquals(0, exp.table().getRowCount());
    }


    @Test
    void unresolvedRelatedDomainYieldsEmptyExpansion()
    {
        IDataTable ae = tbl("AE", new String[]
        {
                "STUDYID", "USUBJID", "AELNKID"
        }, new String[][]
        {
                {
                        "S1", "P1", "L1"
                }
        });
        IDataTable relrec = tbl("RELREC", new String[]
        {
                "STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "RELID"
        }, new String[][]
        {
                {
                        "S1", "AE", "", "AELNKID", "", "G1"
                },
                {
                        "S1", "FA", "", "FALNKGRP", "", "G1"
                }
        });
        // FA not registered in the resolver -> pair skipped.
        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(ae,
                List.of(forwardRelrec()), resolver(Map.of("RELREC", relrec)), "R");
        assertEquals(0, exp.table().getRowCount());
    }

    // ---- numeric key coercion (mirrors the reference's float-normalized merge keys) ----


    @Test
    void recordLevelNumericIdvarvalCoercion()
    {
        // The SEQ columns stringify with a trailing ".0" (numeric typing) while the RELREC
        // IDVARVAL is "1". The reference float-normalizes both sides, so the record-level
        // IDVAR == IDVARVAL filters must still match and the pair must join.
        IDataTable cm = tbl("CM", new String[]
        {
                "STUDYID", "USUBJID", "CMSEQ", "CMTRT"
        }, new String[][]
        {
                {
                        "S1", "P1", "1.0", "ASPIRIN"
                }
        });
        IDataTable fa = tbl("FA", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "FASEQ", "FAOBJ"
        }, new String[][]
        {
                {
                        "S1", "FA", "P1", "1.0", "OBJ1"
                }
        });
        IDataTable relrec = tbl("RELREC", new String[]
        {
                "STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "RELID"
        }, new String[][]
        {
                {
                        "S1", "CM", "P1", "CMSEQ", "1", "R1"
                },
                {
                        "S1", "FA", "P1", "FASEQ", "1", "R1"
                }
        });
        Map<String, IDataTable> t = new HashMap<>();
        t.put("RELREC", relrec);
        t.put("FA", fa);

        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(cm,
                List.of(forwardRelrec()), resolver(t), "R");
        assertEquals(List.of("0:OBJ1"), collect(exp, "FAOBJ"));
    }


    @Test
    void datasetLevelNumericKeyCoercion()
    {
        // AELNKID "1" vs FALNKGRP "1.0": numeric link values typed differently across domains.
        // The dataset-level IDVAR-value equi-join float-normalizes both, so they must match.
        IDataTable ae = tbl("AE", new String[]
        {
                "STUDYID", "USUBJID", "AELNKID", "AETERM"
        }, new String[][]
        {
                {
                        "S1", "P1", "1", "REACTION"
                }
        });
        IDataTable fa = tbl("FA", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "FALNKGRP", "FAOBJ"
        }, new String[][]
        {
                {
                        "S1", "FA", "P1", "1.0", "ERYTHEMA"
                }
        });
        IDataTable relrec = tbl("RELREC", new String[]
        {
                "STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "RELID"
        }, new String[][]
        {
                {
                        "S1", "AE", "", "AELNKID", "", "G1"
                },
                {
                        "S1", "FA", "", "FALNKGRP", "", "G1"
                }
        });
        Map<String, IDataTable> t = new HashMap<>();
        t.put("RELREC", relrec);
        t.put("FA", fa);

        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(ae,
                List.of(forwardRelrec()), resolver(t), "R");
        assertEquals(List.of("0:ERYTHEMA"), collect(exp, "FAOBJ"));
    }

    // ---- mixed record-level + dataset-level for one RDOMAIN is skipped ----


    @Test
    void mixedRecordAndDatasetLevelForOneDomain_isSkipped()
    {
        IDataTable ae = tbl("AE", new String[]
        {
                "STUDYID", "USUBJID", "AESEQ", "AELNKID"
        }, new String[][]
        {
                {
                        "S1", "P1", "1", "L1"
                }
        });
        IDataTable fa = tbl("FA", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "FASEQ", "FALNKGRP", "FAOBJ"
        }, new String[][]
        {
                {
                        "S1", "FA", "P1", "1", "L1", "ERYTHEMA"
                }
        });
        // FA participates both dataset-level (blank IDVARVAL) and record-level (populated) -> skip.
        IDataTable relrec = tbl("RELREC", new String[]
        {
                "STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "RELID"
        }, new String[][]
        {
                {
                        "S1", "AE", "", "AELNKID", "", "G1"
                },
                {
                        "S1", "FA", "", "FALNKGRP", "", "G1"
                },
                {
                        "S1", "AE", "P1", "AESEQ", "1", "G2"
                },
                {
                        "S1", "FA", "P1", "FASEQ", "1", "G2"
                }
        });
        Map<String, IDataTable> t = new HashMap<>();
        t.put("RELREC", relrec);
        t.put("FA", fa);

        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(ae,
                List.of(forwardRelrec()), resolver(t), "R");
        assertEquals(0, exp.table().getRowCount());
    }

    // ---- scan-fallback paths (blank RELREC USUBJID, or subject-scoped dataset-level) ----


    @Test
    void recordLevelBlankUsubjid_joinsViaScanFallback()
    {
        IDataTable cm = tbl("CM", new String[]
        {
                "STUDYID", "USUBJID", "CMSEQ", "CMTRT"
        }, new String[][]
        {
                {
                        "S1", "P1", "1", "A"
                },
                {
                        "S1", "P2", "1", "B"
                }
        });
        IDataTable fa = tbl("FA", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "FASEQ", "FAOBJ"
        }, new String[][]
        {
                {
                        "S1", "FA", "P1", "1", "P1OBJ"
                },
                {
                        "S1", "FA", "P2", "1", "P2OBJ"
                }
        });
        // Record-level (IDVARVAL populated) but blank RELREC USUBJID -> scan fallback joins per
        // (STUDYID, USUBJID) of the dataset rows.
        IDataTable relrec = tbl("RELREC", new String[]
        {
                "STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "RELID"
        }, new String[][]
        {
                {
                        "S1", "CM", "", "CMSEQ", "1", "R1"
                },
                {
                        "S1", "FA", "", "FASEQ", "1", "R1"
                }
        });
        Map<String, IDataTable> t = new HashMap<>();
        t.put("RELREC", relrec);
        t.put("FA", fa);

        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(cm,
                List.of(forwardRelrec()), resolver(t), "R");
        assertEquals(List.of("0:P1OBJ", "1:P2OBJ"), collect(exp, "FAOBJ"));
    }


    @Test
    void datasetLevelSubjectScoped_viaScanFallback()
    {
        IDataTable ae = tbl("AE", new String[]
        {
                "STUDYID", "USUBJID", "AELNKID"
        }, new String[][]
        {
                {
                        "S1", "P1", "L1"
                },
                {
                        "S1", "P2", "L1"
                }
        });
        IDataTable fa = tbl("FA", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "FALNKGRP", "FAOBJ"
        }, new String[][]
        {
                {
                        "S1", "FA", "P1", "L1", "O1"
                },
                {
                        "S1", "FA", "P2", "L1", "O2"
                }
        });
        // Dataset-level (IDVARVAL blank) but a populated RELREC USUBJID -> scan fallback restricts
        // to
        // that subject; P2 must not be pulled in even though its link value matches.
        IDataTable relrec = tbl("RELREC", new String[]
        {
                "STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "RELID"
        }, new String[][]
        {
                {
                        "S1", "AE", "P1", "AELNKID", "", "G1"
                },
                {
                        "S1", "FA", "P1", "FALNKGRP", "", "G1"
                }
        });
        Map<String, IDataTable> t = new HashMap<>();
        t.put("RELREC", relrec);
        t.put("FA", fa);

        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(ae,
                List.of(forwardRelrec()), resolver(t), "R");
        assertEquals(List.of("0:O1"), collect(exp, "FAOBJ"));
    }

    // ---- STUDYID is part of the link identity (PLAN-relrec-studyid-link, T1-1..T1-3) ----
    //
    // SDTMIG 3.4 §3.2.1 lists RELREC's keys as STUDYID, RDOMAIN, USUBJID, IDVAR, IDVARVAL, RELID,
    // and §4.2.3 keeps one USUBJID for one person ACROSS trials — so USUBJID never scopes the
    // study. Two linked rows must be in ONE study on every path (b), and in the RELREC row's own
    // study when that STUDYID is populated (c). A blank or missing RELREC STUDYID names no study
    // (T1-3 (i)): (b) still holds, nothing narrows to a named study.


    /** The pooled-package fixture: one CM row in S1, one FA row per study for the same subject. */
    private static Map<String, IDataTable> pooledFaTables(IDataTable relrec)
    {
        IDataTable fa = tbl("FA", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "FASEQ", "FAOBJ"
        }, new String[][]
        {
                {
                        "S1", "FA", "P1", "1", "X"
                },
                {
                        "S2", "FA", "P1", "1", "Y"
                }
        });
        Map<String, IDataTable> t = new HashMap<>();
        t.put("RELREC", relrec);
        t.put("FA", fa);
        return t;
    }


    private static IDataTable cmS1()
    {
        return tbl("CM", new String[]
        {
                "STUDYID", "USUBJID", "CMSEQ", "CMTRT"
        }, new String[][]
        {
                {
                        "S1", "P1", "1", "A"
                }
        });
    }


    private static IDataTable recordLevelRelrec(String study)
    {
        return tbl("RELREC", new String[]
        {
                "STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "RELID"
        }, new String[][]
        {
                {
                        study, "CM", "P1", "CMSEQ", "1", "R1"
                },
                {
                        study, "FA", "P1", "FASEQ", "1", "R1"
                }
        });
    }


    @Test
    void recordLevel_pooledSameUsubjid_linksOnlyTheRelrecStudy()
    {
        // S1 and S2 both hold P1 with FASEQ 1 (the §4.2.3 pattern: one person, one USUBJID, two
        // trials). The S1 RELREC row names the S1 record; the S2 row must not be pulled in.
        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(cmS1(),
                List.of(forwardRelrec()), resolver(pooledFaTables(recordLevelRelrec("S1"))), "R");
        assertEquals(List.of("0:X"), collect(exp, "FAOBJ"));
    }


    @Test
    void recordLevelRelrecStudyidDiffersFromDataset_doesNotJoin()
    {
        // RELREC STUDYID names a study the data does not hold: (c) — the RELREC row names its
        // record by STUDYID too, so no record is named and nothing links. (Until 2026-09-26 this
        // test asserted the opposite, on Python parity, which is retired.)
        IDataTable fa = tbl("FA", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "FASEQ", "FAOBJ"
        }, new String[][]
        {
                {
                        "S1", "FA", "P1", "1", "OBJ"
                }
        });
        Map<String, IDataTable> t = new HashMap<>();
        t.put("RELREC", recordLevelRelrec("ZZZ"));
        t.put("FA", fa);

        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(cmS1(),
                List.of(forwardRelrec()), resolver(t), "R");
        assertNotNull(exp);
        assertEquals(List.of(), collect(exp, "FAOBJ"));
    }


    @Test
    void recordLevel_blankRelrecStudyid_stillRequiresOneStudy()
    {
        // T1-3 (i): a blank RELREC STUDYID (Req, so the data is already non-conformant) names no
        // study — (b) alone: the S1 CM row links the S1 FA row, never the S2 one.
        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(cmS1(),
                List.of(forwardRelrec()), resolver(pooledFaTables(recordLevelRelrec(""))), "R");
        assertEquals(List.of("0:X"), collect(exp, "FAOBJ"));
    }


    @Test
    void recordLevel_missingRelrecStudyid_sameAsBlank()
    {
        // A SAS-missing RELREC STUDYID is "no study named" exactly like a blank one (T1-3 (i)).
        IDataTable relrec = MockTable.of().colSasMissing("STUDYID", (String) null, (String) null)
                .col("RDOMAIN", "CM", "FA").col("USUBJID", "P1", "P1")
                .col("IDVAR", "CMSEQ", "FASEQ").col("IDVARVAL", "1", "1").col("RELID", "R1", "R1")
                .name("RELREC").build();
        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(cmS1(),
                List.of(forwardRelrec()), resolver(pooledFaTables(relrec)), "R");
        assertEquals(List.of("0:X"), collect(exp, "FAOBJ"));
    }


    /** AE and FA each hold the link value L1 for P1 in S1 AND in S2. */
    private static Map<String, IDataTable> pooledLinkGroupTables(String relrecStudy)
    {
        IDataTable fa = tbl("FA", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "FALNKGRP", "FAOBJ"
        }, new String[][]
        {
                {
                        "S1", "FA", "P1", "L1", "O1"
                },
                {
                        "S2", "FA", "P1", "L1", "O2"
                }
        });
        IDataTable relrec = tbl("RELREC", new String[]
        {
                "STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "RELID"
        }, new String[][]
        {
                {
                        relrecStudy, "AE", "", "AELNKID", "", "G1"
                },
                {
                        relrecStudy, "FA", "", "FALNKGRP", "", "G1"
                }
        });
        Map<String, IDataTable> t = new HashMap<>();
        t.put("RELREC", relrec);
        t.put("FA", fa);
        return t;
    }


    private static IDataTable pooledAe()
    {
        return tbl("AE", new String[]
        {
                "STUDYID", "USUBJID", "AELNKID"
        }, new String[][]
        {
                {
                        "S1", "P1", "L1"
                },
                {
                        "S2", "P1", "L1"
                }
        });
    }


    @Test
    void datasetLevel_relrecStudy_restrictsToThatStudy()
    {
        // Dataset-level (blank USUBJID/IDVARVAL) rows in S1: only the S1 pair, never S2's.
        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(pooledAe(),
                List.of(forwardRelrec()), resolver(pooledLinkGroupTables("S1")), "R");
        assertEquals(List.of("0:O1"), collect(exp, "FAOBJ"));
    }


    @Test
    void datasetLevel_blankRelrecStudy_pairsWithinEachStudy()
    {
        // Control (T1-3 (i)): with no study named, each study pairs with itself and never across.
        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(pooledAe(),
                List.of(forwardRelrec()), resolver(pooledLinkGroupTables("")), "R");
        assertEquals(List.of("0:O1", "1:O2"), collect(exp, "FAOBJ"));
    }


    private static IDataTable faWithoutStudyid()
    {
        return tbl("FA", new String[]
        {
                "DOMAIN", "USUBJID", "FASEQ", "FAOBJ"
        }, new String[][]
        {
                {
                        "FA", "P1", "1", "OBJ"
                }
        });
    }


    @Test
    void fastPath_targetWithoutStudyidColumn_populatedRelrecStudy_noLink()
    {
        // JKM R7: an absent STUDYID column is present-but-empty, and "" is not "S1". The
        // record-level fast path (populated USUBJID) used to key on (USUBJID, value) alone and
        // linked this.
        Map<String, IDataTable> t = new HashMap<>();
        t.put("RELREC", recordLevelRelrec("S1"));
        t.put("FA", faWithoutStudyid());
        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(cmS1(),
                List.of(forwardRelrec()), resolver(t), "R");
        assertEquals(List.of(), collect(exp, "FAOBJ"));
    }


    private static IDataTable cmWithoutStudyid()
    {
        return tbl("CM", new String[]
        {
                "USUBJID", "CMSEQ", "CMTRT"
        }, new String[][]
        {
                {
                        "P1", "1", "A"
                }
        });
    }


    private static IDataTable scanRelrec(String study)
    {
        // Blank RELREC USUBJID on a record-level link: the full-scan path.
        return tbl("RELREC", new String[]
        {
                "STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "RELID"
        }, new String[][]
        {
                {
                        study, "CM", "", "CMSEQ", "1", "R1"
                },
                {
                        study, "FA", "", "FASEQ", "1", "R1"
                }
        });
    }


    @Test
    void scan_neitherDatasetHasStudyid_populatedRelrecStudy_noLink()
    {
        // Both sides read "" (JKM R7), which equals each other (b) but not the named "S1" (c).
        Map<String, IDataTable> t = new HashMap<>();
        t.put("RELREC", scanRelrec("S1"));
        t.put("FA", faWithoutStudyid());
        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(cmWithoutStudyid(),
                List.of(forwardRelrec()), resolver(t), "R");
        assertEquals(List.of(), collect(exp, "FAOBJ"));
    }


    @Test
    void scan_neitherDatasetHasStudyid_blankRelrecStudy_links()
    {
        // Control: "" = "" and no study is named, so the link is made (the pre-fix behaviour on
        // this shape is kept — nothing is manufactured, nothing is lost).
        Map<String, IDataTable> t = new HashMap<>();
        t.put("RELREC", scanRelrec(""));
        t.put("FA", faWithoutStudyid());
        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(cmWithoutStudyid(),
                List.of(forwardRelrec()), resolver(t), "R");
        assertEquals(List.of("0:OBJ"), collect(exp, "FAOBJ"));
    }

    // ---- key text beyond 2^53 (PLAN-relrec-idvar-key-precision, T1-1 (a); RRK E2) ----


    private static IDataTable seqTables(String cmSeq, String faSeq, Map<String, IDataTable> into)
    {
        IDataTable cm = tbl("CM", new String[]
        {
                "STUDYID", "USUBJID", "CMSEQ", "CMTRT"
        }, new String[][]
        {
                {
                        "S1", "P1", cmSeq, "A"
                }
        });
        into.put("FA", tbl("FA", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "FASEQ", "FAOBJ"
        }, new String[][]
        {
                {
                        "S1", "FA", "P1", faSeq, "OBJ"
                }
        }));
        return cm;
    }


    private static IDataTable idvarvalRelrec(String cmIdvarval, String faIdvarval)
    {
        return tbl("RELREC", new String[]
        {
                "STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "RELID"
        }, new String[][]
        {
                {
                        "S1", "CM", "P1", "CMSEQ", cmIdvarval, "R1"
                },
                {
                        "S1", "FA", "P1", "FASEQ", faIdvarval, "R1"
                }
        });
    }


    @Test
    void recordLevel_idvarvalBeyond2p53_isNotItsNeighbour()
    {
        // 9007199254740993 and 9007199254740992 are one double. The IDVAR == IDVARVAL filter
        // compares exact decimal text, so the RELREC value names neither CM nor FA row here.
        Map<String, IDataTable> t = new HashMap<>();
        IDataTable cm = seqTables("9007199254740992", "9007199254740992", t);
        t.put("RELREC", idvarvalRelrec("9007199254740993", "9007199254740993"));
        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(cm,
                List.of(forwardRelrec()), resolver(t), "R");
        assertEquals(List.of(), collect(exp, "FAOBJ"));
    }


    @Test
    void recordLevel_idvarvalBeyond2p53_matchesItsOwnDigits()
    {
        // Control: the same 16-digit value on both sides still links, and "…993.0" folds onto it.
        Map<String, IDataTable> t = new HashMap<>();
        IDataTable cm = seqTables("9007199254740993", "9007199254740993", t);
        t.put("RELREC", idvarvalRelrec("9007199254740993", "9007199254740993.0"));
        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(cm,
                List.of(forwardRelrec()), resolver(t), "R");
        assertEquals(List.of("0:OBJ"), collect(exp, "FAOBJ"));
    }


    @Test
    void datasetLevel_linkValuesBeyond2p53_areTwoKeys()
    {
        IDataTable ae = tbl("AE", new String[]
        {
                "STUDYID", "USUBJID", "AELNKID"
        }, new String[][]
        {
                {
                        "S1", "P1", "9007199254740993"
                }
        });
        IDataTable fa = tbl("FA", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "FALNKGRP", "FAOBJ"
        }, new String[][]
        {
                {
                        "S1", "FA", "P1", "9007199254740992", "ERYTHEMA"
                }
        });
        IDataTable relrec = tbl("RELREC", new String[]
        {
                "STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "RELID"
        }, new String[][]
        {
                {
                        "S1", "AE", "", "AELNKID", "", "G1"
                },
                {
                        "S1", "FA", "", "FALNKGRP", "", "G1"
                }
        });
        Map<String, IDataTable> t = new HashMap<>();
        t.put("RELREC", relrec);
        t.put("FA", fa);
        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(ae,
                List.of(forwardRelrec()), resolver(t), "R");
        assertEquals(List.of(), collect(exp, "FAOBJ"),
                "two integers that differ beyond 2^53 must not fold onto one key");
    }

    // ---- Fix #358 (ruling 2): a forward-RELREC target that ships split resolves the union ----


    /**
     * A dataset-level RELREC link pointing at {@code LB} on a submission that splits LB expands
     * rows targeting <em>either</em> member. ⚠ Real member tables + a {@code WithInventory}
     * resolver ({@link RealTables}) — the plain-lambda resolver above cannot reach the union.
     */
    @Test
    void splitTargetDomain_expandsAgainstTheMemberUnion()
    {
        IDataTable ae = tbl("AE", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "AELNKID", "AETERM"
        }, new String[][]
        {
                {
                        "S1", "AE", "P1", "L1", "REACTION"
                }
        });
        IDataTable lbch = RealTables.of("lbch").str("STUDYID", "S1").str("DOMAIN", "LB")
                .str("USUBJID", "P1").str("LBLNKGRP", "L1").str("LBORRES", "res-ch").build();
        IDataTable lbhe = RealTables.of("lbhe").str("STUDYID", "S1").str("DOMAIN", "LB")
                .str("USUBJID", "P1").str("LBLNKGRP", "L1").str("LBORRES", "res-he").build();
        IDataTable relrec = tbl("RELREC", new String[]
        {
                "STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "RELTYPE", "RELID"
        }, new String[][]
        {
                {
                        "S1", "AE", "", "AELNKID", "", "ONE", "AELB"
                },
                {
                        "S1", "LB", "", "LBLNKGRP", "", "MANY", "AELB"
                }
        });
        Map<String, IDataTable> t = new HashMap<>();
        t.put("RELREC", relrec);
        t.put("LBCH", lbch);
        t.put("LBHE", lbhe);

        RelrecRowExpander.RelrecExpansion exp = RelrecRowExpander.expand(ae,
                List.of(forwardRelrec()), RealTables.inventory(t), "R");
        assertNotNull(exp);
        List<String> got = collect(exp, "LBORRES");
        assertTrue(got.contains("0:res-ch"), "row from lbch must be reachable: " + got);
        assertTrue(got.contains("0:res-he"), "row from lbhe must be reachable: " + got);
        assertEquals(2, exp.table().getRowCount());
    }


    /** Ruling 1: an un-unionable split target propagates as the rule-ERROR exception. */
    @Test
    void splitTargetDomain_typeClash_throws()
    {
        IDataTable ae = tbl("AE", new String[]
        {
                "STUDYID", "DOMAIN", "USUBJID", "AELNKID"
        }, new String[][]
        {
                {
                        "S1", "AE", "P1", "L1"
                }
        });
        IDataTable lbch = RealTables.of("lbch").str("STUDYID", "S1").str("DOMAIN", "LB")
                .str("USUBJID", "P1").str("LBLNKGRP", "L1").lng("LBSTRESN", 1L).build();
        IDataTable lbhe = RealTables.of("lbhe").str("STUDYID", "S1").str("DOMAIN", "LB")
                .str("USUBJID", "P1").str("LBLNKGRP", "L1").str("LBSTRESN", "x").build();
        IDataTable relrec = tbl("RELREC", new String[]
        {
                "STUDYID", "RDOMAIN", "USUBJID", "IDVAR", "IDVARVAL", "RELTYPE", "RELID"
        }, new String[][]
        {
                {
                        "S1", "AE", "", "AELNKID", "", "ONE", "AELB"
                },
                {
                        "S1", "LB", "", "LBLNKGRP", "", "MANY", "AELB"
                }
        });
        Map<String, IDataTable> t = new HashMap<>();
        t.put("RELREC", relrec);
        t.put("LBCH", lbch);
        t.put("LBHE", lbhe);
        DatasetResolver inv = RealTables.inventory(t);

        InvalidJoinedDomainException ex = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidJoinedDomainException.class,
                () -> RelrecRowExpander.expand(ae, List.of(forwardRelrec()), inv, "R"));
        assertTrue(ex.getMessage().contains("LBSTRESN"), ex.getMessage());
    }
}
