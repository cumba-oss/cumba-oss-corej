package net.cumba.corej.define.conformance.rule;

/**
 * A run's <b>family selection</b> cannot be served by the loaded corpus: a family the manifest does
 * not publish, a named family with no package at the document's Define-XML version, or an
 * every-published-family run on a version no family publishes.
 *
 * <p>
 * These are decisions the <em>caller</em> made — the families named on the command line or ticked
 * in a dialog — judged against a corpus that is itself sound. They are deliberately a subtype of
 * {@link DefineRuleSelectionException}, which also covers <em>corpus</em> faults (a manifest that
 * does not read, a package disagreeing with its index, a rules directory that is not there), so
 * every existing catch site still sees them. The distinction matters to a front end that appends a
 * remedy to a selection failure: "point {@code COREJ_DEFINE_RULES_DIR} at the packaged rules
 * directory" is right for a corpus fault and wrong for "no FDA rule package published for
 * Define-XML 2.0", where the corpus is fine and the selection is what has to change. A front end
 * that catches this type first can report the engine's message alone
 * (PLAN-define-families-from-packages, L-5).
 * </p>
 */
public class DefineFamilySelectionException extends DefineRuleSelectionException
{

    private static final long serialVersionUID = 1L;

    public DefineFamilySelectionException(String aMessage)
    {
        super(aMessage);
    }

}
