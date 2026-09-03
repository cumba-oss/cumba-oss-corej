package net.cumba.corej.define.conformance.rule;

/**
 * A run's rule <b>selection</b> is unusable: no family named, a version that cannot be determined,
 * a rules directory that does not resolve, a package that is not published for the requested
 * {@code (family, version)}, or a corpus whose packages disagree with its manifest.
 *
 * <p>
 * These are all things the caller states or supplies, so a front end should report them as usage
 * errors. Evaluating a rule failing is <b>not</b> one of them, and the distinction is the whole
 * reason this type exists: the CLI previously told the two apart by substring-matching exception
 * messages, so a rule-evaluation fault whose text happened to mention a family surfaced as "invalid
 * Define-XML rules configuration" and sent the operator to inspect their own setup for an engine
 * bug. Rewording any message could silently move an error from one class to the other, and nothing
 * enforced the coupling.
 * </p>
 *
 * <p>
 * It extends {@link IllegalStateException} so existing catch sites keep behaving as they did; the
 * type only makes the classification structural instead of textual.
 * </p>
 */
public class DefineRuleSelectionException extends IllegalStateException
{

    private static final long serialVersionUID = 1L;

    public DefineRuleSelectionException(String aMessage)
    {
        super(aMessage);
    }


    public DefineRuleSelectionException(String aMessage, Throwable aCause)
    {
        super(aMessage, aCause);
    }

}
