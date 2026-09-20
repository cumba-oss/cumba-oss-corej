package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * ⭐ <b>DISCOVERS</b> the production classes of the module that ships a given anchor class, so a
 * roster over them is a measured population rather than a hand-written list.
 *
 * <p>
 * ⚠⚠ <b>Why this exists.</b> A hand-written roster of signatures reports GREEN over a population
 * that has grown: the twelfth producer is simply not named, nothing reds, and the guard has
 * silently stopped guarding. That is the dominant defect class in this stack (see the datatable
 * repository's {@code b28e8ab}, which replaced exactly such a list on the buffer side with
 * reflective discovery plus an EXACT-EQUALITY count). Discovery plus exact equality is the shape: a
 * new member reds because the count rose, a deleted one reds because it fell, and the author is
 * forced to look at what moved.
 * </p>
 *
 * <p>
 * ⛔ <b>The blind spot, stated because a scan whose population is a convention has not checked the
 * population.</b> The walk is over the <em>code source directory</em> of the anchor class — i.e.
 * this module's {@code target/classes} — so it sees every package and every nested and anonymous
 * class of THIS module, and is immune to a package rename or a move between packages. It does NOT
 * see: (1) another module of this repo (the define-conformance, report-json, report-xlsx and
 * ruletest modules); (2) another repo, the OSS twin {@code cumba-oss-corej} included; (3) a class
 * generated at run time. A roster built on this must therefore assert the EXPECTED SET, not merely
 * a count — a member that moves out of the module then reds here instead of vanishing quietly.
 * </p>
 *
 * <p>
 * ⚠ A jar code source is a hard FAILURE, never a skip: it would enumerate nothing and every check
 * built on it would pass vacuously — the {@code check_versions.sh} CHECK 4 trap.
 * </p>
 */
final class ProductionClasses
{

    private ProductionClasses()
    {
    }


    /**
     * Every class compiled into the code source that ships {@code anchor}, nested and anonymous
     * ones included.
     *
     * @param anchor
     *            any production class of the module to enumerate
     * @return the discovered classes, never empty
     */
    static List<Class<?>> ofModule(Class<?> anchor)
    {
        Path root = codeSourceOf(anchor);
        List<Class<?>> out = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root))
        {
            for (Path p : files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".class")).sorted().toList())
            {
                String rel = root.relativize(p).toString();
                String binary = rel.substring(0, rel.length() - ".class".length()).replace('/', '.')
                        .replace('\\', '.');
                if (binary.endsWith("package-info") || binary.endsWith("module-info"))
                {
                    continue;
                }
                try
                {
                    out.add(Class.forName(binary, false, anchor.getClassLoader()));
                }
                catch (Throwable t)
                {
                    unreadable.add(binary + " (" + t.getClass().getSimpleName() + ")");
                }
            }
        }
        catch (IOException e)
        {
            throw new AssertionError("cannot walk " + root, e);
        }
        // ⛔ A class this scan cannot load is reported, never skipped: silently dropping one is how
        // a discovered population quietly becomes a partial one.
        assertTrue(unreadable.isEmpty(),
                "these classes could not be loaded, so the discovered population is INCOMPLETE and"
                        + " every roster built on it is partial: " + unreadable);
        assertTrue(out.size() > 200, "only " + out.size() + " classes found under " + root
                + " — this module has far more, so the scan is not seeing the module and every"
                + " roster built on it would pass vacuously");
        return out;
    }


    private static Path codeSourceOf(Class<?> anchor)
    {
        var cs = anchor.getProtectionDomain().getCodeSource();
        if (cs == null || cs.getLocation() == null)
        {
            return fail("no code source for " + anchor.getName()
                    + " — cannot enumerate this module's classes, so refusing to report an empty"
                    + " population as a pass");
        }
        Path root;
        try
        {
            root = Path.of(cs.getLocation().toURI());
        }
        catch (URISyntaxException e)
        {
            throw new AssertionError("un-parseable code source " + cs.getLocation(), e);
        }
        assertTrue(Files.isDirectory(root),
                cs.getLocation() + " is not a directory — this scan reads a classes DIRECTORY; run"
                        + " from a jar it would enumerate nothing and pass vacuously. Refusing.");
        return root;
    }
}
