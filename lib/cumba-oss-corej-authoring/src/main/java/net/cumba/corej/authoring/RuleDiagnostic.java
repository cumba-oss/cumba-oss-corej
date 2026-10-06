package net.cumba.corej.authoring;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One structured diagnostic about an authored rule: a stable code, a severity, the message, and —
 * where the assembler or the loader knows it — the YAML path the message is about and the text
 * range of that path's key in the authored document.
 *
 * <p>
 * The engine's own channel is a single {@code "; "}-joined {@code Rule.loadError} string; this is
 * the editor-facing form of the same facts ({@code PLAN-rule-authoring-api} §2.3 item 1). A path is
 * given only when the message names a bound rule key; a range only when the authored text was
 * available to index. Neither is ever guessed.
 * </p>
 *
 * @param code
 *            a stable identifier of the diagnostic kind, e.g. {@code LOAD_ERROR},
 *            {@code YAML_SYNTAX}, {@code PARKED}
 * @param severity
 *            how the diagnostic affects the rule
 * @param message
 *            the human-readable text, exactly as the engine or the assembler produced it
 * @param path
 *            the authored YAML path the message is about, or {@code null} when unknown
 * @param range
 *            the location of {@code path}'s key in the authored text, or {@code null} when unknown
 */
public record RuleDiagnostic(String code, Severity severity, String message,
        @Nullable YamlPath path, @Nullable TextRange range)
{

    public RuleDiagnostic
    {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
    }

    /** The weight of a diagnostic. */
    public enum Severity
    {
        /** The rule cannot be loaded or executed as authored. */
        ERROR,
        /** The rule loads; something about it deserves attention. */
        WARNING,
        /** A fact about the rule, not a problem. */
        INFO
    }


    /**
     * A path into the authored YAML document, one segment per mapping key or sequence index
     * ({@code /Scope/Domains/Include/0}). The empty path is the document root.
     *
     * @param segments
     *            the keys and indices from the root down
     */
    public record YamlPath(List<String> segments)
    {

        public YamlPath
        {
            segments = List.copyOf(segments);
        }


        /** A path from its slash-separated form ({@code "Core/Id"} or {@code "/Core/Id"}). */
        public static YamlPath of(String aSlashPath)
        {
            String s = aSlashPath.startsWith("/") ? aSlashPath.substring(1) : aSlashPath;
            return new YamlPath(s.isEmpty() ? List.of() : List.of(s.split("/", -1)));
        }


        /** A path from a dotted key chain ({@code "Outcome.Output_Variables"}). */
        public static YamlPath ofDotted(String aDotted)
        {
            return new YamlPath(aDotted.isEmpty() ? List.of() : List.of(aDotted.split("\\.", -1)));
        }


        /** The child path for a key or index. */
        public YamlPath child(String aSegment)
        {
            List<String> out = new java.util.ArrayList<>(segments);
            out.add(aSegment);
            return new YamlPath(out);
        }


        @Override
        public String toString()
        {
            return "/" + String.join("/", segments);
        }
    }


    /**
     * A 1-based, inclusive text range in the authored document. {@code endLine} / {@code endColumn}
     * name the last character of the range.
     */
    public record TextRange(int startLine, int startColumn, int endLine, int endColumn)
    {

        /** A range covering {@code length} characters from a start position on one line. */
        public static TextRange at(int aLine, int aColumn, int aLength)
        {
            return new TextRange(aLine, aColumn, aLine, aColumn + Math.max(aLength, 1) - 1);
        }
    }

    /** True for an {@link Severity#ERROR}. */
    public boolean isError()
    {
        return severity == Severity.ERROR;
    }
}
