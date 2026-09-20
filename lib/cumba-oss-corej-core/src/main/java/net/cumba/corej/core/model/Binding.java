package net.cumba.corej.core.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.convert.OperationExpressionParser;
import org.jspecify.annotations.Nullable;

/**
 * One authored {@code Bindings:} entry — the <b>only</b> authoring surface for a rule's operation
 * bindings since phase 7b of {@code PLAN-typed-expression-engine.md} (owner rulings 2026-09-17):
 * the {@code $}-variable {@link #name} plus the single function-call {@link #expression} that
 * computes it, e.g.
 *
 * <pre>{@code
 * Bindings:
 * - name: "$disposition_event_count"
 *   expression: "record_count(DSCAT, domain=\"DS\", filter=filter(DSCAT=\"DISPOSITION EVENT\"))"
 * }</pre>
 *
 * <p>
 * {@code RulePackageLoader.normalizeOperations} materialises each binding into the executor's
 * internal bound-argument record ({@link Operation}) via
 * {@link net.cumba.corej.core.expr.convert.OperationExpressionParser}. The {@link Operation} class
 * is <em>not</em> an authoring surface any more — {@code Rule.operations} is {@code @JsonIgnore} —
 * so nothing field-shaped binds from YAML/JSON.
 * </p>
 *
 * <p>
 * ⛔ <b>Every retired spelling fails LOUD here, naming its replacement</b> — the same contract phase
 * 7d gave the retired operator-leaf Check ({@link CheckConditionDeserializer}). The loader runs
 * with {@code FAIL_ON_UNKNOWN_PROPERTIES=false}, which would otherwise drop a stray key in silence
 * — the FDA-SD1078 under-report shape the owner's third 2026-09-17 ruling bans ("no element field
 * should overwrite an expression / function parameter"). So the {@link JsonAnySetter} rejects
 * <em>every</em> key that is not {@code name} / {@code expression}:
 * </p>
 * <ul>
 * <li>{@code id:} — the pre-7b entry key, renamed to {@code name:} (owner: "Update to Bindings with
 * name/expression as this is the better wording");</li>
 * <li>{@code operator:} and every operation parameter field ({@code group:}, {@code level:},
 * {@code filter:}, …) — the retired field form; parameters are authored <em>inside</em> the
 * expression as keyword arguments;</li>
 * <li>anything else — a binding carries exactly {@code name:} and {@code expression:}.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
public class Binding
{

    /** The {@code $}-variable this binding defines (e.g. {@code "$ae_record_count"}). */
    private @Nullable String name;

    /**
     * The single operation function call computing the binding's value, e.g.
     * {@code "record_count(group=[USUBJID])"}. Required — the loader files a rule whose binding has
     * none on the {@code loadError} channel.
     */
    private @Nullable String expression;

    /**
     * Rejects every retired or unknown key, loud, naming the replacement — see the class javadoc.
     *
     * @param key
     *            the offending YAML/JSON key
     * @param value
     *            its value (used to name the offending operator)
     * @throws RuleDefinitionException
     *             always
     */
    // Package-private, not private: invoked reflectively by Jackson, and both PMD and SpotBugs
    // flag an uncalled private method.
    @JsonAnySetter
    void rejectRetiredKey(String key, @Nullable JsonNode value)
    {
        if ("id".equals(key))
        {
            throw new RuleDefinitionException("the binding key `id:` is retired — name the binding"
                    + " with `name:` (a `Bindings:` entry is `name:` + `expression:`)");
        }
        if ("operator".equals(key))
        {
            throw new RuleDefinitionException("the field form of a binding"
                    + " (`operator:` + parameter fields) is retired — author the operation as a"
                    + " single `expression:` function call (found operator '"
                    + (value == null ? "" : value.asText()) + "')");
        }
        if (OperationExpressionParser.parameterKeys().contains(key))
        {
            throw new RuleDefinitionException("the field form of a binding is retired — `" + key
                    + ":` is an operation parameter and belongs inside the `expression:` as a"
                    + " keyword argument (`" + key + "=…`); a `Bindings:` entry carries only"
                    + " `name:` and `expression:`");
        }
        throw new RuleDefinitionException(
                "a `Bindings:` entry carries only `name:` and `expression:` — found `" + key
                        + ":`");
    }

}
