package net.cumba.corej.core.metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A model variable's {@code usageRestrictions} text, parsed into what the model walk can act on
 * (PLAN-store-library-content-errata D2 / §2.5). The SDTM 2.x models carry the domain-specific
 * variables as class-table roots with a restriction naming the domain ({@code --EVDTYP} "MH domain
 * only"), and since the errata register the 1.5–1.8 models do too; a walk that ignored the text
 * answered that {@code AEEVDTYP} is model-allowed in an AE dataset. Every text form the store
 * carries is handled <b>explicitly</b> (measured over every {@code models/sdtm/*} product
 * 2026-10-03); an unknown form is {@link Form#UNPARSEABLE}, which restricts nothing and is logged
 * once per distinct text — and the real-corpus seeding test fails on it, so a new form cannot be
 * ignored silently.
 *
 * <p>
 * The forms, each a clause of the semicolon-separated text:
 * </p>
 * <ul>
 * <li>{@code <D> domain only}, {@code <D> Domain Only}, {@code CP, IS, and LB domains only},
 * {@code Only in Findings class specimen-based domains: BS, CP, …} → the variable is walked only
 * for those domains ({@link Form#DOMAINS_ONLY});</li>
 * <li>{@code Not in AE domain}, {@code Not in QS, FT, and clinical classifications use case of RS}
 * → excluded for the named domains ({@link Form#NOT_IN_DOMAINS}; the use-case condition on RS
 * cannot be decided by domain, so RS stays allowed);</li>
 * <li>{@code Not in Findings class domains} → excluded when the walked class is that class
 * ({@link Form#NOT_IN_CLASS});</li>
 * <li>{@code Not in human clinical trials}, {@code Not in nonclinical trials}
 * ({@link Form#TRIAL_TYPE}) and {@code Tobacco IG only} ({@link Form#IG_ONLY}) are recognised and
 * deliberately NOT applied by the domain walk: they restrict by study type, not by domain, and
 * applying them is a separate decision;</li>
 * <li>{@code None} and an absent text restrict nothing ({@link Form#NONE}).</li>
 * </ul>
 */
public final class UsageRestrictions
{

    /** The recognised clause forms. */
    public enum Form
    {
        /** No restriction. */
        NONE,
        /** Walked only for the listed domains. */
        DOMAINS_ONLY,
        /** Excluded for the listed domains. */
        NOT_IN_DOMAINS,
        /** Excluded for the domains of the named observation class. */
        NOT_IN_CLASS,
        /** A study-type restriction, recognised and not applied by the domain walk. */
        TRIAL_TYPE,
        /** An IG restriction, recognised and not applied by the domain walk. */
        IG_ONLY,
        /** A clause no pattern matches: restricts nothing, reported. */
        UNPARSEABLE
    }

    private static final System.Logger LOGGER = System.getLogger(UsageRestrictions.class.getName());

    /** One warning per distinct unparseable text per JVM. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private static final String DOMAIN = "[A-Za-z]{2}";

    private static final String DOMAIN_LIST = DOMAIN + "(?:\\s*,\\s*" + DOMAIN + ")*"
            + "(?:\\s*,?\\s*and\\s+" + DOMAIN + ")?";

    private static final Pattern DOMAINS_ONLY = Pattern
            .compile("(?i)^(" + DOMAIN_LIST + ")\\s+domains?\\s+only$");

    private static final Pattern ONLY_IN_LISTED = Pattern
            .compile("(?i)^only in (?:findings|events|interventions) class [a-z -]*domains:\\s*("
                    + DOMAIN_LIST + ")$");

    private static final Pattern NOT_IN_DOMAINS = Pattern
            .compile("(?i)^not in (" + DOMAIN_LIST + ")\\s+domains?$");

    private static final Pattern NOT_IN_DOMAINS_USE_CASE = Pattern.compile(
            "(?i)^not in (" + DOMAIN_LIST + "),?\\s+and\\s+.+\\s+use case of (" + DOMAIN + ")$");

    private static final Pattern NOT_IN_CLASS = Pattern
            .compile("(?i)^not in (findings|events|interventions) class domains$");

    private static final Pattern TRIAL_TYPE = Pattern
            .compile("(?i)^not in (?:human clinical|nonclinical) trials$");

    private static final Pattern IG_ONLY = Pattern.compile("(?i)^[a-z]+ ig only$");

    private static final Pattern SEMICOLON = Pattern.compile(";");

    private static final Pattern SPLIT_LIST = Pattern.compile("(?i)\\s*(?:,|\\band\\b)\\s*");

    /**
     * Parsed texts by text (review F5): the model walk parses the restriction of every model
     * variable on every walk, and the store's whole vocabulary is about twenty texts. Bounded by
     * {@link #CACHE_LIMIT} so an arbitrary caller cannot grow it without limit; past that, a text
     * is parsed and not cached.
     */
    private static final Map<String, UsageRestrictions> CACHE = new ConcurrentHashMap<>();

    /** The most texts {@link #CACHE} holds — far above the measured vocabulary. */
    static final int CACHE_LIMIT = 512;

    private static final UsageRestrictions UNRESTRICTED = new UsageRestrictions(null,
            List.of(Form.NONE), Set.of(), Set.of(), Set.of());

    private final @Nullable String text;

    private final List<Form> forms;

    private final Set<String> onlyDomains;

    private final Set<String> notDomains;

    private final Set<String> notClasses;

    private UsageRestrictions(@Nullable String aText, List<Form> aForms, Set<String> aOnlyDomains,
            Set<String> aNotDomains, Set<String> aNotClasses)
    {
        text = aText;
        forms = List.copyOf(aForms);
        onlyDomains = Collections.unmodifiableSet(new LinkedHashSet<>(aOnlyDomains));
        notDomains = Collections.unmodifiableSet(new LinkedHashSet<>(aNotDomains));
        notClasses = Collections.unmodifiableSet(new LinkedHashSet<>(aNotClasses));
    }


    /**
     * Parses one {@code usageRestrictions} text. The result is immutable and cached per text, so a
     * repeated text returns the same instance.
     *
     * @param aText
     *            the text as the store holds it, or {@code null}
     * @return the parsed restriction; never {@code null}
     */
    public static UsageRestrictions parse(@Nullable String aText)
    {
        if (aText == null || aText.isBlank() || "none".equalsIgnoreCase(aText.strip()))
        {
            return UNRESTRICTED;
        }
        UsageRestrictions cached = CACHE.get(aText);
        if (cached != null)
        {
            return cached;
        }
        UsageRestrictions parsed = parseUncached(aText);
        if (CACHE.size() < CACHE_LIMIT)
        {
            CACHE.putIfAbsent(aText, parsed);
        }
        return parsed;
    }


    private static UsageRestrictions parseUncached(String aText)
    {
        List<Form> forms = new ArrayList<>();
        Set<String> only = new LinkedHashSet<>();
        Set<String> not = new LinkedHashSet<>();
        Set<String> notClasses = new LinkedHashSet<>();
        for (String rawClause : SEMICOLON.split(aText, -1))
        {
            String clause = rawClause.strip();
            if (!clause.isEmpty())
            {
                parseClause(aText, clause, forms, only, not, notClasses);
            }
        }
        if (forms.isEmpty())
        {
            forms.add(Form.NONE);
        }
        return new UsageRestrictions(aText, forms, only, not, notClasses);
    }


    private static void parseClause(String aText, String aClause, List<Form> aForms,
            Set<String> aOnly, Set<String> aNot, Set<String> aNotClasses)
    {
        Matcher domainsOnly = DOMAINS_ONLY.matcher(aClause);
        if (domainsOnly.matches())
        {
            aForms.add(Form.DOMAINS_ONLY);
            aOnly.addAll(domains(domainsOnly.group(1)));
            return;
        }
        Matcher onlyInListed = ONLY_IN_LISTED.matcher(aClause);
        if (onlyInListed.matches())
        {
            aForms.add(Form.DOMAINS_ONLY);
            aOnly.addAll(domains(onlyInListed.group(1)));
            return;
        }
        Matcher notInDomains = NOT_IN_DOMAINS.matcher(aClause);
        if (notInDomains.matches())
        {
            aForms.add(Form.NOT_IN_DOMAINS);
            aNot.addAll(domains(notInDomains.group(1)));
            return;
        }
        Matcher notInUseCase = NOT_IN_DOMAINS_USE_CASE.matcher(aClause);
        if (notInUseCase.matches())
        {
            // The trailing "<use case> use case of <D>" is a condition on a use case, not on the
            // domain: <D> stays allowed.
            aForms.add(Form.NOT_IN_DOMAINS);
            aNot.addAll(domains(notInUseCase.group(1)));
            return;
        }
        Matcher notInClass = NOT_IN_CLASS.matcher(aClause);
        if (notInClass.matches())
        {
            aForms.add(Form.NOT_IN_CLASS);
            aNotClasses.add(notInClass.group(1).toUpperCase(Locale.ROOT));
            return;
        }
        if (TRIAL_TYPE.matcher(aClause).matches())
        {
            aForms.add(Form.TRIAL_TYPE);
            return;
        }
        if (IG_ONLY.matcher(aClause).matches())
        {
            aForms.add(Form.IG_ONLY);
            return;
        }
        aForms.add(Form.UNPARSEABLE);
        if (REPORTED.add(aText))
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "usageRestrictions text not understood, restricting nothing: ''{0}''"
                            + " (clause ''{1}'') - add its form to UsageRestrictions",
                    aText, aClause);
        }
    }


    private static List<String> domains(String aList)
    {
        List<String> out = new ArrayList<>();
        for (String d : SPLIT_LIST.split(aList, -1))
        {
            if (!d.isBlank())
            {
                out.add(d.strip().toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }


    /**
     * Whether the variable belongs to the model walk of the given domain.
     *
     * @param aDomain
     *            the effective CDISC domain code being walked ({@code MH}; for {@code APMH} the
     *            stripped parent {@code MH})
     * @param aClassNameNorm
     *            the normalised observation class being walked ({@code FINDINGS}), or {@code null}
     *            when unknown — a class restriction then does not apply
     * @return {@code false} when a domain or class clause excludes it; {@code true} otherwise,
     *         including for every unparseable, study-type or IG clause
     */
    public boolean allowsDomain(String aDomain, @Nullable String aClassNameNorm)
    {
        String domain = aDomain.toUpperCase(Locale.ROOT);
        if (!onlyDomains.isEmpty() && !onlyDomains.contains(domain))
        {
            return false;
        }
        if (notDomains.contains(domain))
        {
            return false;
        }
        if (aClassNameNorm == null)
        {
            return true;
        }
        String walked = aClassNameNorm.toUpperCase(Locale.ROOT);
        for (String excluded : notClasses)
        {
            // FINDINGS ABOUT is a Findings-class domain (the FA dataset carries FADTC, never
            // FASTDTC), so a class exclusion covers the derived class too.
            if (walked.equals(excluded) || walked.startsWith(excluded + " "))
            {
                return false;
            }
        }
        return true;
    }


    /**
     * Whether any clause of the text matched no known form.
     *
     * @return {@code true} when the text is not fully understood
     */
    public boolean isUnparseable()
    {
        return forms.contains(Form.UNPARSEABLE);
    }


    /**
     * The clause forms in text order.
     *
     * @return the forms; a single {@link Form#NONE} for no restriction
     */
    public List<Form> forms()
    {
        return forms;
    }


    /**
     * The domains the variable is restricted to.
     *
     * @return the upper-case domain codes; empty when no "only" clause applies
     */
    public Set<String> onlyDomains()
    {
        return onlyDomains;
    }


    /**
     * The domains the variable is excluded from.
     *
     * @return the upper-case domain codes
     */
    public Set<String> notDomains()
    {
        return notDomains;
    }


    /**
     * The observation classes whose domains the variable is excluded from.
     *
     * @return the upper-case class names ({@code FINDINGS})
     */
    public Set<String> notClasses()
    {
        return notClasses;
    }


    @Override
    public String toString()
    {
        return "UsageRestrictions[" + text + " -> " + forms + "]";
    }
}
