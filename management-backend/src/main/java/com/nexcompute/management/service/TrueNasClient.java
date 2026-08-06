package com.nexcompute.management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexcompute.management.config.NasProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TrueNAS v2.0 REST API 同步客户端（nas-allocation D6）。
 * Spring {@link RestClient}，Bearer API key 认证，自签 TLS 关校验（{@link NasProperties.Truenas#isVerifyTls()}=false 时）。
 * 方法 1:1 对照原 Python 门户 TrueNASClient：corePing / userCreate / userFindByUsername / userGetInstance / userUpdate。
 * 启动 {@link ApplicationReadyEvent} ping 仅告警不阻断（D10，ACCOUNT_WRITE 不可预检）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrueNasClient {

    private final NasProperties nasProperties;
    private final ObjectMapper objectMapper;

    private RestClient restClient;
    private int retries;

    /** 信任全部的 TrustManager（仅 verifyTls=false 时启用，dev http:// 无影响） */
    private static final TrustManager[] TRUST_ALL = {
            new X509TrustManager() {
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }

                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // 信任全部
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // 信任全部
                }
            }
    };

    /** 权限错误体标记（命中即判为 TrueNasPermissionError） */
    private static final String[] PERM_MARKERS = {
            "account_write", "full_admin", "not authorized", "not authorised",
            "insufficient privileg", "permission denied", "you do not have", "privilege required"
    };

    @PostConstruct
    void init() {
        NasProperties.Truenas t = nasProperties.getTruenas();
        this.retries = Math.max(0, t.getRetries());
        this.restClient = RestClient.builder()
                .baseUrl(t.getBaseUrl().replaceAll("/+$", ""))
                .requestFactory(buildRequestFactory(t.isVerifyTls(), t.getTimeoutSeconds()))
                .defaultHeader("Authorization", "Bearer " + t.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
        log.info("[TrueNAS] 客户端就绪: baseUrl={}, verifyTls={}, timeout={}s, retries={}",
                t.getBaseUrl(), t.isVerifyTls(), t.getTimeoutSeconds(), retries);
    }

    // ==================== 启动健康检查（D10） ====================

    @EventListener(ApplicationReadyEvent.class)
    public void pingOnStartup() {
        try {
            corePing();
            log.info("[TrueNAS] 启动 ping 成功: {}", nasProperties.getTruenas().getBaseUrl());
        } catch (Exception e) {
            log.warn("[TrueNAS] 启动 ping 失败（不阻断启动；ACCOUNT_WRITE 不可预检，到 approve 才暴露）: {}", e.getMessage());
        }
    }

    // ==================== 高层方法（1:1 对照 Python） ====================

    /** GET /core/ping -> "pong" */
    public String corePing() {
        Object result = request(HttpMethod.GET, "/core/ping", null, null);
        return (result instanceof String s) ? s : "pong";
    }

    /** GET /user?username=&local=true&limit=1 -> 首个本地用户，无则 null */
    @SuppressWarnings("unchecked")
    public Map<String, Object> userFindByUsername(String username) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("username", username);
        params.add("local", "true");
        params.add("limit", "1");
        Object result = request(HttpMethod.GET, "/user", params, null);
        if (!(result instanceof List<?> list)) {
            throw new TrueNasApiError("user.query returned non-list: " + result, "GET /user", null);
        }
        return list.isEmpty() ? null : (Map<String, Object>) list.get(0);
    }

    /**
     * POST /user 创建本地用户（需 ACCOUNT_WRITE）。SMB 默认开启；
     * homeParent 非空时开 SSH/Shell（home 建在 {homeParent}/{username}）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> userCreate(String username, String fullName, String password,
                                          String email, String homeParent, List<Integer> groups) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("full_name", fullName);
        payload.put("password", password);
        payload.put("smb", true);
        payload.put("group_create", true);
        if (email != null) {
            payload.put("email", email);
        }
        if (groups != null && !groups.isEmpty()) {
            payload.put("groups", groups);
        }
        if (homeParent != null && !homeParent.isBlank()) {
            payload.put("home", homeParent.replaceAll("/+$", "") + "/" + username);
            payload.put("home_create", true);
            payload.put("shell", "/usr/bin/zsh");
            payload.put("ssh_password_enabled", true);
        }
        Object result = request(HttpMethod.POST, "/user", null, payload);
        if (!(result instanceof Map<?, ?> map)) {
            throw new TrueNasApiError("user.create returned unexpected result: " + result, "POST /user", null);
        }
        return (Map<String, Object>) map;
    }

    /** GET /user/id/{id} 详情 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> userGetInstance(Integer userId) {
        Object result = request(HttpMethod.GET, "/user/id/" + userId, null, null);
        if (!(result instanceof Map<?, ?> map)) {
            throw new TrueNasApiError("user.get_instance returned unexpected: " + result, "GET /user/id/" + userId, null);
        }
        return (Map<String, Object>) map;
    }

    /** PUT /user/id/{id} 更新（改组等） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> userUpdate(Integer userId, Map<String, Object> fields) {
        Object result = request(HttpMethod.PUT, "/user/id/" + userId, null, fields);
        if (!(result instanceof Map<?, ?> map)) {
            throw new TrueNasApiError("user.update returned unexpected: " + result, "PUT /user/id/" + userId, null);
        }
        return (Map<String, Object>) map;
    }

    // ==================== 底层请求（含重试 + 错误分类） ====================

    /**
     * 执行请求，返回解析后的 JSON（Map/List/String）或 null。
     * 瞬态 IO 错误（{@link ResourceAccessException}）重试 {@link #retries} 次；4xx/5xx 经 {@link #raiseForError} 分类。
     */
    private Object request(HttpMethod method, String path, MultiValueMap<String, String> params, Object body) {
        // 对照 Python：path.lstrip("/")，避免 /api/v2.0//user 双斜杠
        String cleanPath = path.replaceFirst("^/+", "");
        String fullPath = "/api/v2.0/" + cleanPath;
        String label = method + " " + fullPath;
        int attempts = retries + 1;
        Exception lastExc = null;
        for (int i = 0; i < attempts; i++) {
            try {
                RestClient.RequestBodySpec spec = restClient.method(method).uri(uriBuilder -> {
                    uriBuilder.path(fullPath);
                    if (params != null) {
                        uriBuilder.queryParams(params);
                    }
                    return uriBuilder.build();
                });
                if (body != null) {
                    return spec.body(body).retrieve()
                            .onStatus(s -> s.value() >= 400, (req, res) -> raiseForError(res.getStatusCode().value(),
                                    StreamUtils.copyToString(res.getBody(), StandardCharsets.UTF_8), label))
                            .body(Object.class);
                }
                return spec.retrieve()
                        .onStatus(s -> s.value() >= 400, (req, res) -> raiseForError(res.getStatusCode().value(),
                                StreamUtils.copyToString(res.getBody(), StandardCharsets.UTF_8), label))
                        .body(Object.class);
            } catch (ResourceAccessException e) {
                lastExc = e;
                log.debug("[TrueNAS] {} 连接错误，重试 {}/{}: {}", label, i + 1, attempts, e.getMessage());
            }
        }
        throw new TrueNasConnectionError(
                "TrueNAS unreachable after " + attempts + " attempt(s): " + (lastExc == null ? "" : lastExc.getMessage()),
                lastExc);
    }

    /** 解析错误体并按状态/权限标记分类抛出 */
    private void raiseForError(int status, String body, String label) {
        String message = extractMessage(body);
        String detail = message.isBlank() ? "no detail" : message;
        if (status == 401 || status == 403 || isPermissionText(message)) {
            throw new TrueNasPermissionError(
                    "TrueNAS " + label + " permission error (" + status + "): " + detail, label, status);
        }
        throw new TrueNasApiError("TrueNAS " + label + " failed (" + status + "): " + detail, label, status);
    }

    /** 从 JSON 错误体提取 message/error/data.reason；TrueNAS 校验错误 {"field":[{"message":"..."}]}；非 JSON 取原文（截断 200 字符） */
    private String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            Object parsed = objectMapper.readValue(body, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                String msg = str(map.get("message"));
                if (msg == null || msg.isEmpty()) {
                    msg = str(map.get("error"));
                }
                Object data = map.get("data");
                if (data instanceof Map<?, ?> dataMap && (msg == null || msg.isEmpty())) {
                    Object reason = dataMap.get("reason");
                    if (reason != null) {
                        msg = String.valueOf(reason);
                    }
                }
                // TrueNAS 字段校验错误：{"user_create.email":[{"message":"...","errno":22}]}
                if (msg == null || msg.isEmpty()) {
                    msg = extractValidationMessage(map);
                }
                return msg == null ? "" : msg;
            } else if (parsed instanceof String s && !s.isEmpty()) {
                return s;
            }
        } catch (Exception ignored) {
            // 非 JSON，按原文处理
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    /** TrueNAS 校验错误体：遍历值找首个 [{"message":"..."}] 提取 message */
    private static String extractValidationMessage(Map<?, ?> map) {
        for (Object v : map.values()) {
            if (v instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
                Object m = first.get("message");
                if (m != null) {
                    return String.valueOf(m);
                }
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean isPermissionText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lowered = text.toLowerCase();
        for (String marker : PERM_MARKERS) {
            if (lowered.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private ClientHttpRequestFactory buildRequestFactory(boolean verifyTls, int timeoutSeconds) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        if (!verifyTls) {
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, TRUST_ALL, new SecureRandom());
                HttpClient httpClient = HttpClient.newBuilder()
                        .sslContext(sslContext)
                        .connectTimeout(timeout)
                        .build();
                JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
                factory.setReadTimeout(timeout);
                return factory;
            } catch (Exception e) {
                log.warn("[TrueNAS] 构建信任全部 SSLContext 失败，回退默认工厂: {}", e.getMessage());
            }
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        return factory;
    }
}
