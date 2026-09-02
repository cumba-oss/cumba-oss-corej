package net.cumba.cdisc.define.conformance.report;

/**
 * Finding category (plan §1): {@link #SCHEMA} / {@link #SPECIFICATION} mirror the CDISC sheet's
 * {@code Source Type} column; {@link #PMDA} marks PMDA-sheet rules; {@link #XSD} marks findings
 * from the XSD pre-pass. The report summary counts findings per category × severity.
 */
public enum Category
{

    XSD,

    SCHEMA,

    SPECIFICATION,

    PMDA;

}
