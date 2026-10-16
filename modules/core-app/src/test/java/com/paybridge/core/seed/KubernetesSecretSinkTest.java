package com.paybridge.core.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KubernetesSecretSinkTest {
    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private KubernetesSecretSink sink() {
        return new KubernetesSecretSink(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "paybridge", "paybridge-demo-accounts", () -> "sa-token", null);
    }

    @Test
    void mergePatchesOnlyTheNamedKeysOfTheNamedSecret() {
        var passwords = new LinkedHashMap<String, String>();
        passwords.put("demo-operator-a", "Abcdefghij0123456789");
        sink().store(passwords);

        assertThat(method.get()).isEqualTo("PATCH");
        assertThat(path.get()).isEqualTo("/api/v1/namespaces/paybridge/secrets/paybridge-demo-accounts");
        assertThat(contentType.get()).isEqualTo("application/merge-patch+json");
        assertThat(authorization.get()).isEqualTo("Bearer sa-token");
        assertThat(body.get()).isEqualTo("{\"stringData\":{\"demo-operator-a\":\"Abcdefghij0123456789\"}}");
    }

    @Test
    void nothingIsSentWhenThereAreNoNewAccounts() {
        sink().store(Map.of());
        assertThat(method.get()).isNull();
    }

    @Test
    void aFailureNamesTheStatusButNeverAValue() {
        status = 403;
        assertThatThrownBy(() -> sink().store(Map.of("demo-viewer-a", "SuperSecretValue12345")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("403")
                .hasMessageNotContaining("SuperSecretValue12345");
    }

    @Test
    void unreachableApiServerDoesNotLeakValuesEither() {
        server.stop(0);
        assertThatThrownBy(() -> sink().store(Map.of("demo-viewer-a", "SuperSecretValue12345")))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("SuperSecretValue12345");
    }

    @Test
    void fileSinkMergesAndKeepsEarlierEntries(@TempDir Path dir) throws IOException {
        var file = dir.resolve("nested/accounts.env");
        var sink = new FileCredentialSink(file);
        sink.store(Map.of("demo-operator-a", "one"));
        sink.store(Map.of("demo-viewer-a", "two"));
        assertThat(Files.readAllLines(file)).containsExactly("demo-operator-a=one", "demo-viewer-a=two");
    }
}
