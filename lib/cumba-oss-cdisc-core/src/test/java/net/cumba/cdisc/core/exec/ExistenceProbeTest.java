package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Covers the name-existence and metadata probes that survived the removal of the legacy operator
 * engine — the exact entry points {@code ExprCompiler} calls when it compiles {@code var_exists} /
 * {@code ds_exists} / {@code var_is_null} / {@code max_value_length} plans, plus the two helpers
 * shared with {@link CohortRunner} and {@link ScopeVariableSource}.
 *
 * <p>
 * These were previously exercised indirectly through the legacy operator leaves; with those gone
 * the probes need direct coverage, including the dotted {@code DOMAIN.KEY=VALUE} filter form and
 * the dataset-level {@code ${...}} substitution fallback.
 * </p>
 */
class ExistenceProbeTest
{

    private static IDataTable primary()
    {
        return MockTable.of().name("AE").col("USUBJID", "S1", "S2").col("AETERM", "X", "Y").build();
    }


    private static DatasetResolver resolverOf(Map<String, IDataTable> byName)
    {
        return name -> name == null ? null : byName.get(name);
    }


    private static Map<String, IDataTable> map(Object... pairs)
    {
        Map<String, IDataTable> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2)
        {
            m.put((String) pairs[i], (IDataTable) pairs[i + 1]);
        }
        return m;
    }


    private static EvaluationContext ctx(IDataTable table, Map<String, IDataTable> datasets)
    {
        // A null-tolerant map: RuleRunner always supplies a LinkedHashMap, and probes legitimately
        // ask for a null name (an unresolved cursor). Map.of() would throw on get(null).
        return EvaluationContext.builder().table(table).datasetResolver(resolverOf(datasets))
                .variables(new LinkedHashMap<>()).build();
    }

    // ---- exists / existsAsDataset / existsAsVariable ----


    @Test
    void existsAsVariableResolvesColumnsNeverDatasets()
    {
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S1").build();
        EvaluationContext c = ctx(primary(), map("DM", dm));
        assertTrue(OperatorRegistry.existsAsVariable(c, "AETERM"), "present column exists");
        assertFalse(OperatorRegistry.existsAsVariable(c, "NOPE"), "absent column");
        assertFalse(OperatorRegistry.existsAsVariable(c, "DM"),
                "a dataset name is not a column of the dataset under evaluation");
        assertTrue(OperatorRegistry.existsAsDataset(c, "DM"), "dataset presence");
        assertFalse(OperatorRegistry.existsAsDataset(c, "EX"), "absent dataset");
    }


    @Test
    void existsAsDatasetHonoursBooleanVariables()
    {
        IDataTable dm = MockTable.of().name("DM").col("ARM", "A").build();
        EvaluationContext c = ctx(primary(), map("DM", dm));
        assertTrue(OperatorRegistry.existsAsDataset(c, "DM"));
        assertFalse(OperatorRegistry.existsAsDataset(c, "EX"));
        assertFalse(OperatorRegistry.existsAsDataset(c, null), "a null name is never a dataset");

        EvaluationContext preInjected = EvaluationContext.builder().table(primary())
                .datasetResolver(resolverOf(map()))
                .variables(new LinkedHashMap<>(Map.of("EX", Boolean.TRUE))).build();
        assertTrue(OperatorRegistry.existsAsDataset(preInjected, "EX"),
                "a pre-injected boolean variable wins over the resolver");
    }


    @Test
    void existsAsVariableIsAlwaysTheColumnForm()
    {
        IDataTable dm = MockTable.of().name("DM").col("ARM", "A").build();
        // Even on a Domain Presence Check the answer is the column form, never dataset presence.
        EvaluationContext c = ctx(primary(), map("DM", dm));
        assertTrue(OperatorRegistry.existsAsVariable(c, "AETERM"));
        assertFalse(OperatorRegistry.existsAsVariable(c, "DM"), "a dataset name is not a column");
        assertFalse(OperatorRegistry.existsAsVariable(c, null), "a nameless leaf matches nothing");
    }


    @Test
    void probesTolerateANullNameOnADefaultVariablesMap()
    {
        // Regression: EvaluationContext.variables defaults to Map.of(), and the immutable maps
        // throw NullPointerException on get(null) instead of returning null. Every probe that
        // documents a null name as "an unresolved cursor" must therefore survive one on a context
        // built WITHOUT an explicit .variables(...) call.
        EvaluationContext bare = EvaluationContext.builder().table(primary())
                .datasetResolver(resolverOf(map())).build();
        assertFalse(OperatorRegistry.existsAsDataset(bare, null), "null name is not a dataset");
        assertFalse(OperatorRegistry.existsAsVariable(bare, null), "null name is not a column");
        assertTrue(OperatorRegistry.variableIsNull(bare, null), "null name reads as all-null");
        assertEquals(0L, OperatorRegistry.maxValueLength(bare, null), "null name reads as 0");
        assertNull(bare.resolveVariable(null), "a null id resolves to no variable");
    }

    // ---- dotted forms ----


    @Test
    void dottedDatasetColumnResolvesAgainstTheForeignSchema()
    {
        IDataTable dm = MockTable.of().name("DM").col("ARM", "A").build();
        EvaluationContext c = ctx(primary(), map("DM", dm));
        assertTrue(OperatorRegistry.existsAsVariable(c, "DM.ARM"));
        assertFalse(OperatorRegistry.existsAsVariable(c, "DM.NOPE"), "absent foreign column");
        assertFalse(OperatorRegistry.existsAsVariable(c, "EX.ARM"), "absent foreign dataset");
    }


    @Test
    void dottedDatasetColumnFallsBackToTheSuppQnamPivot()
    {
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1").build();
        IDataTable suppae = MockTable.of().name("SUPPAE").col("QNAM", "AETRTEM", "").build();
        EvaluationContext c = ctx(primary(), map("AE", ae, "SUPPAE", suppae));
        assertTrue(OperatorRegistry.existsAsVariable(c, "AE.AETRTEM"),
                "the qualifier surfaces via SUPPAE.QNAM");
        assertFalse(OperatorRegistry.existsAsVariable(c, "AE.NOSUCH"),
                "no literal column and no qualifier");
    }


    @Test
    void aSuppDomainIsNotScannedAgainstItself()
    {
        // The !domain.startsWith("SUPP") guard: for SUPPAE.<col> the pivot must NOT be attempted,
        // i.e. we must not go looking for a SUPPSUPPAE partner. The fixture PROVIDES one carrying
        // the qualifier, so dropping the guard flips this assertion.
        IDataTable suppae = MockTable.of().name("SUPPAE").col("QNAM", "OTHER").build();
        IDataTable suppsuppae = MockTable.of().name("SUPPSUPPAE").col("QNAM", "AETRTEM").build();
        EvaluationContext c = ctx(primary(), map("SUPPAE", suppae, "SUPPSUPPAE", suppsuppae));
        assertTrue(OperatorRegistry.existsAsVariable(c, "SUPPAE.QNAM"),
                "a literal column still resolves");
        assertFalse(OperatorRegistry.existsAsVariable(c, "SUPPAE.AETRTEM"),
                "a SUPP domain must not be pivoted through a SUPP<SUPP...> partner");
    }


    @Test
    void dottedFilterFormMatchesARowValue()
    {
        IDataTable suppae = MockTable.of().name("SUPPAE").col("QNAM", "AETRTEM", "OTHER").build();
        EvaluationContext c = ctx(primary(), map("SUPPAE", suppae));
        assertTrue(OperatorRegistry.existsAsVariable(c, "SUPPAE.QNAM=AETRTEM"),
                "a row carries the value");
        assertFalse(OperatorRegistry.existsAsVariable(c, "SUPPAE.QNAM=MISSING"),
                "no row carries it");
        assertFalse(OperatorRegistry.existsAsVariable(c, "SUPPAE.NOCOL=AETRTEM"),
                "the key column is absent");
        assertFalse(OperatorRegistry.existsAsVariable(c, "NOPE.QNAM=AETRTEM"),
                "the dataset is absent");
    }


    @Test
    void dottedFilterSkipsMissingCells()
    {
        IDataTable supp = MockTable.of().name("SUPPAE").col("QNAM", null, "AETRTEM").build();
        EvaluationContext c = ctx(primary(), map("SUPPAE", supp));
        assertTrue(OperatorRegistry.existsAsVariable(c, "SUPPAE.QNAM=AETRTEM"),
                "a missing cell is skipped, the populated one still matches");
    }


    @Test
    void nonDottedAndUnmatchedDottedNamesFallThrough()
    {
        EvaluationContext c = ctx(primary(), map());
        // Leading dot / lowercase do not match either dotted pattern, so the probe falls back to
        // the plain column lookup (and finds nothing).
        assertFalse(OperatorRegistry.existsAsVariable(c, ".AETERM"));
        assertFalse(OperatorRegistry.existsAsVariable(c, "dm.arm"));
    }

    // ---- existsInSuppQnam (shared with ScopeVariableSource) ----


    @Test
    void existsInSuppQnamHandlesAbsentColumnAndBlankCells()
    {
        IDataTable noQnam = MockTable.of().name("SUPPAE").col("QVAL", "x").build();
        assertFalse(OperatorRegistry.existsInSuppQnam(noQnam, "AETRTEM"), "no QNAM column");

        IDataTable withBlanks = MockTable.of().name("SUPPAE").col("QNAM", "", null, "AETRTEM")
                .build();
        assertTrue(OperatorRegistry.existsInSuppQnam(withBlanks, "AETRTEM"),
                "blank and missing cells are skipped, the populated one still matches");
        assertFalse(OperatorRegistry.existsInSuppQnam(withBlanks, "NOSUCH"));
    }

    // ---- variableIsNull / maxValueLength ----


    @Test
    void variableIsNullCoversNullAbsentEmptyAndPopulated()
    {
        IDataTable t = MockTable.of().col("ALLEMPTY", "", "").col("SOMEVAL", "", "x").build();
        EvaluationContext c = ctx(t, map());
        assertTrue(OperatorRegistry.variableIsNull(c, null), "an unresolved cursor reads as null");
        assertTrue(OperatorRegistry.variableIsNull(c, "NOSUCH"), "an absent column reads as null");
        assertTrue(OperatorRegistry.variableIsNull(c, "ALLEMPTY"), "every value empty");
        assertFalse(OperatorRegistry.variableIsNull(c, "SOMEVAL"), "one populated value is enough");
    }


    @Test
    void maxValueLengthCountsCodepointsAndSkipsMissing()
    {
        IDataTable t = MockTable.of().col("AECOD", "AB", "ABCDE", "").col("EMPTY", null, null, null)
                .build();
        EvaluationContext c = ctx(t, map());
        assertEquals(5L, OperatorRegistry.maxValueLength(c, "AECOD"));
        assertEquals(0L, OperatorRegistry.maxValueLength(c, "EMPTY"), "all missing reads as 0");
        assertEquals(0L, OperatorRegistry.maxValueLength(c, "NOSUCH"), "absent column reads as 0");
        assertEquals(0L, OperatorRegistry.maxValueLength(c, null), "a null name reads as 0");
    }


    @Test
    void maxValueLengthCountsSupplementaryPlaneCharactersOnce()
    {
        // U+1F600 is one codepoint but two UTF-16 chars — pandas counts it once.
        IDataTable t = MockTable.of().col("V", "😀").build();
        EvaluationContext c = ctx(t, map());
        assertEquals(1L, OperatorRegistry.maxValueLength(c, "V"));
    }

    // ---- ${...} substitution: per-row and the dataset-level fallback ----


    @Test
    void existsPerRowBitsResolvesADriverPerRow()
    {
        IDataTable t = MockTable.of().col("APERIOD", "1", "2").col("AP1SDT", "2024-01-01", "x")
                .build();
        EvaluationContext c = ctx(t, map());
        BitSet on = OperatorRegistry.existsPerRowBits(c, "AP${APERIOD}SDT", true);
        assertEquals(1, on.cardinality(), "only row 0 resolves AP1SDT");
        assertTrue(on.get(0));

        BitSet off = OperatorRegistry.existsPerRowBits(c, "AP${APERIOD}SDT", false);
        assertEquals(1, off.cardinality(), "only row 1 resolves the absent AP2SDT");
        assertTrue(off.get(1));
    }


    @Test
    void existsPerRowBitsRaisesOnAnAbsentDriverColumnButSkipsAMissingDriverValue()
    {
        IDataTable noDriver = MockTable.of().col("AP1SDT", "x").build();
        EvaluationContext c1 = ctx(noDriver, map());
        assertThrows(OperandSubstitutor.SubstitutionException.class,
                () -> OperatorRegistry.existsPerRowBits(c1, "AP${APERIOD}SDT", true),
                "an absent driver column is a rule-authoring error and must surface");

        // The driver VALUE must be genuinely missing (null) — an empty string is a PRESENT value
        // that substitutes to the concrete name "APSDT" and never raises SubstitutionException.
        IDataTable blankDriver = MockTable.of().col("APERIOD", "1", null).col("AP1SDT", "x", "x")
                .build();
        EvaluationContext c2 = ctx(blankDriver, map());
        BitSet exists = OperatorRegistry.existsPerRowBits(c2, "AP${APERIOD}SDT", true);
        assertTrue(exists.get(0), "row 0 resolves AP1SDT");
        assertFalse(exists.get(1), "a missing driver VALUE leaves the row unset (exists polarity)");
        // The unset-on-both-polarities contract is the load-bearing half: a missing driver is a
        // data state, not a violation, so the not_exists polarity must NOT fire either.
        BitSet notExists = OperatorRegistry.existsPerRowBits(c2, "AP${APERIOD}SDT", false);
        assertFalse(notExists.get(1),
                "a missing driver VALUE must not fire the not_exists polarity either");
    }


    @Test
    void existsPerRowBitsSupportsTheWildcardForm()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1").col("TRT01PN", "1").build();
        EvaluationContext c = ctx(t, map());
        assertTrue(OperatorRegistry.existsPerRowBits(c, "TRT${*}PN", true).get(0),
                "at least one column matches the wildcard");
        assertTrue(OperatorRegistry.existsPerRowBits(c, "ZZZ${*}QQ", false).get(0),
                "no column matches, so the not_exists polarity fires");
    }


    @Test
    void substitutedNamesResolveOnTheDatasetLevelFallback()
    {
        // Reached through var_exists(...) — a placeholder name on a non-per-row path evaluates
        // conservatively against row 0.
        IDataTable t = MockTable.of().col("APERIOD", "1", "2").col("AP1SDT", "x", "y").build();
        EvaluationContext c = ctx(t, map());
        assertTrue(OperatorRegistry.existsAsVariable(c, "AP${APERIOD}SDT"),
                "row 0 resolves AP1SDT");

        IDataTable other = MockTable.of().col("APERIOD", "2").col("AP1SDT", "x").build();
        assertFalse(OperatorRegistry.existsAsVariable(ctx(other, map()), "AP${APERIOD}SDT"),
                "row 0 resolves the absent AP2SDT");
    }


    @Test
    void substitutedWildcardResolvesOnTheDatasetLevelFallback()
    {
        IDataTable t = MockTable.of().col("TRT01PN", "1").build();
        EvaluationContext c = ctx(t, map());
        assertTrue(OperatorRegistry.existsAsVariable(c, "TRT${*}PN"), "a matching local column");
        assertFalse(OperatorRegistry.existsAsVariable(c, "ZZZ${*}QQ"), "no matching column");
    }


    @Test
    void substitutedWildcardAcrossAnAbsentForeignDatasetIsFalse()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1").build();
        EvaluationContext c = ctx(t, map());
        assertFalse(OperatorRegistry.existsAsVariable(c, "NOPE.TRT${*}PN"),
                "an unresolvable foreign dataset matches no column");
    }


    @Test
    void datasetLevelFallbackOnAnEmptyTableIsFalse()
    {
        IDataTable empty = MockTable.of().col("APERIOD").build();
        EvaluationContext c = ctx(empty, map());
        assertFalse(OperatorRegistry.existsAsVariable(c, "AP${APERIOD}SDT"),
                "no row 0 to substitute against");
    }
}
