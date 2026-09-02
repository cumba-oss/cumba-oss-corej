package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Review H1 (ruled 2026-08-19) — {@code distinct} with a {@code names:} tuple normalises the
 * {@code IDVARVAL} slot with {@link ChildMatchIndex#normalizeJoinToken} semantics, so the SUPP--
 * key set compares equal to the probe side's {@code tuple(USUBJID, --SEQ)} cell, whose numeric
 * rendering is already canonical ({@code DataValueDouble} renders an integral double without the
 * {@code .0}).
 *
 * <p>
 * The four token shapes pinned here are the review's worked forms: SAS right/left padding
 * ({@code " 1"}), a zero-padded integer ({@code "01"}), a float rendering ({@code "1.0"}) and an
 * explicit sign ({@code "+1"}) — all canonicalise to {@code "1"}. A non-numeric token is only
 * stripped, so Char keys such as {@code --SPID} values keep their identity.
 * </p>
 */
class OperationExecutorJoinTokenNormalizeTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    private static Operation tupleOp()
    {
        Operation op = new Operation();
        op.setId("$keys");
        op.setOperator("distinct");
        op.setNames(List.of("USUBJID", "IDVARVAL"));
        return op;
    }


    @SuppressWarnings("unchecked")
    private static Set<List<String>> run(Operation op, IDataTable table)
    {
        Object result = OperationExecutor.execute(List.of(op), table, NO_RESOLVER).get("$keys");
        assertInstanceOf(Set.class, result, "distinct with names yields a tuple set");
        return (Set<List<String>>) result;
    }


    @Test
    void idvarvalTokensAreCanonicalised_paddedZeroPaddedFloatAndSigned()
    {
        IDataTable suppae = MockTable.of().name("SUPPAE").col("USUBJID", "001", "001", "002", "002")
                .col("IDVARVAL", " 1", "01", "1.0", "+1").build();

        Set<List<String>> keys = run(tupleOp(), suppae);

        assertEquals(Set.of(List.of("001", "1"), List.of("002", "1")), keys,
                "\" 1\", \"01\", \"1.0\" and \"+1\" are all the join token 1 — byte-equal to the"
                        + " probe side's canonical numeric rendering");
    }


    @Test
    void nonNumericTokensAreStrippedOnly_charKeysKeepTheirIdentity()
    {
        IDataTable suppae = MockTable.of().name("SUPPAE").col("USUBJID", "001", "001")
                .col("IDVARVAL", " H-14 ", "H-15").build();

        Set<List<String>> keys = run(tupleOp(), suppae);

        assertEquals(Set.of(List.of("001", "H-14"), List.of("001", "H-15")), keys,
                "a non-numeric token is stripped, never numerically rewritten");
    }


    @Test
    void onlyTheIdvarvalSlotIsNormalised_otherColumnsStayVerbatim()
    {
        IDataTable suppae = MockTable.of().name("SUPPAE").col("USUBJID", "01", " 007 ")
                .col("IDVARVAL", "2", "2").build();

        Set<List<String>> keys = run(tupleOp(), suppae);

        assertEquals(Set.of(List.of("01", "2"), List.of(" 007 ", "2")), keys,
                "USUBJID is an identity column, not a join token — \"01\" and \"1\" are different"
                        + " subjects and padding is preserved verbatim");
    }


    @Test
    void filteredRowsStillNormalise_theSd1143Shape()
    {
        IDataTable suppae = MockTable.of().name("SUPPAE").col("RDOMAIN", "AE", "AE", "AE")
                .col("USUBJID", "001", "001", "002").col("IDVAR", "AESEQ", "AESEQ", "AESPID")
                .col("IDVARVAL", " 1", "2.0", "H-14").col("QNAM", "AESOSP", "AESOSP", "AESOSP")
                .build();

        Operation op = tupleOp();
        op.setFilter(Map.of("RDOMAIN", "AE", "IDVAR", "AESEQ", "QNAM", "AESOSP"));
        Set<List<String>> keys = run(op, suppae);

        assertEquals(Set.of(List.of("001", "1"), List.of("001", "2")), keys,
                "the IDVAR filter still selects rows first; the surviving IDVARVAL tokens are"
                        + " canonical, so AESEQ 1 and 2 both match their AE records");
    }

}
