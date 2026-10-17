package com.paybridge.core.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Public keys for the console signature, read from the jwks.json entry of the mounted Secret (D-17). Two keys may be
 * active during rotation; kid selects one. The file is re-read when it changes or when an unknown kid appears (at most
 * once per second), so a rotated Secret takes effect without a restart. Only RSA keys of 2048 bits or more are used.
 */
public class JwksKeyProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MIN_RELOAD_MILLIS = 1000;

    private final Path file;
    private final Clock clock;
    private volatile Map<String, RSAPublicKey> keys = Map.of();
    private volatile long loadedModified = Long.MIN_VALUE;
    private volatile long lastAttemptMillis = 0;

    public JwksKeyProvider(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
        reload();
    }

    public Optional<RSAPublicKey> key(String kid) {
        var found = keys.get(kid);
        if (found != null && !changed()) {
            return Optional.of(found);
        }
        if (clock.millis() - lastAttemptMillis >= MIN_RELOAD_MILLIS) {
            reload();
        }
        return Optional.ofNullable(keys.get(kid));
    }

    public int activeKeyCount() {
        return keys.size();
    }

    private boolean changed() {
        try {
            return Files.getLastModifiedTime(file).toMillis() != loadedModified;
        } catch (IOException e) {
            return false;
        }
    }

    private synchronized void reload() {
        lastAttemptMillis = clock.millis();
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            JsonNode root = JSON.readTree(Files.readAllBytes(file));
            var parsed = new HashMap<String, RSAPublicKey>();
            for (JsonNode k : root.path("keys")) {
                String kid = k.path("kid").asText("");
                if (kid.isEmpty() || !"RSA".equals(k.path("kty").asText())) {
                    continue;
                }
                if (k.has("alg") && !"RS256".equals(k.path("alg").asText())) {
                    continue;
                }
                if (k.has("use") && !"sig".equals(k.path("use").asText())) {
                    continue;
                }
                var n = new BigInteger(1, Base64.getUrlDecoder().decode(k.path("n").asText()));
                var e = new BigInteger(1, Base64.getUrlDecoder().decode(k.path("e").asText()));
                if (n.bitLength() < 2048) {
                    continue;
                }
                parsed.put(kid, (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e)));
            }
            this.keys = Map.copyOf(parsed);
            this.loadedModified = modified;
        } catch (IOException | java.security.GeneralSecurityException | IllegalArgumentException e) {
            // Keep serving the previous keys when a rewrite is half done; an empty provider rejects everything.
        }
    }
}
