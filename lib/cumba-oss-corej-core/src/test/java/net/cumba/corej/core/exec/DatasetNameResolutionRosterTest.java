package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code PLAN-split-supp-resolution} C20 (D4 (c), PG-M10): the roster of every site that turns a
 * dataset <b>name</b> into data is <b>derived from the engine's source</b> — a scan of
 * {@code src/main/java} for the calls that resolve a name ({@code SplitDomainResolution.*},
 * {@code DatasetIdentity.resolve*} / {@code resolveDatasetHalf}, the text policy, a dataset
 * resolver's {@code resolve}) — and asserted <b>equal</b> to the classified map below: every site
 * is either a case of {@link SplitSuppFamilyResolutionTest} / {@link SuppMergeSourcesTest} (it
 * widens to a split supplemental family) or a §9 reason why it must not. A new site reds here until
 * it is classified; a classified site that disappears reds too. An authored roster would confirm
 * itself — this one cannot: the planted control proves the scan sees a new call.
 */
class DatasetNameResolutionRosterTest
{

    /** The engine's main sources: this module's own, found from surefire's working directory. */
    private static final Path MAIN = findMain();

    /**
     * The calls that resolve a dataset name, by kind; the dot may sit on the next line (a
     * fully-qualified {@code net.cumba.corej.core.exec.SplitDomainResolution} wraps there).
     */
    private static final Pattern CALL = Pattern.compile(
            "\\b(?:(SplitDomainResolution|DatasetIdentity|CheckConditionTransformer)\\s*\\.\\s*(resolve|resolveTableOrThrow|resolveTableOrNull|isPresentAsDomain|byName|membersOf|suppFamilyMembers|suppMergeSources|isSuppFamilyName|resolveWildcard|resolveDatasetWildcard|resolveDatasetHalf|resolveTextWildcard)|(\\w+|getDatasetResolver\\(\\))\\s*\\.\\s*(resolve))\\(");

    /**
     * A receiver counts as a dataset resolver when the file declares it with that type — a
     * parameter, field or local of {@code DatasetResolver}, {@code DatasetResolver.WithInventory}
     * or the datatable's {@code net.cumba.datatable.merge.DatasetResolver} — or when it is the
     * context's {@code getDatasetResolver()} (review M2: a receiver list missed
     * {@code ScopeClassLadder}'s {@code aResolver}).
     */
    private static boolean datasetResolverReceiver(String stripped, String receiver)
    {
        return "getDatasetResolver()".equals(receiver) || Pattern.compile(
                "\\b(?:net\\.cumba\\.datatable\\.merge\\.)?DatasetResolver(?:\\.WithInventory)?\\s+(?:@\\w+\\s+)?"
                        + Pattern.quote(receiver) + "\\b")
                .matcher(stripped).find();
    }

    /** A method (or compact constructor) declaration line: modifiers, a type, the name, `(`. */
    private static final Pattern DECLARATION = Pattern.compile(
            "^(\\s*)(?:(?:public|private|protected|static|final|abstract|synchronized|default)\\s+)*(?:[\\w<>\\[\\],.?@]+\\s+)+(\\w+)\\s*\\(");

    private static final Pattern EXCLUDED_LEAD = Pattern
            .compile("^\\s*(?:\\}|return\\b|throw\\b|new\\b|else\\b|case\\b)");

    /** site → why it is on the roster: a C18 / C19 case, or the §9 reason it stays unwidened. */
    private static final Map<String, String> CLASSIFIED = new TreeMap<>(Map.ofEntries(
            // ---- the seam's callers (each a case of SplitSuppFamilyResolutionTest, C18)
            Map.entry(
                    "exec/OperatorRegistry#existsAsDataset:SplitDomainResolution.isPresentAsDomain",
                    "C18 ds_exists"),
            Map.entry(
                    "exec/OperatorRegistry#anyColumnMatches:SplitDomainResolution.resolveTableOrThrow",
                    "C18 var_exists(\"F.QN${*}\")"),
            Map.entry(
                    "exec/OperatorRegistry#existsAsDottedDatasetColumn:SplitDomainResolution.resolveTableOrThrow",
                    "C18 var_exists(\"F.QNAM\")"),
            Map.entry(
                    "exec/OperatorRegistry#existsAsDottedFilter:SplitDomainResolution.resolveTableOrThrow",
                    "C18 var_exists(\"F.QNAM=Q\")"),
            Map.entry("exec/ReadValue#evaluate:SplitDomainResolution.resolveTableOrThrow",
                    "C18 read_value(domain=)"),
            Map.entry(
                    "exec/GroupedAggregate#resolveTarget:SplitDomainResolution.resolveTableOrThrow",
                    "C18 record_count / distinct (domain=)"),
            Map.entry("exec/GroupedAggregate#datasetName:DatasetIdentity.resolveDatasetWildcard",
                    "C18 domain=\"SUPP--\" / \"SQAP--\""),
            Map.entry(
                    "expr/eval/ExprCompiler#readDataLevel:SplitDomainResolution.resolveTableOrThrow",
                    "C18 ds_name / ds_domain(\"F\", \"DATA\")"),
            Map.entry(
                    "expr/eval/ExprCompiler#readProviderLevel:SplitDomainResolution.resolveTableOrThrow",
                    "§9 the LIBRARY joined read: the same seam call as read_value, reached only with a Library provider — not driven by C18"),
            Map.entry("exec/RuleRunner#foreignInventory:SplitDomainResolution.resolveTableOrThrow",
                    "C18 stage B: the Match_Datasets Filter binding and the _matched_ flag"),
            Map.entry(
                    "exec/RuleRunner#describeMissingRequiredDataset:DatasetIdentity.resolveDatasetWildcard",
                    "C18 Requirements.Datasets SUPP--"),
            Map.entry("exec/RuleRunner#describeMissingRequiredDataset:resolver.resolve",
                    "§9 the exact arm of a rule with no native Check (resolvesExactOnly), recomputed from AbsentDatasetSkip's own sets"),
            Map.entry(
                    "exec/RuleRunner#describeMissingRequiredDataset:SplitDomainResolution.isPresentAsDomain",
                    "C18 Requirements.Datasets"),
            Map.entry(
                    "exec/RuleRunner#expandOutputVarWildcards:SplitDomainResolution.resolveTableOrThrow",
                    "C18 ${*} Output_Variables expansion"),
            Map.entry("exec/RuleRunner#resolveOutputVarName:DatasetIdentity.resolveDatasetHalf",
                    "C18 Output_Variables SUPP--.QNAM"),
            Map.entry("exec/AbsentDatasetSkip#decide:resolver.resolve",
                    "C18 AbsentDatasetSkip: the exact probe before the widened one"),
            Map.entry("exec/AbsentDatasetSkip#decide:SplitDomainResolution.isPresentAsDomain",
                    "C18 AbsentDatasetSkip: a present family dependency"),
            // ---- the merge and the chooser (SuppMergeSourcesTest, C19)
            Map.entry("exec/MergedEvaluation#build:SplitDomainResolution.suppMergeSources",
                    "C19 the primary's SUPP sources"),
            Map.entry("exec/MergedEvaluation#build:SplitDomainResolution.resolveTableOrThrow",
                    "C19 the D11 callback: every non-chosen name through the widened seam"),
            Map.entry("exec/MergedEvaluation#construct:datasets.resolve",
                    "§9 the merge's own resolution through the D11 callback, recorded so a Child parent /"
                            + " RELREC part's lookup holds the table the merge read (B5 review L2, ENGINE"
                            + " FOLD MED-1); a refusal propagates as the merge's own ERROR"),
            Map.entry("exec/MergedEvaluation#keyedSpecs:DatasetIdentity.resolveDatasetWildcard",
                    "C18 non-Child Match_Datasets SUPP--"),
            Map.entry("exec/MergedEvaluation#keyedSpecs:SplitDomainResolution.resolveTableOrThrow",
                    "C18 a keyed Match_Datasets partner; C19 F1"),
            Map.entry(
                    "exec/MergedEvaluation#refuseSuppQualifierKey:SplitDomainResolution.suppMergeSources",
                    "C19 the refusal sees exactly the fed members"),
            Map.entry("exec/MergedEvaluation#partnerSupp:SplitDomainResolution.suppMergeSources",
                    "C19 a keyed partner's SUPP sources (D12)"),
            Map.entry("exec/SuppQualifiers#of:SplitDomainResolution.suppMergeSources",
                    "C18 existsViaSuppQnam / var_exists(\"AE.AETRTEM\"); C19 the QNAM set equals the view's"),
            // ---- the scope gate (C18)
            Map.entry(
                    "exec/ScopeVariableSource#localQualifier:SplitDomainResolution.suppMergeSources",
                    "C18 the bare probe"),
            Map.entry(
                    "exec/ScopeVariableSource#suppQvalType:SplitDomainResolution.suppMergeSources",
                    "C18 the typed probe"),
            Map.entry(
                    "exec/ScopeVariableSource#resolveQualifierName:DatasetIdentity.resolveDatasetWildcard",
                    "C18 a qualified SUPP--.QNAM entry"),
            Map.entry("exec/ScopeVariableSource#resolveIgnoringCase:SplitDomainResolution.byName",
                    "§9 the exact lookup of a qualified entry's dataset (its family leg runs first in resolveTables)"),
            Map.entry(
                    "exec/ScopeVariableSource#resolveTables:SplitDomainResolution.isSuppFamilyName",
                    "C18 qualified Requirements.Variables (metasOf / tablesOf)"),
            Map.entry(
                    "exec/ScopeVariableSource#resolveTables:SplitDomainResolution.suppFamilyMembers",
                    "C18 qualified Requirements.Variables (metasOf / tablesOf)"),
            Map.entry("exec/ScopeVariableSource#resolveTables:SplitDomainResolution.membersOf",
                    "C18 side (a): a throwing unrelated dataset beside a split-LB entry"),
            // ---- the string channels (C18)
            Map.entry(
                    "exec/ScalarMetadataFunctions#crossDatasetVariableMetadata:DatasetIdentity.resolveDatasetWildcard",
                    "C18 cross_dataset_variable_metadata(domain=\"SUPP--\")"),
            Map.entry(
                    "exec/ScalarMetadataFunctions#crossDatasetVariableMetadata:SplitDomainResolution.isPresentAsDomain",
                    "C18 cross_dataset_variable_metadata(domain=F); side (b)"),
            Map.entry("exec/ScalarMetadataFunctions#countAcrossInventory:resolver.resolve",
                    "§9 an inventory walk by member name (variable_count's family dedup), not a dataset-name position"),
            Map.entry("exec/VariableMetadataResult#build:SplitDomainResolution.resolveTableOrThrow",
                    "C18 the var_*(dataset=) channel; side (b)"),
            Map.entry("exec/VariableMetadataResult#buildFromAllDatasets:resolver.resolve",
                    "§9 an inventory walk by member name (domain=\"*\")"),
            Map.entry(
                    "exec/ReferencedDatasetVariables#tablesNamed:SplitDomainResolution.isSuppFamilyName",
                    "C18 referenced_dataset_variables (and, through tablesNamed, the W1b VA"
                            + " readers referenced_record_count / referenced_key_is_unique)"),
            Map.entry(
                    "exec/ReferencedDatasetVariables#tablesNamed:SplitDomainResolution.suppFamilyMembers",
                    "C18 referenced_dataset_variables (and the W1b VA readers)"),
            Map.entry("exec/ReferencedDatasetVariables#tablesNamed:SplitDomainResolution.byName",
                    "§9 the exact member of a non-family value (a domain's own name)"),
            Map.entry("exec/ReferencedDatasetVariables#tablesNamed:SplitDomainResolution.membersOf",
                    "§9 the split-domain members of a two-character value (Fix #358)"),
            // ---- the text policy and the specialiser (C18, C22, C25)
            Map.entry(
                    "exec/RuleSpecialiser#resolveMatchDataset:DatasetIdentity.resolveDatasetWildcard",
                    "C18 / C25 non-Child Match_Datasets SUPP-- / SQAP--"),
            Map.entry(
                    "exec/RuleSpecialiser#resolveKnownName:CheckConditionTransformer.resolveTextWildcard",
                    "§9 a variable-name list (grouping, known names); its dataset half is the text policy's (C22)"),
            Map.entry(
                    "exec/ExprPrefixResolver#resolveName:CheckConditionTransformer.resolveTextWildcard",
                    "C22 the Check text policy: SUPP--.QVAL on APMH is SQAPMH.QVAL"),
            Map.entry(
                    "exec/CheckConditionTransformer#resolveTextWildcard:DatasetIdentity.resolveDatasetHalf",
                    "C22 the dataset half of the text policy"),
            // ---- sites left unchanged, with the reason (§9)
            Map.entry("exec/ParentModelColumnOrder#parentTables:resolver.resolve",
                    "§9 get_parent_model_column_order(RDOMAIN) names a domain, never a SUPP family"),
            Map.entry("exec/ParentModelColumnOrder#parentTables:SplitDomainResolution.membersOf",
                    "§9 the parent domain's members"),
            Map.entry("exec/DatasetIdentity#domainOfDataset:resolver.resolve",
                    "§9 resolves a member by its own name to read its DOMAIN cell; null for SUPP / SQ"),
            Map.entry("exec/InventoryLists#splitSiblingLengthMismatch:inventory.resolve",
                    "§9 an inventory walk by member name (R4 moves the family key of a lower-case member)"),
            Map.entry("exec/ChildEntries#pointerKind:parents.resolve",
                    "§9 a Child parent is a domain, resolved through the datatable callback (D11)"),
            Map.entry("metadata/ScopeClassLadder#classOf:SplitDomainResolution.resolveTableOrNull",
                    "§9 the AP parent looked up by its code for the class ladder — since runbook W2 AP through the seam's non-throwing reader: ignoring case, a split parent as its union, an un-unionable one undetermined (ScopeClassLadderTest); the AP suffix is a domain code, never a supplemental family")));

    @Test
    void everyResolutionSiteIsClassified_andEveryClassifiedSiteExists() throws IOException
    {
        Set<String> found = scan(MAIN);
        List<String> unclassified = new ArrayList<>();
        for (String site : found)
        {
            if (!CLASSIFIED.containsKey(site))
            {
                unclassified.add(site);
            }
        }
        List<String> gone = new ArrayList<>();
        for (String site : CLASSIFIED.keySet())
        {
            if (!found.contains(site))
            {
                gone.add(site);
            }
        }
        assertEquals(List.of(), unclassified,
                "a new dataset-name resolution site: classify it as a C18 / C19 case or a §9 reason");
        assertEquals(List.of(), gone, "a classified site is gone: retire its row (and its case)");
    }


    @Test
    void aPlantedSiteRedsTheScan(@TempDir Path tmp) throws IOException
    {
        // the control: a call planted into a scratch copy of one file is a site the scan reports
        Path source = MAIN.resolve("exec").resolve("ReadValue.java");
        String text = Files.readString(source, StandardCharsets.UTF_8);
        int end = text.lastIndexOf('}');
        String planted = text.substring(0, end)
                + "\n    static void plantedForTheRoster(DatasetResolver r)\n    {\n"
                + "        SplitDomainResolution.resolveTableOrThrow(r, \"PLANT\", null);\n    }\n}\n";
        Path dir = tmp.resolve("exec");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ReadValue.java"), planted, StandardCharsets.UTF_8);
        Set<String> sites = scan(tmp);
        assertTrue(sites.contains(
                "exec/ReadValue#plantedForTheRoster:SplitDomainResolution.resolveTableOrThrow"),
                "the planted site must be seen: " + sites);
        assertTrue(
                sites.contains("exec/ReadValue#evaluate:SplitDomainResolution.resolveTableOrThrow"),
                "and the real one beside it: " + sites);
    }

    // ---------------------------------------------------------------- the scan


    /** Every site under {@code root}: {@code <path>#<method>:<kind>}. */
    static Set<String> scan(Path root) throws IOException
    {
        Set<String> sites = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root))
        {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList())
            {
                if (p.getFileName().toString().equals("SplitDomainResolution.java"))
                {
                    continue; // the seam itself: its internals are SplitDomainResolutionTest's
                }
                String rel = root.relativize(p).toString().replace('\\', '/');
                rel = rel.substring(0, rel.length() - ".java".length());
                String stripped = stripCommentsAndLiterals(
                        Files.readString(p, StandardCharsets.UTF_8));
                String[] lines = stripped.split("\n", -1);
                Matcher m = CALL.matcher(stripped);
                while (m.find())
                {
                    if (m.group(1) == null && !datasetResolverReceiver(stripped, m.group(3)))
                    {
                        continue; // some other type's resolve (a Path, a VlmResolver, ...)
                    }
                    String kind = m.group(1) != null ? m.group(1) + "." + m.group(2)
                            : m.group(3) + "." + m.group(4);
                    int line = lineOf(stripped, m.start());
                    sites.add(rel + "#" + enclosingMethod(lines, line) + ":" + kind);
                }
            }
        }
        return sites;
    }


    /** The zero-based line of {@code offset} in {@code text}. */
    private static int lineOf(String text, int offset)
    {
        int line = 0;
        for (int i = 0; i < offset; i++)
        {
            if (text.charAt(i) == '\n')
            {
                line++;
            }
        }
        return line;
    }


    /** The nearest declaration above line {@code i} with a smaller indent. */
    private static String enclosingMethod(String[] lines, int i)
    {
        int indent = indentOf(lines[i]);
        for (int j = i - 1; j >= 0; j--)
        {
            String line = lines[j];
            if (line.isBlank() || indentOf(line) >= indent)
            {
                continue;
            }
            if (EXCLUDED_LEAD.matcher(line).find())
            {
                continue;
            }
            Matcher d = DECLARATION.matcher(line);
            if (d.find())
            {
                return d.group(2);
            }
        }
        return "?";
    }


    private static int indentOf(String line)
    {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ')
        {
            n++;
        }
        return n;
    }


    /**
     * The module's {@code src/main/java/net/cumba/corej/core}: the working directory itself when
     * surefire runs the module, else the engine module under a repository root walked up to — named
     * {@code lib/cumba-corej-core} internally and {@code lib/cumba-oss-corej-core} in the OSS twin
     * (the test is byte-identical in both).
     */
    private static Path findMain()
    {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null)
        {
            for (String module : List.of("", "lib/cumba-corej-core", "lib/cumba-oss-corej-core"))
            {
                Path candidate = dir.resolve(module).resolve("src").resolve("main").resolve("java")
                        .resolve("net").resolve("cumba").resolve("corej").resolve("core");
                if (Files.isDirectory(candidate.resolve("exec")))
                {
                    return candidate;
                }
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "engine sources not found from " + Path.of("").toAbsolutePath());
    }


    /** Blanks comments and the inside of string / char literals, keeping line structure. */
    private static String stripCommentsAndLiterals(String aSource)
    {
        char[] out = aSource.toCharArray();
        int n = out.length;
        int i = 0;
        while (i < n)
        {
            char c = aSource.charAt(i);
            if (c == '/' && i + 1 < n && aSource.charAt(i + 1) == '/')
            {
                int end = aSource.indexOf('\n', i);
                end = end < 0 ? n : end;
                blank(out, i, end);
                i = end;
            }
            else if (c == '/' && i + 1 < n && aSource.charAt(i + 1) == '*')
            {
                int end = aSource.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                blank(out, i, end);
                i = end;
            }
            else if (c == '"' && i + 2 < n && aSource.charAt(i + 1) == '"'
                    && aSource.charAt(i + 2) == '"')
            {
                int end = aSource.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                blank(out, i, end);
                i = end;
            }
            else if (c == '"' || c == '\'')
            {
                int end = literalEnd(aSource, i, c);
                blank(out, i + 1, end - 1);
                i = end;
            }
            else
            {
                i++;
            }
        }
        return new String(out);
    }


    private static void blank(char[] aOut, int aFrom, int aTo)
    {
        for (int k = Math.max(aFrom, 0); k < Math.min(aTo, aOut.length); k++)
        {
            if (aOut[k] != '\n')
            {
                aOut[k] = ' ';
            }
        }
    }


    private static int literalEnd(String aSource, int aStart, char aQuote)
    {
        int j = aStart + 1;
        while (j < aSource.length() && aSource.charAt(j) != aQuote)
        {
            if (aSource.charAt(j) == '\\')
            {
                j++;
            }
            j++;
        }
        return Math.min(j + 1, aSource.length());
    }
}
