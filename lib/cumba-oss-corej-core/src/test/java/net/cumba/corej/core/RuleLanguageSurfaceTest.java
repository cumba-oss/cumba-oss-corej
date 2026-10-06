package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The language-surface golden ({@code PLAN-rule-authoring-api} §3.2): {@code language-surface.txt}
 * must carry {@link RuleLanguage#LEVEL} in its header <b>and</b> the live
 * {@link RuleLanguage#surface()} in its body.
 *
 * <h2>Why an equality on the level alone is the wrong guard</h2>
 *
 * <p>
 * It catches bumping one side only; the likely failure is a developer adding a function or a bound
 * key and bumping <em>neither</em> side. So the level is tied to the measured surface: a surface
 * change makes the body comparison red, and the regenerator
 * ({@code -Dcorej.languageSurface.regenerate=true}) refuses to rewrite a changed body unless the
 * level in code is greater than the level in the file — "regenerate" cannot be the way to make a
 * surface change green without a bump.
 * </p>
 */
class RuleLanguageSurfaceTest
{

    static final String REGENERATE_PROPERTY = "corej.languageSurface.regenerate";

    private static Path golden()
    {
        return Path.of(System.getProperty("projectBasedir"), "src", "test", "resources",
                "language-surface.txt");
    }


    @Test
    void theGoldenCarriesTheDeclaredLevelAndTheLiveSurface() throws IOException
    {
        Path file = golden();
        String live = RuleLanguage.goldenText();
        if (Boolean.getBoolean(REGENERATE_PROPERTY))
        {
            regenerate(file, live);
        }
        assertTrue(Files.isRegularFile(file), file + " is missing — run with -D"
                + REGENERATE_PROPERTY + "=true to write it (and bump RuleLanguage.LEVEL)");
        String committed = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(RuleLanguage.LEVEL, RuleLanguage.headerLevel(committed),
                "RuleLanguage.LEVEL and the golden's header disagree — bump both sides together");
        assertEquals(committed, live,
                "the rule-language surface changed (a function, a parameter,"
                        + " a bound key or an authored enum value): bump RuleLanguage.LEVEL and"
                        + " rules-src/LANGUAGE-LEVEL, then regenerate with -D" + REGENERATE_PROPERTY
                        + "=true");
    }


    /**
     * The refusing regenerator: a changed body is written only when the level in code is above the
     * header in the file.
     */
    static void regenerate(Path aFile, String aLive) throws IOException
    {
        if (Files.isRegularFile(aFile))
        {
            String committed = Files.readString(aFile, StandardCharsets.UTF_8);
            boolean bodyChanged = !body(committed).equals(body(aLive));
            if (bodyChanged && RuleLanguage.headerLevel(committed) >= RuleLanguage.LEVEL)
            {
                throw new IllegalStateException("refusing to regenerate " + aFile
                        + ": the language surface changed but RuleLanguage.LEVEL ("
                        + RuleLanguage.LEVEL + ") is not above the golden's level ("
                        + RuleLanguage.headerLevel(committed) + ") — bump the level first");
            }
        }
        Files.createDirectories(aFile.getParent());
        Files.writeString(aFile, aLive, StandardCharsets.UTF_8);
    }


    static String body(String aGolden)
    {
        int eol = aGolden.indexOf('\n');
        return eol < 0 ? "" : aGolden.substring(eol + 1);
    }


    @Test
    void theSurfaceListsFunctionsKeysAndEnums()
    {
        String surface = RuleLanguage.surface();
        assertTrue(surface.startsWith("functions:\n"));
        assertTrue(surface.contains("\nkeys:\n"));
        assertTrue(surface.contains("\nenums:\n"));
        assertTrue(surface.contains("\nRULE: "), "the BoundRuleKeys.RULE roster is listed");
        assertTrue(surface.contains("\nvar_exists("), "a registered function is listed");
        assertTrue(surface.contains("\nSensitivity: "), "an authored enum is listed");
        assertEquals(surface, RuleLanguage.surface(), "deterministic");
    }


    @Test
    void theRegeneratorRefusesAChangedBodyWithoutABump(@org.junit.jupiter.api.io.TempDir Path aDir)
        throws IOException
    {
        Path file = aDir.resolve("language-surface.txt");
        // A golden at the CURRENT level whose body differs from the live one: a surface change
        // with no bump. The regenerator must refuse.
        Files.writeString(file, RuleLanguage.LEVEL_HEADER + RuleLanguage.LEVEL + "\nfunctions:\n"
                + "planted(x:string) kind=value\n", StandardCharsets.UTF_8);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> regenerate(file, RuleLanguage.goldenText()));
        assertTrue(e.getMessage().contains("bump the level first"), e.getMessage());
        // A golden one level BELOW: the bump happened, so the rewrite goes through.
        Files.writeString(file, RuleLanguage.LEVEL_HEADER + (RuleLanguage.LEVEL - 1)
                + "\nfunctions:\nplanted(x:string) kind=value\n", StandardCharsets.UTF_8);
        regenerate(file, RuleLanguage.goldenText());
        assertEquals(RuleLanguage.goldenText(), Files.readString(file, StandardCharsets.UTF_8));
        // An unchanged body never needs a bump.
        regenerate(file, RuleLanguage.goldenText());
        assertFalse(Files.readString(file, StandardCharsets.UTF_8).isEmpty());
    }


    @Test
    void theHeaderParserRejectsAFileWithoutALevel()
    {
        assertThrows(IllegalArgumentException.class,
                () -> RuleLanguage.headerLevel("functions:\n"));
        assertEquals(3, RuleLanguage.headerLevel("LEVEL=3\nfunctions:\n"));
    }
}
