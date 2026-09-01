package net.cumba.cdisc.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class MetadataKeysTest
{

    @Test
    void keysAreNonNullAndDistinct()
    {
        String[] keys =
        {
                MetadataKeys.STANDARD_NAME, MetadataKeys.STANDARD_VERSION, MetadataKeys.CT_VERSION,
                MetadataKeys.IS_CUSTOM_DOMAIN, MetadataKeys.MODEL_COLUMN_ORDER,
                MetadataKeys.CLASS_NAME, MetadataKeys.DATASET_STRUCTURE, MetadataKeys.CORE,
                MetadataKeys.ROLE, MetadataKeys.CODELIST, MetadataKeys.CODELIST_CONCEPT_ID,
                MetadataKeys.CODELIST_SUBMISSION_VALUE,
        };
        for (String k : keys)
        {
            assertNotNull(k);
        }
        for (int i = 0; i < keys.length; i++)
        {
            for (int j = i + 1; j < keys.length; j++)
            {
                assertNotEquals(keys[i], keys[j], "duplicate key: " + keys[i]);
            }
        }
    }


    @Test
    void keyValuesAreStable()
    {
        // These names are the public contract — changing them is a breaking change
        // for any producer that populates meta-keys by string constant.
        assertEquals("StandardName", MetadataKeys.STANDARD_NAME);
        assertEquals("StandardVersion", MetadataKeys.STANDARD_VERSION);
        assertEquals("CtVersion", MetadataKeys.CT_VERSION);
        assertEquals("IsCustomDomain", MetadataKeys.IS_CUSTOM_DOMAIN);
        assertEquals("ModelColumnOrder", MetadataKeys.MODEL_COLUMN_ORDER);
        assertEquals("ClassName", MetadataKeys.CLASS_NAME);
        assertEquals("DatasetStructure", MetadataKeys.DATASET_STRUCTURE);
        assertEquals("Core", MetadataKeys.CORE);
        assertEquals("Role", MetadataKeys.ROLE);
        assertEquals("Codelist", MetadataKeys.CODELIST);
        assertEquals("CodelistConceptId", MetadataKeys.CODELIST_CONCEPT_ID);
        assertEquals("CodelistSubmissionValue", MetadataKeys.CODELIST_SUBMISSION_VALUE);
    }

}
