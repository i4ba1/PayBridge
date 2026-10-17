package com.paybridge.core.security;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Minimal RS256 signer and JWKS writer for unit tests. */
final class TestJwt {
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    record Key(String kid, KeyPair pair) {}

    private TestJwt() {}

    static Key key(String kid, int bits) {
        try {
            var gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(bits);
            return new Key(kid, gen.generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static Key key(String kid) {
        return key(kid, 2048);
    }

    static void writeJwks(Path file, List<Key> keys) throws IOException {
        var sb = new StringBuilder("{\"keys\":[");
        for (int i = 0; i < keys.size(); i++) {
            var pub = (RSAPublicKey) keys.get(i).pair().getPublic();
            sb.append(i > 0 ? "," : "").append("{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\"")
                    .append(keys.get(i).kid()).append("\",\"n\":\"").append(B64.encodeToString(unsigned(pub.getModulus())))
                    .append("\",\"e\":\"").append(B64.encodeToString(unsigned(pub.getPublicExponent()))).append("\"}");
        }
        Files.writeString(file, sb.append("]}").toString());
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        return b.length > 1 && b[0] == 0 ? java.util.Arrays.copyOfRange(b, 1, b.length) : b;
    }

    static Map<String, Object> claims(long now) {
        var c = new LinkedHashMap<String, Object>();
        c.put("iss", "paybridge-console");
        c.put("aud", "paybridge-core");
        c.put("sub", "00000000-0000-4000-8000-000000000501");
        c.put("mid", "00000000-0000-4000-8000-000000000301");
        c.put("op", "RequestPayment");
        c.put("rid", "demo-run-001");
        c.put("jti", UUID.randomUUID().toString());
        c.put("iat", now);
        c.put("nbf", now);
        c.put("exp", now + 30);
        return c;
    }

    static String sign(Key key, Map<String, Object> claims) {
        return sign(key, Map.of("alg", "RS256", "typ", "JWT", "kid", key.kid()), claims);
    }

    static String sign(Key key, Map<String, Object> header, Map<String, Object> claims) {
        try {
            String h = B64.encodeToString(json(header).getBytes(StandardCharsets.UTF_8));
            String p = B64.encodeToString(json(claims).getBytes(StandardCharsets.UTF_8));
            var sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(key.pair().getPrivate());
            sig.update((h + "." + p).getBytes(StandardCharsets.US_ASCII));
            return h + "." + p + "." + B64.encodeToString(sig.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String json(Map<String, Object> map) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String b64(String json) {
        return B64.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
