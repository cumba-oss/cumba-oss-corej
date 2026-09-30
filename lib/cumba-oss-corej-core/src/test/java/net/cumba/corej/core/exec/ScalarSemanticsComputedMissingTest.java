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
        // ⭐ §9c's expectation-aware overload is the one the evaluation path calls and the one every
        // production implementation overrides. ⚑ Its three-argument sibling (delegating with
        // numericExpected = false) was censused here too until 2026-09-25, when it was retired as
        // having no production caller (PLAN-retire-dead-multi-match-lookup U14).
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
     * {@link #theTypedCellChannelDeclaresNoNullableValue()} names its signatures BY HAND. It reds
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
     * and the one §2a says the COMPILER cannot see either: {@code NullAway} does not check a
     * lambda's or method reference's return against a generic type argument, and
     * {@link #everyIDataValueDeclarationInThisModuleIsDiscoveredCountedAndNonNullable()} filters on
     * the return type BEING an {@code IDataValue}, so a {@code List<IDataValue>} never enters it.
     *
     * <p>
     * ⭐⭐ <b>THE CONTRACT — neither the LIST nor an ELEMENT may be null.</b> Every element of such a
     * list is an expression input, so it owes a real value or a {@code MissingValue}. And since
     * 2026-09-21 no multi-value declaration may hand back a nullable LIST either: the one three-way
     * vote contract that did ({@code JoinedCandidatesVector.candidateCells} — {@code null} meant
     * "no lookup is live, this row casts no vote") went with its class when the unqualified-join
     * fallback was removed, and a NEW nullable list needs a three-way justification of its own.
     * </p>
     *
     * <p>
     * ⭐ <b>The population is ZERO today, asserted exactly.</b> One-line history: three producers on
     * 2026-09-18; 3 → 2 on 2026-09-21 with {@code JoinedCandidatesVector}; 2 → 0 on 2026-09-25 when
     * the 0..N lookup channel itself ({@code JoinLookup} / {@code DatasetLookup} {@code lookupAll}
     * + {@code lookupAllValues}) was retired as unreachable
     * ({@code PLAN-retire-dead-multi-match-lookup} wave A). ⇒ The discovery can no longer prove
     * itself non-vacuous against a production member, and requiring one would fail forever. So
     * control 1 runs the SAME discovery ({@link #censusMultiValue}) over a test-local control class
     * that declares three {@code List<IDataValue>} methods — a clean one, one with a
     * {@code @Nullable} LIST and one with a {@code @Nullable} ELEMENT — plus two that are not
     * multi-value (a scalar {@code IDataValue}, a {@code List<String>}). It requires the discovery
     * to find exactly the three, and each nullable detector to report exactly its own declaration:
     * that proves the discovery reaches type arguments, and that both detectors can fire, without
     * needing a production population. ⛔ A new 0..N producer reds the exact count; read it against
     * both halves of the contract before bumping {@link #EXPECTED_MULTI_VALUE_PRODUCERS}.
     * </p>
     */
    @Test
    void everyMultiValueDeclarationInThisModuleIsDiscoveredCountedAndHasNonNullElements()
    {
        // --- non-vacuity control 1: the discovery must reach a type argument ------------------
        // ⛔ Run over a KNOWN population, because the production one is empty: a discovery that
        // returned nothing for every class would satisfy the exact-zero count below vacuously.
        MultiValueCensus control = censusMultiValue(List.of(MultiValueDiscoveryControl.class));
        assertEquals(
                List.of("MultiValueDiscoveryControl.multiValue",
                        "MultiValueDiscoveryControl.nullableElementValue",
                        "MultiValueDiscoveryControl.nullableListValue"),
                control.found(),
                "CONTROL FAILED: over a class declaring exactly three List<IDataValue> methods (plus"
                        + " a scalar IDataValue one and a List<String> one) the discovery must find"
                        + " exactly those — otherwise it is not reaching type arguments, and the"
                        + " exact-zero production count below is satisfied by a blind scan: "
                        + control.found());
        // ⛔ The two nullable detectors run over the SAME control population, each of which must
        // see exactly its own violation. Without this the census's nullableList / nullableElement
        // arms below are asserted empty over an empty production population — a detector that
        // never reported anything would pass them forever.
        assertEquals(List.of("MultiValueDiscoveryControl.nullableListValue"),
                control.nullableList(),
                "CONTROL FAILED: the nullable-LIST detector must see exactly the @Nullable List"
                        + " declaration: " + control.nullableList());
        assertEquals(List.of("MultiValueDiscoveryControl.nullableElementValue"),
                control.nullableElement(),
                "CONTROL FAILED: the nullable-ELEMENT detector must see exactly the"
                        + " List<@Nullable IDataValue> declaration: " + control.nullableElement());

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

        // --- the production population --------------------------------------------------------
        MultiValueCensus census = censusMultiValue(
                ProductionClasses.ofModule(ScalarSemantics.class));

        assertEquals(EXPECTED_MULTI_VALUE_PRODUCERS, census.found().size(),
                "⚑ a new 0..N IDataValue producer appeared in this module (the population has been"
                        + " ZERO since the lookupAll channel was retired on 2026-09-25). Read it"
                        + " against BOTH halves of the contract — the LIST may not be null and an"
                        + " ELEMENT may not be null (every element is an expression input) — before"
                        + " this constant is bumped. Discovered: " + census.found());

        // ⭐⭐ EMPTY since 2026-09-21, and that is the contract: no multi-value declaration may hand
        // back a nullable LIST. The one that did (JoinedCandidatesVector.candidateCells) existed
        // only to let an UNQUALIFIED name vote per joined candidate, which the owner's uniformity
        // ruling abolished. Do not re-add the old one to make this pass.
        assertEquals(List.of(), census.nullableList(),
                "the set of multi-value declarations whose LIST is @Nullable changed. NONE is allowed"
                        + " since 2026-09-21; a NEW nullable LIST needs a three-way justification of"
                        + " its own");

        assertTrue(census.nullableElement().isEmpty(),
                "⛔ these multi-value declarations allow a NULL ELEMENT, and an element of one of"
                        + " these lists is an expression input. Every value a rule reads is a real"
                        + " value or a MissingValue — produce ScalarSemantics.computedMissing() (or"
                        + " DataValues.of, which already answers a MIS carrier for a null input)"
                        + " instead of admitting a null: " + census.nullableElement());
    }

    /** What {@link #censusMultiValue} found: every id sorted, so failures read the same twice. */
    private record MultiValueCensus(List<String> found, List<String> nullableList,
            List<String> nullableElement)
    {
    }

    /**
     * The multi-value DISCOVERY, over any population of classes: every non-synthetic declared
     * method whose return type carries an {@code IDataValue} in a type argument, array component or
     * wildcard bound ({@link #valuePositionsWithin}), with the ids whose LIST and whose ELEMENT
     * type are {@code @Nullable} reported alongside. ⚑ Extracted from the test body on 2026-09-25
     * precisely so the same code runs over the control population and the production one.
     */
    private static MultiValueCensus censusMultiValue(List<Class<?>> classes)
    {
        List<String> found = new ArrayList<>();
        List<String> nullableList = new ArrayList<>();
        List<String> nullableElement = new ArrayList<>();
        for (Class<?> c : classes)
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
        return new MultiValueCensus(found, nullableList, nullableElement);
    }

    /**
     * ⛔ The permanent positive control for the multi-value DISCOVERY and its two nullable detectors
     * (control 1 above): three methods returning a 0..N container of {@code IDataValue} — a clean
     * one, one whose LIST is {@code @Nullable} and one whose ELEMENT is — beside one scalar
     * producer and one list of a non-value type, neither of which may be found. Package-private and
     * reached reflectively on purpose: nothing calls these, and that is the point.
     */
    static final class MultiValueDiscoveryControl
    {

        static List<IDataValue> multiValue()
        {
            return new ArrayList<>();
        }


        static @Nullable List<IDataValue> nullableListValue()
        {
            return new ArrayList<>(); // the DECLARATION is the control; the body never runs
        }


        static List<@Nullable IDataValue> nullableElementValue()
        {
            return new ArrayList<>();
        }


        static IDataValue notMultiValue()
        {
            return ScalarSemantics.computedMissing();
        }


        static List<String> notAValueList()
        {
            return new ArrayList<>();
        }
    }

    /**
     * The number of declarations in this module returning a 0..N container of {@code IDataValue}. ⭐
     * <b>ZERO since 2026-09-25</b> ({@code PLAN-retire-dead-multi-match-lookup} wave A retired the
     * last two, {@code DatasetLookup.lookupAllValues} and {@code JoinLookup.lookupAllValues}; 3 → 2
     * had been {@code JoinedCandidatesVector.candidateCells} on 2026-09-21). ⛔ A ratchet with exact
     * equality, and deliberately SEPARATE from {@link #EXPECTED_VALUE_PRODUCERS}: folding the
     * scalar and multi-value populations into one figure would hide which of two different
     * contracts moved. Bump it only after reading the new producer against both halves of the
     * contract.
     *
     * <p>
     * ⚑ <b>0 → 3 on 2026-09-29, READ:</b> {@code SuppPivot.SuppQnamColumn.materialise}, its builder
     * {@code resolve} and the publication holder's accessor {@code Materialised.values} return the
     * merged column's {@code IDataValue[]} ({@code PLAN-operation-replacements} §2.3). Both halves
     * hold: the array is allocated and returned on every path (never {@code null}), and every
     * element is {@code SuppPivot.BLANK} unless a stored SUPP cell replaces it, so no element is
     * {@code null}. It is a per-column materialisation cache, not a lookup channel — nothing
     * outside the column reads it.
     * </p>
     *
     * <p>
     * ⚑ <b>3 → 4 on 2026-09-29, READ:</b> {@code GroupedAggregate.Grouped.byKey} — the memoised
     * per-execution block map of a grouped aggregate function ({@code PLAN-grouped-aggregate-
     * functions} §2.2: one {@code IDataValue} per group key). Both halves hold: the map is built
     * and wrapped unmodifiable on every path (never {@code null}; an absent dataset or target
     * column stores an empty map), and every value is what the block's aggregator answered — a
     * winning cell, a carried missing or {@code ScalarSemantics.computedMissing()} — so no element
     * is {@code null}; every block claims its key with a value, never a placeholder.
     * </p>
     *
     * <p>
     * ⚑ <b>5 → 6 in the combined review of runbook W2–W8 (XCUT PERF 1), READ:</b>
     * {@code JoinCache.SharedIndexCache.getOrBuildSuppQnamColumn} hands the SAME
     * {@code IDataValue[]} that {@code SuppQnamColumn.resolve} builds through the run's shared
     * cache, so one parent's qualifier column is materialised once per run instead of once per
     * rule. Both halves hold for the same reason as the 0 → 3 entry: the array is the builder's
     * (allocated on every path, every element {@code SuppPivot.BLANK} or a stored SUPP cell), and
     * the cache stores it as-is — {@code IdentityWeakCache.getOrBuild} never stores {@code null}.
     * </p>
     */
    private static final int EXPECTED_MULTI_VALUE_PRODUCERS = 6;

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
     *
     * <p>
     * ⚑ <b>17 → 18 on 2026-09-21, and the ADDITION is ACCOUNTED FOR</b> — which is what this
     * ratchet asks for and the reason it is not a formality.
     * {@code JoinLookup.absentJoinedColumnValue} is the extraction of an arm that had <b>three</b>
     * production implementations ({@code PLAN-join-key-missing-semantics} phase 6b(3)), and the
     * ratchet caught it on the first run. <b>Read for its verdict:</b> it answers
     * {@code ScalarSemantics.computedMissing()} for a numeric read and
     * {@code DataValueSupport.defaultForType(STRING)} otherwise — both real values, never
     * {@code null}, and its return type carries no {@code @Nullable}. ⇒ the net population is +1
     * while the three <em>call sites</em> that used to spell this inline are unchanged in count,
     * because each still declares its own {@code lookupValue}. The producer moved; it did not
     * multiply.
     * </p>
     *
     * <p>
     * ⚑ <b>18 → 17 on 2026-09-25, ACCOUNTED FOR:</b> {@code JoinLookup}'s three-argument
     * {@code lookupValue} default — the form without the expectation flag, delegating with
     * {@code false} — was retired by {@code PLAN-retire-dead-multi-match-lookup} (U14): no
     * production code called it, and every production implementation overrides the four-argument
     * form. The four-argument default and the three overrides stay, so the channel lost a
     * delegating wrapper and no producer of its own.
     * </p>
     *
     * <p>
     * ⚑ <b>17 → 18 on 2026-09-28, READ:</b> {@code BuiltinFunctions.carrierCell}
     * ({@code PLAN-case-fold-missing-d36}) — the cell an n-ary string producer ({@code concat},
     * {@code substring}, {@code prefix}/{@code suffix}) hands through for the D86a-combined
     * identity of its missing operands: the operand whose own missing it is (the input cell
     * verbatim, D85c), else {@code ScalarSemantics.computedMissing()} for two collapsed identities.
     * Never {@code null}; its {@code @Nullable} half is a {@code MissingValue}
     * ({@code combinedMissing}, {@code null} = every operand present), which is outside this
     * population by type. The unary producers return the input's {@code TypedValue.cell()} directly
     * and declare no {@code IDataValue} of their own.
     * </p>
     *
     * <p>
     * ⚑ <b>18 → 18 on 2026-09-29, a MOVE, READ:</b>
     * {@code PLAN-missing-identity-nonstring-functions} moved {@code carrierCell} (and its
     * {@code MissingValue}-typed partner {@code combinedMissing}) out of {@code BuiltinFunctions}
     * into {@code ArithmeticSemantics}, beside {@code combineIdentities}, as the one public helper
     * the n-ary function producers share ({@code concat}, {@code substring},
     * {@code prefix}/{@code suffix}, {@code coalesce}, {@code StudyDay}).
     * {@code BuiltinFunctions.carrierCell} −1, {@code ArithmeticSemantics.carrierCell} +1 — same
     * body, still never {@code null}. The non-string producers the plan changed ({@code len},
     * {@code char}, {@code numericValue}, {@code dateComponent}, {@code hullBound},
     * {@code splitBy}, {@code colref}, {@code Primitives.numConversion}) are lambdas or
     * {@code Object}-returning helpers that hand a cell or {@code computedMissing()} through the
     * untyped {@code ComputedVector} channel and declare no {@code IDataValue} of their own.
     * </p>
     *
     * <p>
     * ⚑ <b>18 → 20 on 2026-09-29, READ:</b> the declared SUPP merge ({@code SuppPivot},
     * {@code PLAN-operation-replacements} §2.3). {@code SuppQnamIndex.Entry.qval} is the record
     * accessor of the parsed SUPP row's {@code QVAL} cell — the table's own cell (a missing
     * {@code QVAL} keeps its identity) or {@code SuppPivot.BLANK} when the table has no
     * {@code QVAL} column; never {@code null}. {@code SuppPivot.SuppQnamColumn.getDataValue} reads
     * the materialised column: every slot is pre-filled with {@code BLANK} (the present blank a
     * record no qualifier row reaches owes, D34 #3) and overwritten only with a stored cell; never
     * {@code null}.
     * </p>
     *
     * <p>
     * ⚑ <b>20 → 23 on 2026-09-29, READ:</b> {@code row_max} as a registry function
     * ({@code PLAN-per-row-functions}, {@code RowMax}). {@code RowMax.rowMax} answers the winning
     * candidate cell, the carried missing of an all-missing row or
     * {@code ScalarSemantics.computedMissing()}; {@code RowMax.carrierOf} the first matched cell
     * carrying the combined missing identity or {@code computedMissing()};
     * {@code RowMax.numericMax} one of its (non-empty) candidate cells. None is {@code null}. The
     * retired {@code OperationExecutor.evalRowExtreme} answered a {@code GroupedResult} of strings
     * and was not in this population.
     * </p>
     *
     * <p>
     * ⚑ <b>23 → 28 on 2026-09-29, READ:</b> the grouped aggregate functions
     * ({@code PLAN-grouped-aggregate-functions}, {@code GroupedAggregate}).
     * {@code GroupedAggregate.maxOf} answers the winning candidate cell (numeric or text), the
     * carried missing of an all-missing block or {@code ScalarSemantics.computedMissing()};
     * {@code GroupedAggregate.dateExtremeOf} the cell whose raw text the EC-46 accumulator
     * selected, else the same two missings; {@code MissingScan.noCandidate} the first read cell
     * carrying the block's combined missing identity or {@code computedMissing()};
     * {@code Aggregator.aggregate} is the interface those two implement (declared non-null);
     * {@code ReadValue.Spec.selectWithin} the selected row's cell or X's type default
     * ({@code DataValueSupport.defaultForType}, D13) for a group the filter empties;
     * {@code GroupedAggregate.RowAnswers.at} the primary row's own block answer, every slot
     * pre-filled with the no-group answer. None is {@code null}. The retired
     * {@code OperationExecutor.evalMaxGrouped} / {@code evalDateExtremeGrouped} answered a
     * {@code GroupedResult} of boxed doubles and strings and were not in this population.
     * </p>
     */
    // Runbook W7 (PLAN-distinct-function) added no producer: an absent distinct target reads
    // ScalarSemantics.computedMissing() through an inline lambda (a named helper was 28 -> 29 for
    // one gate run, then folded away for PMD's unused-parameter rule).
    // 28 → 27 in runbook W8 (PLAN-retire-operation-surface): the computed-target materialiser's
    // synthetic column (`TargetExpressionMaterializer.VectorColumn.getDataValue`) went with the
    // operation surface — a deleted producer, read and accounted for, not a new one.
    // 27 → 28 in the combined review of runbook W2–W8 (XCUT PERF 3):
    // GroupedAggregate.RowAnswers.at,
    // the per-row answer slots of a grouping over the PRIMARY table (no key derived per row).
    // 28 → 29 in PLAN-rprfdy-offset-tp-join phase 3 (C3): RuleRunner.QualifiedGroupKey.cell, the
    // one read of a qualified rule-level grouping key's component — a plain member's own cell, a
    // qualified member's cell of the row's bound source record through JoinLookup.lookupValue.
    // Read: both channels answer a real value or a MissingValue (the lookup's typed contract),
    // never null.
    // 29 -> 30 (PLAN-scalar-date-extremes): ScalarDateExtremes.pairExtreme, the per-row producer of
    // earliest_date / latest_date — read: it answers the winning INPUT cell, the carried identity
    // when both inputs are missing, or ScalarSemantics.computedMissing(); never null.
    // 30 -> 34 (PLAN-dynamic-column-functions phase 2): DynamicColumnRead.cell / .nameOf / .member
    // (colref's one resolver) and the boolean ExprCompiler.dottedNotSuppliedDefault overload. Read:
    // cell answers the column's own cell (a missing one handed through), the joined lookup's typed
    // value, or the D76 default (computedMissing / the present ""); nameOf answers computedMissing
    // for a non-string first hop and cell() otherwise; member answers the element's own missing
    // cell, cell(), or computedMissing; the overload answers computedMissing or "". Never null.
    // 34 -> 35 (PLAN-dynamic-column-functions phase 3): DynamicColumnRead.memberCell — read: it
    // answers its input cell, or DataValues.of(the input's text) for a present character cell;
    // never null.
    private static final int EXPECTED_VALUE_PRODUCERS = 35;

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
