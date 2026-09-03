package net.cumba.corej.define.conformance.rule;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Optional external input a rule needs; absent input ⇒ the rule SKIPs (plan §3.6). */
public enum Requires
{

    /** CDISC Controlled Terminology via a {@code CtProvider}. */
    CT,

    /** The submission folder on disk (file-existence checks). */
    FOLDER,

    /** The CDISC implementation-guide library via a {@code LibraryProvider}. */
    LIBRARY;

    /** Case-insensitive factory for the YAML {@code Requires:} field. */
    @JsonCreator
    public static @Nullable Requires fromJson(@Nullable String aValue)
    {
        return aValue == null ? null : valueOf(aValue.toUpperCase(Locale.ROOT));
    }

}
