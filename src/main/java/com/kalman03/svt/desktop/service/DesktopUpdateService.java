package com.kalman03.svt.desktop.service;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.kalman03.svt.desktop.util.HttpClientUtil;
import com.kalman03.svt.desktop.util.HttpClientUtil.HttpResult;
import com.kalman03.svt.desktop.util.HttpClientUtil.JsonResponse;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/** 启动后异步检查公开桌面版本。 */
@Slf4j
@Service
public class DesktopUpdateService {

    @Value("${app.api.base-url:https://snapvideotools.com}")
    private String apiBaseUrl;
    @Value("${app.version:0.0.1}")
    private String currentVersion;

    public void checkAsync(Consumer<LatestRelease> onUpdate) {
        CompletableFuture.runAsync(() -> {
            LatestRelease release = check();
            if (release != null && release.isAvailable() && release.isUpdateAvailable()) {
                onUpdate.accept(release);
            }
        });
    }

    private LatestRelease check() {
        try {
            String platform = platform();
            String arch = architecture();
            String base = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
            String url = HttpClientUtil.buildUrl(base + "/desktop/api/releases/latest",
                    HttpClientUtil.params("platform", platform, "arch", arch, "currentVersion", currentVersion));
            HttpResult result = HttpClientUtil.get(url);
            if (result == null || !result.isSuccess()) {
                log.debug("Desktop update endpoint is unavailable, status={}",
                        result == null ? "network-error" : result.statusCode());
                return null;
            }
            JsonResponse<LatestRelease> response = result.toJsonResponse(LatestRelease.class);
            return response != null && response.isSuccess() ? response.getData() : null;
        } catch (Exception exception) {
            log.debug("Desktop update check failed", exception);
            return null;
        }
    }

    private static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return "macos";
        }
        if (os.contains("win")) {
            return "windows";
        }
        return "linux";
    }

    private static String architecture() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "x64";
    }

    @Data
    public static class LatestRelease {
        private boolean available;
        private boolean updateAvailable;
        private boolean forceUpdate;
        private String latestVersion;
        private String publishedAt;
        private String releaseNotes;
        private String downloadUrl;
        private String fileName;
        private String sha256;
        private String message;
    }
}
