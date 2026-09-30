package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.NativeExprEvaluator;
import net.cumba.corej.core.gen.DefineXMLProvider;
import net.cumba.corej.core.metadata.DefineXmlMetadataProvider;
import net.cumba.datatable.testkit.SyntheticDataTable;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * Register {@code NNL §1} ({@code PLAN-no-null-list-elements} §3, row 14): a {@code null} element
 * in a list value is a producer defect that throws <b>where the list is born</b>, naming the
 * producer — at {@link ConstVector#of} for a constant list and at each function's own birth site
 * (an operation result's was {@code OperationExecutor.executeOne}, retired in runbook W8) — and
 * nowhere per row.
 *
 * <p>
 * Mockito-free: a five-method {@link DefineXMLProvider} fake, real {@link SyntheticDataTable}.
 * Red-before on HEAD: (1) returned the list with its {@code null} intact, (2) built the vector.
 * </p>
 */
class ListValueGuardTest
{

    /** A Define provider whose dataset list carries a {@code null} — the producer defect. */
    private static final class DefectiveDefine implements DefineXMLProvider
    {

        @Override
        public Map<String, String> getDatasetMetadata(String datasetName)
        {
            return Map.of();
        }


        @Override
        public List<Map<String, String>> getVariables(String datasetName)
        {
            return List.of();
        }


        @Override
        public List<Map<String, String>> getCodelistTerms(String codelistOID)
        {
            return List.of();
        }


        @Override
        public List<String> getDatasetNames()
        {
            return Arrays.asList("AE", null);
        }


        @Override
        public List<String> getKeyVariables(String datasetName)
        {
            return List.of();
        }
    }

    @Test
    void aDefineWalkRejectsANullElementNamingTheFunction()
    {
        // define_dataset_names() is a registry function since wave 4; its list is born at
        // ConstVector.of, the guard site, which names the function.
        SyntheticDataTable dm = new SyntheticDataTable("DM", List.of("STUDYID"), new String[]
        {
                "S1"
        }, 1);
        DefineXmlMetadataProvider define = new DefineXmlMetadataProvider(new DefectiveDefine(),
                null);
        EvaluationContext ctx = EvaluationContext.builder().table(dm).defineProvider(define)
                .ruleId("T-NNL").build();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DefineLists.defineDatasetNames(EvalRun.fullRange(ctx), List.of()));

        assertEquals("define_dataset_names() produced a list with a null element at index 1"
                + " — nothing is ever null (register NNL §1)", e.getMessage());
    }


    @Test
    void constVectorRejectsANullElementAndAcceptsMissingAndEmpty()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ConstVector.of(Arrays.asList("A", null)));
        assertTrue(e.getMessage().startsWith(
                "a constant list value (ConstVector.of) produced a list with a null element at"
                        + " index 1"),
                e.getMessage());

        // Not throwing IS the contract for a clean list: a MissingValue and "" are values.
        List<Object> clean = List.of("A", MissingValue.MIS, "");
        ConstVector v = assertDoesNotThrow(() -> ConstVector.of(clean));
        assertEquals(clean, v.value(0).resolved(), "the clean list is carried unchanged");
    }


    @Test
    void aScalarNullIsNotAListValue()
    {
        // A scalar null is the untyped scalar channel (NF §1, still target) — out of this guard's
        // scope, so not throwing is the contract here.
        assertDoesNotThrow(() -> ListValueGuard.requireNoNullElement(null, () -> "scalar"),
                "a scalar null result is not a list value");
    }


    @Test
    void immutableTuplesPassAndOneNestingLevelIsScanned()
    {
        // List.of(...).contains(null) throws NPE — the scan is a loop, so the immutable tuples a
        // distinct([A, B]) result is made of pass without touching contains(null).
        List<List<String>> tuples = List.of(List.of("A", "B"), List.of("C", "D"));
        assertDoesNotThrow(() -> ListValueGuard.requireNoNullElement(tuples, () -> "distinct"));

        // One nesting level: a null INSIDE a tuple is found. The inner index (0) and the outer one
        // (1) differ on purpose, so the message is seen to name BOTH positions — the tuple and the
        // component (review round 1, L-1).
        List<List<String>> nested = List.of(List.of("A", "B"), Arrays.asList(null, "C"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ListValueGuard.requireNoNullElement(nested, () -> "distinct"));
        assertEquals("distinct produced a list with a null element at index 0 of the nested list"
                + " at index 1 — nothing is ever null (register NNL §1)", e.getMessage());
    }

    // ---- review round 1, L-2: the per-row list scan pinned THROUGH its producer --------------


    /**
     * {@code get_parent_model_column_order} answers a per-row list keyed by {@code RDOMAIN} whose
     * values are the library's lists — so a library provider that emits a {@code null} variable
     * name is a producer defect, and it must throw where the list is born. Pinned here, not only
     * through {@link ListValueGuard#requireNoNullElement}. (Review round 1 pinned it through the
     * operation executor's grouped result, both retired in runbook W8.)
     */
    @Test
    void theParentWalkScansEachParentsListNamingTheParent()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> parentModelColumnOrder(Arrays.asList("STUDYID", null)));
        assertEquals("get_parent_model_column_order(AE) produced a list with a null element at"
                + " index 1 — nothing is ever null (register NNL §1)", e.getMessage());

        assertTrue(
                parentModelColumnOrder(List.of("STUDYID",
                        "DOMAIN")) instanceof net.cumba.corej.core.expr.eval.ComputedVector,
                "the clean answer is a per-row list, so the throw above came from its scan");
    }


    private static net.cumba.corej.core.expr.eval.Vector parentModelColumnOrder(
            List<String> modelVariables)
    {
        SyntheticDataTable suppae = new SyntheticDataTable("SUPPAE", List.of("RDOMAIN"),
                new String[]
                {
                        "AE"
                }, 1);
        SyntheticDataTable ae = new SyntheticDataTable("AE", List.of("AETERM"), new String[]
        {
                "x"
        }, 1);
        EvaluationContext ctx = EvaluationContext.builder().table(suppae)
                .datasetResolver(n -> "AE".equals(n) ? ae : null)
                .libraryProvider(new Library(modelVariables, List.of())).ruleId("T-NNL").build();
        int rdomain = suppae.getMetaData().getColumnIndex("RDOMAIN");
        return ParentModelColumnOrder.evaluate(EvalRun.fullRange(ctx),
                List.of(new net.cumba.corej.core.expr.eval.ColumnVector("RDOMAIN",
                        suppae.getColumn(rdomain),
                        suppae.getMetaData().getColumn(rdomain).getType())));
    }

    // ---- review round 1, LOW-1: ConstVector.of names the producer at each list birth site ------


    /** A {@code $}-variable read through {@code variableVector}: the error names the variable. */
    @Test
    void aVariableListNamesTheVariable()
    {
        SyntheticDataTable t = new SyntheticDataTable("DS", List.of("X"), new String[]
        {
                "A"
        }, 1);
        EvaluationContext ctx = EvaluationContext.builder().table(t)
                .variables(Map.of("$lst", Arrays.asList("A", null))).build();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NativeExprEvaluator
                        .evaluate(CheckExpressionParser.parse("contains($lst, \"A\")"), ctx));
        assertTrue(
                e.getMessage()
                        .startsWith("variable $lst produced a list with a null element at index 1"),
                e.getMessage());
    }


    /**
     * A list accessor ({@code DefineMetadataListCodec.decode}): a JSON {@code null} element in the
     * provider's encoded list is served as a {@code null} — the error names the accessor.
     */
    @Test
    void aListAccessorNamesTheAccessor() throws Exception
    {
        String json = "{\"Core\":{\"Id\":\"R1\"},\"Variable_Universe\":\"Define\","
                + "\"Sensitivity\":\"Dataset\",\"Check\":{\"all\":["
                + "{\"expression\":\"define_variable_codelist_coded_codes in [\\\"AE\\\"]\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}";
        net.cumba.corej.core.model.Rule rule = net.cumba.corej.core.RulePackageLoader
                .loadFromString("{\"rules\":{\"R1\":" + json + "}}").getRules().get("R1");
        SyntheticDataTable ae = new SyntheticDataTable("AE", List.of("AEACN"), new String[]
        {
                "x"
        }, 1);
        MetadataProvider define = new StubMetadataProvider().variable("AE",
                Map.of("name", "AEACN", "codelist_coded_codes", "[\"AE\",null]"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RuleRunnerCalls.execute(rule, ae, _ -> null, "AE", null, null, define));
        assertTrue(
                e.getMessage()
                        .startsWith("list accessor var_codelist_coded_codes"
                                + " produced a list with a null element at index 1"),
                e.getMessage());
    }


    /**
     * {@code get_codelist_attributes}: a Library answering a {@code null} attribute value names the
     * function. Red before: {@code List.copyOf} threw a bare {@code NullPointerException} naming
     * nothing, before the value ever reached {@code ConstVector.of}.
     */
    @Test
    void codelistAttributesNamesTheFunction()
    {
        SyntheticDataTable ts = new SyntheticDataTable("TS", List.of("TSVCDREF"), new String[]
        {
                "CDISC"
        }, 1);
        EvaluationContext ctx = EvaluationContext.builder().table(ts)
                .libraryProvider(new Library(List.of(), Arrays.asList("C1", null))).build();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CodelistAttributes.evaluate(EvalRun.fullRange(ctx),
                        List.of(ConstVector.of("CDISC"), ConstVector.of("2024-09-27"),
                                ConstVector.of("Term CCODE"))));
        assertEquals(
                "get_codelist_attributes(ct_attribute=\"Term CCODE\") produced a list with a"
                        + " null element at index 1 — nothing is ever null (register NNL §1)",
                e.getMessage());
    }

    /**
     * A ten-method Library fake: {@code getStandardModelVariables} and {@code getCodelistAttribute}
     * answer the lists it is given, everything else is empty.
     */
    private static final class Library implements MetadataProvider
    {

        private final List<String> modelVariables;

        private final List<String> attributeValues;

        Library(List<String> aModelVariables, List<String> aAttributeValues)
        {
            modelVariables = aModelVariables;
            attributeValues = aAttributeValues;
        }


        @Override
        public List<String> getStandardModelVariables(net.cumba.datatable.IDataTable aTable,
                DatasetResolver aResolver)
        {
            return modelVariables;
        }


        @Override
        public List<String> getCodelistAttribute(String aCtPackageId, String aCtAttribute)
        {
            return attributeValues;
        }


        @Override
        public List<String> getRequiredVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getExpectedVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getColumnOrder(String domain)
        {
            return List.of();
        }


        @Override
        public boolean isDomainCustom(String domain)
        {
            return false;
        }


        @Override
        public List<String> getCodelistTerms(String codelistCode)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getVariableMetadata(String domain, String variable)
        {
            return Map.of();
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String domain)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getDatasetMetadata(String domain)
        {
            return Map.of();
        }


        @Override
        public java.util.Optional<Boolean> isCodelistExtensible(String codelistName)
        {
            return java.util.Optional.empty();
        }


        @Override
        public String getStandard()
        {
            return "sdtmig";
        }
    }

}
