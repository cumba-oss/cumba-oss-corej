package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * ⭐⭐ Makes {@link JoinLookup}'s own javadoc claim ENFORCEABLE: <i>"this default is reached by TEST
 * doubles only — treat a new production implementation that relies on it as a defect."</i>
 *
 * <p>
 * ⚠⚠ <b>Three separate lanes verified that claim TRUE by reading the tree, and nothing made it
 * HOLD.</b> A fourth production implementation that overrides only {@link JoinLookup#lookup} (the
 * {@code String} channel) compiles green, passes every gate, and silently drops the DOTTED-PARITY
 * INVARIANT on its own path: the four-argument
 * {@link JoinLookup#lookupValue(IDataTable, long, String, boolean)} default cannot honour the
 * three-way non-value contract — it can only answer the computed {@code MIS}, losing a supplied
 * missing identity and reading an absent CHARACTER column as missing where the constant {@code ""}
 * is owed. A verified-by-reading claim is worth nothing next session; only a check is.
 * </p>
 *
 * <p>
 * ⛔⛔ <b>A DECLARATION IS NOT A BEHAVIOUR — and the cheapest way to make a declaration-only guard
 * green is to lose the expectation anyway (review round 2, H2).</b> This class asserted only that
 * each implementation <em>declares</em> the four-argument form. The green-buying move that guard
 * invited is to paste {@link JoinLookup}'s own default body into the override:
 * {@code String s = lookup(…); return s == null ? computedMissing() : getAsDataValue(s, STRING);}.
 * The declaration then exists, {@link #probes()} gets bumped by its author, the gate is green, and
 * dotted parity is silently dropped on that path — an absent numeric-expected joined column answers
 * {@code ""} again. Copying the default is exactly what an author does when a guard demands a
 * declaration and nothing demands a behaviour. ⇒ Every discovered implementation is therefore
 * PROBED: {@code lookupValue(table, row, <a column absent from the joined dataset>, true)} must
 * answer a {@link MissingValue} and the same read with {@code false} must answer the present
 * constant {@code ""}. All three branch on the flag in exactly that arm, so a real probe is
 * available and no reflection stands in for it. {@link CopiedInterfaceDefaultLookup} is the
 * permanent control for precisely this shape: it PASSES the declaration check and must FAIL the
 * behavioural one.
 * </p>
 *
 * <p>
 * ⛔ <b>What this guard would report if the population MOVED — stated, because a scan whose
 * population is a convention has not checked the population.</b> The enumeration is
 * {@link ProductionClasses#ofModule}, a walk of this module's compiled classes, so it is immune to
 * a package rename or a move between packages and it DOES see nested and anonymous implementations.
 * It is blind to an implementation compiled into a different module of this repo, into another repo
 * (the OSS twin {@code cumba-oss-corej} included), or generated at run time. ⇒ Which is why the
 * probe roster is asserted as an EXACT SET and not as a floor: an implementation that leaves this
 * module reds HERE, with a message saying it left, instead of quietly dropping out of a population
 * nobody counts. A new one that lands OUTSIDE the module still escapes, and closing that needs a
 * guard in that module — the same per-repo limitation
 * {@code MetadataProviderDecoratorDelegationGuardTest} records for the client repos.
 * </p>
 */
class JoinLookupProductionOverrideGuardTest
{

    /**
     * A column name no fixture below declares, so every probe lands in the absent-column arm —
     * {@code DatasetLookup:346}, {@code KeyMatchExpandedLookup:78},
     * {@code RelrecExpandedLookup:139} — which is the one arm that reads {@code numericExpected}.
     */
    private static final String ABSENT_COLUMN = "ZZNOSUCHCOLUMN";

    /** One built implementation plus the primary table and row to probe it at. */
    private record Probe(JoinLookup lookup, IDataTable primary, long row)
    {
    }

    /**
     * The production {@link JoinLookup} implementations of this module, 2026-09-18, each paired
     * with a BUILT INSTANCE the behavioural probe can call. ⛔ Exact equality against the discovered
     * set in both directions: a new one must be READ (does it honour the expectation?) <em>and</em>
     * a probe must be built for it, and a departed one must be accounted for. ⚑ Requiring an
     * instance rather than a name is deliberate — it is what makes the roster impossible to satisfy
     * without exercising the behaviour.
     */
    private static Map<String, Probe> probes()
    {
        IDataTable child = MockTable.of().col("USUBJID", "S1").col("ARM", "A").name("DM").build();
        IDataTable primary = MockTable.of().col("USUBJID", "S1").name("AE").build();
        Map<String, Probe> out = new TreeMap<>();
        out.put("DatasetLookup",
                new Probe(
                        Objects.requireNonNull(DatasetLookup.build("DM", child, List.of("USUBJID")),
                                "DatasetLookup.build answered null for a non-null dataset"),
                        primary, 0));
        out.put("KeyMatchExpandedLookup",
                new Probe(new KeyMatchExpandedLookup("DM", child, new long[]
                {
                        0
                }), primary, 0));
        out.put("RelrecExpandedLookup", new Probe(new RelrecExpandedLookup(List.of(child), new int[]
        {
                0
        }, new long[]
        {
                0
        }), primary, 0));
        return out;
    }


    @Test
    void everyProductionJoinLookupHonoursTheRuleExpectationOnAnAbsentColumn()
    {
        List<Class<?>> impls = new ArrayList<>();
        for (Class<?> c : ProductionClasses.ofModule(JoinLookup.class))
        {
            if (JoinLookup.class.isAssignableFrom(c) && !c.isInterface()
                    && !Modifier.isAbstract(c.getModifiers()))
            {
                impls.add(c);
            }
        }

        Map<String, Probe> probes = probes();
        assertEquals(new ArrayList<>(probes.keySet()),
                new ArrayList<>(new TreeSet<>(impls.stream().map(Class::getSimpleName).toList())),
                "the set of PRODUCTION JoinLookup implementations in this module changed. Read the"
                        + " new one against the dotted-parity invariant on"
                        + " JoinLookup.lookupValue(…, boolean) and BUILD A PROBE for it in probes()"
                        + " before bumping this roster — and if one LEFT the module, note that this"
                        + " guard can no longer see it");

        List<String> notDeclared = new ArrayList<>();
        List<String> ignoresTheExpectation = new ArrayList<>();
        for (Class<?> c : impls)
        {
            if (!declaresExpectationAwareLookupValue(c))
            {
                notDeclared.add(c.getSimpleName());
            }
            if (!honoursTheRuleExpectation(probes.get(c.getSimpleName())))
            {
                ignoresTheExpectation.add(c.getSimpleName());
            }
        }
        assertTrue(notDeclared.isEmpty(),
                "⛔ these production JoinLookup implementations do NOT declare lookupValue(IDataTable,"
                        + " long, String, boolean) and therefore inherit the interface default,"
                        + " which cannot honour the three-way non-value contract: an absent CHARACTER"
                        + " column reads as a computed MissingValue.MIS where the constant \"\" is"
                        + " owed, and a present-but-missing cell loses its own missing identity."
                        + " Override it, as DatasetLookup / KeyMatchExpandedLookup /"
                        + " RelrecExpandedLookup do: " + notDeclared);
        assertTrue(ignoresTheExpectation.isEmpty(),
                "⛔⛔ these production JoinLookup implementations DECLARE the expectation-aware"
                        + " lookupValue and do not HONOUR it: for a column absent from the joined"
                        + " dataset, numericExpected=true must answer MissingValue.MIS and"
                        + " numericExpected=false the present constant \"\" (dotted parity, §9c /"
                        + " D72 / D76 / §1b). Answering the same thing for both is what pasting"
                        + " JoinLookup's own default body into the override produces — see"
                        + " CopiedInterfaceDefaultLookup: " + ignoresTheExpectation);
    }


    /**
     * ⭐ Sensitivity arm, PERMANENT rather than a one-off sabotage: each detector must classify
     * every way of losing the expectation as a violation. Without this the checks above could pass
     * vacuously — a renamed parameter type or a reflection mistake would make
     * {@code declaresExpectationAwareLookupValue} answer {@code true} for everything, and a probe
     * built on the wrong column (one the fixture actually declares) would never enter the arm that
     * reads the flag, so {@code honoursTheRuleExpectation} would report on a branch it never took.
     */
    @Test
    void theDetectorsRedOnEveryWayOfLosingTheExpectation()
    {
        IDataTable primary = MockTable.of().col("USUBJID", "S1").name("AE").build();

        // --- the DECLARATION detector ------------------------------------------------------
        assertTrue(declaresExpectationAwareLookupValue(DatasetLookup.class),
                "CONTROL FAILED: a real production implementation must be recognised as compliant,"
                        + " or the guard above is asserting nothing");
        assertFalse(declaresExpectationAwareLookupValue(StringChannelOnlyLookup.class),
                "CONTROL FAILED: an implementation overriding only lookup() must be detected as"
                        + " relying on the interface default. The guard above cannot see the defect"
                        + " it exists for, and every check in this class passed vacuously.");
        assertFalse(declaresExpectationAwareLookupValue(ThreeArgOnlyLookup.class),
                "CONTROL FAILED: overriding the THREE-argument lookupValue is not the same as"
                        + " overriding the expectation-aware form — the delegating default hands it"
                        + " numericExpected=false, so the expectation is discarded. It must be"
                        + " detected as non-compliant too.");

        // --- the BEHAVIOURAL detector ------------------------------------------------------
        for (Map.Entry<String, Probe> e : probes().entrySet())
        {
            assertTrue(honoursTheRuleExpectation(e.getValue()),
                    "CONTROL FAILED: " + e.getKey() + " is a real production implementation and"
                            + " must be recognised as HONOURING the expectation — if this fails the"
                            + " probe is not reaching the absent-column arm (wrong column name?)"
                            + " and the behavioural check above reports on a branch never taken");
        }
        assertFalse(honoursTheRuleExpectation(new Probe(new StringChannelOnlyLookup(), primary, 0)),
                "CONTROL FAILED: an implementation on the interface default cannot honour the"
                        + " expectation — it answers a computed MIS for BOTH flags");
        assertFalse(honoursTheRuleExpectation(new Probe(new ThreeArgOnlyLookup(), primary, 0)),
                "CONTROL FAILED: the three-argument override is reached with numericExpected"
                        + " discarded, so it cannot honour the expectation either");

        // ⭐⭐ THE THIRD SHAPE, and the reason this test exists in this form: an implementation that
        // DECLARES the four-argument form and pastes the interface default's own body into it. It
        // must PASS the declaration check — that is what makes it the likeliest mistake — and FAIL
        // the behavioural one. Without this control the behavioural check has exactly the weakness
        // it was added to repair.
        assertTrue(declaresExpectationAwareLookupValue(CopiedInterfaceDefaultLookup.class),
                "CONTROL FAILED: the copied-default shape MUST satisfy the declaration check. If it"
                        + " does not, this control is not the shape it claims to be and says nothing"
                        + " about the declaration/behaviour gap.");
        assertFalse(
                honoursTheRuleExpectation(
                        new Probe(new CopiedInterfaceDefaultLookup(), primary, 0)),
                "CONTROL FAILED: an override whose body is the interface default's, copied, ignores"
                        + " numericExpected and must be detected as NOT honouring the expectation."
                        + " If this passes, the behavioural check is blind to the cheapest way of"
                        + " buying a green and the guard is back to checking a declaration.");
    }


    private static boolean declaresExpectationAwareLookupValue(Class<?> c)
    {
        try
        {
            java.lang.reflect.Method override = c.getDeclaredMethod("lookupValue", IDataTable.class,
                    long.class, String.class, boolean.class);
            // ⚠ A bridge or synthetic declaration is the COMPILER's, not the author's, and carries
            // no implementation of its own — it must not count as an override.
            return !override.isBridge() && !override.isSynthetic();
        }
        catch (NoSuchMethodException e)
        {
            return false;
        }
    }


    /**
     * The BEHAVIOURAL detector: for a column absent from the joined dataset, does this lookup
     * actually apply the RULE's expected default — {@code MissingValue.MIS} when the rule expects a
     * number, the present constant {@code ""} otherwise?
     *
     * <p>
     * ⚠ Both halves matter and for different reasons. Answering {@code MIS} for the numeric read is
     * dotted parity with an absent primary column; answering a PRESENT {@code ""} for the character
     * read is the D96a absent-vs-blank regression, where a computed {@code MIS} sorts below every
     * value under the D34 #5 order arm. An implementation that answers the same thing for both
     * flags has discarded the expectation, whichever thing it answers.
     * </p>
     */
    private static boolean honoursTheRuleExpectation(Probe p)
    {
        IDataValue numeric = p.lookup().lookupValue(p.primary(), p.row(), ABSENT_COLUMN, true);
        IDataValue character = p.lookup().lookupValue(p.primary(), p.row(), ABSENT_COLUMN, false);
        return numeric.isMissingOrInvalid() && MissingValue.MIS.equals(numeric.getValue())
                && !character.isMissingOrInvalid() && "".equals(character.getValueAsString());
    }

    /** The defect shape: the {@code String} channel only, inheriting the typed default. */
    private static final class StringChannelOnlyLookup implements JoinLookup
    {

        @Override
        public @Nullable String lookup(IDataTable primaryTable, long row, String columnName)
        {
            return null;
        }


        @Override
        public String getDatasetName()
        {
            return "CONTROL";
        }
    }


    /** The subtler defect shape: the legacy three-argument typed form, which loses the flag. */
    private static final class ThreeArgOnlyLookup implements JoinLookup
    {

        @Override
        public @Nullable String lookup(IDataTable primaryTable, long row, String columnName)
        {
            return null;
        }


        @Override
        public IDataValue lookupValue(IDataTable primaryTable, long row, String columnName)
        {
            return ScalarSemantics.computedMissing();
        }


        @Override
        public String getDatasetName()
        {
            return "CONTROL";
        }
    }


    /**
     * ⭐⭐ The likeliest defect shape, and the one a declaration-only guard invites: the
     * four-argument form IS declared, and its body is {@link JoinLookup}'s own default, COPIED
     * VERBATIM — so {@code numericExpected} is accepted and thrown away. ⛔ Do not "simplify" this
     * to delegate to {@code JoinLookup.super.lookupValue(…)}: the point of the control is that the
     * body is the default's, written out, exactly as an author would paste it.
     */
    private static final class CopiedInterfaceDefaultLookup implements JoinLookup
    {

        @Override
        public @Nullable String lookup(IDataTable primaryTable, long row, String columnName)
        {
            return null;
        }


        @Override
        public IDataValue lookupValue(IDataTable primaryTable, long row, String columnName,
                boolean numericExpected)
        {
            String s = lookup(primaryTable, row, columnName);
            return s == null ? ScalarSemantics.computedMissing()
                    : DataValueSupport.getAsDataValue(s, DataValueType.STRING);
        }


        @Override
        public String getDatasetName()
        {
            return "CONTROL";
        }
    }
}
