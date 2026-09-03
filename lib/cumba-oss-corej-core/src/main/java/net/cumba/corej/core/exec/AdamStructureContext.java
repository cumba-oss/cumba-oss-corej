package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.List;

import net.cumba.corej.core.metadata.AdamDataStructureDetector;
import net.cumba.corej.core.metadata.AdamSubclassDetector;
import net.cumba.datatable.DataTableMeta;
import org.jspecify.annotations.Nullable;

/**
 * The run-time derivation of a dataset's ADaM data-structure set, in <b>one</b> place.
 *
 * <p>
 * Two surfaces need it and they must not disagree. {@link RuleRunner} uses it to decide whether a
 * rule's {@code Scope.Data_Structures} covers the dataset at all; {@link OperationExecutor} uses it
 * to key {@code required_variables()} / {@code expected_variables()} into an ADaM product, whose
 * variable model is structure-keyed. If the gate said <i>"this is a BASIC DATA STRUCTURE, the rule
 * applies"</i> and the operation then resolved its list against some other structure, the rule
 * would report against a standard it was never admitted under — and nothing would say so.
 * </p>
 *
 * <p>
 * The tier order is Fix #154's and is not restated here: Define-XML declaration, then the metadata
 * library's declaration, then the local column/name heuristic, with {@code corej.defineFirst}
 * (default {@code true}) deciding whether a declaration outranks the heuristic. See
 * {@link AdamDataStructureDetector#detect(String, java.util.Collection, String, boolean)}.
 * </p>
 */
public final class AdamStructureContext
{

    private AdamStructureContext()
    {
    }


    /**
     * The dataset's structure set, most-specific first, exactly as the
     * {@code Scope.Data_Structures} gate sees it.
     *
     * @param aMeta
     *            the dataset's metadata
     * @param aDefineProvider
     *            the Define-XML provider, or {@code null}
     * @param aLibraryProvider
     *            the metadata-library provider, or {@code null}
     * @return the structure set, never empty
     */
    public static List<String> detectAll(DataTableMeta aMeta,
            @Nullable MetadataProvider aDefineProvider, @Nullable MetadataProvider aLibraryProvider)
    {
        boolean defineFirst = AdamDataStructureDetector.defineFirstPreference();
        return AdamDataStructureDetector.detectAll(aMeta.getName(), columnNamesOf(aMeta),
                declaredClassOf(aMeta.getName(), aDefineProvider, aLibraryProvider), defineFirst);
    }


    /**
     * The dataset's resolved ADaM <b>subclass</b> tokens, most-specific first — the exact set the
     * {@code Scope.Subclasses} gate matches against ({@link RuleRunner}), and the set
     * {@link OperationExecutor} hands to
     * {@link MetadataProvider#getRequiredVariablesForStructure(String, java.util.List)} so the
     * published {@code subClass} can select the governing data structure.
     *
     * <p>
     * ⚠ This is the {@link #detectAll} of the subclass axis and it exists for the same reason: the
     * gate and the operation must not be able to disagree about what this dataset is. A rule
     * admitted as {@code ADVERSE EVENT} must resolve its variable list from the {@code AE}
     * structure, not from the base — and the only way to guarantee that is one derivation.
     * </p>
     *
     * <p>
     * ⛔ It is a <b>reader</b> of {@link AdamSubclassDetector}, never a second opinion. Detection is
     * computed independently of any variable-resolution concern (plan ruling 7 / §3 non-goal 1):
     * {@code ADAE} still detects structure {@code [OCCURRENCE DATA STRUCTURE]} and is still matched
     * by a rule scoped to that structure. Subclass-awareness selects <em>which structure's
     * variables answer</em>; it never narrows scope.
     * </p>
     *
     * @param aMeta
     *            the dataset's metadata
     * @param aDefineProvider
     *            the Define-XML provider, or {@code null}
     * @param aLibraryProvider
     *            the metadata-library provider, or {@code null}
     * @param aDetectedStructures
     *            the dataset's structure set from {@link #detectAll} — the subclass heuristics'
     *            BDS/OCCDS preconditions read it
     * @return the resolved subclass tokens, most-specific first; empty when the dataset has none
     */
    public static List<String> detectSubclasses(DataTableMeta aMeta,
            @Nullable MetadataProvider aDefineProvider, @Nullable MetadataProvider aLibraryProvider,
            List<String> aDetectedStructures)
    {
        boolean defineFirst = AdamDataStructureDetector.defineFirstPreference();
        return AdamSubclassDetector.resolve(aMeta.getName(), aDetectedStructures,
                columnNamesOf(aMeta),
                declaredSubClassesOf(aMeta.getName(), aDefineProvider, aLibraryProvider),
                defineFirst);
    }


    /** The dataset's column names, in declaration order. */
    public static List<String> columnNamesOf(DataTableMeta aMeta)
    {
        List<String> names = new ArrayList<>(aMeta.getColumnCount());
        for (int i = 0; i < aMeta.getColumnCount(); i++)
        {
            names.add(aMeta.getColumn(i).getName());
        }
        return names;
    }


    /**
     * Fix #154: the dataset's declared class ({@code def:Class}) — the <b>Define-XML</b> provider,
     * falling back to the <b>metadata library</b> when the define has none.
     *
     * <p>
     * Before Fix #154 the two were mutually exclusive — {@code defineProvider != null ?
     * defineProvider : libraryProvider} consulted the library only when there was no define at all,
     * so a define that declared nothing for this dataset silently suppressed a library declaration
     * that did. The tiers are now chained: an <em>answer</em> from the define wins, an
     * <em>absence</em> falls through.
     * </p>
     */
    public static @Nullable String declaredClassOf(@Nullable String aDatasetName,
            @Nullable MetadataProvider aDefineProvider, @Nullable MetadataProvider aLibraryProvider)
    {
        if (aDatasetName == null)
        {
            return null;
        }
        String declared = aDefineProvider != null
                ? aDefineProvider.getDeclaredDatasetClass(aDatasetName)
                : null;
        if (declared == null && aLibraryProvider != null)
        {
            declared = aLibraryProvider.getDeclaredDatasetClass(aDatasetName);
        }
        return declared;
    }


    /**
     * Fix #154: the {@code def:SubClass} counterpart of
     * {@link #declaredClassOf(String, MetadataProvider, MetadataProvider)} — the Define-XML
     * declaration, falling back to the metadata library when the define declares none.
     */
    public static List<String> declaredSubClassesOf(@Nullable String aDatasetName,
            @Nullable MetadataProvider aDefineProvider, @Nullable MetadataProvider aLibraryProvider)
    {
        if (aDatasetName == null)
        {
            return List.of();
        }
        List<String> declared = aDefineProvider != null
                ? aDefineProvider.getDeclaredSubClasses(aDatasetName)
                : List.of();
        if (declared.isEmpty() && aLibraryProvider != null)
        {
            declared = aLibraryProvider.getDeclaredSubClasses(aDatasetName);
        }
        return declared;
    }
}
