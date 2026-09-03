package net.cumba.corej.core.gen;

/**
 * Provides MedDRA dictionary lookup for hierarchy validation. Optional — when not available, MedDRA
 * rules are silently skipped.
 */
public interface MedDRAProvider
{

    String getVersion();


    boolean isValidPTCode(String ptCode);


    String getPreferredTerm(String ptCode);


    String getPreferredTermCode(String ptName);


    boolean isValidPreferredTerm(String ptName);


    String getLLTName(String lltCode);


    boolean isLLTUnderPT(String lltCode, String ptCode);


    String getHLTName(String hltCode);


    boolean isPTUnderHLT(String ptCode, String hltCode);


    String getHLGTName(String hlgtCode);


    String getSOCName(String socCode);


    boolean isPTUnderSOC(String ptCode, String socCode);

}
