package com.kalman03.svt.desktop.util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP 客户端工具类
 * 使用 Java 11+ HttpClient，支持 GET/POST 请求
 * 使用 Jackson 进行 JSON 序列化/反序列化
 */
@Slf4j
public class HttpClientUtil {

    public static final String HEADER_AUTH_TOKEN = "Authorization";
    public static final String HEADER_DESKTOP_VERSION = "x-desktop-version";

    private static final String APP_VERSION = loadAppVersion();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(DEFAULT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // 全局 token 提供者
    private static volatile TokenProvider tokenProvider;
    private static volatile LanguageProvider languageProvider;

    /**
     * Token 提供者接口
     */
    public interface TokenProvider {
        String getAccessToken();
    }

    public interface LanguageProvider {
        String getLanguageTag();
    }

    /**
     * 设置全局 token 提供者
     */
    public static void setTokenProvider(TokenProvider provider) {
        tokenProvider = provider;
    }

    /** 设置 API 与浏览器授权流程使用的当前语言。 */
    public static void setLanguageProvider(LanguageProvider provider) {
        languageProvider = provider;
    }

    /**
     * 获取 ObjectMapper 实例
     */
    public static ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    /**
     * HTTP 响应结果
     */
    public record HttpResult(int statusCode, String body) {

        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }

        public boolean isUnauthorized() {
            return statusCode == 401;
        }

        /**
         * 解析为 JsonResponse（无泛型 data）
         */
        public JsonResponse<?> toJsonResponse() {
            if (!hasJsonBody()) {
                logNonJsonResponse();
                return null;
            }
            return parseJson(body, new TypeReference<JsonResponse<Object>>() {});
        }

        /**
         * 解析为 JsonResponse（指定 data 类型）
         */
        public <T> JsonResponse<T> toJsonResponse(Class<T> dataType) {
            try {
                if (!hasJsonBody()) {
                    logNonJsonResponse();
                    return null;
                }
                return objectMapper.readValue(body,
                    objectMapper.getTypeFactory().constructParametricType(JsonResponse.class, dataType));
            } catch (Exception e) {
                log.error("Failed to parse JSON response: {}", body, e);
                return null;
            }
        }

        /**
         * 解析为 JsonResponse（使用 TypeReference）
         */
        public <T> JsonResponse<T> toJsonResponse(TypeReference<JsonResponse<T>> typeRef) {
            return parseJson(body, typeRef);
        }

        /**
         * 获取响应中的 code
         */
        public Integer getCode() {
            JsonResponse<?> response = toJsonResponse();
            return response != null ? response.getCode() : null;
        }

        /**
         * 获取响应中的 message
         */
        public String getMessage() {
            JsonResponse<?> response = toJsonResponse();
            return response != null ? response.getMessage() : null;
        }

        /**
         * 判断业务是否成功（code=0）
         */
        public boolean isBusinessSuccess() {
            Integer code = getCode();
            return code != null && code == 0;
        }

        /**
         * 判断业务是否未授权（code=401）
         */
        public boolean isBusinessUnauthorized() {
            Integer code = getCode();
            return code != null && code == 401;
        }

        private boolean hasJsonBody() {
            if (body == null || body.isBlank()) {
                return false;
            }
            String normalized = body.stripLeading();
            return normalized.startsWith("{") || normalized.startsWith("[");
        }

        private void logNonJsonResponse() {
            String preview = body == null ? "" : body.replaceAll("\\s+", " ").trim();
            if (preview.length() > 160) {
                preview = preview.substring(0, 160) + "…";
            }
            log.debug("API returned a non-JSON response, status={}, body={}", statusCode, preview);
        }
    }

    /**
     * API 响应结构
     * 服务端返回格式: {"code": 0, "message": "success", "data": {...}}
     * code=0 表示成功，code=401 表示未授权
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JsonResponse<T> {
        private Integer code;
        private String message;
        private T data;

        public boolean isSuccess() {
            return code != null && code == 0;
        }

        public boolean isUnauthorized() {
            return code != null && code == 401;
        }
    }

    /**
     * 发送 GET 请求
     */
    public static HttpResult get(String url) {
        return get(url, null);
    }

    /**
     * 发送 GET 请求（带额外 headers）
     */
    public static HttpResult get(String url, Map<String, String> extraHeaders) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(DEFAULT_TIMEOUT)
                    .GET();

            applyDefaultHeaders(builder);
            applyExtraHeaders(builder, extraHeaders);

            HttpRequest request = builder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            return new HttpResult(response.statusCode(), response.body());
        } catch (Exception e) {
            log.error("GET request failed: {}", url, e);
            return null;
        }
    }

    /**
     * 发送 POST 请求（JSON body）
     */
    public static HttpResult postJson(String url, String jsonBody) {
        return postJson(url, jsonBody, null);
    }

    /**
     * 发送 POST 请求（对象自动序列化为 JSON）
     */
    public static HttpResult postJson(String url, Object body) {
        return postJson(url, body, null);
    }

    /**
     * 发送 POST 请求（对象自动序列化为 JSON，带额外 headers）
     */
    public static HttpResult postJson(String url, Object body, Map<String, String> extraHeaders) {
        try {
            String jsonBody = body instanceof String ? (String) body : objectMapper.writeValueAsString(body);
            return postJsonInternal(url, jsonBody, extraHeaders);
        } catch (Exception e) {
            log.error("Failed to serialize object to JSON", e);
            return null;
        }
    }

    /**
     * 发送 POST 请求（JSON body，带额外 headers）
     */
    private static HttpResult postJsonInternal(String url, String jsonBody, Map<String, String> extraHeaders) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(DEFAULT_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

            applyDefaultHeaders(builder);
            applyExtraHeaders(builder, extraHeaders);

            HttpRequest request = builder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            return new HttpResult(response.statusCode(), response.body());
        } catch (Exception e) {
            log.error("POST JSON request failed: {}", url, e);
            return null;
        }
    }

    /**
     * 发送 POST 请求（form-urlencoded）
     */
    public static HttpResult postForm(String url, Map<String, String> formData) {
        return postForm(url, formData, null);
    }

    /**
     * 发送 POST 请求（form-urlencoded，带额外 headers）
     */
    public static HttpResult postForm(String url, Map<String, String> formData, Map<String, String> extraHeaders) {
        try {
            String formBody = buildFormBody(formData);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(DEFAULT_TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8));

            applyDefaultHeaders(builder);
            applyExtraHeaders(builder, extraHeaders);

            HttpRequest request = builder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            return new HttpResult(response.statusCode(), response.body());
        } catch (Exception e) {
            log.error("POST form request failed: {}", url, e);
            return null;
        }
    }

    /**
     * 解析 JSON 字符串
     */
    public static <T> T parseJson(String json, Class<T> clazz) {
        try {
            if (json == null || json.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", json, e);
            return null;
        }
    }

    /**
     * 解析 JSON 字符串（使用 TypeReference）
     */
    public static <T> T parseJson(String json, TypeReference<T> typeRef) {
        try {
            if (json == null || json.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", json, e);
            return null;
        }
    }

    /**
     * 对象序列化为 JSON 字符串
     */
    public static String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("Failed to serialize object to JSON", e);
            return null;
        }
    }

    /**
     * 应用默认 headers（Authorization Bearer、x-desktop-version）
     */
    private static void applyDefaultHeaders(HttpRequest.Builder builder) {
        builder.header("Accept", "application/json");
        builder.header(HEADER_DESKTOP_VERSION, APP_VERSION);
        if (languageProvider != null) {
            String languageTag = languageProvider.getLanguageTag();
            if (languageTag != null && !languageTag.isBlank()) {
                builder.header("Accept-Language", languageTag);
            }
        }

        if (tokenProvider != null) {
            String token = tokenProvider.getAccessToken();
            if (token != null && !token.isEmpty()) {
                builder.header(HEADER_AUTH_TOKEN, "Bearer " + token);
            }
        }
    }

    /**
     * 应用额外 headers
     */
    private static void applyExtraHeaders(HttpRequest.Builder builder, Map<String, String> extraHeaders) {
        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 构建 form-urlencoded body
     */
    private static String buildFormBody(Map<String, String> formData) {
        if (formData == null || formData.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : formData.entrySet()) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(urlEncode(entry.getKey()))
              .append("=")
              .append(urlEncode(entry.getValue()));
        }
        return sb.toString();
    }

    /**
     * URL 编码
     */
    public static String urlEncode(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 转义 JSON 字符串中的特殊字符
     */
    public static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    /**
     * 构建带查询参数的 URL
     */
    public static String buildUrl(String baseUrl, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return baseUrl;
        }

        StringBuilder sb = new StringBuilder(baseUrl);
        sb.append(baseUrl.contains("?") ? "&" : "?");

        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                sb.append("&");
            }
            sb.append(urlEncode(entry.getKey()))
              .append("=")
              .append(urlEncode(entry.getValue()));
            first = false;
        }
        return sb.toString();
    }

    /**
     * 创建参数 Map 的便捷方法
     */
    public static Map<String, String> params(String... keyValues) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length - 1; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static String loadAppVersion() {
        String override = System.getProperty("snapvideotools.version");
        if (override != null && !override.isBlank()) {
            return override;
        }
        try (java.io.InputStream input = HttpClientUtil.class.getResourceAsStream("/application.properties")) {
            java.util.Properties properties = new java.util.Properties();
            if (input != null) {
                properties.load(input);
            }
            return properties.getProperty("app.version", "0.0.1");
        } catch (Exception ignored) {
            return "0.0.1";
        }
    }
}
