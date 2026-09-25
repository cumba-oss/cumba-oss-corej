package net.cumba.corej.core.report;

/**
 * Identifies one report output format offered by a {@link ReportWriterSupplier}.
 *
 * <h2>The name is the identity</h2>
 *
 * <p>
 * A report's persisted reference <em>is</em> its name — a CLI {@code --output-format} token, a REST
 * payload field — so this record deliberately carries no uuid: a second identity would be one
 * nothing reads. {@link ReportManager} routes on {@link #name()} alone, which is why two suppliers
 * claiming the same name are rejected at registration rather than resolved by iteration order.
 * </p>
 *
 * <h2>Suffix, not extension</h2>
 *
 * <p>
 * The v2 JSON report writes {@code <base>.v2.json}: the string appended to the shared output base
 * is {@code .v2.json}, so {@code json} and {@code json-2} share a file extension. Nothing selects a
 * format by extension — <b>the format argument is authoritative; never infer v1-vs-v2 from a file
 * name.</b> (⚑ The record carried a bare {@code fileExtension} and a {@code description} for CLI
 * help too; neither was read — PLAN-retire-dead-multi-match-lookup U12, C30.)
 * </p>
 *
 * @param name
 *            the format identity, e.g. {@code json}, {@code json-2}, {@code xlsx}; lower-case by
 *            convention and unique across all registered suppliers
 * @param fileSuffix
 *            the string appended to an output base to name the file, e.g. {@code .json},
 *            {@code .v2.json} or {@code .xlsx}
 */
public record ReportFormat(String name, String fileSuffix)
{

    /**
     * @throws IllegalArgumentException
     *             when any component is null or blank — a nameless format could never be routed to,
     *             and a blank suffix would silently overwrite the output base
     */
    public ReportFormat
    {
        requireText(name, "name");
        requireText(fileSuffix, "fileSuffix");
    }


    private static void requireText(String aValue, String aComponent)
    {
        if (aValue == null || aValue.isBlank())
        {
            throw new IllegalArgumentException("ReportFormat." + aComponent + " must not be blank");
        }
    }
}
