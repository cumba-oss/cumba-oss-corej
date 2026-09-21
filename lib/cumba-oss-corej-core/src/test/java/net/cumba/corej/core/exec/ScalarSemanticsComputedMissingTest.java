package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.TypedValue;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * ⭐⭐ {@link ScalarSemantics#computedMissing()} and the <b>typed-cell channel's non-null
 * contract</b> — the {@code IDataValue} half of the value rule that nothing pinned.
 *
 * <p>
 * ⚑ TARGET-INVARIANT(null-free-value-channel). Read {@code computedMissing()}'s javadoc first: it
 * carries the rule (a variable value, a function result, an expression result and a parameter are
 * each a real value or a {@link MissingValue}, never {@code null}), the fact that the rule is a
 * TARGET rather than present fact, and the condition for promoting it.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>Why this class exists, measured 2026-09-18.</b> Two tests pinned
 * {@link ScalarSemantics#resolvedString}'s <b>{@code String}</b> contract and <b>nothing pinned the
 * {@code IDataValue} contract</b> — so eleven producers in this module returned a bare {@code null}
 * into the typed-cell channel with every gate green. The prose was already written down in two
 * places and still did not catch it. A statement of this rule is therefore worth nothing unless
 * something FAILS when it is violated, which is what the two halves below are for:
 * </p>
 * <ol>
 * <li>{@link #theComputedMissingCellIsMisAndIsNotNull()} pins what the "no result" cell IS, and
 * that it is interchangeable with the sentinel {@code TypedValue.typedCell} used to mint from a
 * {@code null}, so the hardening moved no behaviour.</li>
 * <li>{@link #theTypedCellChannelDeclaresNoNullableValue()} is the <b>ratchet</b>: it fails if any
 * signature in the hardened channel gets its {@code @Nullable} back. Re-adding one is the
 * documented wrong way to buy a green — it is silent, it compiles, and NullAway then stops
 * objecting — so only a check on the declarations themselves can see it.</li>
 * </ol>
 */
class ScalarSemanticsComputedMissingTest
{

    /** The computed-missing cell: present as an object, missing as a value, and {@code MIS}. */
    @Test
    void theComputedMissingCellIsMisAndIsNotNull()
    {
        IDataValue cell = ScalarSemantics.computedMissing();

        assertNotNull(cell, "the 'no result' answer is a cell, never null — that IS the rule");
        assertTrue(cell.isMissingOrInvalid(), "it must read as missing");
        assertEquals(MissingValue.MIS, cell.getValue(),
                "a COMPUTED missing is always MissingValue.MIS (D36 #8)");
        assertSame(MissingValue.MIS, TypedValue.missingIdentityOf(cell),
                "and the carrier must decode that identity");

        // Byte-identical to the sentinel TypedValue.typedCell minted for a null cell before the
        // nullability was removed, which is what makes the hardening verdict-neutral.
        IDataValue legacySentinel = DataValueSupport.getAsDataValue(null, DataValueType.MISSING);
        assertEquals(legacySentinel.getValue(), cell.getValue(),
                "the retired null-sentinel and computedMissing() must be the same value");

        TypedValue tv = TypedValue.typedCell(DataValueType.STRING, cell);
        assertTrue(tv.isMissing(), "the carrier reports missing");
        assertSame(MissingValue.MIS, tv.missing(), "with the MIS identity");
        assertNull(tv.resolved(),
                "resolved() answers null for ANY missing — that channel is the legacy untyped"
                        + " transport and is deliberately unchanged here");
        assertTrue(DataValueSupport.isEmptyOrMissing(cell),
                "and the empty()/isEmptyOrMissing fold is untouched by this class");
    }


    /**
     * ⭐ The ratchet. Every signature in the hardened typed-cell channel must declare a NON-null
     * value, and the control below proves the detector can actually see a {@code @Nullable} when
     * one is there — without it a renamed annotation or a non-runtime retention would make this
     * test pass vacuously over a fully re-nulled channel.
     */
    @Test
    void theTypedCellChannelDeclaresNoNullableValue() throws ReflectiveOperationException
    {
        List<String> violations = new ArrayList<>();

        // Returns: the three ExprCompiler row functions and the JoinLookup value accessor.
        Class<?> compiler = Class.forName("net.cumba.corej.core.expr.eval.ExprCompiler");
        // ⚠ firstJoinedCell was REMOVED 2026-09-21 (PLAN-unqualified-name-primary-only): it
        // resolved an unqualified name out of a joined dataset, which the uniformity ruling
        // abolished. Dropped from the roster rather than the assertion weakened -- this test
        // says so itself: "this ratchet has lost its target and would pass vacuously".
        for (String name : List.of("arithmeticCell", "substitutedScalarCell"))
        {
            Method m = declared(compiler, name);
            if (isNullable(m.getAnnotatedReturnType()))
            {
                violations.add("ExprCompiler." + name + " return");
            }
        }
        Method lookupValue = JoinLookup.class.getMethod("lookupValue", IDataTable.class, long.class,
                String.class);
        if (isNullable(lookupValue.getAnnotatedReturnType()))
        {
            violations.add("JoinLookup.lookupValue return");
        }
        // ⭐ BOTH forms, not only the legacy three-argument one: §9c's expectation-aware overload is
        // the one the evaluation path now calls and the one every production implementation
        // overrides, so a ratchet that checked only the delegating form would guard the channel
        // nobody uses.
        Method lookupValueTyped = JoinLookup.class.getMethod("lookupValue", IDataTable.class,
                long.class, String.class, boolean.class);
        if (isNullable(lookupValueTyped.getAnnotatedReturnType()))
        {
            violations.add("JoinLookup.lookupValue(numericExpected) return");
        }
        // ⚠ JoinedCandidatesVector.firstNonNullCell was censused here until 2026-09-21. The class
        // is GONE: its only producer was ExprCompiler.joinedColumnVector, which resolved an
        // unqualified name out of a join -- abolished by the uniformity ruling. ⛔ Review round 1
        // caught that leaving it here would have made this census include a producer that can never
        // produce, which is worse than omitting it: the roster would read complete while pinning a
        // symbol nothing reaches.

        // Parameters: the carrier factory, and ComputedVector.typed's producer TYPE ARGUMENT --
        // the nullability that has to be read one level in, and the one a sweep would miss.
        Method typedCell = TypedValue.class.getMethod("typedCell", DataValueType.class,
                IDataValue.class);
        if (isNullable(typedCell.getAnnotatedParameterTypes()[1]))
        {
            violations.add("TypedValue.typedCell(cell) parameter");
        }
        Method typed = ComputedVector.class.getMethod("typed", int.class, DataValueType.class,
                IntFunction.class);
        AnnotatedType producer = typed.getAnnotatedParameterTypes()[2];
        assertTrue(producer instanceof AnnotatedParameterizedType,
                "ComputedVector.typed's producer must stay a parameterised IntFunction<IDataValue>"
                        + " — a raw or erased parameter would make the check below blind");
        AnnotatedType arg = ((AnnotatedParameterizedType) producer)
                .getAnnotatedActualTypeArguments()[0];
        if (isNullable(arg))
        {
            violations.add("ComputedVector.typed producer type argument");
        }

        assertTrue(violations.isEmpty(),
                "⚑ TARGET-INVARIANT(null-free-value-channel): these signatures in the typed-cell"
                        + " channel declare a NULLABLE value again, which un-enforces the owner's"
                        + " rule that a value is never null. Produce"
                        + " ScalarSemantics.computedMissing() (or the cell / type default the site"
                        + " owes) instead of re-adding @Nullable: " + violations);

        // --- non-vacuity control: the detector must SEE a @Nullable where one exists -----------
        Method resolvedString = ScalarSemantics.class.getMethod("resolvedString", IDataTable.class,
                int.class, long.class);
        assertTrue(isNullable(resolvedString.getAnnotatedReturnType()),
                "CONTROL FAILED: ScalarSemantics.resolvedString IS @Nullable (its String channel"
                        + " answers null for a blank cell, deliberately and by ruling). If this"
                        + " assertion fails the detector above cannot see @Nullable at all and"
                        + " every check in this test passed vacuously.");
        assertTrue(isNullable(TypedValue.class.getMethod("missing").getAnnotatedReturnType()),
                "CONTROL FAILED: TypedValue.missing() IS @Nullable (null = the value is present)"
                        + " — a second, independently-annotated site for the same control");
    }


    /**
     * ⭐⭐ The <b>population</b> half of the ratchet: every {@code IDataValue}-returning declaration
     * in this module is DISCOVERED, counted with exact equality, and required to be non-null except
     * for one allow-listed engine-plumbing helper.
     *
     * <p>
     * ⚠⚠ <b>Why the hand-written half above is not enough.</b>
     * {@link #theTypedCellChannelDeclaresNoNullableValue()} names seven signatures BY HAND. It reds
     * on a rename and on a signature move — but it is blind in exactly one direction, which is the
     * direction defects arrive from: a TWELFTH producer landing with a {@code @Nullable IDataValue}
     * return is simply not named, NullAway is satisfied within the new method's own body, nothing
     * counts the population, <b>the gate is green and the channel is silently re-nulled</b>. That
     * is the asymmetry the datatable repository's {@code b28e8ab} removed on the buffer side
     * (reflective discovery + an exact-equality count), and this is the same repair on the value
     * side.
     * </p>
     *
     * <p>
     * ⛔ <b>Exact equality in BOTH directions is the point, and it is deliberately noisy.</b> A new
     * producer reds because the count rose; a deleted one reds because it fell. Bump
     * {@link #EXPECTED_VALUE_PRODUCERS} only after reading what moved — never to get a green.
     * </p>
     *
     * <p>
     * ⚠ <b>Scope, stated so the guard is not read as wider than it is.</b> The discovery is over
     * THIS module's classes ({@link ProductionClasses#ofModule}, whose javadoc carries the blind
     * spots): a producer in the ruletest module, in the OSS twin, or in another repo is outside it.
     * The expected-SET assertion below is what makes a member moving OUT of the module red here
     * rather than vanish quietly.
     * </p>
     */
    @Test
    void everyIDataValueDeclarationInThisModuleIsDiscoveredCountedAndNonNullable()
    {
        List<String> found = new ArrayList<>();
        List<String> nullable = new ArrayList<>();
        for (Class<?> c : ProductionClasses.ofModule(ScalarSemantics.class))
        {
            for (Method m : c.getDeclaredMethods())
            {
                // ⚠ isAssignableFrom, NOT `!= IDataValue.class`: an exact-type filter lets a
                // producer declared to return a SUBTYPE escape the population entirely
                // (`DataValueMissing foo()` would be invisible). Grepped 2026-09-18: no such
                // declaration exists in this module today, so widening the filter keeps the count
                // — which is the point of widening it before one arrives rather than after.
                if (m.isSynthetic() || m.isBridge()
                        || !IDataValue.class.isAssignableFrom(m.getReturnType()))
                {
                    continue;
                }
                String id = c.getSimpleName() + "." + m.getName();
                found.add(id);
                if (isNullable(m.getAnnotatedReturnType()))
                {
                    nullable.add(id);
                }
            }
        }
        java.util.Collections.sort(found);
        java.util.Collections.sort(nullable);

        // --- non-vacuity control: the discovery must actually CONTAIN the hand-named channel -----
        // Without this the count could be satisfied by 19 unrelated methods while every signature
        // the channel is about had moved out of the module.
        for (String required : List.of("ExprCompiler.substitutedScalarCell",
                "ExprCompiler.arithmeticCell", "JoinLookup.lookupValue",
                "ScalarSemantics.computedMissing", "DatasetLookup.lookupValue",
                "KeyMatchExpandedLookup.lookupValue", "RelrecExpandedLookup.lookupValue"))
        {
            assertTrue(found.contains(required),
                    "CONTROL FAILED: the discovery did not find " + required
                            + ", so it is not seeing the typed-cell channel and the count below"
                            + " would be satisfied by an unrelated population: " + found);
        }

        assertEquals(EXPECTED_VALUE_PRODUCERS, found.size(),
                "⚑ the number of IDataValue-returning declarations in this module MOVED. This is"
                        + " the population half of the null-free value ratchet: a new producer must"
                        + " be READ (does it answer a real value or the cell / type default it"
                        + " owes?) and a deleted one must be accounted for, before this constant is"
                        + " bumped. Discovered: " + found);

        // ⚑ Two allowed @Nullable declarations, both OUTSIDE the value channel by §1b's boundary
        // test (does this flow into expression evaluation as a VALUE? no):
        //
        // * PolymorphicMergedColumn.readParentDataValue — a private helper answering "this merged
        // column has no parent column object here"; its caller converts before publishing.
        // * TypedValue.sourceCell — PROVENANCE, not a value: null means "this value was not
        // produced from a cell" (a resolved literal / untyped computed value), and consumers
        // branch on exactly that (Primitives:260). The VALUE channel of the same carrier is
        // TypedValue.cell(), which is non-null and derives a DataValues.of wrapper when there
        // is no source cell.
        //
        // ⭐⭐ sourceCell is the immediate deliverable of this discovery: the hand-written roster
        // above does NOT name it, and a source grep for `@Nullable IDataValue` does not find it
        // either — its annotation sits on its OWN LINE. Reflection over a discovered population
        // found it; two narrower methods did not.
        //
        // ⛔ Exact equality, so REMOVING an annotation reds too and an allowance cannot outlive its
        // reason unnoticed.
        assertEquals(
                List.of("PolymorphicMergedColumn.readParentDataValue", "TypedValue.sourceCell"),
                nullable,
                "the set of @Nullable IDataValue declarations in this module changed. Every value"
                        + " a rule reads is a real value or a MissingValue, never null — produce"
                        + " ScalarSemantics.computedMissing() or the owed default instead of"
                        + " re-adding @Nullable");
    }


    /**
     * ⭐⭐ The <b>0..N MULTI-VALUE channel</b> — the population the scalar ratchet above cannot see,
     * and the one §2a says the COMPILER cannot see either. ⇒ Before this test it had neither
     * instrument: {@code NullAway} does not check a lambda's or method reference's return against a
     * generic type argument, and
     * {@link #everyIDataValueDeclarationInThisModuleIsDiscoveredCountedAndNonNullable()} filters on
     * the return type BEING an {@code IDataValue}, so a {@code List<IDataValue>} never entered it.
     *
     * <p>
     * ⭐⭐ <b>THE CONTRACT, MEASURED RATHER THAN ASSUMED — the LIST may be null, an ELEMENT may
     * not.</b> The two halves have genuinely different verdicts and it would have been wrong to
     * assert one rule for both:
     * </p>
     * <ul>
     * <li><b>A {@code null} LIST is legitimate and load-bearing</b>, not a violation.
     * {@code JoinedCandidatesVector.candidateCells} publishes a documented THREE-WAY vote contract,
     * and {@code Primitives.scan} consumes exactly that: {@code null} means <i>no lookup is live,
     * so this row casts NO VOTE</i> and the consumer {@code continue}s; an EMPTY list means <i>the
     * live lookup matched elsewhere but not here</i> and the row votes once with a missing probe; a
     * non-empty list is ANY-MATCH over its cells. By §1b's boundary test the {@code null} never
     * flows into expression evaluation as a value at all — it decides <em>whether</em> a vote
     * happens. That is engine plumbing, and collapsing it into an empty list would silently convert
     * every no-lookup row into a missing-probe vote.</li>
     * <li><b>A {@code null} ELEMENT would be a violation.</b> Every element is handed straight to
     * {@code RowTest.test(value, row)}, i.e. it IS an expression input, so it owes a real value or
     * a {@code MissingValue}. Measured at all three producers and none can emit one today:
     * {@code DatasetLookup.lookupAllValues} adds {@code getDataValue(row)} (non-null, and only when
     * {@code !isMissingOrInvalid()}); {@code JoinLookup.lookupAllValues} wraps each non-null
     * {@code String} of {@code lookupAll} through {@code DataValueSupport.getAsDataValue};
     * {@code candidateCells}'s transform arm wraps through {@code DataValues.of}, which answers a
     * {@code MissingValue.MIS} carrier even for a {@code null} input.</li>
     * </ul>
     *
     * <p>
     * ⇒ So the assertions below are: an EXACT-EQUALITY population count of its own; the set of
     * declarations whose LIST is {@code @Nullable} allow-listed WITH its reason; and no
     * {@code @Nullable} element type argument anywhere. ⛔ Do not "harden" {@code candidateCells}'s
     * nullable return to get a uniform rule — that is the one of the two halves the code and the
     * owner's boundary test both say is correct.
     * </p>
     */
    @Test
    void everyMultiValueDeclarationInThisModuleIsDiscoveredCountedAndHasNonNullElements()
    {
        List<String> found = new ArrayList<>();
        List<String> nullableList = new ArrayList<>();
        List<String> nullableElement = new ArrayList<>();
        for (Class<?> c : ProductionClasses.ofModule(ScalarSemantics.class))
        {
            for (Method m : c.getDeclaredMethods())
            {
                if (m.isSynthetic() || m.isBridge())
                {
                    continue;
                }
                AnnotatedType ret = m.getAnnotatedReturnType();
                List<AnnotatedType> elements = valuePositionsWithin(ret);
                if (elements.isEmpty())
                {
                    continue;
                }
                String id = c.getSimpleName() + "." + m.getName();
                found.add(id);
                if (isNullable(ret))
                {
                    nullableList.add(id);
                }
                for (AnnotatedType e : elements)
                {
                    if (isNullable(e))
                    {
                        nullableElement.add(id);
                    }
                }
            }
        }
        java.util.Collections.sort(found);
        java.util.Collections.sort(nullableList);
        java.util.Collections.sort(nullableElement);

        // --- non-vacuity control 1: the discovery must contain the channel it is about ----------
        // ⚠ JoinedCandidatesVector is GONE (2026-09-21, PLAN-unqualified-name-primary-only's
        // closure
        // sweep): its only producer resolved an unqualified name out of a join, so with that
        // removed
        // nothing could construct one and the class went with it. Dropped from the roster rather
        // than
        // the assertion weakened -- a control that requires a symbol nothing can reach fails
        // forever,
        // and a count that includes it censuses a producer that can never produce.
        for (String required : List.of("DatasetLookup.lookupAllValues",
                "JoinLookup.lookupAllValues"))
        {
            assertTrue(found.contains(required),
                    "CONTROL FAILED: the discovery did not find " + required + ", so it is not"
                            + " seeing the multi-value channel and the count below would be"
                            + " satisfied by an unrelated population: " + found);
        }

        // --- non-vacuity control 2: the ELEMENT detector must SEE a @Nullable type argument -----
        // ⛔ This is the arm the whole test turns on. isNullable() reads annotations off ONE
        // AnnotatedType; a mistake in valuePositionsWithin (returning the container instead of the
        // argument, or an empty list) makes `nullableElement` empty no matter what the channel
        // declares, and the assertion below then passes over a fully re-nulled element type.
        List<AnnotatedType> controlElements = valuePositionsWithin(
                declared(ScalarSemanticsComputedMissingTest.class, "controlWithANullableElement")
                        .getAnnotatedReturnType());
        assertEquals(1, controlElements.size(),
                "CONTROL FAILED: the element extractor found " + controlElements.size()
                        + " value positions in List<@Nullable IDataValue>, not 1 — it is not"
                        + " reaching type arguments and every element check here is vacuous");
        assertTrue(isNullable(controlElements.get(0)),
                "CONTROL FAILED: the detector cannot see a @Nullable on an element TYPE ARGUMENT at"
                        + " all, so nullableElement would stay empty over a re-nulled channel");

        assertEquals(EXPECTED_MULTI_VALUE_PRODUCERS, found.size(),
                "⚑ the number of MULTI-VALUE (0..N) IDataValue declarations in this module MOVED."
                        + " A new one must be READ against both halves of the contract — may its"
                        + " LIST be null (a three-way vote contract) and can an ELEMENT ever be"
                        + " null (it may not: every element is an expression input)? — before this"
                        + " constant is bumped. Discovered: " + found);

        // ⭐⭐ EMPTY since 2026-09-21, and the assertion's own warning is what this documents: "a
        // REMOVED one means that vote contract has been collapsed, which changes verdicts". It HAS
        // been collapsed -- deliberately. candidateCells' three-way vote (null = no vote, empty
        // list
        // = a MISSING-probe vote, otherwise ANY-MATCH) existed only to let an UNQUALIFIED name vote
        // per joined candidate, which the owner's uniformity ruling abolished; the class had no
        // producer left and went with it. ⇒ The allowlist is empty, and that is now the contract:
        // no
        // multi-value declaration may hand back a nullable LIST.
        assertEquals(List.of(), nullableList,
                "the set of multi-value declarations whose LIST is @Nullable changed. Exactly one is"
                        + " allowed and its nullness is DELIBERATE: candidateCells answers null for"
                        + " \"no lookup is live\", which Primitives.scan reads as \"this row casts no"
                        + " vote\" — plumbing, not a value, so §1b does not reach it. A new nullable"
                        + " LIST needs the same three-way justification; and a REMOVED one means"
                        + " that vote contract has been collapsed, which changes verdicts");

        assertTrue(nullableElement.isEmpty(),
                "⛔ these multi-value declarations allow a NULL ELEMENT, and an element of one of"
                        + " these lists is an expression input: Primitives.scan hands each cell"
                        + " straight to RowTest.test. Every value a rule reads is a real value or a"
                        + " MissingValue — produce ScalarSemantics.computedMissing() (or"
                        + " DataValues.of, which already answers a MIS carrier for a null input)"
                        + " instead of admitting a null: " + nullableElement);
    }


    /**
     * ⭐⭐ The multi-value contract asserted as a BEHAVIOUR, not only as a declaration — because the
     * declaration ratchet above only catches an author who ANNOTATES the null they admit. An
     * {@code out.add(null)} with no annotation is invisible to it, to NullAway (§2a: a generic
     * element type is exactly what it cannot check) and to every gate.
     *
     * <p>
     * Both producers are probed over a fixture that exercises the interesting rows: a matched cell
     * with a value, a matched cell that is MISSING, and an unmatched row. ⚠ Note what
     * {@code DatasetLookup.lookupAllValues} does with the missing one — it <b>filters it out</b>
     * rather than admitting it, which is the same filter {@code lookupAll} applies and is why a
     * null element never had to be invented for it. That is pinned here, because "the list is
     * shorter" and "the list carries a null" are the two ways this could have been written and only
     * one of them keeps the channel null-free.
     * </p>
     *
     * <p>
     * ⚑ {@code JoinedCandidatesVector.candidateCells} is NOT probed here and is covered by the
     * declaration half only: constructing one needs a whole {@code EvaluationContext}. ⭐ Its
     * untransformed arm is nonetheless covered TRANSITIVELY — it returns
     * {@code lookup.lookupAllValues(…)} verbatim, which is the very method probed below. The
     * uncovered remainder is its transform arm, which wraps through {@code DataValues.of} — the one
     * factory that answers a {@code MissingValue.MIS} carrier even for a {@code null} input.
     * </p>
     */
    @Test
    void neitherMultiValueProducerEverYieldsANullElement()
    {
        // ⛔ colSasMissing, NOT col(…, null), and that is F2: the installed testkit jar (18:24)
        // predates the datatable repository's d1585c9 (19:20), so a col(null) fixture asserts one
        // thing against a fresh testkit and the opposite against this sandbox's. colSasMissing
        // mints the generic MIS on both sides, so this test measures the engine rather than the
        // jar.
        IDataTable child = MockTable.of().col("USUBJID", "S1", "S2").colSasMissing("ARM", "A", null)
                .name("DM").build();
        IDataTable primary = MockTable.of().col("USUBJID", "S1", "S2", "S3").name("AE").build();
        DatasetLookup lk = java.util.Objects.requireNonNull(
                DatasetLookup.build("DM", child, List.of("USUBJID")), "build answered null");

        // row 0: a matched, present cell -> exactly one element, and it is a real value.
        List<IDataValue> present = lk.lookupAllValues(primary, 0, "ARM");
        assertEquals(1, present.size());
        assertNotNull(present.get(0), "no element of the 0..N channel is ever null");
        assertEquals("A", present.get(0).getValueAsString());

        // row 1: a matched cell that is MISSING -> FILTERED OUT, never admitted as a null element.
        assertEquals(List.of(), lk.lookupAllValues(primary, 1, "ARM"),
                "a missing matched cell contributes NOTHING — the same filter lookupAll applies."
                        + " ⛔ If this ever becomes a one-element list, read the element: adding the"
                        + " cell is fine, adding a null is the violation");

        // row 2: no partner at all, and an absent column: both empty, neither null.
        assertNotNull(lk.lookupAllValues(primary, 2, "ARM"),
                "the LIST of this producer is non-null");
        assertEquals(List.of(), lk.lookupAllValues(primary, 2, "ARM"));
        assertEquals(List.of(), lk.lookupAllValues(primary, 0, "ZZNOSUCHCOLUMN"));

        // The interface DEFAULT, which is a separate declaration and a separate implementation: it
        // wraps lookupAll's strings, and a null-free list in must stay a null-free list out.
        JoinLookup textOnly = new JoinLookup()
        {

            @Override
            public @Nullable String lookup(IDataTable t, long row, String columnName)
            {
                return "X";
            }


            @Override
            public List<String> lookupAll(IDataTable t, long row, String columnName)
            {
                return List.of("X", "");
            }


            @Override
            public String getDatasetName()
            {
                return "PROBE";
            }
        };
        List<IDataValue> wrapped = textOnly.lookupAllValues(primary, 0, "ARM");
        assertEquals(2, wrapped.size());
        for (IDataValue v : wrapped)
        {
            assertNotNull(v, "the interface default must not wrap anything into a null element");
        }
    }

    /**
     * The measured number of declarations in this module returning a 0..N container of
     * {@code IDataValue}, 2026-09-18 — three, all of them {@code List<IDataValue>}. ⚑ <b>3 → 2 on
     * 2026-09-21</b>: {@code JoinedCandidatesVector.candidateCells} went with its class, which had
     * no producer left once the unqualified-join fallback was removed. ⛔ A ratchet, and
     * deliberately SEPARATE from {@link #EXPECTED_VALUE_PRODUCERS}: folding the scalar and
     * multi-value populations into one figure would hide which of two different contracts moved.
     */
    private static final int EXPECTED_MULTI_VALUE_PRODUCERS = 2;

    /**
     * ⛔ The permanent positive control for the element detector — a declaration carrying a
     * {@code @Nullable} exactly where a real violation would carry one. It is package-private and
     * reached reflectively on purpose: nothing calls it, and that is the point.
     *
     * @return nothing; never invoked
     */
    static List<@Nullable IDataValue> controlWithANullableElement()
    {
        return new ArrayList<>();
    }


    /**
     * Every VALUE position inside {@code type}: each type argument, array component or wildcard
     * bound that is an {@code IDataValue} or a subtype, found recursively so a
     * {@code Map<String, List<IDataValue>>} is seen as well as a plain {@code List<IDataValue>}.
     *
     * <p>
     * ⚠ A plain {@code IDataValue} return yields an EMPTY list, which is what keeps this population
     * disjoint from the scalar one above.
     * </p>
     */
    private static List<AnnotatedType> valuePositionsWithin(AnnotatedType type)
    {
        List<AnnotatedType> out = new ArrayList<>();
        if (type instanceof java.lang.reflect.AnnotatedArrayType arr)
        {
            collectValuePosition(arr.getAnnotatedGenericComponentType(), out);
        }
        else if (type instanceof AnnotatedParameterizedType par)
        {
            for (AnnotatedType arg : par.getAnnotatedActualTypeArguments())
            {
                collectValuePosition(arg, out);
            }
        }
        else if (type instanceof java.lang.reflect.AnnotatedWildcardType wild)
        {
            for (AnnotatedType bound : wild.getAnnotatedUpperBounds())
            {
                collectValuePosition(bound, out);
            }
        }
        return out;
    }


    private static void collectValuePosition(AnnotatedType candidate, List<AnnotatedType> out)
    {
        if (candidate.getType() instanceof Class<?> raw && IDataValue.class.isAssignableFrom(raw))
        {
            out.add(candidate);
        }
        out.addAll(valuePositionsWithin(candidate));
    }

    /**
     * The measured number of declarations in this module that return an {@link IDataValue} <b>or a
     * subtype of one</b>, 2026-09-18. ⛔ Not a style limit — a ratchet: see the test above before
     * changing it.
     *
     * <p>
     * ⛔⛔ <b>SCOPE — this figure is the SCALAR channel only, and reading it as "the value channel"
     * is wrong (review round 2, not-covered 1).</b> The discovery it gates keeps only methods whose
     * return type IS an {@code IDataValue}, so it counts neither of these:
     * </p>
     * <ul>
     * <li>the <b>0..N multi-value channel</b> — {@code List<IDataValue>} returns, whose own
     * population and contract are asserted separately by
     * {@link #everyMultiValueDeclarationInThisModuleIsDiscoveredCountedAndHasNonNullElements()}. ⛔
     * The two counts are deliberately NOT folded into one: a {@code null} LIST and a {@code null}
     * ELEMENT are different contracts with different verdicts, and one figure would hide which of
     * them moved;</li>
     * <li>anything outside this module — the ruletest module, the OSS twin, another repo
     * ({@link ProductionClasses#ofModule} carries the full blind-spot list).</li>
     * </ul>
     *
     * <p>
     * ⚑ <b>19 → 18 → 17 on 2026-09-21, and BOTH deletions are ACCOUNTED FOR</b> as this ratchet's
     * own failure message demands. {@code ExprCompiler.firstJoinedCell} was removed by
     * {@code PLAN-unqualified-name-primary-only}: it resolved an unqualified name out of a joined
     * dataset — the behaviour the uniformity ruling abolished — and it had exactly one caller, the
     * site that was changed. Then {@code JoinedCandidatesVector.firstNonNullCell} went too, when
     * review round 1 measured that the class had no producer left at all. No producer was added.
     * </p>
     */
    private static final int EXPECTED_VALUE_PRODUCERS = 17;

    private static Method declared(Class<?> owner, String name)
    {
        for (Method m : owner.getDeclaredMethods())
        {
            if (m.getName().equals(name))
            {
                return m;
            }
        }
        throw new AssertionError("no method " + owner.getSimpleName() + "." + name
                + " — this ratchet has lost its target and would pass vacuously; re-point it");
    }


    private static boolean isNullable(AnnotatedType type)
    {
        for (var a : type.getAnnotations())
        {
            if ("Nullable".equals(a.annotationType().getSimpleName()))
            {
                return true;
            }
        }
        return false;
    }
}
