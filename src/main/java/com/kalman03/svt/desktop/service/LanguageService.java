package com.kalman03.svt.desktop.service;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.NodeOrientation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/** 桌面端语言服务，语言代码与 PC Web 端保持一致。 */
@Slf4j
@Service
public class LanguageService {

    public static final Locale ENGLISH = Locale.ENGLISH;
    public static final Locale CHINESE = Locale.SIMPLIFIED_CHINESE;
    public static final Locale VIETNAMESE = Locale.forLanguageTag("vi-VN");

    private static final List<SupportedLanguage> SUPPORTED_LANGUAGES = List.of(
            new SupportedLanguage("en", ENGLISH, "English", "EN", false),
            new SupportedLanguage("ar", Locale.forLanguageTag("ar"), "العربية", "AR", true),
            new SupportedLanguage("br", Locale.forLanguageTag("pt-BR"), "Português (Brasil)", "PT", false),
            new SupportedLanguage("cn", CHINESE, "简体中文", "简", false),
            new SupportedLanguage("da", Locale.forLanguageTag("da"), "Dansk", "DA", false),
            new SupportedLanguage("de", Locale.GERMAN, "Deutsch", "DE", false),
            new SupportedLanguage("el", Locale.forLanguageTag("el"), "Ελληνικά", "EL", false),
            new SupportedLanguage("es", Locale.forLanguageTag("es"), "Español", "ES", false),
            new SupportedLanguage("fi", Locale.forLanguageTag("fi"), "Suomi", "FI", false),
            new SupportedLanguage("fr", Locale.FRENCH, "Français", "FR", false),
            new SupportedLanguage("he", Locale.forLanguageTag("he"), "עברית", "HE", true),
            new SupportedLanguage("hi", Locale.forLanguageTag("hi"), "हिन्दी", "HI", false),
            new SupportedLanguage("hu", Locale.forLanguageTag("hu"), "Magyar", "HU", false),
            new SupportedLanguage("id", Locale.forLanguageTag("id"), "Bahasa Indonesia", "ID", false),
            new SupportedLanguage("it", Locale.ITALIAN, "Italiano", "IT", false),
            new SupportedLanguage("jp", Locale.JAPANESE, "日本語", "日", false),
            new SupportedLanguage("kr", Locale.KOREAN, "한국어", "한", false),
            new SupportedLanguage("ms", Locale.forLanguageTag("ms"), "Bahasa Melayu", "MS", false),
            new SupportedLanguage("nb", Locale.forLanguageTag("nb"), "Norsk bokmål", "NO", false),
            new SupportedLanguage("nl", Locale.forLanguageTag("nl"), "Nederlands", "NL", false),
            new SupportedLanguage("pl", Locale.forLanguageTag("pl"), "Polski", "PL", false),
            new SupportedLanguage("ro", Locale.forLanguageTag("ro"), "Română", "RO", false),
            new SupportedLanguage("ru", Locale.forLanguageTag("ru"), "Русский", "RU", false),
            new SupportedLanguage("sv", Locale.forLanguageTag("sv"), "Svenska", "SV", false),
            new SupportedLanguage("th", Locale.forLanguageTag("th"), "ไทย", "TH", false),
            new SupportedLanguage("tr", Locale.forLanguageTag("tr"), "Türkçe", "TR", false),
            new SupportedLanguage("tw", Locale.forLanguageTag("zh-TW"), "繁體中文", "繁", false),
            new SupportedLanguage("uk", Locale.forLanguageTag("uk"), "Українська", "UK", false),
            new SupportedLanguage("vi", VIETNAMESE, "Tiếng Việt", "VI", false));

    private static final Map<String, String> LEGACY_LANGUAGE_CODES = Map.of(
            "zh", "cn", "zh-cn", "cn", "zh-tw", "tw", "pt", "br",
            "pt-br", "br", "ja", "jp", "ko", "kr", "no", "nb");
    private static final String CONFIG_DIR = System.getProperty("user.home") + File.separator + ".snapvideotools";
    private static final String CONFIG_FILE = CONFIG_DIR + File.separator + "settings.properties";
    private static final String LANGUAGE_KEY = "language";

    private final ObjectProperty<Locale> currentLocale;
    private final Map<Object, Runnable> languageChangeListeners = new LinkedHashMap<>();
    private SupportedLanguage currentLanguage;
    private ResourceBundle bundle;
    private ResourceBundle englishBundle;

    public LanguageService() {
        englishBundle = loadBundle("en");
        currentLanguage = loadSavedLanguage();
        currentLocale = new SimpleObjectProperty<>(currentLanguage.locale());
        bundle = loadBundle(currentLanguage.code());
        log.info("Language initialized: {}", currentLanguage.code());
    }

    public List<SupportedLanguage> getSupportedLanguages() {
        return SUPPORTED_LANGUAGES;
    }

    public SupportedLanguage getCurrentLanguage() {
        return currentLanguage;
    }

    private SupportedLanguage loadSavedLanguage() {
        try {
            File configFile = new File(CONFIG_FILE);
            if (configFile.exists()) {
                Properties props = new Properties();
                try (FileInputStream input = new FileInputStream(configFile)) {
                    props.load(input);
                }
                SupportedLanguage saved = findByCode(props.getProperty(LANGUAGE_KEY));
                if (saved != null) {
                    return saved;
                }
            }
        } catch (Exception exception) {
            log.warn("Failed to load saved language setting", exception);
        }
        return detectSystemLanguage();
    }

    private void saveLanguage(SupportedLanguage language) {
        try {
            File configDir = new File(CONFIG_DIR);
            if (!configDir.exists() && !configDir.mkdirs()) {
                log.warn("Failed to create settings directory: {}", CONFIG_DIR);
            }
            Properties props = new Properties();
            File configFile = new File(CONFIG_FILE);
            if (configFile.exists()) {
                try (FileInputStream input = new FileInputStream(configFile)) {
                    props.load(input);
                }
            }
            props.setProperty(LANGUAGE_KEY, language.code());
            try (FileOutputStream output = new FileOutputStream(configFile)) {
                props.store(output, "SnapVideoTools Settings");
            }
        } catch (Exception exception) {
            log.error("Failed to save language setting", exception);
        }
    }

    private SupportedLanguage detectSystemLanguage() {
        Locale systemLocale = Locale.getDefault();
        String languageTag = systemLocale.toLanguageTag().toLowerCase(Locale.ROOT);
        if (languageTag.startsWith("zh-tw") || languageTag.startsWith("zh-hk")
                || languageTag.startsWith("zh-hant")) {
            return findByCode("tw");
        }
        if (languageTag.startsWith("pt-br")) {
            return findByCode("br");
        }
        return SUPPORTED_LANGUAGES.stream()
                .filter(language -> language.locale().getLanguage().equals(systemLocale.getLanguage()))
                .findFirst().orElse(SUPPORTED_LANGUAGES.getFirst());
    }

    private ResourceBundle loadBundle(String code) {
        String resourcePath = "/i18n/messages_" + code + ".properties";
        try (InputStream input = getClass().getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("Resource bundle not found: " + resourcePath);
            }
            return new PropertyResourceBundle(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            if (!"en".equals(code) && englishBundle != null) {
                log.warn("Failed to load resource bundle {}, falling back to English", resourcePath);
                return englishBundle;
            }
            throw new IllegalStateException("Unable to load English resource bundle", exception);
        }
    }

    public Locale getCurrentLocale() {
        return currentLocale.get();
    }

    public ObjectProperty<Locale> currentLocaleProperty() {
        return currentLocale;
    }

    public void toggleLanguage() {
        int currentIndex = SUPPORTED_LANGUAGES.indexOf(currentLanguage);
        setLanguage(SUPPORTED_LANGUAGES.get((currentIndex + 1) % SUPPORTED_LANGUAGES.size()));
    }

    public void setLocale(Locale locale) {
        if (locale == null) {
            return;
        }
        SupportedLanguage match = SUPPORTED_LANGUAGES.stream()
                .filter(language -> language.locale().equals(locale))
                .findFirst()
                .orElseGet(() -> SUPPORTED_LANGUAGES.stream()
                        .filter(language -> language.locale().getLanguage().equals(locale.getLanguage()))
                        .findFirst().orElse(null));
        if (match != null) {
            setLanguage(match);
        }
    }

    public void setLanguage(SupportedLanguage language) {
        if (language == null || language.equals(currentLanguage)) {
            return;
        }
        currentLanguage = language;
        bundle = loadBundle(language.code());
        currentLocale.set(language.locale());
        saveLanguage(language);
        notifyListeners();
        log.info("Language changed to: {}", language.code());
    }

    public String get(String key) {
        return getPattern(key);
    }

    public String get(String key, Object... args) {
        try {
            return String.format(currentLocale.get(), getPattern(key), args);
        } catch (Exception exception) {
            log.warn("Failed to format translation for key: {}", key, exception);
            return getPattern(key);
        }
    }

    private String getPattern(String key) {
        try {
            if (bundle != null && bundle.containsKey(key)) {
                return bundle.getString(key);
            }
            if (englishBundle.containsKey(key)) {
                log.debug("Using English fallback for translation key: {} ({})", key, currentLanguage.code());
                return englishBundle.getString(key);
            }
        } catch (Exception exception) {
            log.warn("Missing translation for key: {}", key, exception);
        }
        return key;
    }

    public boolean isEnglish() {
        return "en".equals(currentLanguage.code());
    }

    public boolean isChinese() {
        return "cn".equals(currentLanguage.code());
    }

    public boolean isVietnamese() {
        return "vi".equals(currentLanguage.code());
    }

    public boolean isRightToLeft() {
        return currentLanguage.rightToLeft();
    }

    public NodeOrientation getNodeOrientation() {
        return isRightToLeft() ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT;
    }

    public String getLanguageCode() {
        return currentLanguage.shortLabel();
    }

    public void addLanguageChangeListener(Object owner, Runnable listener) {
        if (owner != null && listener != null) {
            languageChangeListeners.put(owner, listener);
        }
    }

    public void removeLanguageChangeListener(Object owner) {
        languageChangeListeners.remove(owner);
    }

    private void notifyListeners() {
        for (Runnable listener : List.copyOf(languageChangeListeners.values())) {
            try {
                listener.run();
            } catch (Exception exception) {
                log.error("Error notifying language change listener", exception);
            }
        }
    }

    private SupportedLanguage findByCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return null;
        }
        String normalized = rawCode.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        normalized = LEGACY_LANGUAGE_CODES.getOrDefault(normalized, normalized);
        String code = normalized;
        return SUPPORTED_LANGUAGES.stream()
                .filter(language -> language.code().equals(code))
                .findFirst().orElse(null);
    }

    public record SupportedLanguage(String code, Locale locale, String nativeName, String shortLabel,
                                    boolean rightToLeft) {
        @Override
        public String toString() {
            return nativeName;
        }
    }
}
