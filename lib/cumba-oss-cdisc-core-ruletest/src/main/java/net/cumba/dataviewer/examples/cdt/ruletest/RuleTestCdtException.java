package net.cumba.dataviewer.examples.cdt.ruletest;

/**
 * Thrown by {@link RuleTestCdt} when an extended-CDT "scenario" file cannot be parsed. Messages
 * follow the same {@code source:line: msg} pattern as
 * {@link net.cumba.datatable.provider.cdt.CdtParseException}.
 */
public class RuleTestCdtException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    public RuleTestCdtException(String aMessage)
    {
        super(aMessage);
    }


    public RuleTestCdtException(String aMessage, Throwable aCause)
    {
        super(aMessage, aCause);
    }
}
