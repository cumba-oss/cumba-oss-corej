package net.cumba.cdisc.define.conformance.report;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Finding severity (plan §1). The PMDA sheet's {@code PMDA Severity} column is used verbatim
 * (Reject / Error / Warning); all CDISC-sheet rules are {@link #ERROR}.
 */
public enum Severity
{

    REJECT,

    ERROR,

    WARNING;

    /** Case-insensitive factory for the YAML {@code Severity:} field ("Reject", "error", …). */
    @JsonCreator
    public static @Nullable Severity fromJson(@Nullable String aValue)
    {
        return aValue == null ? null : valueOf(aValue.toUpperCase(Locale.ROOT));
    }

}
