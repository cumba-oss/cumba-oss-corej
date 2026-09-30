package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Value-level pins for the registry functions whose result is the operand a rule compares against —
 * {@code extract_metadata} since wave 4b — carried over from the {@code OperationExecutor}
 * evaluators they replaced (the executor retired in runbook W8).
 *
 * <p>
 * <b>Why this matters.</b> A {@code $}-binding's value is not a diagnostic — it is one half of
 * every comparison the rule makes. If {@code max} ignores its {@code filter}, or
 * {@code extract_metadata("filename")} returns the directory instead of the file, then a
 * <em>correct</em> rule silently fires on the wrong records. No rule reviewer can see that. Every
 * assertion below therefore pins the exact value the function publishes, with a negative case for
 * each branch.
 * </p>
 */
// Test fixture exposing LinkedHashMap for ordered iteration in the assertions.
@SuppressWarnings("NonApiType")
class ExtractMetadataFilenamePinTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    // ==================================================================
    // extract_metadata("filename" / "dataset_location") — the source-URI basename.
    // A rule that checks the submitted file name against the dataset name reads
    // exactly this string; an off-by-one in the path split renames every dataset.
    // ==================================================================

    private static @Nullable Object filenameOf(@Nullable String uri)
    {
        MockTable mt = MockTable.of().name("AE").col("AETERM", "x");
        if (uri != null)
        {
            mt = mt.uri(uri);
        }
        // A registry function since wave 4b (PLAN-scalar-metadata-functions): the pin reads the
        // same basename through ScalarMetadataFunctions.extractMetadata.
        return ScalarMetadataFunctions
                .extractMetadata(
                        net.cumba.corej.core.expr.eval.EvalRun.fullRange(EvaluationContext.builder()
                                .table(mt.build()).datasetResolver(NO_RESOLVER).build()),
                        List.of(net.cumba.corej.core.expr.eval.ConstVector.of("filename")))
                .value(0).resolved();
    }


    @Test
    void filenameFromUri_isTheLastPathSegment_andNullWhenThereIsNone()
    {
        // Ordinary absolute file URI: everything before the final '/' is dropped, and exactly
        // one character after it is kept — a +1/-1 slip here yields "a/ae.xpt".
        assertEquals("ae.xpt", filenameOf("file:///data/ae.xpt"), "last path segment, decoded");
        // The separator at index 0 — the case a ">= 0" narrowed to "> 0" silently loses, which
        // would publish "/ae.xpt" (leading slash) as the file name.
        assertEquals("ae.xpt", filenameOf("file:/ae.xpt"), "a path whose only '/' is at index 0");
        // No separator at all: the whole path is the name.
        assertEquals("ae.xpt", filenameOf("ae.xpt"), "a bare relative name");
        // Opaque URI — getPath() is null, so the scheme-specific part is the fallback.
        assertEquals("ae.xpt", filenameOf("s3:bucket/ae.xpt"), "opaque URI falls back to the SSP");
        // Empty path with a non-empty SSP — the second half of the same fallback.
        assertEquals("host", filenameOf("http://host"), "an authority-only URI falls back too");
        // Negative — no URI at all: the accessor has no answer and must publish none.
        assertNull(filenameOf(null), "no source URI ⇒ no file name (never an empty string)");
        // Negative — a URI with neither a path nor a scheme-specific part.
        assertNull(filenameOf("#frag"), "nothing usable in the URI ⇒ no file name");
        // Negative — a path ending in the separator has an empty last segment.
        assertNull(filenameOf("file:///data/"), "a trailing separator leaves no file name");
    }

    // ==================================================================
    // max with group + filter. The filter is the rule author's row selection; ignoring
    // it publishes the maximum of the WRONG subset to every row of the group.
    // ==================================================================

    // ==================================================================
    // Provider stub
    // ==================================================================

}
