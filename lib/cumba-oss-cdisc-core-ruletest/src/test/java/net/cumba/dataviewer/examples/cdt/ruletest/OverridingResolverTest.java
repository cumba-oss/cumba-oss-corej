package net.cumba.dataviewer.examples.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.cumba.cdisc.core.exec.DatasetResolver;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link OverridingResolver} — static factories, case-insensitive key normalisation, lookup
 * precedence (dropped → overrides → delegate),
 * {@link DatasetResolver.WithInventory#availableDatasets()} merging, chaining, and the
 * introspection getters.
 */
class OverridingResolverTest
{

    private static OverlayDataTable table(String aName)
    {
        OverlayDataTable t = OverlayDataTable.empty(aName, aName, 1);
        t.addColumn("A");
        t.setValue(0, "A", "x");
        return t;
    }


    /** Inventory-aware delegate yielding three named datasets. */
    private static DatasetResolver.WithInventory inventoryDelegate(IDataTable aDm, IDataTable aAe,
            IDataTable aSs)
    {
        return new DatasetResolver.WithInventory()
        {

            @Override
            public IDataTable resolve(String aDomain)
            {
                if (aDomain == null)
                {
                    return null;
                }
                return switch (aDomain.toUpperCase(java.util.Locale.ROOT))
                {
                case "DM" -> aDm;
                case "AE" -> aAe;
                case "SS" -> aSs;
                default -> null;
                };
            }


            @Override
            public Set<String> availableDatasets()
            {
                return Set.of("DM", "AE", "SS");
            }
        };
    }


    /** Bare resolver (no inventory) — exercises the {@code instanceof} fallback path. */
    private static DatasetResolver bareDelegate(IDataTable aDm)
    {
        return aName -> aName != null && "DM".equalsIgnoreCase(aName) ? aDm : null;
    }

    @Nested
    class FactoryTests
    {

        @Test
        void overrides_normalisesKeysToUpperCase()
        {
            IDataTable t = table("dm");
            Map<String, IDataTable> overrides = new LinkedHashMap<>();
            overrides.put("dm", t);
            overrides.put("Ae", table("Ae"));

            OverridingResolver r = OverridingResolver.overrides(bareDelegate(null), overrides);

            // All keys are stored upper-case.
            assertTrue(r.getOverrides().containsKey("DM"));
            assertTrue(r.getOverrides().containsKey("AE"));
            assertFalse(r.getOverrides().containsKey("dm"));
            assertFalse(r.getOverrides().containsKey("Ae"));
            assertEquals(2, r.getOverrides().size());
        }


        @Test
        void overrides_nullMapIsTolerated()
        {
            OverridingResolver r = OverridingResolver.overrides(bareDelegate(null), null);
            assertTrue(r.getOverrides().isEmpty());
            assertTrue(r.getDropped().isEmpty());
        }


        @Test
        void overrides_nullKeyIsSkipped()
        {
            Map<String, IDataTable> in = new HashMap<>();
            in.put(null, table("ignored"));
            in.put("X", table("X"));

            OverridingResolver r = OverridingResolver.overrides(bareDelegate(null), in);

            assertEquals(1, r.getOverrides().size());
            assertTrue(r.getOverrides().containsKey("X"));
        }


        @Test
        void override_shorthandWrapsSingleDomain()
        {
            IDataTable t = table("AE");
            OverridingResolver r = OverridingResolver.override(bareDelegate(null), "ae", t);

            assertEquals(1, r.getOverrides().size());
            assertSame(t, r.getOverrides().get("AE"));
            assertTrue(r.getDropped().isEmpty());
        }


        @Test
        void override_nullDomainThrows()
        {
            // Map.of(null, …) throws NullPointerException — and so does override().
            OverlayDataTable x = table("X");
            DatasetResolver delegate = bareDelegate(null);
            assertThrows(NullPointerException.class,
                    () -> OverridingResolver.override(delegate, null, x));
        }


        @Test
        void without_normalisesKeysToUpperCase()
        {
            OverridingResolver r = OverridingResolver.without(bareDelegate(null), "ae", "DM", "ss");

            assertEquals(Set.of("AE", "DM", "SS"), r.getDropped());
            assertTrue(r.getOverrides().isEmpty());
        }


        @Test
        void without_nullEntryIsSkipped()
        {
            OverridingResolver r = OverridingResolver.without(bareDelegate(null), "AE", null, "SS");
            assertEquals(Set.of("AE", "SS"), r.getDropped());
        }


        @Test
        void without_emptyVarargsYieldsEmptyDroppedSet()
        {
            OverridingResolver r = OverridingResolver.without(bareDelegate(null));
            assertTrue(r.getDropped().isEmpty());
            assertTrue(r.getOverrides().isEmpty());
        }
    }


    @Nested
    class ResolveTests
    {

        @Test
        void resolve_nullName_returnsNull()
        {
            OverridingResolver r = OverridingResolver.overrides(bareDelegate(table("DM")),
                    Map.of());
            assertNull(r.resolve(null));
        }


        @Test
        void resolve_droppedTakesPrecedenceOverEverythingElse()
        {
            IDataTable dm = table("DM");
            IDataTable override = table("DM-override");
            DatasetResolver.WithInventory delegate = inventoryDelegate(dm, null, null);

            // Build a resolver that has BOTH an override and a dropped entry for DM —
            // dropped must win.
            OverridingResolver r = OverridingResolver.overrides(delegate, Map.of("DM", override))
                    .without("DM");

            assertNull(r.resolve("DM"));
            assertNull(r.resolve("dm"));
        }


        @Test
        void resolve_overrideBeatsDelegate()
        {
            IDataTable delegateDm = table("DM-delegate");
            IDataTable overrideDm = table("DM-override");
            DatasetResolver.WithInventory delegate = inventoryDelegate(delegateDm, null, null);

            OverridingResolver r = OverridingResolver.override(delegate, "DM", overrideDm);

            // Case-insensitive lookup hits the override.
            assertSame(overrideDm, r.resolve("DM"));
            assertSame(overrideDm, r.resolve("dm"));
            assertSame(overrideDm, r.resolve("Dm"));
        }


        @Test
        void resolve_delegatesWhenNoOverrideOrDrop()
        {
            IDataTable dm = table("DM");
            IDataTable ae = table("AE");
            DatasetResolver.WithInventory delegate = inventoryDelegate(dm, ae, null);

            OverridingResolver r = OverridingResolver.override(delegate, "SS", table("SS-new"));

            assertSame(dm, r.resolve("DM"));
            assertSame(ae, r.resolve("ae"));
        }


        @Test
        void resolve_unknownName_returnsNullViaDelegate()
        {
            DatasetResolver.WithInventory delegate = inventoryDelegate(table("DM"), null, null);
            OverridingResolver r = OverridingResolver.overrides(delegate, Map.of());

            assertNull(r.resolve("UNKNOWN"));
        }


        @Test
        void resolve_chained_withoutThenWithout_addsDroppedNames()
        {
            IDataTable dm = table("DM");
            IDataTable ae = table("AE");
            IDataTable ss = table("SS");
            DatasetResolver.WithInventory delegate = inventoryDelegate(dm, ae, ss);

            OverridingResolver r = OverridingResolver.without(delegate, "ae").without("ss");

            assertNull(r.resolve("AE"));
            assertNull(r.resolve("SS"));
            assertSame(dm, r.resolve("DM"));
            assertEquals(Set.of("AE", "SS"), r.getDropped());
        }


        @Test
        void resolve_chained_withoutPreservesOverrides()
        {
            IDataTable delegateDm = table("DM-delegate");
            IDataTable overrideDm = table("DM-override");
            IDataTable ae = table("AE");
            DatasetResolver.WithInventory delegate = inventoryDelegate(delegateDm, ae, null);

            OverridingResolver r = OverridingResolver.override(delegate, "DM", overrideDm)
                    .without("AE");

            assertSame(overrideDm, r.resolve("DM"));
            assertNull(r.resolve("AE"));
            assertEquals(Set.of("DM"), r.getOverrides().keySet());
            assertEquals(Set.of("AE"), r.getDropped());
        }


        @Test
        void resolve_chained_withoutNullEntryIsSkipped()
        {
            DatasetResolver.WithInventory delegate = inventoryDelegate(table("DM"), null, null);
            OverridingResolver r = OverridingResolver.without(delegate, "AE")
                    .without((String) null);

            assertEquals(Set.of("AE"), r.getDropped());
        }
    }


    @Nested
    class AvailableDatasetsTests
    {

        @Test
        void availableDatasets_mergesDelegateInventoryWithOverrides()
        {
            DatasetResolver.WithInventory delegate = inventoryDelegate(table("DM"), table("AE"),
                    table("SS"));
            OverridingResolver r = OverridingResolver.override(delegate, "NEW", table("NEW"));

            assertEquals(Set.of("DM", "AE", "SS", "NEW"), r.availableDatasets());
        }


        @Test
        void availableDatasets_removesDroppedEntries()
        {
            DatasetResolver.WithInventory delegate = inventoryDelegate(table("DM"), table("AE"),
                    table("SS"));
            OverridingResolver r = OverridingResolver.without(delegate, "AE");

            Set<String> avail = r.availableDatasets();
            assertFalse(avail.contains("AE"));
            assertTrue(avail.contains("DM"));
            assertTrue(avail.contains("SS"));
        }


        @Test
        void availableDatasets_combinesOverrideAndDropped()
        {
            DatasetResolver.WithInventory delegate = inventoryDelegate(table("DM"), table("AE"),
                    table("SS"));
            OverridingResolver r = OverridingResolver.override(delegate, "NEW", table("NEW"))
                    .without("AE");

            Set<String> avail = r.availableDatasets();
            assertEquals(Set.of("DM", "SS", "NEW"), avail);
        }


        @Test
        void availableDatasets_bareDelegate_yieldsEmptyBaseInventory()
        {
            // A plain functional-interface DatasetResolver has no inventory; OverridingResolver
            // must fall back to an empty base set.
            DatasetResolver bare = bareDelegate(table("DM"));
            OverridingResolver r = OverridingResolver.override(bare, "NEW", table("NEW"))
                    .without("FOO");

            // Only the override is visible, "FOO" was not in the empty base so dropping is a no-op
            // for inventory purposes — but it still appears in the dropped set.
            assertEquals(Set.of("NEW"), r.availableDatasets());
            assertEquals(Set.of("FOO"), r.getDropped());
        }


        @Test
        void availableDatasets_isUnmodifiable()
        {
            DatasetResolver.WithInventory delegate = inventoryDelegate(table("DM"), null, null);
            OverridingResolver r = OverridingResolver.overrides(delegate, Map.of());

            Set<String> avail = r.availableDatasets();
            assertThrows(UnsupportedOperationException.class, () -> avail.add("X"));
        }
    }


    @Nested
    class IntrospectionTests
    {

        @Test
        void getUnderlying_returnsTheConstructorArgument()
        {
            DatasetResolver delegate = bareDelegate(table("DM"));
            OverridingResolver r = OverridingResolver.overrides(delegate, Map.of());
            assertSame(delegate, r.getUnderlying());
        }


        @Test
        void getOverrides_isUnmodifiable()
        {
            OverridingResolver r = OverridingResolver.override(bareDelegate(null), "X", table("X"));
            Map<String, IDataTable> overrides = r.getOverrides();
            OverlayDataTable y = table("Y");
            assertThrows(UnsupportedOperationException.class, () -> overrides.put("Y", y));
        }


        @Test
        void getDropped_isUnmodifiable()
        {
            OverridingResolver r = OverridingResolver.without(bareDelegate(null), "X");
            Set<String> dropped = r.getDropped();
            assertThrows(UnsupportedOperationException.class, () -> dropped.add("Y"));
        }


        @Test
        void chained_preservesUnderlyingReference()
        {
            DatasetResolver delegate = bareDelegate(null);
            OverridingResolver original = OverridingResolver.override(delegate, "DM", table("DM"));
            OverridingResolver chained = original.without("AE");

            assertSame(delegate, chained.getUnderlying());
            // Overrides survive the without() copy.
            assertEquals(original.getOverrides().keySet(), chained.getOverrides().keySet());
        }


        @Test
        void withInventoryContract_isSatisfied()
        {
            // OverridingResolver must implement DatasetResolver.WithInventory.
            DatasetResolver.WithInventory wi = OverridingResolver.overrides(bareDelegate(null),
                    Map.of());
            assertNotNull(wi.availableDatasets());
        }
    }
}
