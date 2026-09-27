package net.cumba.corej.core.exec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import net.cumba.datatable.values.GroupKey;
import net.cumba.datatable.values.GroupKeyPolicy.KeyPart;

/**
 * A test-side <b>rendered view</b> of a {@link GroupedResult}'s keys: each key as the
 * {@code "\0"}-joined {@link KeyPart#reportingForm()} of its components — the text the result was
 * keyed by until {@code PLAN-grouping-key-identity}. It lets the evaluator tests keep pinning
 * <em>which value each group got</em> in the readable form they were written in, while the result
 * itself keys on the identity ({@link GroupKey}).
 *
 * <p>
 * ⚠ A projection, and a lossy one on purpose: two identity keys that render alike (the noise pair
 * {@code 4.9999999999994} / {@code 5.0}) are exactly the collision that plan closed, so this view
 * <b>refuses</b> them rather than silently keeping one. A test about the identity itself asserts on
 * the {@link GroupKey} (or through {@link GroupedResult#getForRow}), never through this view.
 * </p>
 */
public final class GroupedResultTextView
{

    private GroupedResultTextView()
    {
    }


    /**
     * The rendered form of one key: a {@link GroupKey}'s components rendered and
     * {@code "\0"}-joined, a bare identity (or a {@link GroupedResult.KeyMode#TEXT} key) rendered
     * as its own component.
     *
     * @param aKey
     *            a key of {@link GroupedResult#results()}
     * @return its rendered form
     */
    public static String render(Object aKey)
    {
        if (aKey instanceof GroupKey key)
        {
            StringJoiner sj = new StringJoiner("\0");
            for (int i = 0; i < key.size(); i++)
            {
                sj.add(KeyPart.ofIdentity(key.get(i)).reportingForm());
            }
            return sj.toString();
        }
        return KeyPart.ofIdentity(aKey).reportingForm();
    }


    /**
     * The result's entries keyed by their {@linkplain #render rendered} keys, in the result's
     * order.
     *
     * @param aResult
     *            the result
     * @return the rendered view
     * @throws IllegalStateException
     *             when two keys render alike — the collision the identity keys exist to prevent
     */
    public static Map<String, Object> byText(GroupedResult aResult)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : aResult.results().entrySet())
        {
            String text = render(e.getKey());
            if (out.putIfAbsent(text, e.getValue()) != null)
            {
                throw new IllegalStateException("two keys render as " + text.replace("\0", "\\0")
                        + " -- assert on the identity keys instead");
            }
        }
        return out;
    }
}
