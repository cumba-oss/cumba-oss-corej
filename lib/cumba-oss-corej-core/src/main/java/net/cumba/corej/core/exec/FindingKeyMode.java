package net.cumba.corej.core.exec;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * How much record-key enrichment a run attaches to its findings (EC-40).
 *
 * <p>
 * A finding always carries {@code USUBJID} and {@code <DOMAIN>SEQ} / {@code ASEQ} — that pair is
 * unconditional and unaffected by this setting. This mode governs only the <em>additional</em>
 * record key resolved by {@link RecordKeyResolver}, which exists so findings can be aligned across
 * data versions (where {@code --SEQ} is regenerated per extract and therefore unstable).
 * </p>
 *
 * <p>
 * Resolved (highest precedence first) from the system property {@code corej.findingKeys}, then the
 * environment variable {@code COREJ_FINDING_KEYS}, then {@link #OFF}. An unrecognised value falls
 * back to {@link #OFF} rather than failing the run — this is report enrichment, never a correctness
 * gate. Read once per rule execution, so the property stays a live knob in a long-running service
 * (same contract as {@link EngineLimits}).
 * </p>
 */
public enum FindingKeyMode
{

    /**
     * No record-key resolution. Report output is byte-identical to a build without EC-40. The
     * default, so nothing moves for an existing consumer until it opts in.
     */
    OFF,

    /**
     * Sponsor-declared and structural keys only — the {@code DEFINE_KEY} and {@code STRUCTURAL}
     * tiers. Needs no CDISC Library access and has bounded width, which makes it the safe everyday
     * setting.
     */
    DEFINE,

    /**
     * Every tier, including {@code NATURAL} (the full natural-key role set). Maximally identifying
     * and correspondingly the widest — a Findings-class dataset can contribute 10-15 key columns.
     */
    FULL;

    static final String PROP = "corej.findingKeys";

    static final String ENV = "COREJ_FINDING_KEYS";

    /**
     * The configured mode for this run.
     *
     * @return the configured mode; {@link #OFF} when unset or unrecognised.
     */
    public static FindingKeyMode configured()
    {
        FindingKeyMode m = parse(System.getProperty(PROP));
        if (m == null)
        {
            m = parse(System.getenv(ENV));
        }
        return m != null ? m : OFF;
    }


    /**
     * Parses a configured value, case-insensitively.
     *
     * @param aValue
     *            the raw property / environment value; may be {@code null} or blank.
     * @return the parsed mode, or {@code null} when absent, blank or unrecognised.
     */
    static @Nullable FindingKeyMode parse(@Nullable String aValue)
    {
        if (aValue == null || aValue.isBlank())
        {
            return null;
        }
        return switch (aValue.strip().toLowerCase(Locale.ROOT))
        {
        case "off", "false", "none" -> OFF;
        case "define" -> DEFINE;
        case "full", "true", "all" -> FULL;
        default -> null;
        };
    }


    /**
     * Whether this mode resolves any record key at all.
     *
     * @return {@code true} for every mode except {@link #OFF}.
     */
    public boolean isEnabled()
    {
        return this != OFF;
    }


    /**
     * Whether this mode admits the Library-backed {@code NATURAL} tier.
     *
     * @return {@code true} only for {@link #FULL}.
     */
    public boolean allowsNatural()
    {
        return this == FULL;
    }

}
