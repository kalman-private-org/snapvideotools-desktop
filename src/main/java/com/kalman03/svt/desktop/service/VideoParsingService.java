package com.kalman03.svt.desktop.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.kalman03.svt.desktop.enums.TabType;
import com.kalman03.svt.desktop.model.VideoInfo;
import com.kalman03.svt.desktop.util.HttpClientUtil;
import com.kalman03.svt.desktop.util.HttpClientUtil.HttpResult;
import com.kalman03.svt.desktop.util.HttpClientUtil.JsonResponse;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

/**
 * 视频解析服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoParsingService {

    private final AuthService authService;

    @Value("${app.api.base-url:https://snapvideotools.com}")
    private String apiBaseUrl;

    private static final int DEFAULT_USER_VIDEOS_PAGE_SIZE = 20;
    private static final int MAX_USER_VIDEOS_PAGE_SIZE = 50;

    private final Map<String, UserVideosPaginationCache> userVideosPagination = new ConcurrentHashMap<>();

    // URL正则表达式
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DOUYIN_MODAL_ID_PATTERN = Pattern.compile("(?:[?&])modal_id=(\\d+)");

    public static class ApiParseException extends RuntimeException {
        public ApiParseException(String message) {
            super(message);
        }
    }

    /**
     * 从文本中提取所有URL
     *
     * @param text 输入文本
     * @return URL列表
     */
    public List<String> extractUrls(String text) {
        List<String> urls = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return urls;
        }

        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            urls.add(matcher.group(1));
        }

        return urls;
    }

    /**
     * 解析视频链接获取视频信息
     *
     * @param url 视频链接
     * @return 视频信息，解析失败返回 null
     */
    public VideoInfo parseVideoUrl(String url) {
        requireAuthenticatedSession();
        String normalizedUrl = normalizeVideoUrl(url);
        log.info("Parsing video URL: {}", normalizedUrl);

        try {
            String apiUrl = getApiOrigin() + "/desktop/api/video/parse";
            Map<String, String> request = Map.of("url", normalizedUrl, "requestId", UUID.randomUUID().toString());
            HttpResult result = HttpClientUtil.postJson(apiUrl, request);

            if (result == null) {
                log.error("Failed to get response from API");
                throw new ApiParseException("Failed to get response from API");
            }

            if (result.isUnauthorized()) {
                log.warn("Unauthorized, need to login");
                if (authService.refreshAccessToken()) {
                    result = HttpClientUtil.postJson(apiUrl, request);
                } else {
                    authService.handleUnauthorized();
                    throw new ApiParseException(result.getMessage());
                }
            }

            if (result == null) {
                throw new ApiParseException("Failed to get response from API");
            }
            if (result.isUnauthorized() || result.isBusinessUnauthorized()) {
                log.warn("Unauthorized, need to login");
                authService.handleUnauthorized();
                throw new ApiParseException(result.getMessage());
            }

            JsonResponse<DesktopVideoParseResponse> response = result.toJsonResponse(DesktopVideoParseResponse.class);
            if (response == null) {
                log.error("API returned empty response");
                throw new ApiParseException("Empty response from server");
            }
            if (isSubscriptionRequired(result.statusCode(), response.getCode(), response.getMessage())) {
                authService.handleSubscriptionRequired(response.getMessage());
                throw new ApiParseException(normalizeSubscriptionMessage(response.getMessage()));
            }
            if (result.statusCode() == 429) {
                authService.refreshAccountInfo();
                authService.handleSubscriptionRequired("DAILY_QUOTA_EXHAUSTED");
                throw new ApiParseException(response.getMessage());
            }
            if (!response.isSuccess()) {
                String message = response.getMessage();
                log.error("API returned error: {}", message);
                throw new ApiParseException(message != null ? message : "Failed to parse video URL");
            }

            if (response.getData() == null || response.getData().getVideo() == null) {
                throw new ApiParseException("Empty video response from server");
            }
            authService.refreshAccountInfo();
            return response.getData().getVideo();
        } catch (ApiParseException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse video URL: {}", normalizedUrl, e);
            throw new ApiParseException("Failed to parse video URL");
        }
    }

    /**
     * 抖音从用户主页打开视频时会复制出带 modal_id 的主页地址，解析接口需要标准视频地址。
     */
    String normalizeVideoUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            if (host == null || !(host.equalsIgnoreCase("douyin.com")
                    || host.toLowerCase(Locale.ROOT).endsWith(".douyin.com"))) {
                return url.trim();
            }
            Matcher modalIdMatcher = DOUYIN_MODAL_ID_PATTERN.matcher(url);
            if (modalIdMatcher.find()) {
                String normalized = "https://www.douyin.com/video/" + modalIdMatcher.group(1);
                log.info("Normalized Douyin modal URL to video URL: {}", normalized);
                return normalized;
            }
        } catch (IllegalArgumentException exception) {
            log.debug("Keep original video URL because URI normalization failed: {}", url);
        }
        return url.trim();
    }

    /**
     * 含 modal_id 的抖音主页地址代表一个明确作品，必须覆盖当前主页 Tab 并走单作品解析。
     */
    public TabType resolveTabType(TabType selectedTab, String content) {
        if (selectedTab == TabType.USER_PROFILE && containsDouyinModalUrl(content)) {
            return TabType.VIDEO_LINK;
        }
        return selectedTab == null ? TabType.VIDEO_LINK : selectedTab;
    }

    boolean containsDouyinModalUrl(String content) {
        return extractUrls(content).stream().anyMatch(url -> {
            try {
                URI uri = URI.create(url);
                String host = uri.getHost();
                return host != null
                        && (host.equalsIgnoreCase("douyin.com")
                                || host.toLowerCase(Locale.ROOT).endsWith(".douyin.com"))
                        && DOUYIN_MODAL_ID_PATTERN.matcher(url).find();
            } catch (IllegalArgumentException exception) {
                return false;
            }
        });
    }

    /** 确保解析请求一定携带登录令牌；令牌缺失时先尝试刷新，再触发重新登录。 */
    private void requireAuthenticatedSession() {
        String accessToken = authService.getAccessToken();
        if (accessToken != null && !accessToken.isBlank()) {
            return;
        }
        authService.handleUnauthorized();
        accessToken = authService.getAccessToken();
        if (accessToken == null || accessToken.isBlank()) {
            throw new ApiParseException("Login required");
        }
    }

    /**
     * 获取用户主页下的所有视频（分页）
     *
     * @param profileUrl 用户主页URL
     * @param page       页码（从1开始）
     * @param pageSize   每页数量
     * @return 视频信息列表
     */
    public List<VideoInfo> getUserVideos(String profileUrl, int page, int pageSize) {
        requireAuthenticatedSession();
        log.info("Fetching user videos from: {}, page: {}, pageSize: {}", profileUrl, page, pageSize);
        // TODO: 实现用户主页视频列表获取
        return doGetUserVideos(profileUrl, page, pageSize);
    }

    /**
     * 检查是否还有更多视频
     *
     * @param profileUrl 用户主页URL
     * @param page       当前页码
     * @param pageSize   每页数量
     * @return 是否还有更多
     */
    public boolean hasMoreVideos(String profileUrl, int page, int pageSize) {
        // TODO: 实现分页检查
        return doHasMoreVideos(profileUrl, page, pageSize);
    }

    /**
     * 清理指定用户主页的分页缓存
     */
    public void resetUserVideosPagination(String profileUrl) {
        String normalizedProfileUrl = normalizeProfileUrlOrText(profileUrl);
        if (normalizedProfileUrl == null || normalizedProfileUrl.isBlank()) {
            return;
        }
        String prefix = normalizedProfileUrl + "##";
        for (String key : new ArrayList<>(userVideosPagination.keySet())) {
            if (key.startsWith(prefix)) {
                userVideosPagination.remove(key);
            }
        }
    }

    /**
     * 获取 API Origin
     */
    private List<VideoInfo> doGetUserVideos(String profileUrl, int page, int pageSize) {
        String normalizedProfileUrl = normalizeProfileUrlOrText(profileUrl);
        if (normalizedProfileUrl == null || normalizedProfileUrl.isBlank()) {
            return Collections.emptyList();
        }

        int normalizedPage = Math.max(1, page);
        int normalizedPageSize = normalizePageSize(pageSize);

        String cacheKey = buildPaginationKey(normalizedProfileUrl, normalizedPageSize);
        UserVideosPaginationCache cache = userVideosPagination.computeIfAbsent(cacheKey,
                k -> new UserVideosPaginationCache(normalizedProfileUrl, normalizedPageSize));

        DesktopUserWorksPageVO pageVO = ensureUserVideosPage(cache, normalizedPage);
        if (pageVO == null || pageVO.getList() == null) {
            return Collections.emptyList();
        }
        return pageVO.getList();
    }

    private boolean doHasMoreVideos(String profileUrl, int page, int pageSize) {
        String normalizedProfileUrl = normalizeProfileUrlOrText(profileUrl);
        if (normalizedProfileUrl == null || normalizedProfileUrl.isBlank()) {
            return false;
        }

        int normalizedPage = Math.max(1, page);
        int normalizedPageSize = normalizePageSize(pageSize);

        String cacheKey = buildPaginationKey(normalizedProfileUrl, normalizedPageSize);
        UserVideosPaginationCache cache = userVideosPagination.computeIfAbsent(cacheKey,
                k -> new UserVideosPaginationCache(normalizedProfileUrl, normalizedPageSize));

        DesktopUserWorksPageVO pageVO = ensureUserVideosPage(cache, normalizedPage);
        return pageVO != null && pageVO.isHasMore();
    }

    private int normalizePageSize(int pageSize) {
        int normalized = pageSize <= 0 ? DEFAULT_USER_VIDEOS_PAGE_SIZE : pageSize;
        return Math.min(normalized, MAX_USER_VIDEOS_PAGE_SIZE);
    }

    private String buildPaginationKey(String normalizedProfileUrl, int pageSize) {
        return normalizedProfileUrl + "##" + pageSize;
    }

    private String normalizeProfileUrlOrText(String profileUrlOrText) {
        if (profileUrlOrText == null) {
            return null;
        }
        String trimmed = profileUrlOrText.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }

        List<String> urls = extractUrls(trimmed);
        if (urls.isEmpty()) {
            return trimmed;
        }
        return urls.get(0);
    }

    private DesktopUserWorksPageVO ensureUserVideosPage(UserVideosPaginationCache cache, int targetPage) {
        synchronized (cache) {
            for (int currentPage = 1; currentPage <= targetPage; currentPage++) {
                if (cache.getPages().containsKey(currentPage)) {
                    continue;
                }

                DesktopUserWorksPageVO prev = cache.getPages().get(currentPage - 1);
                if (currentPage > 1) {
                    if (prev == null) {
                        log.warn("User videos paging stopped: missing prev page, url={}, page={}", cache.normalizedProfileUrl,
                                currentPage);
                        break;
                    }

                    boolean cursorAdvances = prev.getNextCursor() != prev.getCursor();
                    boolean hasItems = prev.getList() != null && !prev.getList().isEmpty();
                    if (!prev.isHasMore() && !(cursorAdvances && hasItems)) {
                        log.info(
                                "User videos paging stopped: hasMore=false and no further cursor progress, url={}, page={}, cursor={}, nextCursor={}, items={}",
                                cache.normalizedProfileUrl, currentPage, prev.getCursor(), prev.getNextCursor(),
                                prev.getList() == null ? 0 : prev.getList().size());
                        break;
                    }

                    if (prev.getNextCursor() == prev.getCursor()) {
                        log.warn(
                                "User videos paging detected non-advancing cursor, stopping to avoid duplicates: url={}, page={}, cursor={}",
                                cache.normalizedProfileUrl, currentPage, prev.getCursor());
                        break;
                    }
                }

                long cursor = currentPage == 1 ? 0 : prev.getNextCursor();
                log.info("Fetching user videos page: url={}, page={}, cursor={}, pageSize={}", cache.normalizedProfileUrl,
                        currentPage, cursor, cache.pageSize);
                DesktopUserWorksPageVO current = fetchUserVideosPage(cache.getNormalizedProfileUrl(), cursor,
                        cache.getPageSize());
                if (current == null) {
                    return null;
                }

                if (current.getList() == null) {
                    current.setList(Collections.emptyList());
                }
                cache.getPages().put(currentPage, current);

                log.info("Fetched user videos page: url={}, page={}, cursor={}, nextCursor={}, hasMore={}, items={}",
                        cache.normalizedProfileUrl, currentPage, current.getCursor(), current.getNextCursor(),
                        current.isHasMore(), current.getList().size());

                if (!current.isHasMore() && current.getNextCursor() == current.getCursor()) {
                    break;
                }
            }

            return cache.getPages().get(targetPage);
        }
    }

    private ApiParseException buildProfileException(String message) {
        return new ApiParseException(message != null ? message : "Failed to parse profile");
    }

    private boolean isSubscriptionRequired(int statusCode, Integer code, String message) {
        if (statusCode == 402 || (code != null && (code == 402 || code == 403))) {
            return true;
        }
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("subscription")
                || normalized.contains("subscribe")
                || normalized.contains("member")
                || message.contains("会员")
                || message.contains("订阅")
                || message.contains("付费");
    }

    private String normalizeSubscriptionMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Subscription required";
        }
        return message;
    }

    private DesktopUserWorksPageVO fetchUserVideosPage(String normalizedProfileUrl, long cursor, int pageSize) {
        requireAuthenticatedSession();
        try {
            Map<String, String> params = new HashMap<>();
            params.put("url", normalizedProfileUrl);
            params.put("cursor", String.valueOf(Math.max(0, cursor)));
            params.put("count", String.valueOf(pageSize));

            String apiUrl = HttpClientUtil.buildUrl(getApiOrigin() + "/desktop/api/user/videos", params);
            log.info("Calling user videos API: {}", apiUrl);
            HttpResult result = HttpClientUtil.get(apiUrl);

            if (result == null) {
                log.error("Failed to get response from API, url={}", apiUrl);
                throw buildProfileException("Failed to get response from API");
            }

            if (result.isUnauthorized() || result.isBusinessUnauthorized()) {
                log.warn("Unauthorized when fetching user videos, need to login");
                if (authService.refreshAccessToken()) {
                    result = HttpClientUtil.get(apiUrl);
                } else {
                    authService.handleUnauthorized();
                    throw buildProfileException(result.getMessage());
                }
            }
            if (result == null) {
                throw buildProfileException("Failed to get response from API");
            }
            if (result.isUnauthorized() || result.isBusinessUnauthorized()) {
                authService.handleUnauthorized();
                throw buildProfileException(result.getMessage());
            }

            TypeReference<JsonResponse<DesktopUserWorksPageVO>> typeRef = new TypeReference<>() {};
            JsonResponse<DesktopUserWorksPageVO> response = result.toJsonResponse(typeRef);
            if (response == null) {
                log.error("API returned empty response when fetching user videos");
                throw buildProfileException("Empty response from server");
            }
            if (response.isUnauthorized()) {
                log.warn("Unauthorized when fetching user videos, need to login");
                authService.handleUnauthorized();
                throw buildProfileException(response.getMessage());
            }
            if (isSubscriptionRequired(result.statusCode(), response.getCode(), response.getMessage())) {
                authService.handleSubscriptionRequired(response.getMessage());
                throw buildProfileException(normalizeSubscriptionMessage(response.getMessage()));
            }
            if (!response.isSuccess()) {
                log.error("API returned error when fetching user videos: code={}, message={}", response.getCode(),
                        response.getMessage());
                throw buildProfileException(response.getMessage());
            }

            DesktopUserWorksPageVO data = response.getData();
            if (data == null) {
                return null;
            }

            if (data.getList() == null) {
                data.setList(Collections.emptyList());
            }

            // Be tolerant to occasional backend hasMore issues: if cursor advances and we got items, allow paging.
            if (!data.isHasMore() && data.getNextCursor() != data.getCursor() && !data.getList().isEmpty()) {
                log.warn("Backend returned hasMore=false but cursor advanced; treating as hasMore=true: url={}, cursor={}, nextCursor={}, items={}",
                        normalizedProfileUrl, data.getCursor(), data.getNextCursor(), data.getList().size());
                data.setHasMore(true);
            }

            return data;
        } catch (ApiParseException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch user videos page: url={}, cursor={}, pageSize={}", normalizedProfileUrl, cursor,
                    pageSize, e);
            throw buildProfileException("Failed to fetch user videos");
        }
    }

    @Data
    private static class DesktopVideoParseResponse {
        private VideoInfo video;
    }

    @Data
    private static class DesktopUserWorksPageVO {
        private String platform;
        private String userUrl;
        private String secUserId;
        private long cursor;
        private long nextCursor;
        private boolean hasMore;
        private List<VideoInfo> list;
    }

    @Data
    private static class UserVideosPaginationCache {
        private final String normalizedProfileUrl;
        private final int pageSize;
        private final Map<Integer, DesktopUserWorksPageVO> pages = new HashMap<>();
    }

    // API origin for desktop endpoints
    private String getApiOrigin() {
        if (apiBaseUrl == null || apiBaseUrl.trim().isEmpty()) {
            return "https://snapvideotools.com";
        }

        String trimmed = apiBaseUrl.trim();
        try {
            URI uri = URI.create(trimmed);
            if (uri.getScheme() == null || uri.getAuthority() == null) {
                uri = URI.create("https://" + trimmed.replaceFirst("^https?://", ""));
            }

            String scheme = uri.getScheme() == null ? "https" : uri.getScheme();
            String authority = uri.getAuthority();
            if (authority == null || authority.isEmpty()) {
                String host = trimmed.replaceFirst("^https?://", "").split("/")[0];
                return scheme + "://" + host;
            }
            return scheme + "://" + authority;
        } catch (Exception e) {
            String host = trimmed.replaceFirst("^https?://", "").split("/")[0];
            return "https://" + host;
        }
    }
}
