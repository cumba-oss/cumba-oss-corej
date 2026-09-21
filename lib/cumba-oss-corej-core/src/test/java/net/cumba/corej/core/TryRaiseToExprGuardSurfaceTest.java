package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * R2-1 — the {@code tryRaiseToExpr(...)} guard surface, enumerated instead of counted by hand.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>
 * {@code RulePackageLoader.tryRaiseToExpr} returns {@code null} for a Check with no faithful
 * expression surface, and each of its callers guards on that. Since D121 the guards are
 * <b>unreachable</b> ({@code CheckToExpr.toExpr} has no throw site left) and are kept deliberately,
 * because deleting them means deleting the narrow {@code ExpressionException} catch — a fail-loud
 * behaviour change owed its own decision (D132a).
 * </p>
 *
 * <p>
 * ⚠⚠ <b>The hazard is the SIZE of that surface, not the surface.</b> Commit {@code 40947e5}
 * recorded it as "exactly 5"; a later filing re-derived the number and certified it as holding —
 * and both were wrong, because both derivations reused the same search spelling. There are seven
 * call sites carrying six {@code == null} guards plus one null-tolerant {@code != null} use. A
 * sweep told "delete the five named sites" would leave the catch alive at an <b>uncounted</b> one
 * while reporting the surface empty.
 * </p>
 *
 * <p>
 * So the count is derived from the source rather than written down: this test parses
 * {@code RulePackageLoader.java}, classifies every call site by the guard that follows it, and
 * fails when one is added, removed, or left <b>unguarded</b>. The last of those is the assertion
 * that actually matters — the totals are the ratchet that makes a change deliberate.
 * </p>
 *
 * <h2>What counts as a guard</h2>
 *
 * <p>
 * ⚠⚠ A <b>token</b> is not a guard, and this test learned that the hard way: its first version
 * classified a site by {@code body.contains(var + " == null")} over the following six lines, and
 * R2-1's own review showed two mutations of a real guard that it reported <b>green</b> — deleting
 * the guard but leaving its explanatory comment, and keeping the {@code if} but replacing its
 * {@code return} with a log. Both leave the site genuinely unguarded. An instrument that cannot
 * fail is the defect class this whole round exists to remove, so it may not be one itself.
 * </p>
 *
 * <p>
 * A site is therefore guarded only when the {@code var == null} test is on a line that is itself a
 * conditional ({@code code(...)} drops comments first) <b>and</b> the block it opens leaves the
 * flow ({@link #blockExits}). Those two mutations are the acceptance test for this classifier: both
 * must red.
 * </p>
 */
class TryRaiseToExprGuardSurfaceTest
{

    private static final String METHOD = "tryRaiseToExpr";

    /**
     * {@code <lhs> = tryRaiseToExpr(} — every call site is an assignment, which is what lets the
     * guard be located by the assigned name rather than by a fixed line offset.
     */
    private static final Pattern CALL = Pattern.compile("(\\w+)\\s*=\\s*" + METHOD + "\\s*\\(");

    /** How many lines after the call a guard may appear (the call itself may wrap one line). */
    private static final int GUARD_WINDOW = 6;

    /** How far a guard's block may run before its closing brace must have been seen. */
    private static final int BLOCK_WINDOW = 60;

    /** Leaves the enclosing flow, so the {@code null} the guard caught cannot flow on. */
    private static final Pattern EXIT = Pattern.compile("\\b(return|continue|break|throw)\\b");

    /** Guard shapes. Only the first two count as guarded; see {@link #everyCallSiteIsGuarded()}. */
    private static final String NULL_GUARD = "== null";

    private static final String TOLERANT = "!= null";

    private static final String NONE = "NONE";

    private static final String FALLS_THROUGH = "== null, FALLS THROUGH";

    /**
     * The line with any trailing {@code //} comment removed, stripped.
     *
     * <p>
     * ⚠ This is what stops <b>prose</b> from being read as a guard. R2-1's own review mutated
     * {@code :1770} by deleting {@code if (pre == null) { return; }} and leaving behind the
     * explanatory comment {@code // pre == null cannot happen since D121 …} — the dominant house
     * style in this file, where every guard carries three to ten lines of it — and the earlier
     * {@code body.contains(var + " == null")} classifier reported the site <b>guarded</b>. A
     * ratchet that cannot fail is worse than no ratchet.
     * </p>
     *
     * <p>
     * ⚑ Residual: a {@code //} inside a string literal truncates the line early. No call site or
     * guard body here contains one, and the effect would be to see <i>less</i> code, never to
     * invent a guard.
     * </p>
     */
    private static String code(String line)
    {
        int comment = line.indexOf("//");
        return (comment < 0 ? line : line.substring(0, comment)).strip();
    }


    /**
     * Does the {@code if} block opening at {@code ifLine} (0-based) leave the flow?
     *
     * <p>
     * ⚠ This is what stops a <b>fall-through</b> from being read as a guard: R2-1's review also
     * mutated {@code :1770} by keeping {@code if (pre == null)} and replacing its {@code return;}
     * with a {@code LOGGER.log(...)}, and the earlier classifier reported that guarded too —
     * although {@code null} now flows straight on into the code the guard exists to protect.
     * <b>Testing for null is not guarding against it.</b>
     * </p>
     *
     * <p>
     * The extent of the block is read from the indentation of its closing brace, which Allman
     * braces plus a Spotless-enforced format make reliable here; a block that does not close within
     * {@link #BLOCK_WINDOW} lines throws rather than being guessed at.
     * </p>
     *
     * <p>
     * ⚑ Residual: an exit nested inside a further conditional within the block would be read as
     * unconditional. No guard body here is shaped that way, and a text parser cannot decide it; the
     * assertion messages point a reader at the source rather than pretending otherwise.
     * </p>
     */
    private static boolean blockExits(List<String> lines, int ifLine)
    {
        String raw = lines.get(ifLine);
        if (EXIT.matcher(code(raw)).find())
        {
            return true; // single-line form: `if (x == null) return;`
        }
        int indent = raw.length() - raw.stripLeading().length();
        for (int j = ifLine + 1; j < Math.min(lines.size(), ifLine + BLOCK_WINDOW); j++)
        {
            String body = lines.get(j);
            String stripped = body.strip();
            if (stripped.isEmpty())
            {
                continue;
            }
            int bodyIndent = body.length() - body.stripLeading().length();
            if (bodyIndent == indent && stripped.startsWith("{"))
            {
                continue; // the block's own opening brace, on its own line
            }
            if (bodyIndent == indent && stripped.startsWith("}"))
            {
                return false; // closed without leaving the flow
            }
            if (EXIT.matcher(code(body)).find())
            {
                return true;
            }
        }
        throw new IllegalStateException("the guard at line " + (ifLine + 1) + " does not close"
                + " within " + BLOCK_WINDOW + " lines — teach this parser the shape rather than"
                + " widening the window until it passes");
    }


    private static Path loaderSource()
    {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null)
        {
            Path candidate = dir
                    .resolve("src/main/java/net/cumba/corej/core/RulePackageLoader.java");
            if (Files.isRegularFile(candidate))
            {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "RulePackageLoader.java not found from " + Path.of("").toAbsolutePath());
    }

    /** A call site and the guard shape that follows it. */
    private record Site(int line, String variable, String guard)
    {
    }

    private static List<Site> sites() throws IOException
    {
        List<String> lines = Files.readAllLines(loaderSource(), StandardCharsets.UTF_8);
        List<Site> out = new ArrayList<>();
        int declarations = 0;
        int unattributed = 0;
        for (int i = 0; i < lines.size(); i++)
        {
            String line = lines.get(i);
            String stripped = line.strip();
            if (stripped.startsWith("*") || stripped.startsWith("//")
                    || !line.contains(METHOD + "("))
            {
                continue; // javadoc / prose / commented-out mentions are not call sites
            }
            if (line.contains("private static") && line.contains("(CheckCondition"))
            {
                declarations++;
                continue;
            }
            Matcher m = CALL.matcher(line);
            if (!m.find())
            {
                unattributed++;
                continue;
            }
            String var = m.group(1);
            String guard = NONE;
            for (int j = i; j < Math.min(lines.size(), i + GUARD_WINDOW); j++)
            {
                String body = code(lines.get(j));
                if (!body.startsWith("if (") && !body.startsWith("} else if ("))
                {
                    continue; // only a conditional can be a guard — a comment saying so is not
                }
                if (body.contains(var + " == null"))
                {
                    guard = blockExits(lines, j) ? NULL_GUARD : FALLS_THROUGH;
                    break;
                }
                if (body.contains(var + " != null"))
                {
                    guard = TOLERANT;
                    break;
                }
            }
            out.add(new Site(i + 1, var, guard));
        }
        assertEquals(1, declarations, "tryRaiseToExpr must be declared exactly once");
        assertEquals(0, unattributed,
                "a tryRaiseToExpr call site that is not a simple assignment cannot be classified by"
                        + " this test — give it an assigned variable, or teach the parser its"
                        + " shape; leaving it is how the surface goes uncounted again");
        return out;
    }


    @Test
    void everyCallSiteIsGuarded() throws IOException
    {
        List<Site> unguarded = sites().stream()
                .filter(s -> NONE.equals(s.guard()) || FALLS_THROUGH.equals(s.guard())).toList();
        assertTrue(unguarded.isEmpty(),
                "tryRaiseToExpr returns null for a Check with no expression surface; these call"
                        + " sites either never test the result (NONE) or test it and let the null"
                        + " flow on anyway (FALLS THROUGH): " + unguarded);
    }


    @Test
    void theGuardSurfaceIsEightSitesAndSevenNullGuards() throws IOException
    {
        List<Site> all = sites();
        List<Site> nullGuards = all.stream().filter(s -> "== null".equals(s.guard())).toList();
        List<Site> tolerant = all.stream().filter(s -> "!= null".equals(s.guard())).toList();
        assertEquals(8, all.size(), "call sites changed: " + all);
        assertEquals(7, nullGuards.size(),
                "`== null` guards changed — 40947e5 recorded 5, a re-derivation confirmed 5, the"
                        + " leaf-scope work took it to 6, and PLAN-expansion-over-all-variables'"
                        + " G3 added preconditionReadsCursor as the 7th. Update it here, from this"
                        + " list: " + nullGuards);
        assertEquals(1, tolerant.size(), "null-tolerant `!= null` uses changed: " + tolerant);
    }
}
