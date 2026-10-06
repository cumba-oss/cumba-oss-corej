package net.cumba.corej.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.expr.eval.Parameter;
import net.cumba.corej.core.model.Executability;
import net.cumba.corej.core.model.ExpansionSource;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.corej.core.model.VariableUniverse;
import net.cumba.datatable.report.Severity;

/**
 * The rule-language level — the one number an authoring tool compares to say <em>"this corpus was
 * gated against engine language level N; this engine runs level M"</em>.
 *
 * <h2>What the level is, honestly</h2>
 *
 * <p>
 * Both the engine and the corpus sit on {@code 0.1.0-SNAPSHOT}, so artifact versions say nothing.
 * The level is <b>declared</b> here and <b>surface-guarded</b>: {@code language-surface.txt} (a
 * golden file in this module's tests) records {@code LEVEL} together with the canonical listing
 * {@link #surface()} produces, and a test asserts both still hold. A change to the language surface
 * — a function, a parameter, a bound key, an authored enum value — without a bump cannot go green,
 * because the golden regenerator refuses to rewrite a changed listing unless {@link #LEVEL} is
 * greater than the file's header ({@code PLAN-rule-authoring-api} §3.2). The corpus pins the level
 * it was written for in {@code rules-src/LANGUAGE-LEVEL} and a test there asserts equality. The
 * level is not a measurement of which features a rule uses.
 * </p>
 *
 * <h2>Bumping it</h2>
 *
 * <p>
 * Raise {@link #LEVEL} by one, regenerate the golden
 * ({@code -Dcorej.languageSurface.regenerate=true} on {@code RuleLanguageSurfaceTest}), and raise
 * {@code rules-src/LANGUAGE-LEVEL} in the corpus (both twins) in the same round. The rule guide's
 * "Changing the rule language" page says the same from the author's side.
 * </p>
 */
public final class RuleLanguage
{

    /** The declared rule-language level. Bump it whenever {@link #surface()} changes. */
    public static final int LEVEL = 2;

    /** The header line of the golden file. */
    public static final String LEVEL_HEADER = "LEVEL=";

    private RuleLanguage()
    {
    }


    /**
     * The canonical listing of the language surface: every registered function with its parameters,
     * the bound JSON property rosters of the rule model, and the authored enum values.
     * Deterministic: sorted, one entry per line, no measured figures.
     *
     * @return the listing (without the {@code LEVEL=} header)
     */
    public static String surface()
    {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("functions:\n");
        List<String> functions = new ArrayList<>();
        for (FunctionDescriptor d : FunctionRegistry.all())
        {
            functions.add(describe(d));
        }
        functions.stream().distinct().sorted().forEach(line -> sb.append(line).append('\n'));
        sb.append("keys:\n");
        for (String line : keyRosters())
        {
            sb.append(line).append('\n');
        }
        sb.append("enums:\n");
        sb.append(enumLine("Sensitivity", Sensitivity.values(), Sensitivity::getJsonValue));
        sb.append(enumLine("Executability", Executability.values(), Executability::getJsonValue));
        sb.append(enumLine("Variable_Universe", VariableUniverse.values(),
                VariableUniverse::getJsonValue));
        sb.append(enumLine("Expansion.source", ExpansionSource.values(),
                ExpansionSource::getJsonValue));
        sb.append(enumLine("Severity", Severity.values(), Severity::name));
        return sb.toString();
    }


    private static String describe(FunctionDescriptor aDescriptor)
    {
        StringBuilder sb = new StringBuilder(64);
        sb.append(aDescriptor.name()).append('(');
        boolean first = true;
        for (Parameter p : aDescriptor.parameters())
        {
            if (!first)
            {
                sb.append(", ");
            }
            first = false;
            sb.append(p.name()).append(':').append(p.type().describe());
            if (!p.required())
            {
                sb.append('?');
            }
            if (p.collector())
            {
                sb.append("...");
            }
        }
        sb.append(") kind=").append(aDescriptor.kind().name().toLowerCase(Locale.ROOT));
        if (aDescriptor.aggregate())
        {
            sb.append(" aggregate");
        }
        if (aDescriptor.perRow())
        {
            sb.append(" perRow");
        }
        if (aDescriptor.perVariableMap())
        {
            sb.append(" perVariable");
        }
        if (aDescriptor.provider() != null)
        {
            sb.append(" provider=")
                    .append(aDescriptor.provider().kind().name().toLowerCase(Locale.ROOT));
            if (aDescriptor.provider().typeParameter() != null)
            {
                sb.append('<').append(aDescriptor.provider().typeParameter()).append('>');
            }
        }
        return sb.toString();
    }


    /**
     * Every {@code public static final Set<String>} roster of {@link BoundRuleKeys}, by field name,
     * sorted members — found reflectively so a roster added there enters the surface by itself.
     */
    private static List<String> keyRosters()
    {
        List<String> out = new ArrayList<>();
        for (Field f : BoundRuleKeys.class.getFields())
        {
            if (!Modifier.isStatic(f.getModifiers()) || !Set.class.isAssignableFrom(f.getType()))
            {
                continue;
            }
            try
            {
                Object value = f.get(null);
                if (value instanceof Collection<?> members)
                {
                    Set<String> sorted = new TreeSet<>();
                    for (Object m : members)
                    {
                        sorted.add(String.valueOf(m));
                    }
                    out.add(f.getName() + ": " + String.join(",", sorted));
                }
            }
            catch (IllegalAccessException e)
            {
                throw new IllegalStateException("cannot read BoundRuleKeys." + f.getName(), e);
            }
        }
        out.sort(null);
        return out;
    }


    private static <E extends Enum<E>> String enumLine(String aName, E[] aValues,
            Function<E, String> aJson)
    {
        Set<String> sorted = new TreeSet<>();
        for (E v : aValues)
        {
            sorted.add(aJson.apply(v));
        }
        return aName + ": " + String.join(",", sorted) + "\n";
    }


    /** The golden text: the header line, then {@link #surface()}. */
    public static String goldenText()
    {
        return LEVEL_HEADER + LEVEL + "\n" + surface();
    }


    /**
     * The level a golden text declares in its header.
     *
     * @param aGolden
     *            the file text
     * @return the level
     * @throws IllegalArgumentException
     *             when the first line is not {@code LEVEL=<n>}
     */
    public static int headerLevel(String aGolden)
    {
        int eol = aGolden.indexOf('\n');
        String first = (eol < 0 ? aGolden : aGolden.substring(0, eol)).strip();
        if (!first.startsWith(LEVEL_HEADER))
        {
            throw new IllegalArgumentException("language-surface golden does not start with "
                    + LEVEL_HEADER + "<n>: '" + first + "'");
        }
        return Integer.parseInt(first.substring(LEVEL_HEADER.length()).strip());
    }
}
