package net.cumba.corej.core.metadata;

import java.util.LinkedHashMap;
import java.util.Map;
import net.cumba.cdisc.define.CodeList;
import net.cumba.cdisc.define.CodeListItem;
import net.cumba.cdisc.define.Decode;
import net.cumba.cdisc.define.TranslatedText;
import org.jspecify.annotations.Nullable;

/**
 * Shared Define-XML {@code CodedValue → Decode} extraction (Fix #123).
 *
 * <p>
 * A Define-XML {@code CodeList} may carry its decode text on each {@code CodeListItem} (an
 * {@code EnumeratedItem} never does, and an {@code ExternalCodeList} has no items at all). Two
 * consumers need exactly the same reading of that structure:
 * </p>
 * <ul>
 * <li>{@link VlmResolver} — the <b>value-level</b> codelist behind
 * {@code define_vlm_decode_matches} (CT2003/CT2006), and</li>
 * <li>{@link OdmDefineXMLProvider} — the <b>variable-level</b> ItemDef codelist behind
 * {@code define_variable_decode_matches} (Fix #123).</li>
 * </ul>
 *
 * <p>
 * The logic lives here once so the two can never drift: a divergence would mean the same define
 * codelist yields different decode expectations depending on which rule reads it.
 * </p>
 */
public final class CodeListDecodes
{

    private CodeListDecodes()
    {
    }


    /**
     * The first {@code TranslatedText} value of a {@code Decode}, or {@code null} when the decode
     * is absent or carries no translated text. Only the first translation is used — Define-XML
     * allows several (one per language) and the engine has no locale to choose with, so the
     * document order decides, deterministically.
     *
     * @param decode
     *            the {@code Decode} element, may be {@code null}
     * @return the decode text, or {@code null}
     */
    public static @Nullable String decodeText(@Nullable Decode decode)
    {
        if (decode == null || decode.getTranslatedTexts() == null
                || decode.getTranslatedTexts().isEmpty())
        {
            return null;
        }
        TranslatedText tt = decode.getTranslatedTexts().get(0);
        return tt == null ? null : tt.getValue();
    }


    /**
     * The codelist's {@code CodedValue → Decode} mapping, built from {@code CodeListItem}s only
     * (each carries both a {@code CodedValue} and a {@code Decode}) so the pair is always aligned.
     * An item without a coded value or without decode text is skipped, so the map contains only
     * entries usable for a code/decode comparison.
     *
     * <p>
     * Returns an <b>empty</b> map for an {@code EnumeratedItem}-only codelist (coded values but no
     * decodes) and for an {@code ExternalCodeList} (a dictionary reference, no items) — in both
     * cases there is no decode expectation to check against.
     * </p>
     *
     * @param cl
     *            the codelist, may be {@code null}
     * @return coded value → decode text, never {@code null}
     */
    public static Map<String, String> codeDecodeMap(@Nullable CodeList cl)
    {
        Map<String, String> out = new LinkedHashMap<>();
        if (cl == null || cl.getCodeListItems() == null)
        {
            return out;
        }
        for (CodeListItem it : cl.getCodeListItems())
        {
            if (it == null)
            {
                continue;
            }
            String decode = decodeText(it.getDecode());
            if (it.getCodedValue() != null && decode != null)
            {
                out.put(it.getCodedValue(), decode);
            }
        }
        return out;
    }

}
