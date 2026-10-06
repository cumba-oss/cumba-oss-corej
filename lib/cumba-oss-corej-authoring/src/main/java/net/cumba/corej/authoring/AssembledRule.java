package net.cumba.corej.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import net.cumba.corej.core.model.Rule;
import org.jspecify.annotations.Nullable;

/**
 * The result of {@link AuthoredRuleAssembler#assemble}: one authored rule turned into the two node
 * shapes the corpus generator emits, the engine's view of it, and every diagnostic raised on the
 * way.
 *
 * <ul>
 * <li>{@link #canonicalNode} — the <em>assembled</em> rule: check fields minus {@code Standards},
 * plus the full {@code Authorities} join (citations included). This is the node
 * {@code RuleStorageAssembler.assemblePackage} places under {@code rules.<id>}, and the shape an
 * editor shows when asked for the rule's authorities.</li>
 * <li>{@link #packagedNode} — the <em>released</em> rule: the canonical node after the
 * carry-through conversion and {@link ReleaseShapeTrimmer} ({@code Authorities} collapsed to
 * {@code Organization} + {@code Rule_Ids}, {@code Source} / {@code Source_Proposed} dropped).
 * Byte-equal to the rule's entry in a generated
 * {@code rules-<family>-<standard>-<version>.json}.</li>
 * <li>{@link #rule} — the packaged node loaded through {@code RulePackageLoader}, i.e. what the
 * engine executes; {@code null} when the rule is {@link #parked} or the package could not be loaded
 * at all (a diagnostic says which).</li>
 * </ul>
 *
 * @param coreId
 *            the rule's {@code Core.Id}, or the placeholder {@code "<unknown>"} when the check
 *            carries none
 * @param rule
 *            the loaded rule, or {@code null}
 * @param canonicalNode
 *            the assembled node; {@code null} only when the check YAML could not be read at all
 * @param packagedNode
 *            the released node; {@code null} under the same condition
 * @param diagnostics
 *            every diagnostic, errors first in the order raised
 * @param parked
 *            whether {@code Executability: "Not Executable"} parks the rule (then no rule is
 *            loaded, but both nodes are still present so an editor can show them)
 */
public record AssembledRule(String coreId, @Nullable Rule rule, @Nullable ObjectNode canonicalNode,
        @Nullable JsonNode packagedNode, List<RuleDiagnostic> diagnostics, boolean parked)
{

    public AssembledRule
    {
        diagnostics = List.copyOf(diagnostics);
    }


    /** True when at least one diagnostic is an error. */
    public boolean hasErrors()
    {
        return diagnostics.stream().anyMatch(RuleDiagnostic::isError);
    }


    /** The error diagnostics only. */
    public List<RuleDiagnostic> errors()
    {
        return diagnostics.stream().filter(RuleDiagnostic::isError).toList();
    }
}
