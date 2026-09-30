package com.nexcompute.management.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.config.NexcomputeProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RegistryClient 单元测试（任务 2.2）：JDK 内置 HttpServer 模拟 Registry v2 API。
 * 覆盖 exists 200/404/连接异常三分支、catalog 分页 Link、tags 200/404。
 */
class RegistryClientTest {

    private HttpServer server;
    private RegistryClient client;
    private String baseUrl;

    /** 控制器标志：true=模拟不可达（直接关闭连接处理） */
    private volatile boolean simulateDown = false;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/v2", exchange -> {
            if (simulateDown) {
                exchange.close();
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            if (path.equals("/v2/lab404-jupyter/manifests/0.1")) {
                respond(exchange, 200, null, null);
            } else if (path.startsWith("/v2/") && path.contains("/manifests/")) {
                respond(exchange, 404, "{\"errors\":[{\"code\":\"MANIFEST_UNKNOWN\"}]}", "application/json");
            } else if (path.equals("/v2/_catalog")) {
                if (query != null && query.contains("last=repo-a")) {
                    // 第二页（无 Link -> 结束）
                    respond(exchange, 200, "{\"repositories\":[\"repo-b\"]}", "application/json");
                } else {
                    respond(exchange, 200, "{\"repositories\":[\"repo-a\"]}", "application/json",
                            "</v2/_catalog?last=repo-a&n=1000>; rel=\"next\"");
                }
            } else if (path.equals("/v2/lab404-jupyter/tags/list")) {
                respond(exchange, 200, "{\"name\":\"lab404-jupyter\",\"tags\":[\"0.1\",\"latest\"]}", "application/json");
            } else if (path.endsWith("/tags/list")) {
                respond(exchange, 404, "{\"errors\":[{\"code\":\"NAME_UNKNOWN\"}]}", "application/json");
            } else {
                respond(exchange, 404, null, null);
            }
        });
        server.start();

        NexcomputeProperties properties = new NexcomputeProperties();
        properties.getRegistry().setUrl(baseUrl);
        client = new RegistryClient(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body,
                         String contentType) throws IOException {
        respond(exchange, status, body, contentType, null);
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body,
                         String contentType, String linkHeader) throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        if (linkHeader != null) {
            exchange.getResponseHeaders().set("Link", linkHeader);
        }
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body == null ? -1 : bytes.length);
        if (body != null) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } else {
            exchange.close();
        }
    }

    @Test
    void exists_200_returnsTrue() {
        assertThat(client.exists("lab404-jupyter", "0.1")).isTrue();
    }

    @Test
    void exists_404_returnsFalse() {
        assertThat(client.exists("lab404-jupyter", "no-such-tag")).isFalse();
        assertThat(client.exists("no-such-repo", "0.1")).isFalse();
    }

    @Test
    void exists_connectionError_throwsBusinessException_notFalse() {
        simulateDown = true;
        assertThatThrownBy(() -> client.exists("lab404-jupyter", "0.1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.REGISTRY_UNAVAILABLE));
    }

    @Test
    void exists_unreachableHost_throwsBusinessException() {
        // 封闭端口（HttpServer 已停）：连接拒绝必须抛错而非误判不存在
        server.stop(0);
        server = null;
        assertThatThrownBy(() -> client.exists("lab404-jupyter", "0.1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.REGISTRY_UNAVAILABLE));
    }

    @Test
    void catalog_followsLinkPagination() {
        List<String> repos = client.catalog();
        assertThat(repos).containsExactly("repo-a", "repo-b");
    }

    @Test
    void tags_200_returnsList() {
        assertThat(client.tags("lab404-jupyter")).containsExactly("0.1", "latest");
    }

    @Test
    void tags_404_returnsEmpty() {
        assertThat(client.tags("no-such-repo")).isEmpty();
    }

    @Test
    void urls_deriveFromConfig() {
        assertThat(client.getRegistryUrl()).isEqualTo(baseUrl);
        assertThat(client.getApiBaseUrl()).isEqualTo("http://" + baseUrl + "/v2");
    }
}
