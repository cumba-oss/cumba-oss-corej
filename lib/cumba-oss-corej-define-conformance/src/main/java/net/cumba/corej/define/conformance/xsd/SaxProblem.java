package net.cumba.corej.define.conformance.xsd;

import org.xml.sax.SAXParseException;

/**
 * One problem reported by the {@link XsdValidator} {@code ErrorHandler}: the parser severity
 * channel it arrived on, the full SAX message (which for Xerces schema errors starts with the
 * stable error key, e.g. {@code "cvc-complex-type.4: Attribute 'Mandatory' must appear on element
 * 'ItemRef'."}), and the source location. {@link SaxErrorClassifier} maps these onto PMDA rule ids.
 */
public record SaxProblem(Kind kind, String message, int line, int column)
{

    /** The {@code org.xml.sax.ErrorHandler} channel the problem was reported on. */
    public enum Kind
    {

        WARNING,

        ERROR,

        /** Well-formedness failure; parsing stops at the first one. */
        FATAL;

    }

    /** Captures a handler callback; a missing message becomes an empty string. */
    public static SaxProblem of(Kind aKind, SAXParseException aException)
    {
        String message = aException.getMessage();
        return new SaxProblem(aKind, message == null ? "" : message, aException.getLineNumber(),
                aException.getColumnNumber());
    }

}
