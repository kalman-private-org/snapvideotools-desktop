package com.kalman03.svt.desktop.service;

import static org.apache.commons.lang3.StringUtils.isBlank;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.kalman03.svt.desktop.util.HttpClientUtil;
import com.kalman03.svt.desktop.util.HttpClientUtil.HttpResult;
import com.kalman03.svt.desktop.util.HttpClientUtil.JsonResponse;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 管理标准设备授权、Bearer 访问令牌和轮换刷新令牌。
 *
 * @author Codex
 * @since 2026-08-17
 */
@Slf4j
@Service
public class AuthService {

    @Value("${app.api.base-url:https://snapvideotools.com}")
    private String apiBaseUrl;

    private static final String CONFIG_DIR = System.getProperty("user.home") + File.separator + ".snapvideotools";
    private static final String CONFIG_FILE = CONFIG_DIR + File.separator + "settings.properties";
    private static final String ACCESS_TOKEN_KEY = "desktop_access_token";
    private static final String REFRESH_TOKEN_KEY = "desktop_refresh_token";
    private static final String ACCESS_EXPIRES_AT_KEY = "desktop_access_expires_at";

    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile long accessExpiresAt;
    private volatile AccountInfo accountInfo;
    private Runnable onUnauthorized;
    private Consumer<String> onSubscriptionRequired;
    private final AtomicLong lastSubscriptionPromptAt = new AtomicLong(0);
    private final ConcurrentHashMap<Object, Runnable> accountListeners = new ConcurrentHashMap<>();

    public AuthService() {
        loadTokens();
    }

    @PostConstruct
    public void init() {
        HttpClientUtil.setTokenProvider(this::getAccessToken);
    }

    public void setOnUnauthorized(Runnable callback) {
        this.onUnauthorized = callback;
    }

    public void setOnSubscriptionRequired(Consumer<String> callback) {
        this.onSubscriptionRequired = callback;
    }

    public void addAccountChangeListener(Object owner, Runnable listener) {
        if (owner != null && listener != null) {
            accountListeners.put(owner, listener);
        }
    }

    public AccountInfo getAccountInfo() {
        return accountInfo;
    }

    public boolean isLoggedIn() {
        if (isBlank(accessToken) && isBlank(refreshToken)) {
            return false;
        }
        if ((isBlank(accessToken) || System.currentTimeMillis() >= accessExpiresAt - 60_000)
                && !refreshAccessToken()) {
            clearTokens();
            return false;
        }
        return refreshAccountInfo();
    }

    public DeviceAuthorization beginDeviceAuthorization() {
        HttpResult result = HttpClientUtil.postJson(getApiOrigin() + "/desktop/api/auth/device", java.util.Map.of());
        JsonResponse<DeviceAuthorization> response = result == null ? null
                : result.toJsonResponse(DeviceAuthorization.class);
        return response != null && response.isSuccess() ? response.getData() : null;
    }

    public PollTokenResult pollDeviceToken(String deviceCode) {
        HttpResult result = HttpClientUtil.postJson(getApiOrigin() + "/desktop/api/auth/token",
                java.util.Map.of("deviceCode", deviceCode));
        if (result == null) {
            return new PollTokenResult("network_error", null);
        }
        JsonResponse<TokenPair> response = result.toJsonResponse(TokenPair.class);
        if (response != null && response.isSuccess() && response.getData() != null) {
            saveTokenPair(response.getData());
            refreshAccountInfo();
            return new PollTokenResult(null, response.getData());
        }
        return new PollTokenResult(response == null ? "invalid_response" : response.getMessage(), null);
    }

    public synchronized boolean refreshAccessToken() {
        if (isBlank(refreshToken)) {
            return false;
        }
        HttpResult result = HttpClientUtil.postJson(getApiOrigin() + "/desktop/api/auth/refresh",
                java.util.Map.of("refreshToken", refreshToken));
        JsonResponse<TokenPair> response = result == null ? null : result.toJsonResponse(TokenPair.class);
        if (response == null || !response.isSuccess() || response.getData() == null) {
            return false;
        }
        saveTokenPair(response.getData());
        return true;
    }

    public boolean refreshAccountInfo() {
        HttpResult result = HttpClientUtil.get(getApiOrigin() + "/desktop/api/user/info");
        if (result != null && result.isUnauthorized() && refreshAccessToken()) {
            result = HttpClientUtil.get(getApiOrigin() + "/desktop/api/user/info");
        }
        JsonResponse<AccountInfo> response = result == null ? null : result.toJsonResponse(AccountInfo.class);
        if (response == null || !response.isSuccess() || response.getData() == null) {
            return false;
        }
        accountInfo = response.getData();
        notifyAccountChanged();
        return true;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void handleUnauthorized() {
        if (refreshAccessToken()) {
            return;
        }
        clearTokens();
        if (onUnauthorized != null) {
            onUnauthorized.run();
        }
    }

    public void handleSubscriptionRequired(String message) {
        if (onSubscriptionRequired == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSubscriptionPromptAt.get() < 5_000) {
            return;
        }
        lastSubscriptionPromptAt.set(now);
        onSubscriptionRequired.accept(message);
    }

    public void logout() {
        if (!isBlank(accessToken)) {
            HttpClientUtil.postJson(getApiOrigin() + "/desktop/api/auth/logout", java.util.Map.of());
        }
        clearTokens();
    }

    public String getWebOrigin() {
        return getApiOrigin();
    }

    private void saveTokenPair(TokenPair pair) {
        accessToken = pair.getAccessToken();
        refreshToken = pair.getRefreshToken();
        accessExpiresAt = System.currentTimeMillis() + pair.getExpiresIn() * 1_000L;
        saveTokens();
    }

    private void loadTokens() {
        try {
            File configFile = new File(CONFIG_FILE);
            if (!configFile.exists()) {
                return;
            }
            Properties properties = new Properties();
            try (FileInputStream input = new FileInputStream(configFile)) {
                properties.load(input);
            }
            accessToken = properties.getProperty(ACCESS_TOKEN_KEY);
            refreshToken = properties.getProperty(REFRESH_TOKEN_KEY);
            accessExpiresAt = Long.parseLong(properties.getProperty(ACCESS_EXPIRES_AT_KEY, "0"));
        } catch (Exception exception) {
            log.warn("Unable to load desktop tokens", exception);
        }
    }

    private synchronized void saveTokens() {
        try {
            Path directory = Path.of(CONFIG_DIR);
            Files.createDirectories(directory);
            Properties properties = new Properties();
            File file = new File(CONFIG_FILE);
            if (file.exists()) {
                try (FileInputStream input = new FileInputStream(file)) {
                    properties.load(input);
                }
            }
            putOrRemove(properties, ACCESS_TOKEN_KEY, accessToken);
            putOrRemove(properties, REFRESH_TOKEN_KEY, refreshToken);
            properties.setProperty(ACCESS_EXPIRES_AT_KEY, String.valueOf(accessExpiresAt));
            try (FileOutputStream output = new FileOutputStream(file)) {
                properties.store(output, "SnapVideoTools Settings");
            }
            applyOwnerOnlyPermissions(file.toPath());
        } catch (Exception exception) {
            log.error("Unable to save desktop tokens", exception);
        }
    }

    private void clearTokens() {
        accessToken = null;
        refreshToken = null;
        accessExpiresAt = 0;
        accountInfo = null;
        saveTokens();
        notifyAccountChanged();
    }

    private void notifyAccountChanged() {
        accountListeners.values().forEach(listener -> {
            try {
                listener.run();
            } catch (Exception exception) {
                log.debug("Account listener failed", exception);
            }
        });
    }

    private String getApiOrigin() {
        String value = isBlank(apiBaseUrl) ? "https://snapvideotools.com" : apiBaseUrl.trim();
        try {
            URI uri = URI.create(value.contains("://") ? value : "https://" + value);
            return uri.getScheme() + "://" + uri.getAuthority();
        } catch (Exception exception) {
            return "https://snapvideotools.com";
        }
    }

    private static void putOrRemove(Properties properties, String key, String value) {
        if (isBlank(value)) {
            properties.remove(key);
        } else {
            properties.setProperty(key, value);
        }
    }

    private static void applyOwnerOnlyPermissions(Path path) {
        try {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE));
            }
        } catch (Exception exception) {
            log.debug("Unable to restrict settings file permissions", exception);
        }
    }

    @Data
    public static class DeviceAuthorization {
        private String deviceCode;
        private String userCode;
        private String verificationUri;
        private String verificationUriComplete;
        private int expiresIn;
        private int interval;
    }

    @Data
    public static class TokenPair {
        private String accessToken;
        private String refreshToken;
        private long expiresIn;
        private String refreshExpiresAt;
    }

    public record PollTokenResult(String error, TokenPair tokenPair) {
        public boolean pending() {
            return "authorization_pending".equals(error);
        }
    }

    @Data
    public static class AccountInfo {
        private Long userId;
        private String nickname;
        private String email;
        private String avatar;
        private SubscriptionInfo subscription;
        private QuotaInfo quota;
        private boolean profileExtraction;
    }

    @Data
    public static class SubscriptionInfo {
        private String status;
        private String billingInterval;
        private Instant currentPeriodEnd;
        private boolean cancelAtPeriodEnd;
        private boolean active;
    }

    @Data
    public static class QuotaInfo {
        private int limit;
        private int used;
        private int remaining;
        private String resetAt;
        private boolean unlimited;
    }
}
