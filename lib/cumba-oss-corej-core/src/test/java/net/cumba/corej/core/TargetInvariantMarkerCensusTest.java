package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ⭐⭐ <b>Counts the target-invariant MARKER and pins the exact files that carry it, so erosion to
 * zero cannot be silent — and neither can a marker that merely moves.</b>
 *
 * <p>
 * {@code PLAN-null-free-value-channel} §6 requires the marker to be ONE literal tree-wide and to
 * carry <i>"its expected count so a grep returning a different number is itself a signal"</i>. A
 * stated count in prose is still only prose: nothing re-derives it, so the marker set can drift, or
 * be swept away by a refactor, with every gate green — which is the failure mode §6 itself names.
 * This test is the assertion the requirement was missing for this repo.
 * </p>
 *
 * <p>
 * ⚠ <b>The literal is ASSEMBLED from parts here on purpose</b>, so this census does not count
 * itself and its own prose cannot perturb the number it asserts. ⇒ Do not "tidy" the concatenation
 * into a constant string: that silently adds occurrences, and the first person to see it will be
 * whoever bumps {@link #EXPECTED_IN_THIS_REPO} to make the gate green again.
 * </p>
 *
 * <p>
 * ⛔ <b>Both assertions are exact equalities — never a floor, never a subset.</b> Both siblings (the
 * datatable repository's {@code TargetInvariantMarkerCountTest} and the rules repository's census
 * of the same name) give the reason: <i>"a floor would pass over a banner that was deleted while
 * another was added, and a subset check would pass over a banner that wandered into a file the
 * invariant does not describe."</i> Until 2026-09-19 this census asserted the TOTAL ONLY — the
 * weakest of the three, over the largest share of the tree's markers. So deleting the whole
 * target-invariant paragraph from {@code ScalarSemantics} (a <b>main</b>-source statement of the
 * invariant, 4 of the 16 occurrences) while adding markers to {@code README.md} in the same commit
 * kept the total unchanged: census green, gate green, and the statement of the invariant gone from
 * production source. That edit reds in either sibling repo, and now reds here too. The file list is
 * compared <b>sorted</b>, because walk order is a property of the filesystem rather than of the
 * invariant.
 * </p>
 *
 * <p>
 * ⛔ <b>Scope:</b> this repo only. The plan's other two repos (the datatable repository, the rules
 * repository) carry their own occurrences and need their own census — a CI run here cannot read
 * them. The tree-wide total is therefore NOT asserted anywhere by this class, and claiming
 * otherwise would be the vacuous kind of coverage.
 * </p>
 */
class TargetInvariantMarkerCensusTest
{

    /**
     * ⭐ The measured number of marker occurrences in this repo, 2026-09-18. ⛔ A ratchet, not a
     * limit: read what moved before changing it. Adding a well-placed marker is fine and is
     * expected to raise this by exactly the number added; a DROP means statements of the invariant
     * were removed, which is the erosion this test exists to catch. ⚡ It never moves alone:
     * {@link #EXPECTED_FILES} carries the same measurement per file and must be updated with it.
     */
    private static final int EXPECTED_IN_THIS_REPO = 16;

    /** ⚠ Assembled, not written out — see the class javadoc. */
    private static final String MARKER = "TARGET-" + "INVARIANT" + "(null-free-value-channel)";

    /** The two rival spellings §6 retired; they must not come back and re-fragment the grep. */
    private static final List<String> RETIRED_SPELLINGS = List
            .of("TARGET-" + "INVARIANT" + "-NOT-YET-FACT", "TARGET-" + "INVARIANT" + "-NULL-FREE");

    /**
     * ⭐ The exact set of files allowed to carry {@link #MARKER}, each with its own occurrence
     * count, sorted and repository-relative. Re-derived 2026-09-19: 16 occurrences across these 12
     * files, all of them {@code .java}, all under this module.
     *
     * <p>
     * ⛔ Exact equality against this list — see the class javadoc for why neither a floor nor a
     * subset would do. ⚡ <b>The per-file counts are part of the expectation, not decoration:</b> a
     * total plus a bare set of filenames would still pass over a statement moved from one censused
     * file to another, and how many times a file states the invariant is itself the thing being
     * pinned.
     * </p>
     *
     * <p>
     * ⚠ Each entry is split across two string fragments only because the full path plus its count
     * does not fit the line limit — unlike {@link #MARKER}, whose splitting IS load-bearing. These
     * paths carry no marker, so nothing here perturbs the number being asserted; do not read the
     * split as the self-count trick and do not "restore" it when adding an entry that happens to
     * fit.
     * </p>
     */
    private static final List<String> EXPECTED_FILES = List.of(
            "lib/cumba-oss-corej-core/src/main/java/net/cumba/corej/core"
                    + "/exec/KeyMatchRowExpander.java ×1",
            "lib/cumba-oss-corej-core/src/main/java/net/cumba/corej/core"
                    + "/exec/PolymorphicMergedColumn.java ×1",
            "lib/cumba-oss-corej-core/src/main/java/net/cumba/corej/core"
                    + "/exec/ScalarSemantics.java ×4",
            "lib/cumba-oss-corej-core/src/main/java/net/cumba/corej/core"
                    + "/expr/eval/ComputedVector.java ×1",
            "lib/cumba-oss-corej-core/src/main/java/net/cumba/corej/core"
                    + "/expr/eval/spi/BuiltinFunctions.java ×1",
            "lib/cumba-oss-corej-core/src/main/java/net/cumba/corej/core"
                    + "/expr/typed/TypeExpectations.java ×1",
            "lib/cumba-oss-corej-core/src/test/java/net/cumba/corej/core"
                    + "/exec/KeyMatchMissingJoinKeyTest.java ×1",
            "lib/cumba-oss-corej-core/src/test/java/net/cumba/corej/core"
                    + "/exec/ScalarSemanticsComputedMissingTest.java ×2",
            "lib/cumba-oss-corej-core/src/test/java/net/cumba/corej/core"
                    + "/expr/eval/DottedNotSuppliedDefaultTest.java ×1",
            "lib/cumba-oss-corej-core/src/test/java/net/cumba/corej/core"
                    + "/expr/eval/SubstitutedScalarValueChannelTest.java ×1",
            "lib/cumba-oss-corej-core/src/test/java/net/cumba/corej/core"
                    + "/expr/typed/DottedRefTypeExpectationTest.java ×1",
            "lib/cumba-oss-corej-core/src/test/java/net/cumba/corej/core"
                    + "/expr/typed/StageBCheckerTest.java ×1");

    /**
     * Population floor over the scanned population ({@code .java} + {@code .md} outside
     * {@code target/}). Measured 2026-09-19: <b>1007</b> files — 941 {@code .java} and 66
     * {@code .md}. A walk that visits materially fewer has gone wrong and must FAIL rather than
     * report a comfortable zero. ⚠ A floor is the one place a non-exact assertion is right here:
     * its job is to catch a broken walk, not to pin a file count that moves with every commit.
     */
    private static final int MIN_SOURCE_FILES = 800;

    private static final Path REPO_ROOT = findRepoRoot();

    private static Path findRepoRoot()
    {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null)
        {
            if (Files.isDirectory(dir.resolve("lib")) && Files.isRegularFile(dir.resolve("pom.xml"))
                    && Files.isDirectory(dir.resolve("lib").resolve("cumba-oss-corej-core")))
            {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("cannot locate the repository root from "
                + Path.of("").toAbsolutePath() + " — this census would scan nothing");
    }


    private static int countIn(String text, String needle)
    {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length()))
        {
            n++;
        }
        return n;
    }


    @Test
    void theMarkerAppearsTheStatedNumberOfTimesAndOnlyInTheExpectedFiles() throws IOException
    {
        List<String> perFile = new ArrayList<>();
        int total = 0;
        int scanned = 0;
        try (Stream<Path> files = Files.walk(REPO_ROOT))
        {
            for (Path p : files.filter(Files::isRegularFile).filter(f ->
            {
                String s = f.toString();
                return (s.endsWith(".java") || s.endsWith(".md")) && !s.contains("/target/")
                        && !s.contains("/.git/") && !s.contains("/node_modules/");
            }).sorted().toList())
            {
                scanned++;
                String raw = Files.readString(p, StandardCharsets.UTF_8);
                String relative = REPO_ROOT.relativize(p).toString().replace(File.separatorChar,
                        '/');
                int n = countIn(raw, MARKER);
                if (n > 0)
                {
                    total += n;
                    perFile.add(relative + " ×" + n);
                }
                for (String retired : RETIRED_SPELLINGS)
                {
                    assertEquals(0, countIn(raw, retired), relative
                            + " uses a RETIRED marker spelling. §6 picked"
                            + " ONE literal tree-wide precisely because three lanes invented"
                            + " three and none grepped the others: " + retired);
                }
            }
        }

        // --- non-vacuity: the walk must have seen the tree -------------------------------------
        assertTrue(scanned >= MIN_SOURCE_FILES,
                "only " + scanned + " .java/.md files scanned under " + REPO_ROOT
                        + ", below the floor of " + MIN_SOURCE_FILES + " — this repo has far more,"
                        + " so the census is not seeing the tree and its count would be"
                        + " meaningless");

        assertEquals(EXPECTED_IN_THIS_REPO, total,
                "the target-invariant marker count MOVED. A DROP means statements of the null-free"
                        + " value invariant were removed or reworded away — read them back before"
                        + " touching this constant; a RISE is fine and the constant should follow"
                        + " it, together with EXPECTED_FILES. Occurrences found: " + perFile);
        assertEquals(EXPECTED_FILES, perFile.stream().sorted().toList(),
                "the marker must sit on EXACTLY these files, with EXACTLY these per-file counts."
                        + " A file gone means a statement of the invariant was deleted; an extra"
                        + " file means a marker wandered onto source the invariant does not"
                        + " describe; a changed per-file count means a statement moved between"
                        + " files with the total intact — all three of which a total-only census,"
                        + " the shape this test had until 2026-09-19, passes over in silence."
                        + " Occurrences found: " + perFile);
    }


    /**
     * ⭐ Sensitivity arm, mirroring the rules repository's census. The counter itself is proved to
     * count — over a synthetic text with a known number of occurrences, and to answer ZERO for a
     * near-miss spelling. Without this, a counter that had silently stopped matching (an encoding
     * change, a typo in the assembled literal, an {@code indexOf} loop that returns early) would
     * report a stable number for a tree that no longer carries the marker at all, and the census
     * above would be the most reassuring possible form of nothing.
     */
    @Test
    void theCounterActuallyCounts()
    {
        String text = "a " + MARKER + " b " + MARKER + " c\n" + MARKER + "\n";
        assertEquals(3, countIn(text, MARKER), "SENSITIVITY FAILED: the counter must find all three"
                + " occurrences, including the one at the start of a line");
        assertEquals(0, countIn("nothing here at all", MARKER),
                "SENSITIVITY FAILED: the counter must answer zero for a text without the marker");
        assertEquals(0, countIn(text, MARKER + "-XYZ"),
                "SENSITIVITY FAILED: the counter must not match a longer near-miss spelling");
        assertEquals(1, countIn(MARKER + MARKER.substring(0, 5), MARKER),
                "SENSITIVITY FAILED: a trailing partial occurrence must not be counted");
        for (String retired : RETIRED_SPELLINGS)
        {
            assertEquals(1, countIn("x " + retired + " y", retired),
                    "SENSITIVITY FAILED: the retired-spelling arm must actually match its own"
                            + " literal, or a typo in an assembled fragment would let the spelling"
                            + " it bans back in with nothing red — " + retired);
            assertEquals(0, countIn(MARKER, retired),
                    "SENSITIVITY FAILED: the agreed marker must NOT be read as a retired spelling,"
                            + " or every file carrying it would red — " + retired);
        }
    }
}
