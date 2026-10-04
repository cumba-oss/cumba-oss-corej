package net.cumba.corej.core.exec;

import net.cumba.corej.core.expr.BuiltinRegistry;
import net.cumba.corej.core.expr.typed.ExprType;
import net.cumba.corej.core.expr.typed.StageAChecker;
import net.cumba.datatable.values.MissingValue;

/**
 * What an effective {@code Output_Variables} entry <em>is</em>, and therefore what a finding
 * reports for it when it resolves to <b>no value</b>
 * ({@code PLAN-output-variable-null-key-provenance} §2.1, owner 2026-10-04: <i>"nothing can be null
 * … the variable based default absence value (empty string on char or MissingValue MIS on
 * num)"</i>).
 *
 * <p>
 * The three cases of §2.1, by kind:
 * </p>
 * <ul>
 * <li>a {@link #COLUMN} entry — a raw column name (bare, dot-qualified or {@code --}-prefixed) or
 * {@code variable_value} — is decided by the <em>table</em>: an absent column is <b>omitted</b>
 * from the finding (case 1), a present column's missing cell reports that cell's own
 * {@link MissingValue} (case 2), and a present column on a finding that has no row (a column-level
 * finding) is omitted too — the deferred state {@code PLAN-finding-values-record-level-only} gives
 * a name-only form (the corpus roster {@code nullkey/present-column-omitted.tsv} lists it); never a
 * {@code null} key;</li>
 * <li>a {@link #BINDING} ({@code $name}) is decided by its <em>evaluated</em> type
 * ({@code BindingValue.reportedHandOver});</li>
 * <li>a metadata attribute or dataset virtual carries the typed default of its <em>static</em>
 * type: {@link #CHAR} {@code ""}, {@link #NUM} / {@link #BOOLEAN} / {@link #LIST}
 * {@code MissingValue.MIS} (H3: {@code ""} is a char value, so only a char entry may print it; D36
 * — a missing boolean is a {@code MissingValue}).</li>
 * </ul>
 *
 * <p>
 * <b>One type source.</b> The static type is {@link StageAChecker#builtinOperandType} — the type
 * the Check itself sees for the bare operand — so a finding's default can never disagree with what
 * the rule compared against. Nothing here invents a type: a name that is neither a builtin nor a
 * metadata / dataset-scope prefix is a column, and the table decides.
 * </p>
 */
public enum OutputEntryKind
{

    /** {@code variable_name} — the column cursor, always resolved. */
    NAME,
    /** A raw column name or {@code variable_value}: the table decides (§2.1 cases 1 and 2). */
    COLUMN,
    /** A {@code $} binding: its evaluated type decides. */
    BINDING,
    /** A string-valued metadata attribute or dataset virtual; the default is {@code ""}. */
    CHAR,
    /**
     * A numeric metadata attribute ({@code *_length}, {@code *_ordinal}, {@code record_count}, …).
     */
    NUM,
    /**
     * A boolean metadata attribute ({@code *_has_*}, {@code *_extensible}, {@code *_matches}, …).
     */
    BOOLEAN,
    /** A list-valued metadata attribute ({@code *_values}, {@code *_codes}). */
    LIST;

    /** The {@code variable_name} entry. */
    public static final String VARIABLE_NAME = "variable_name";

    /** The {@code variable_value} entry. */
    public static final String VARIABLE_VALUE = "variable_value";

    /**
     * The kind of an effective {@code Output_Variables} entry (an include entry, never {@code !X}).
     */
    public static OutputEntryKind of(String entry)
    {
        if (entry.startsWith("$"))
        {
            return BINDING;
        }
        if (VARIABLE_NAME.equals(entry))
        {
            return NAME;
        }
        if (VARIABLE_VALUE.equals(entry))
        {
            return COLUMN;
        }
        if (!isMetadataOrVirtual(entry))
        {
            return COLUMN;
        }
        ExprType type = StageAChecker.builtinOperandType(entry);
        if (type == ExprType.Primitive.NUMBER)
        {
            return NUM;
        }
        if (type == ExprType.Primitive.BOOLEAN)
        {
            return BOOLEAN;
        }
        if (type instanceof ExprType.ListOf)
        {
            return LIST;
        }
        return CHAR;
    }


    /**
     * Whether {@code entry} names a metadata attribute of the current variable ({@code variable_*},
     * {@code library_variable_*}, {@code define_variable_*}, {@code define_vlm_*}) or a
     * dataset-scope virtual ({@code dataset_*}, {@code library_dataset_*},
     * {@code define_dataset_*}, {@code record_count}) — the registered builtins plus every key the
     * per-variable projection copies under those prefixes from a provider map.
     */
    public static boolean isMetadataOrVirtual(String entry)
    {
        return BuiltinRegistry.isBuiltin(entry) || isVariableMetadata(entry)
                || isDatasetVirtual(entry);
    }


    /** A metadata attribute of the current variable (see {@link #isMetadataOrVirtual}). */
    public static boolean isVariableMetadata(String entry)
    {
        return entry.startsWith("variable_") || entry.startsWith("library_variable_")
                || entry.startsWith("define_variable_") || entry.startsWith("define_vlm_");
    }


    /** A dataset-scope virtual (see {@link #isMetadataOrVirtual}). */
    public static boolean isDatasetVirtual(String entry)
    {
        return "record_count".equals(entry) || entry.startsWith("dataset_")
                || entry.startsWith("library_dataset_") || entry.startsWith("define_dataset_");
    }


    /**
     * The default absence value of a typed entry — {@code ""} for {@link #CHAR}, the
     * {@link MissingValue#MIS} marker ({@code .}) for {@link #NUM}, {@link #BOOLEAN} and
     * {@link #LIST}.
     *
     * @throws IllegalStateException
     *             for {@link #NAME}, {@link #COLUMN} and {@link #BINDING}, whose value the table or
     *             the binding decides — a caller asking here has lost that type source
     */
    public String defaultValue()
    {
        return switch (this)
        {
        case CHAR -> "";
        case NUM, BOOLEAN, LIST -> MissingValue.MIS.toString();
        case NAME, COLUMN, BINDING -> throw new IllegalStateException(
                this + " has no static default — the table or the binding decides its value");
        };
    }


    /**
     * The default absence value of a {@code $} binding or a column cell of the given stored type:
     * {@code ""} for a string, the {@code MIS} marker for every other type (number, boolean,
     * complex, …).
     */
    public static String defaultValue(net.cumba.datatable.values.DataValueType type)
    {
        return type == net.cumba.datatable.values.DataValueType.STRING ? ""
                : MissingValue.MIS.toString();
    }
}
