package net.cumba.cdisc.core.metadata.dictionary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What an install run did: what it wrote, what it skipped, and every warning it raised. */
public final class InstallReport
{

    private final Map<String, String> installed = new LinkedHashMap<>();

    private final List<String> skipped = new ArrayList<>();

    private final List<String> warnings = new ArrayList<>();

    /** Records a dictionary written at a version. */
    public void installed(String aType, String aVersion)
    {
        installed.put(aType, aVersion);
    }


    public void skipped(String aMessage)
    {
        skipped.add(aMessage);
    }


    public void warning(String aMessage)
    {
        warnings.add(aMessage);
    }


    /** Type &rarr; version for everything written, in install order. */
    public Map<String, String> getInstalled()
    {
        return Map.copyOf(installed);
    }


    public List<String> getSkipped()
    {
        return List.copyOf(skipped);
    }


    public List<String> getWarnings()
    {
        return List.copyOf(warnings);
    }


    /** Whether anything was written. */
    public boolean isEmpty()
    {
        return installed.isEmpty();
    }


    /** The single line a CLI prints on completion. */
    public String summary()
    {
        return installed.size() + " installed, " + skipped.size() + " skipped, " + warnings.size()
                + " warning(s)";
    }

}
