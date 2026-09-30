package net.cumba.corej.core.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.cumba.corej.core.expr.RuleDefinitionException;
import org.jspecify.annotations.Nullable;

/**
 * One authored {@code Bindings:} entry — the <b>only</b> authoring surface for a rule's bindings
 * since phase 7b of {@code PLAN-typed-expression-engine.md} (owner rulings 2026-09-17): the
 * {@code $}-variable {@link #name} plus the {@link #expression} that computes it, e.g.
 *
 * <pre>{@code
 * Bindings:
 * - name: "$disposition_event_count"
 *   expression: "record_count(domain=\"DS\", filter=(DSCAT == \"DISPOSITION EVENT\"))"
 * }</pre>
 *
 * <p>
 * {@code RulePackageLoader.materialiseBindings} parses each binding once into a
 * {@link CompiledBinding}, compiled like the Check ({@code PLAN-binding-expressions} wave 0; since
 * runbook W8 the one kind of binding — the operation record a single top-level operation call used
 * to become went with the retired carrier). Nothing field-shaped binds from YAML/JSON.
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
     * The expression computing the binding's value — a function call such as
     * {@code "record_count(group=[USUBJID])"}, or since wave 0 any expression the Check could
     * contain ({@code "upper(AETERM)"}, {@code "$n - 1"}, {@code "[\"A\", \"B\"]"}). Required — the
     * loader files a rule whose binding has none on the {@code loadError} channel.
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
                    + " (`operator:` + parameter fields) is retired — author the binding as a"
                    + " single `expression:` (a function call) (found operator '"
                    + (value == null ? "" : value.asText()) + "')");
        }
        throw new RuleDefinitionException(
                "a `Bindings:` entry carries only `name:` and `expression:` — found `" + key
                        + ":`");
    }

}
