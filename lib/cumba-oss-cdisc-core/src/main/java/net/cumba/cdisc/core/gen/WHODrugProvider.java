package net.cumba.cdisc.core.gen;

import java.util.List;

/**
 * Provides WHO Drug dictionary lookup for coding validation. Optional — when not available, WHO
 * Drug rules are silently skipped.
 */
public interface WHODrugProvider
{

    String getVersion();


    boolean isValidDrugName(String drugName);


    List<String> getATCCodes(String drugName);


    boolean isValidATCCode(String atcCode);


    String getATCText(String atcCode);


    boolean isDrugUnderATC(String drugName, String atcCode);

}
