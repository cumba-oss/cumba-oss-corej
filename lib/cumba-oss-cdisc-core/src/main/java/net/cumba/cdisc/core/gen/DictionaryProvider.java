package net.cumba.cdisc.core.gen;

/**
 * Optional provider for medical dictionaries (MedDRA, WHO Drug). When not configured,
 * dictionary-based rules are silently skipped.
 * <p>
 * This is a plain interface — no service file resolution. Implementations are created by the caller
 * and passed to the {@link RuleGenerator} constructor.
 * </p>
 */
public interface DictionaryProvider
{

    /** Returns the MedDRA provider, or {@code null} if not available. */
    MedDRAProvider getMedDRA();


    /** Returns the WHO Drug provider, or {@code null} if not available. */
    WHODrugProvider getWHODrug();

}
