package net.cumba.corej.define.conformance.xsd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.cumba.corej.define.conformance.report.ConformanceFinding;
import net.cumba.corej.define.conformance.report.Severity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The pre-pass classifies the same Define-XML identically whatever the JVM's default locale.
 *
 * <p>
 * JDK Xerces localises its messages to the default locale, and its {@code locale} property is
 * accepted and ignored (measured on both {@code Validator} and a schema-bearing {@code XMLReader}),
 * so English cannot be forced. {@link SaxErrorClassifier} therefore must not read English prose: on
 * a German machine it used to, and an invalid integer or datetime fell through to the
 * Reject-severity catch-all {@code PMDA-DD0001} instead of its Warning rule. CI runs in English, so
 * only a forced locale can see that.
 * </p>
 */
class DefinePrePassLocaleTest
{

    private static ConformanceFinding soleFinding(String aFixture, Locale aLocale)
    {
        // All three: setDefault(Locale) also resets FORMAT and DISPLAY, which a Windows JVM with
        // regional settings different from its UI language holds separately.
        Locale saved = Locale.getDefault();
        Locale savedFormat = Locale.getDefault(Locale.Category.FORMAT);
        Locale savedDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        try (InputStream in = DefinePrePassLocaleTest.class
                .getResourceAsStream("/fixtures/" + aFixture))
        {
            Locale.setDefault(aLocale);
            List<ConformanceFinding> findings = DefinePrePass
                    .run(Objects.requireNonNull(in, aFixture).readAllBytes()).findings();
            assertEquals(1, findings.size(), () -> aLocale + ": " + findings);
            return findings.get(0);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("cannot load fixture " + aFixture, e);
        }
        finally
        {
            Locale.setDefault(saved);
            Locale.setDefault(Locale.Category.FORMAT, savedFormat);
            Locale.setDefault(Locale.Category.DISPLAY, savedDisplay);
        }
    }


    @ParameterizedTest
    @ValueSource(strings =
    {
            "en", "de", "fr", "es", "it", "ja", "ko", "pt-BR", "sv", "zh-CN", "zh-TW"
    })
    void anInvalidIntegerIsOd0013InEveryLocale(String aLanguageTag)
    {
        ConformanceFinding finding = soleFinding("xsd-invalid-integer-21.xml",
                Locale.forLanguageTag(aLanguageTag));
        assertEquals("PMDA-OD0013", finding.getRuleId(), finding::getMessage);
        assertEquals(Severity.WARNING, finding.getSeverity());
        assertTrue(finding.getMessage().contains("OrderNumber"), finding.getMessage());
    }


    @ParameterizedTest
    @ValueSource(strings =
    {
            "en", "de", "fr", "es", "it", "ja", "ko", "pt-BR", "sv", "zh-CN", "zh-TW"
    })
    void anInvalidDatetimeIsOd0017InEveryLocale(String aLanguageTag)
    {
        ConformanceFinding finding = soleFinding("xsd-invalid-datetime-21.xml",
                Locale.forLanguageTag(aLanguageTag));
        assertEquals("PMDA-OD0017", finding.getRuleId(), finding::getMessage);
        assertEquals(Severity.WARNING, finding.getSeverity());
        assertTrue(finding.getMessage().contains("CreationDateTime"), finding.getMessage());
    }
}
