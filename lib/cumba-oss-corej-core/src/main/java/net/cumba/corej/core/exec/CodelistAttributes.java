package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.ProviderNeed;
import net.cumba.corej.core.expr.eval.UnusableProviderAnswerException;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * The registry function {@code get_codelist_attributes(name, version, ct_attribute=)} — the
 * list-valued exemplar of wave 0 ({@code PLAN-binding-expressions} §3.1 / phase 3), ported from the
 * retired {@code GET_CODELIST_ATTRIBUTES} operation ({@code CDISC-CG0288}).
 *
 * <p>
 * Per row it derives a CT package id from the target value ({@code TSVCDREF}, e.g.
 * {@code "CDISC CT"}) and the version value ({@code TSVCDVER}) — {@link #ctPackageId} — and unions
 * {@code ct_attribute} (e.g. {@code "Term CCODE"}) across every distinct package the rows resolve
 * to, order-preserving and deduplicated. The result is ONE list broadcast to every row
 * ({@link ConstVector}, option 2 of §3.1): the function is an <b>aggregate</b> (SPEC §1.4 — it
 * folds the rows of its operands), so its binding is dataset-level exactly as the operation's
 * scalar result was.
 * </p>
 *
 * <p>
 * ⭐ <b>Provider capability {@link ProviderNeed#LIBRARY}.</b> It never answers an empty list: with
 * no provider, with a Library that could not be consulted (Fix #369), or with nothing resolved (an
 * unknown / absent CT package, an absent target or version column — both fold to {@code ""}, so no
 * package resolves) it raises {@link UnusableProviderAnswerException}, which {@code RuleRunner}
 * turns into {@code SKIPPED} — the retired operation's library-not-available SKIP, unchanged.
 * Answering {@code []} instead would let {@code not empty($VALID_TERM_CODES)} read {@code false}
 * and the rule PASS.
 * </p>
 */
public final class CodelistAttributes
{

    /** The function name as authored. */
    public static final String NAME = "get_codelist_attributes";

    private CodelistAttributes()
    {
    }


    /**
     * The {@code EvalFunction} body: {@code args} are the bound {@code name}, {@code version} and
     * {@code ct_attribute} vectors.
     *
     * @param run
     *            the evaluation run
     * @param args
     *            the bound argument vectors
     * @return the broadcast attribute list
     * @throws UnusableProviderAnswerException
     *             when the Library cannot answer or answers nothing usable
     */
    public static Vector evaluate(EvalRun run, List<Vector> args)
    {
        MetadataProvider provider = run.ctx().getLibraryProvider();
        if (provider == null)
        {
            throw new UnusableProviderAnswerException(NAME, ProviderNeed.Kind.LIBRARY,
                    "no CDISC Library provider");
        }
        if (provider.isLibraryUnavailable() && !LibraryAnswerability.libraryAnswerable(provider))
        {
            throw new UnusableProviderAnswerException(NAME, ProviderNeed.Kind.LIBRARY,
                    "the CDISC Library could not be consulted");
        }
        if (run.rowCount() == 0)
        {
            // No row resolves a package — the operation's empty result, and its SKIP.
            throw new UnusableProviderAnswerException(NAME, ProviderNeed.Kind.LIBRARY,
                    "no rows to resolve a CT package from");
        }
        String attribute = args.get(2).value(0).cell().getValueAsString();
        List<String> values = resolve(provider, args.get(0), args.get(1), attribute,
                run.rowCount());
        if (values.isEmpty())
        {
            throw new UnusableProviderAnswerException(NAME, ProviderNeed.Kind.LIBRARY,
                    "no codelist attributes resolved for " + attribute);
        }
        return ConstVector.of(values, () -> NAME + "(ct_attribute=\"" + attribute + "\")");
    }


    /**
     * The union of {@code attribute} over every distinct CT package the rows resolve to, in row
     * order. Mirrors the retired operation arm exactly: a missing target or version cell reads as
     * {@code ""}.
     *
     * @param provider
     *            the Library provider
     * @param target
     *            the target values ({@code TSVCDREF})
     * @param version
     *            the version values ({@code TSVCDVER})
     * @param attribute
     *            the CT attribute to extract
     * @param rowCount
     *            the rows to read
     * @return the attribute values; empty when nothing resolves
     */
    static List<String> resolve(MetadataProvider provider, Vector target, Vector version,
            String attribute, int rowCount)
    {
        String standard = provider.getStandard();
        List<String> packageIds = new ArrayList<>();
        for (int r = 0; r < rowCount; r++)
        {
            String pkgId = ctPackageId(text(target.value(r).cell()), text(version.value(r).cell()),
                    standard);
            if (pkgId != null && !packageIds.contains(pkgId))
            {
                packageIds.add(pkgId);
            }
        }
        // Order-preserving union with O(1) membership across the (typically one) resolved packages.
        Set<String> out = new LinkedHashSet<>();
        for (String pkgId : packageIds)
        {
            out.addAll(provider.getCodelistAttribute(pkgId, attribute));
        }
        // Not List.copyOf: it throws a bare NullPointerException on a null element, naming
        // nothing, before the list reaches ConstVector.of's ListValueGuard, which names this
        // function (register NNL §1; PLAN-no-null-list-elements review round 1, LOW-1).
        return java.util.Collections.unmodifiableList(new ArrayList<>(out));
    }


    private static String text(IDataValue cell)
    {
        return cell.isMissingOrInvalid() ? "" : cell.getValueAsString();
    }


    /**
     * Derives a CT package id from the row's target value, version value and the active standard,
     * matching the reference engine's {@code _get_ct_package}. Returns {@code null} when the
     * version is blank.
     *
     * @param aTargetVal
     *            the target value
     * @param aVersionVal
     *            the version value
     * @param aStandard
     *            the active standard, or {@code null}
     * @return the package id, or {@code null}
     */
    static @Nullable String ctPackageId(String aTargetVal, String aVersionVal,
            @Nullable String aStandard)
    {
        String versionText = aVersionVal.strip();
        if (versionText.isEmpty())
        {
            return null;
        }
        String targetText = aTargetVal.strip();
        if ("CDISC".equals(targetText) || "CDISC CT".equals(targetText))
        {
            String std = aStandard == null ? "" : aStandard.toLowerCase(Locale.ROOT);
            // (TIG substandard handling is not exercised by any shipping rule; the standard name is
            // used directly, matching the non-TIG branch of the reference logic.)
            String prefix;
            if (std.contains("adam"))
            {
                prefix = "adamct";
            }
            else if (std.contains("send"))
            {
                prefix = "sendct";
            }
            else
            {
                prefix = "sdtmct";
            }
            return prefix + "-" + versionText;
        }
        return targetText + "-" + versionText;
    }

}
