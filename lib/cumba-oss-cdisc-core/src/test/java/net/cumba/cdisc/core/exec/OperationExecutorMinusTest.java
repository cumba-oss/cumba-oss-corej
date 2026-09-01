package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@link OperationExecutor} {@code minus} (order-preserving set difference) — mirrors Python's
 * {@code operations/minus.py}: result = elements of {@code name} not in {@code subtract},
 * preserving {@code name}'s order; both operands are {@code $}-refs to prior operation results.
 */
class OperationExecutorMinusTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    /** minus is table-independent; any non-null table satisfies the dispatch. */
    private static final IDataTable TABLE = MockTable.of().col("X", "1").build();

    private static Operation minusOp(String name, String subtract)
    {
        Operation op = new Operation();
        op.setId("$missing");
        op.setOperator("minus");
        op.setName(name);
        op.setSubtract(subtract);
        return op;
    }


    @SuppressWarnings("unchecked")
    private static List<String> run(Operation op, Map<String, Object> prior)
    {
        Object r = OperationExecutor.executeOne(op, TABLE, NO_RESOLVER, null, prior);
        return (List<String>) r;
    }


    @Test
    void basicDifferencePreservesMinuendOrder()
    {
        Map<String, Object> prior = new LinkedHashMap<>();
        prior.put("$a", List.of("A", "B", "C"));
        prior.put("$b", List.of("B"));
        assertEquals(List.of("A", "C"), run(minusOp("$a", "$b"), prior));
    }


    @Test
    void emptyMinuendYieldsEmpty()
    {
        Map<String, Object> prior = new LinkedHashMap<>();
        prior.put("$a", List.of());
        prior.put("$b", List.of("B"));
        assertEquals(List.of(), run(minusOp("$a", "$b"), prior));
    }


    @Test
    void absentMinuendYieldsEmpty()
    {
        // $a not in the prior-results map → normalizes to [] → empty result.
        assertEquals(List.of(), run(minusOp("$a", "$b"), new LinkedHashMap<>()));
    }


    @Test
    void absentSubtractRefYieldsMinuendUnchanged()
    {
        Map<String, Object> prior = new LinkedHashMap<>();
        prior.put("$a", List.of("A", "B"));
        // subtract field is null → minuend returned unchanged.
        assertEquals(List.of("A", "B"), run(minusOp("$a", null), prior));
    }


    @Test
    void nullSubtractValueYieldsMinuendUnchanged()
    {
        Map<String, Object> prior = new LinkedHashMap<>();
        prior.put("$a", List.of("A", "B"));
        // subtract names $b but $b resolves to null → minuend returned unchanged.
        assertEquals(List.of("A", "B"), run(minusOp("$a", "$b"), prior));
    }


    @Test
    void duplicatesInMinuendArePreserved()
    {
        Map<String, Object> prior = new LinkedHashMap<>();
        prior.put("$a", List.of("A", "B", "A", "C"));
        prior.put("$b", List.of("C"));
        assertEquals(List.of("A", "B", "A"), run(minusOp("$a", "$b"), prior));
    }


    @Test
    void scalarOperandsAreCoercedToSingletonLists()
    {
        Map<String, Object> prior = new LinkedHashMap<>();
        prior.put("$a", "SOLO");
        prior.put("$b", "OTHER");
        assertEquals(List.of("SOLO"), run(minusOp("$a", "$b"), prior));
    }


    @Test
    void scalarMinuendRemovedBySubtrahend()
    {
        Map<String, Object> prior = new LinkedHashMap<>();
        prior.put("$a", "X");
        prior.put("$b", List.of("X"));
        assertEquals(List.of(), run(minusOp("$a", "$b"), prior));
    }


    @Test
    void arrayOperandsAreCoercedToLists()
    {
        Map<String, Object> prior = new LinkedHashMap<>();
        prior.put("$a", new String[]
        {
                "A", "B", "C"
        });
        prior.put("$b", new String[]
        {
                "B"
        });
        assertEquals(List.of("A", "C"), run(minusOp("$a", "$b"), prior));
    }

    // ---- EC-7: literal `value` list minuend --------------------------------------------------


    private static Operation minusValueOp(List<String> value, String subtract)
    {
        Operation op = new Operation();
        op.setId("$missing");
        op.setOperator("minus");
        op.setValue(value);
        op.setSubtract(subtract);
        return op;
    }


    @Test
    void literalValueMinuendSubtractsRefSubtrahend()
    {
        // EC-7: value=[AGEU, SDESIGN, ARM] minus a $-ref subtrahend containing the present ones ⇒
        // the authored literals absent from the data are reported.
        Map<String, Object> prior = new LinkedHashMap<>();
        prior.put("$present", List.of("AGEU", "ARM"));
        assertEquals(List.of("SDESIGN"),
                run(minusValueOp(List.of("AGEU", "SDESIGN", "ARM"), "$present"), prior));
    }


    @Test
    void literalValueMinuendEmptyYieldsEmpty()
    {
        // An empty literal minuend yields [] regardless of the subtrahend.
        Map<String, Object> prior = new LinkedHashMap<>();
        prior.put("$present", List.of("AGEU"));
        assertEquals(List.of(), run(minusValueOp(List.of(), "$present"), prior));
    }


    @Test
    void literalValueMinuendWithMissingSubtractYieldsLiteralUnchanged()
    {
        // subtract names $present but it is absent from prior → the literal minuend is returned
        // unchanged (order preserved).
        assertEquals(List.of("AGEU", "SDESIGN"),
                run(minusValueOp(List.of("AGEU", "SDESIGN"), "$present"), new LinkedHashMap<>()));
    }


    @Test
    void literalValueTakesPrecedenceOverNameRef()
    {
        // When both value and name are set, the literal value wins as the minuend.
        Map<String, Object> prior = new LinkedHashMap<>();
        prior.put("$a", List.of("SHOULD", "NOT", "BE", "USED"));
        prior.put("$b", List.of("B"));
        Operation op = minusValueOp(List.of("A", "B", "C"), "$b");
        op.setName("$a");
        assertEquals(List.of("A", "C"), run(op, prior));
    }


    @Test
    void chainsThroughLegacyMultiOpDriver()
    {
        // Two distinct operations feed a third minus operation through the accumulating
        // prior-results map of OperationExecutor.execute (the legacy multi-op driver).
        IDataTable table = MockTable.of().col("A", "X", "Y", "Z").col("B", "Y", "Y", "Y").build();
        Operation d1 = new Operation();
        d1.setId("$all");
        d1.setOperator("distinct");
        d1.setName("A");
        Operation d2 = new Operation();
        d2.setId("$some");
        d2.setOperator("distinct");
        d2.setName("B");
        Operation minus = minusOp("$all", "$some");
        Map<String, Object> vars = OperationExecutor.execute(List.of(d1, d2, minus), table,
                NO_RESOLVER);
        assertEquals(List.of("X", "Z"), vars.get("$missing"));
    }
}
