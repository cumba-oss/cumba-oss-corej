package net.cumba.corej.define.conformance.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.cumba.corej.define.conformance.ct.StubCtProvider;
import net.cumba.corej.define.conformance.library.StubLibraryProvider;
import net.cumba.corej.define.conformance.rule.RuleSet;
import org.junit.jupiter.api.Test;

/**
 * {@link DefineConformanceInput} — in particular {@code resolvedSubmissionFolder}, which decides
 * whether the folder-gated rules (referenced files, the stylesheet) run at all. Getting it wrong in
 * the silent direction turns every file-existence rule into a SKIP, and a report full of SKIPs
 * still reads as a clean define.xml to anyone who only looks at the findings.
 */
class DefineConformanceInputTest
{

    private static final Path DEFINE = Path.of("/submission/analysis/define.xml");

    @Test
    void anExplicitFolderWinsOverTheDefault()
    {
        Path explicit = Path.of("/elsewhere");
        assertEquals(Optional.of(explicit), DefineConformanceInput.builder(DEFINE)
                .submissionFolder(explicit).build().resolvedSubmissionFolder());
        // ...and it still wins when defaulting is explicitly on.
        assertEquals(Optional.of(explicit),
                DefineConformanceInput.builder(DEFINE).submissionFolder(explicit)
                        .useDefaultSubmissionFolder(true).build().resolvedSubmissionFolder());
    }


    @Test
    void withNoExplicitFolderTheDefineParentIsUsedOnlyWhileDefaultingIsOn()
    {
        assertEquals(Optional.of(Path.of("/submission/analysis")),
                DefineConformanceInput.builder(DEFINE).build().resolvedSubmissionFolder());
        assertEquals(Optional.empty(), DefineConformanceInput.builder(DEFINE)
                .useDefaultSubmissionFolder(false).build().resolvedSubmissionFolder());
        // A define.xml with no parent at all cannot default to one.
        assertEquals(Optional.empty(), DefineConformanceInput.builder(Path.of("define.xml")).build()
                .resolvedSubmissionFolder());
    }


    @Test
    void theBuildersOptionalInputsAreCarriedThroughAndItReturnsItself()
    {
        StubCtProvider ct = new StubCtProvider();
        StubLibraryProvider library = new StubLibraryProvider();
        DefineConformanceInput.Builder builder = DefineConformanceInput.builder(DEFINE);
        assertSame(builder, builder.ctProvider(ct));
        assertSame(builder, builder.libraryProvider(library));
        assertSame(builder, builder.submissionFolder(Path.of("/x")));
        assertSame(builder, builder.rulesFiles(List.of(Path.of("/site.json"))));
        DefineConformanceInput input = builder.families(List.of(RuleSet.CDISC)).build();
        assertSame(ct, input.ctProvider());
        assertSame(library, input.libraryProvider());
        assertEquals(List.of(Path.of("/site.json")), input.rulesFiles());
        assertEquals(List.of(RuleSet.CDISC), input.families());
    }


    @Test
    void theCollectionInputsAreDefensivelyCopiedAndNeverNull()
    {
        List<Path> mutable = new ArrayList<>(List.of(Path.of("/a.json")));
        DefineConformanceInput input = DefineConformanceInput.builder(DEFINE).rulesFiles(mutable)
                .build();
        mutable.add(Path.of("/b.json"));
        assertEquals(List.of(Path.of("/a.json")), input.rulesFiles());
        assertThrows(UnsupportedOperationException.class,
                () -> input.rulesFiles().add(Path.of("/c.json")));
        // A null families/rulesFiles becomes an empty list rather than an NPE at use site.
        DefineConformanceInput nulls = new DefineConformanceInput(DEFINE, null, null, false, null,
                null, null, null, null);
        assertTrue(nulls.families().isEmpty());
        assertTrue(nulls.rulesFiles().isEmpty());
        assertThrows(NullPointerException.class, () -> new DefineConformanceInput(null, null, null,
                false, null, null, null, null, null));
    }

}
