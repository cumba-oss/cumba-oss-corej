package net.cumba.cdisc.core.exec;

import net.cumba.datatable.IDataTable;

/**
 * Result of {@link SplitDomainResolution#resolve}: the three-way outcome of resolving a
 * {@code Match_Datasets} name / {@code RDOMAIN} value against the submission. Callers map
 * {@link Absent} to today's "not available" skip, {@link Table} to the join, and {@link Invalid} to
 * a rule-level ERROR (via {@link InvalidJoinedDomainException}).
 */
sealed interface DomainResolution
{

    /** The name resolved — exactly, to a single member, or to a split-domain union. */
    record Table(IDataTable table) implements DomainResolution
    {
    }


    /** No dataset and no split domain by that name. */
    record Absent() implements DomainResolution
    {
    }


    /** The domain is split and its members cannot be unioned (e.g. a column type clash). */
    record Invalid(String domain, String message) implements DomainResolution
    {
    }

}
