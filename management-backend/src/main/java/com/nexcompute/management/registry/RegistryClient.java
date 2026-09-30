package com.nexcompute.management.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.config.NexcomputeProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 私有镜像仓库 Registry v2 API 客户端（registry-image-distribution D3）。
 *
 * 基址由 nexcompute.registry.url 派生（http://{url}/v2/...），与 docker push/pull 同端口同服务。
 * - {@link #exists}：HEAD manifest 判断镜像是否已推送（200/404）。
 * - {@link #catalog}：枚举仓库全部 repo（_catalog，按 Link rel="next" 翻页）。
 * - {@link #tags}：repo 的 tag 列表。
 *
 * 连接异常/非预期状态码统一抛 {@link BusinessException}(REGISTRY_UNAVAILABLE)，
 * 绝不误判为"不存在"（有效性检查语义：不可达 != 无效，D3）。
 * 实测（任务 1.1）：标准 registry:2 返回结构，catalog/tags 为 JSON 数组字段，manifest 存在返回 OCI index。
 */
@Slf4j
@Component
public class RegistryClient {

    /** manifest Accept 头：覆盖 docker v2 / manifest list / OCI index（实测 .25 返回 OCI index）。 */
    private static final String MANIFEST_ACCEPT =
            "application/vnd.docker.distribution.manifest.v2+json,"
                    + "application/vnd.docker.distribution.manifest.list.v2+json,"
                    + "application/vnd.oci.image.manifest.v1+json,"
                    + "application/vnd.oci.image.index.v1+json,"
                    + "*/*";

    /** catalog 单页条数（风险节：按 Link 翻页，单页 n=1000）。 */
    private static final int CATALOG_PAGE_SIZE = 1000;

    /** catalog 翻页安全上限（防异常 Link 死循环）。 */
    private static final int MAX_PAGES = 100;

    private final NexcomputeProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RegistryClient(NexcomputeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @PostConstruct
    public void logConfig() {
        log.info("[RegistryClient] v2 API 基址: {}", getApiBaseUrl());
    }

    /** v2 API 基址（http://{url}/v2）。 */
    public String getApiBaseUrl() {
        return "http://" + properties.getRegistry().getUrl() + "/v2";
    }

    /** docker 引用前缀（{url}，如 10.13.66.25:5000/name:tag 的前缀）。 */
    public String getRegistryUrl() {
        return properties.getRegistry().getUrl();
    }

    /**
     * 镜像是否已推送至仓库：HEAD /v2/{repo}/manifests/{tag}。
     *
     * @return true=存在（200）；false=不存在（404）
     * @throws BusinessException 仓库不可达 / 非预期状态码（不误判为不存在）
     */
    public boolean exists(String repo, String tag) {
        // tag 字符集为 [a-zA-Z0-9._-]（registry 规范），无需 URL 编码
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getApiBaseUrl() + "/" + trimSlash(repo) + "/manifests/" + tag))
                .header("Accept", MANIFEST_ACCEPT)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<Void> response = execute(request, "HEAD manifest " + repo + ":" + tag);
        if (response.statusCode() == 200) {
            return true;
        }
        if (response.statusCode() == 404) {
            return false;
        }
        throw unavailable("HEAD manifest 状态码异常: " + response.statusCode() + "（" + repo + ":" + tag + "）");
    }

    /**
     * 枚举仓库全部 repo（GET /v2/_catalog?n=1000，Link rel="next" 翻页）。
     */
    public List<String> catalog() {
        List<String> all = new ArrayList<>();
        String query = "?n=" + CATALOG_PAGE_SIZE;
        for (int page = 0; page < MAX_PAGES && query != null; page++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getApiBaseUrl() + "/_catalog" + query))
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> response = executeForString(request, "catalog");
            if (response.statusCode() != 200) {
                throw unavailable("_catalog 状态码异常: " + response.statusCode());
            }
            all.addAll(parseStringArray(response.body(), "repositories", "_catalog"));
            query = parseNextLinkQuery(response.headers().firstValue("Link").orElse(null));
        }
        return all;
    }

    /**
     * repo 的 tag 列表（GET /v2/{repo}/tags/list）。repo 无 tag（被 GC）时可能 404，返回空列表。
     */
    public List<String> tags(String repo) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getApiBaseUrl() + "/" + trimSlash(repo) + "/tags/list"))
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> response = executeForString(request, "tags " + repo);
        if (response.statusCode() == 404) {
            return List.of();
        }
        if (response.statusCode() != 200) {
            throw unavailable("tags/list 状态码异常: " + response.statusCode() + "（" + repo + "）");
        }
        return parseStringArray(response.body(), "tags", "tags " + repo);
    }

    // ===== 内部工具 =====

    private HttpResponse<Void> execute(HttpRequest request, String what) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            throw unavailable(what + " 请求失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(what + " 请求被中断");
        }
    }

    private HttpResponse<String> executeForString(HttpRequest request, String what) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw unavailable(what + " 请求失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(what + " 请求被中断");
        }
    }

    /** 解析 JSON body 的字符串数组字段（如 repositories/tags；tag 列表可能为 null）。 */
    private List<String> parseStringArray(String body, String field, String what) {
        try {
            JsonNode node = objectMapper.readTree(body).path(field);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (JsonNode n : node) {
                String v = n.asText(null);
                if (v != null && !v.isBlank()) {
                    result.add(v);
                }
            }
            return result;
        } catch (Exception e) {
            throw unavailable("解析 " + what + " 响应失败: " + e.getMessage());
        }
    }

    /**
     * 解析 _catalog 响应 Link 头的 next 页查询串（如 </v2/_catalog?last=xxx&n=1000>; rel="next"）。
     * 无 next 页返回 null。
     */
    private String parseNextLinkQuery(String linkHeader) {
        if (linkHeader == null || linkHeader.isBlank()) {
            return null;
        }
        for (String part : linkHeader.split(",")) {
            String p = part.trim();
            if (!p.endsWith("rel=\"next\"")) {
                continue;
            }
            int lt = p.indexOf('<');
            int gt = p.indexOf('>');
            if (lt < 0 || gt <= lt) {
                return null;
            }
            String url = p.substring(lt + 1, gt);
            int q = url.indexOf('?');
            return q >= 0 ? url.substring(q) : null;
        }
        return null;
    }

    private BusinessException unavailable(String message) {
        return new BusinessException(ErrorCode.REGISTRY_UNAVAILABLE,
                "私有镜像仓库(" + properties.getRegistry().getUrl() + ")检查失败：" + message);
    }

    private String trimSlash(String s) {
        String r = s == null ? "" : s.trim();
        while (r.startsWith("/")) r = r.substring(1);
        while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r;
    }
}
