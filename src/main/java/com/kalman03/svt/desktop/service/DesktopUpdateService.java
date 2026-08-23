package com.kalman03.svt.desktop.service;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern SEMANTIC_VERSION_PATTERN = Pattern.compile(
            "^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    @Value("${app.api.base-url:https://snapvideotools.com}")
    private String apiBaseUrl;
    @Value("${app.version:0.0.1}")
    private String currentVersion;

    public void checkAsync(Consumer<LatestRelease> onUpdate) {
        CompletableFuture.runAsync(() -> {
            LatestRelease release = check();
            if (shouldNotify(release)) {
                onUpdate.accept(release);
            }
        });
    }

    boolean shouldNotify(LatestRelease release) {
        if (release == null || !release.isAvailable()) {
            return false;
        }

        // 更新弹窗以客户端实际版本比较为最终依据，避免服务端缓存或错误标志导致同版本重复提醒。
        boolean newerVersion = isNewerVersion(release.getLatestVersion(), currentVersion);
        if (release.isUpdateAvailable() && !newerVersion) {
            log.debug("Ignored desktop update response because the release is not newer than the installed version");
        }
        return newerVersion;
    }

    static boolean isNewerVersion(String latestVersion, String currentVersion) {
        Optional<SemanticVersion> latest = SemanticVersion.parse(latestVersion);
        Optional<SemanticVersion> current = SemanticVersion.parse(currentVersion);
        return latest.isPresent() && current.isPresent() && latest.get().compareTo(current.get()) > 0;
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

    private record SemanticVersion(BigInteger major, BigInteger minor, BigInteger patch,
            String[] preRelease) implements Comparable<SemanticVersion> {

        private static Optional<SemanticVersion> parse(String value) {
            if (value == null) {
                return Optional.empty();
            }
            Matcher matcher = SEMANTIC_VERSION_PATTERN.matcher(value.trim());
            if (!matcher.matches()) {
                return Optional.empty();
            }
            String preReleaseValue = matcher.group(4);
            return Optional.of(new SemanticVersion(
                    new BigInteger(matcher.group(1)),
                    new BigInteger(matcher.group(2)),
                    new BigInteger(matcher.group(3)),
                    preReleaseValue == null ? new String[0] : preReleaseValue.split("\\.")));
        }

        @Override
        public int compareTo(SemanticVersion other) {
            int coreComparison = major.compareTo(other.major);
            if (coreComparison == 0) {
                coreComparison = minor.compareTo(other.minor);
            }
            if (coreComparison == 0) {
                coreComparison = patch.compareTo(other.patch);
            }
            if (coreComparison != 0) {
                return coreComparison;
            }
            if (preRelease.length == 0 || other.preRelease.length == 0) {
                return preRelease.length == 0 ? (other.preRelease.length == 0 ? 0 : 1) : -1;
            }
            for (int index = 0; index < Math.max(preRelease.length, other.preRelease.length); index++) {
                if (index >= preRelease.length || index >= other.preRelease.length) {
                    return Integer.compare(preRelease.length, other.preRelease.length);
                }
                int identifierComparison = compareIdentifier(preRelease[index], other.preRelease[index]);
                if (identifierComparison != 0) {
                    return identifierComparison;
                }
            }
            return 0;
        }

        private static int compareIdentifier(String left, String right) {
            boolean leftNumeric = left.matches("\\d+");
            boolean rightNumeric = right.matches("\\d+");
            if (leftNumeric && rightNumeric) {
                return new BigInteger(left).compareTo(new BigInteger(right));
            }
            if (leftNumeric != rightNumeric) {
                return leftNumeric ? -1 : 1;
            }
            return left.compareTo(right);
        }
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
