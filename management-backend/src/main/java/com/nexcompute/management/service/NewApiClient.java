package com.nexcompute.management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexcompute.management.config.NewApiProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NewAPI（v1.0.0-rc.22）用户管理 REST 同步客户端（newapi-user-allocation D6）。
 * Spring {@link RestClient}，{@code Authorization: Bearer ${accessToken}} + {@code New-Api-User: ${apiUser}} 头。
 * NewAPI 为 http，无需自签 TLS 关校验（比 {@code TrueNasClient} 简单）。
 * <p>响应经 {@code {success,message,data}} 信封封装：成功取 {@code data}，{@code success=false} 抛 {@link NewApiApiError}。
 * <p>方法 1:1 对照 {@code TrueNasClient}：{@link #statusPing} / {@link #userSearch} / {@link #userCreate} /
 * {@link #userGetInstance} / {@link #userUpdate} / {@link #userDelete}。
 * <p>启动 {@link ApplicationReadyEvent} ping 仅告警不阻断（D10）。
 *
 * <h3>⚠ 严禁调用 {@code GET /api/user/token}</h3>
 * 该端点 <b>非只读</b>：它会重新生成当前用户的系统访问令牌，旧令牌立即失效（实测被轮换）。
 * 本客户端方法集 <b>显式排除</b>该端点，严禁新增对应方法。
 * 令牌轮换须在 NewUI/数据库侧操作后更新 {@code NEWAPI_ACCESS_TOKEN} 并重启容器。
 * 详见记忆 newapi-user-quota-quirks。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewApiClient {

    private final NewApiProperties newApiProperties;
    private final ObjectMapper objectMapper;

    private RestClient restClient;
    private int retries;

    /** 权限错误体标记（命中即判为 NewApiPermissionError） */
    private static final String[] PERM_MARKERS = {
            "unauthorized", "forbidden", "no permission", "permission denied",
            "insufficient privileg", "not authorized", "access denied", "无权", "无权限"
    };

    @PostConstruct
    void init() {
        this.retries = Math.max(0, newApiProperties.getRetries());
        Duration timeout = Duration.ofSeconds(newApiProperties.getTimeoutSeconds());
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + newApiProperties.getAccessToken())
                .defaultHeader("New-Api-User", newApiProperties.getApiUser())
                .defaultHeader("Content-Type", "application/json")
                .build();
        log.info("[NewAPI] 客户端就绪: baseUrl={}, apiUser={}, timeout={}s, retries={}",
                newApiProperties.getBaseUrl(), newApiProperties.getApiUser(),
                newApiProperties.getTimeoutSeconds(), retries);
    }

    // ==================== 启动健康检查（D10） ====================

    @EventListener(ApplicationReadyEvent.class)
    public void pingOnStartup() {
        try {
            statusPing();
            log.info("[NewAPI] 启动 ping 成功: {}", newApiProperties.getBaseUrl());
        } catch (Exception e) {
            log.warn("[NewAPI] 启动 ping 失败（不阻断启动；到 approve 时才暴露为 FAILED）: {}", e.getMessage());
        }
    }

    // ==================== 高层方法（1:1 对照 TrueNasClient） ====================

    /** GET /api/status -> NewAPI 系统信息（启动健康检查，只读安全） */
    public Object statusPing() {
        return request(HttpMethod.GET, "/api/status", null, null);
    }

    /** GET /api/user/search?keyword= -> 匹配用户列表（可能为空） */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> userSearch(String keyword) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("keyword", keyword);
        Object result = request(HttpMethod.GET, "/api/user/search", params, null);
        if (result == null) {
            return List.of();
        }
        // NewAPI rc.22 search 返回分页对象 {page, page_size, total, items:[...]}；兼容直接 list 旧格式
        Object list = result;
        if (result instanceof Map<?, ?> m && m.get("items") instanceof List<?> items) {
            list = items;
        }
        if (!(list instanceof List<?> l)) {
            throw new NewApiApiError("user.search returned unexpected: " + result, "GET /api/user/search", null);
        }
        return (List<Map<String, Object>>) (List<?>) l;
    }

    /**
     * POST /api/user/ 建用户（body 含 username/password/display_name/group，不传 quota）。
     * 响应仅 {success:true} 无 id（D13）：建后内部 userSearch 回查拿 id 返回。
     * 用户名唯一，search 总命中；未命中抛 {@link NewApiApiError}。
     */
    public Integer userCreate(String username, String password, String displayName, String email, String group) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("password", password);
        payload.put("display_name", displayName);
        if (group != null && !group.isBlank()) {
            payload.put("group", group);
        }
        if (email != null && !email.isBlank()) {
            payload.put("email", email);
        }
        // quota 不传（NewAPI 忽略，额度走全局 QuotaForNewUser）
        request(HttpMethod.POST, "/api/user/", null, payload);
        log.info("[NewAPI] 用户已创建（响应无 id，回查 search）: username={}", username);
        // 建后回查拿 id
        Integer id = findUserIdByUsername(username);
        if (id == null) {
            throw new NewApiApiError("user.create succeeded but search found no id for username=" + username,
                    "POST /api/user/", null);
        }
        return id;
    }

    /** GET /api/user/{id} 详情；404 = 用户已删（用于 NOT_FOUND 翻转） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> userGetInstance(Integer userId) {
        Object result = request(HttpMethod.GET, "/api/user/" + userId, null, null);
        if (result != null && !(result instanceof Map<?, ?>)) {
            throw new NewApiApiError("user.get_instance returned unexpected: " + result, "GET /api/user/" + userId, null);
        }
        return (Map<String, Object>) result;
    }

    /**
     * PUT /api/user/ 更新（改 group 等）。body 须含 id + username + 变更字段（缺 username 报 Invalid parameters，D14）。
     * 不改 quota（PUT 不生效，额度走全局 QuotaForNewUser）。
     */
    public void userUpdate(Integer userId, String username, Map<String, Object> fields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", userId);
        payload.put("username", username);
        if (fields != null) {
            payload.putAll(fields);
        }
        request(HttpMethod.PUT, "/api/user/", null, payload);
        log.info("[NewAPI] 用户已更新: id={}, username={}, fields={}", userId, username, fields == null ? "{}" : fields.keySet());
    }

    /** DELETE /api/user/{id} 删除用户 */
    public void userDelete(Integer userId) {
        request(HttpMethod.DELETE, "/api/user/" + userId, null, null);
        log.info("[NewAPI] 用户已删除: id={}", userId);
    }

    // ==================== 内部辅助 ====================

    /** 按 username 精确匹配查 NewAPI 用户 id；无精确匹配返回 null（供幂等查重 + 建后回查） */
    private Integer findUserIdByUsername(String username) {
        for (Map<String, Object> u : userSearch(username)) {
            Object name = u.get("username");
            if (name != null && name.toString().equals(username)) {
                Object id = u.get("id");
                if (id instanceof Number n) {
                    return n.intValue();
                }
            }
        }
        return null;
    }

    // ==================== 底层请求（含重试 + 错误分类 + 信封解包） ====================

    /**
     * 执行请求，返回解包后的 data（Map/List/String/Integer/null）。
     * 瞬态 IO 错误（{@link ResourceAccessException}）重试 {@link #retries} 次；
     * 4xx/5xx 经 {@link #raiseForError} 分类；2xx 但 success=false 抛 {@link NewApiApiError}。
     * URL 以完整字符串构造以保留 /api/user/ 尾斜杠（gin POST/PUT 须尾斜杠）。
     */
    private Object request(HttpMethod method, String path, Map<String, String> params, Object body) {
        String base = newApiProperties.getBaseUrl().replaceAll("/+$", "");
        String url = base + path;
        String label = method + " " + path;
        URI uri = buildUri(url, params);
        int attempts = retries + 1;
        Exception lastExc = null;
        for (int i = 0; i < attempts; i++) {
            try {
                RestClient.RequestBodySpec spec = restClient.method(method).uri(uri);
                if (body != null) {
                    return unwrap(spec.body(body).retrieve()
                            .onStatus(s -> s.value() >= 400, (req, res) -> raiseForError(res.getStatusCode().value(),
                                    StreamUtils.copyToString(res.getBody(), StandardCharsets.UTF_8), label))
                            .body(Object.class), label);
                }
                return unwrap(spec.retrieve()
                        .onStatus(s -> s.value() >= 400, (req, res) -> raiseForError(res.getStatusCode().value(),
                                StreamUtils.copyToString(res.getBody(), StandardCharsets.UTF_8), label))
                        .body(Object.class), label);
            } catch (ResourceAccessException e) {
                lastExc = e;
                log.debug("[NewAPI] {} 连接错误，重试 {}/{}: {}", label, i + 1, attempts, e.getMessage());
            }
        }
        throw new NewApiConnectionError(
                "NewAPI unreachable after " + attempts + " attempt(s): " + (lastExc == null ? "" : lastExc.getMessage()),
                lastExc);
    }

    /** 拼完整 URI（含 query）；query 值经 URLEncoder 编码防特殊字符 */
    private static URI buildUri(String url, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return URI.create(url);
        }
        StringBuilder sb = new StringBuilder(url).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return URI.create(sb.toString());
    }

    /**
     * 解包 NewAPI 信封 {success,message,data}：
     * success=true 返回 data；success=false 抛 {@link NewApiApiError}（带 message）；
     * 非信封（无 success 键）原样返回。
     */
    private Object unwrap(Object parsed, String label) {
        if (!(parsed instanceof Map<?, ?> map)) {
            return parsed;
        }
        Object success = map.get("success");
        if (success == null) {
            return parsed; // 非信封响应，原样返回
        }
        boolean ok = Boolean.TRUE.equals(success) || "true".equalsIgnoreCase(String.valueOf(success));
        if (!ok) {
            String msg = str(map.get("message"));
            String detail = (msg == null || msg.isBlank()) ? "no detail" : msg;
            // NewAPI rc.22 对"用户不存在"返回 200 + {success:false, message:"record not found"}（非 HTTP 404）；
            // 语义化为 httpStatus=404，让 getDetail/refreshAllStatuses 的 NOT_FOUND 翻转逻辑命中
            Integer status = (msg != null && msg.toLowerCase().contains("not found")) ? 404 : null;
            throw new NewApiApiError("NewAPI " + label + " returned success=false: " + detail, label, status);
        }
        return map.get("data");
    }

    /** 解析错误体并按状态/权限标记分类抛出 */
    private void raiseForError(int status, String body, String label) {
        String message = extractMessage(body);
        String detail = message.isBlank() ? "no detail" : message;
        if (status == 401 || status == 403 || isPermissionText(message)) {
            throw new NewApiPermissionError(
                    "NewAPI " + label + " permission error (" + status + "): " + detail, label, status);
        }
        throw new NewApiApiError("NewAPI " + label + " failed (" + status + "): " + detail, label, status);
    }

    /** 从 JSON 错误体提取 message/error；非 JSON 取原文（截断 200 字符） */
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
                return msg == null ? "" : msg;
            } else if (parsed instanceof String s && !s.isEmpty()) {
                return s;
            }
        } catch (Exception ignored) {
            // 非 JSON，按原文处理
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
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
}
