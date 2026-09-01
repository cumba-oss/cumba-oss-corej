package net.cumba.cdisc.core.report;

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
 * <h2>Why extension and suffix are separate</h2>
 *
 * <p>
 * The v2 JSON report writes {@code <base>.v2.json}: its file <em>extension</em> is still
 * {@code json} (that is what a file filter matches on), but the string appended to the shared
 * output base is {@code .v2.json}. One field cannot serve both. Note that {@code json} and
 * {@code json-2} therefore share an extension — that is expected, not a defect, because nothing
 * selects a format by extension. <b>The format argument is authoritative; never infer v1-vs-v2 from
 * a file name.</b>
 * </p>
 *
 * @param name
 *            the format identity, e.g. {@code json}, {@code json-2}, {@code xlsx}; lower-case by
 *            convention and unique across all registered suppliers
 * @param description
 *            a human-readable one-liner for CLI help and UI pickers
 * @param fileExtension
 *            the bare file extension without a dot, e.g. {@code json} or {@code xlsx}; used for
 *            filtering only
 * @param fileSuffix
 *            the string appended to an output base to name the file, e.g. {@code .json},
 *            {@code .v2.json} or {@code .xlsx}
 */
public record ReportFormat(String name, String description, String fileExtension, String fileSuffix)
{

    /**
     * @throws IllegalArgumentException
     *             when any component is null or blank — a nameless format could never be routed to,
     *             and a blank suffix would silently overwrite the output base
     */
    public ReportFormat
    {
        requireText(name, "name");
        requireText(description, "description");
        requireText(fileExtension, "fileExtension");
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
