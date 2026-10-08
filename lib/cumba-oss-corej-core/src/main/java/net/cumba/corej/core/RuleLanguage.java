package net.cumba.corej.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import net.cumba.corej.core.exec.GroupedAggregate;
import net.cumba.corej.core.exec.ReadValue;
import net.cumba.corej.core.expr.BuiltinRegistry;
import net.cumba.corej.core.expr.ExpressionLexer;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.expr.eval.FunctionDescriptor;
import net.cumba.corej.core.expr.eval.FunctionRegistry;
import net.cumba.corej.core.expr.eval.MetadataAttribute;
import net.cumba.corej.core.expr.eval.MetadataLevel;
import net.cumba.corej.core.expr.eval.Parameter;
import net.cumba.corej.core.expr.typed.ElementTable;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.expr.typed.StageAChecker;
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
    public static final int LEVEL = 5;

    /** The header line of the golden file. */
    public static final String LEVEL_HEADER = "LEVEL=";

    private RuleLanguage()
    {
    }


    /**
     * The canonical listing of the language surface: every registered function with its parameters
     * ({@code functions:}, always first), then — since {@code PLAN-engine-language-runbook} §6.0
     * (G) — the metadata accessors with their scope, levels, result type and keywords
     * ({@code accessors:}), the operator spellings, keywords and literal forms the lexer reads
     * ({@code operators:}, {@code keywords:}, {@code literal-forms:}), the built-in operands
     * ({@code operands:}), the retired call names ({@code retired:}), the static — literal-only —
     * parameters with their vocabularies ({@code static-parameters:}), the enumerated parameter
     * values read at plan build ({@code parameter-values:}), the keyed result types
     * ({@code keyed-results:}), the bound JSON property rosters of the rule model ({@code keys:})
     * and the authored enum values ({@code enums:}). Deterministic: sorted, one entry per line, no
     * measured figures. Every element a wave of the runbook changes has a line here, so the golden
     * sees the change.
     *
     * @return the listing (without the {@code LEVEL=} header)
     */
    public static String surface()
    {
        StringBuilder sb = new StringBuilder(16384);
        sb.append("functions:\n");
        List<String> functions = new ArrayList<>();
        for (FunctionDescriptor d : FunctionRegistry.all())
        {
            functions.add(describe(d));
        }
        functions.stream().distinct().sorted().forEach(line -> sb.append(line).append('\n'));
        sb.append("accessors:\n");
        for (String line : accessorLines())
        {
            sb.append(line).append('\n');
        }
        sb.append("operators: ").append(String.join(" ", sorted(ExpressionLexer.operators())))
                .append('\n');
        sb.append("keywords: ").append(String.join(",", sorted(ExpressionLexer.keywords())))
                .append('\n');
        sb.append("literal-forms: ").append(String.join(" ", ExpressionLexer.literalForms()))
                .append('\n');
        sb.append("operands:\n");
        for (String name : sorted(BuiltinRegistry.names()))
        {
            sb.append(name).append('\n');
        }
        sb.append("retired:\n");
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(
                RulePackageLoader.retiredFunctionNames()).entrySet())
        {
            sb.append(e.getKey()).append(" -> ").append(e.getValue()).append('\n');
        }
        sb.append("static-parameters:\n");
        for (String line : staticParameterLines())
        {
            sb.append(line).append('\n');
        }
        sb.append("parameter-values:\n");
        for (String line : parameterValueLines())
        {
            sb.append(line).append('\n');
        }
        sb.append("keyed-results:\n");
        for (Map.Entry<String, Map<String, ExprType>> e : new java.util.TreeMap<>(
                ElementTable.keyedResultTypes()).entrySet())
        {
            List<String> keyed = new ArrayList<>();
            for (Map.Entry<String, ExprType> k : new java.util.TreeMap<>(e.getValue()).entrySet())
            {
                keyed.add(k.getKey() + ":" + k.getValue().describe());
            }
            sb.append(e.getKey()).append(": ").append(String.join(",", keyed)).append('\n');
        }
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
            if (p.nameSlot())
            {
                // a name slot (PLAN-string-literal-prefix-rewrite S2; level 4): a quoted spelling
                // here is a name, specialised at bind time — every other STRING literal is a value
                sb.append("@name");
            }
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
        if (aDescriptor.elementwise())
        {
            // the element-wise lift over a list at parameter 0 (PLAN-elementwise-list-functions
            // §3.4, runbook W1a E4)
            sb.append(" elementwise");
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
     * One line per {@link MetadataAttribute}: {@code name scope=… levels=… result=…}, the
     * {@code default=DATA} marker where a level-less call reads DATA, then the keywords the
     * accessor takes ({@code dataset=} on the four cross-dataset readers, {@code stem=} on
     * {@code label_template}). Sorted by accessor name.
     */
    private static List<String> accessorLines()
    {
        List<String> out = new ArrayList<>();
        for (MetadataAttribute attr : MetadataAttribute.values())
        {
            StringBuilder sb = new StringBuilder(96);
            sb.append(attr.functionName()).append(" scope=")
                    .append(attr.scope().name().toLowerCase(Locale.ROOT)).append(" levels=");
            List<String> levels = new ArrayList<>();
            for (MetadataLevel level : attr.supportedLevels())
            {
                levels.add(level.name());
            }
            sb.append(String.join(",", levels)).append(" result=")
                    .append(StageAChecker.accessorType(attr).describe());
            if (attr.supports(MetadataLevel.DATA))
            {
                // PLAN-metadata-accessor-default-level D1 (runbook W1a MA-r1): a call with no
                // level reads DATA
                sb.append(" default=DATA");
            }
            List<String> keywords = new ArrayList<>();
            if (ExprCompiler.takesDatasetKeyword(attr))
            {
                keywords.add("dataset");
            }
            if (attr == MetadataAttribute.LABEL_TEMPLATE)
            {
                keywords.add(ExprCompiler.STEM_KEYWORD);
            }
            if (!keywords.isEmpty())
            {
                sb.append(" kwargs=").append(String.join(",", keywords));
            }
            out.add(sb.toString());
        }
        out.sort(null);
        return out;
    }


    /**
     * One line per <b>(function, parameter)</b> the compiler holds to a literal: {@code literal} (a
     * string literal; with its closed vocabulary in braces where one exists). A unit that widens a
     * parameter to computed values drops its line: W1a U3 dropped {@code minus}'s / {@code union}'s
     * {@code binding-or-list} lines ({@code PLAN-union-function} §3.3); W1a BS1 dropped the
     * {@code literal-list} lines ({@code codelists}, {@code ct_package_types}), the dictionary
     * functions' {@code case_sensitive} lines and the open-vocabulary {@code literal} lines
     * ({@code extract_metadata} / {@code variable_count} {@code name}, {@code key_value}) — each is
     * any dataset-level expression of its type now, judged by its level
     * ({@code ListOperandLevel.PER_CALL_PARAMETERS}, {@code PLAN-binding-substitutability} C9–C11).
     */
    private static List<String> staticParameterLines()
    {
        Set<String> out = new TreeSet<>();
        for (Map.Entry<String, Set<String>> e : ExprCompiler.staticStringParameters().entrySet())
        {
            for (String param : e.getValue())
            {
                out.add(e.getKey() + "." + param + ": literal");
            }
        }
        for (Map.Entry<String, Map<String, Set<String>>> e : ExprCompiler.staticStringVocabularies()
                .entrySet())
        {
            for (Map.Entry<String, Set<String>> p : e.getValue().entrySet())
            {
                Set<String> vocabulary = p.getValue();
                out.add(e.getKey() + "." + p.getKey() + ": literal" + (vocabulary == null ? ""
                        : " {" + String.join(",", sorted(vocabulary)) + "}"));
            }
        }
        return new ArrayList<>(out);
    }


    /**
     * The enumerated values of parameters a function reads at plan build rather than per row —
     * {@code read_value}'s {@code mode}, the {@code missing_values} policy, the next-record
     * {@code relation} — one line per (function, parameter) that declares the parameter, plus the
     * metadata level vocabulary of the accessors.
     */
    private static List<String> parameterValueLines()
    {
        Set<String> out = new TreeSet<>();
        List<String> modes = new ArrayList<>();
        for (ReadValue.Mode mode : ReadValue.Mode.values())
        {
            modes.add(mode.name());
        }
        Set<String> missingValues = Set.of(GroupedAggregate.MISSING_VALUES_SKIP,
                GroupedAggregate.MISSING_VALUES_INDETERMINATE);
        for (FunctionDescriptor d : FunctionRegistry.all())
        {
            if (ReadValue.NAME.equals(d.name()) && d.parameter("mode") != null)
            {
                out.add(d.name() + ".mode: " + String.join(",", sorted(modes)));
            }
            if (d.parameter(GroupedAggregate.MISSING_VALUES_PARAMETER) != null)
            {
                out.add(d.name() + "." + GroupedAggregate.MISSING_VALUES_PARAMETER + ": "
                        + String.join(",", sorted(missingValues)));
            }
            if (d.parameter("relation") != null)
            {
                out.add(d.name() + ".relation: " + String.join(",",
                        sorted(net.cumba.corej.core.model.NextRecordRelation.SPELLINGS)));
            }
        }
        List<String> levels = new ArrayList<>();
        for (MetadataLevel level : MetadataLevel.values())
        {
            levels.add(level.name());
        }
        out.add("metadata-level: " + String.join(",", levels));
        return new ArrayList<>(out);
    }


    private static List<String> sorted(Collection<String> aValues)
    {
        List<String> out = new ArrayList<>(aValues);
        out.sort(null);
        return out;
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
