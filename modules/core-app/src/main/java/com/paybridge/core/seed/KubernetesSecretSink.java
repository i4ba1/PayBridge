package com.paybridge.core.seed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;

/**
 * Merge patches the Secret paybridge-demo-accounts through the Kubernetes API using the pod's own token. The Secret is
 * created empty by the installer because the seed Role has no {@code create} verb (compatibility.md, T03). Only the
 * keys named in the patch change. Values are sent in {@code stringData} and are never logged or put in an exception.
 */
public final class KubernetesSecretSink implements CredentialSink {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI secretUri;
    private final Supplier<String> token;
    private final HttpClient http;

    public KubernetesSecretSink(URI apiServer, String namespace, String secretName, Supplier<String> token,
                                SSLContext tls) {
        this.secretUri = apiServer.resolve("/api/v1/namespaces/" + namespace + "/secrets/" + secretName);
        this.token = token;
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        if (tls != null) {
            builder.sslContext(tls);
        }
        this.http = builder.build();
    }

    @Override
    public void store(Map<String, String> passwordsByUsername) {
        if (passwordsByUsername.isEmpty()) {
            return;
        }
        String body;
        try {
            body = JSON.writeValueAsString(Map.of("stringData", passwordsByUsername));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not encode the patch");
        }
        var request = HttpRequest.newBuilder(secretUri).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token.get())
                .header("Content-Type", "application/merge-patch+json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        try {
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status / 100 != 2) {
                throw new IllegalStateException("patching the credential Secret failed with HTTP " + status);
            }
        } catch (IOException e) {
            throw new IllegalStateException("patching the credential Secret failed: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while patching the credential Secret");
        }
    }
}
