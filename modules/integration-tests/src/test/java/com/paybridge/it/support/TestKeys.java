package com.paybridge.it.support;

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

/** RSA key pairs, a JWKS file writer and an RS256 token signer for service context tests. */
public final class TestKeys {
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    public record Key(String kid, KeyPair pair) {}

    private TestKeys() {}

    public static Key generate(String kid) {
        try {
            var gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return new Key(kid, gen.generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void writeJwks(Path file, List<Key> keys) {
        var sb = new StringBuilder("{\"keys\":[");
        for (int i = 0; i < keys.size(); i++) {
            var pub = (RSAPublicKey) keys.get(i).pair().getPublic();
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\"").append(keys.get(i).kid())
                    .append("\",\"n\":\"").append(B64.encodeToString(unsigned(pub.getModulus())))
                    .append("\",\"e\":\"").append(B64.encodeToString(unsigned(pub.getPublicExponent()))).append("\"}");
        }
        try {
            Files.writeString(file, sb.append("]}").toString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] t = new byte[b.length - 1];
            System.arraycopy(b, 1, t, 0, t.length);
            return t;
        }
        return b;
    }

    /** Claims in a valid token: everything the contract lists, 30 second lifetime starting now. */
    public static Map<String, Object> claims(String sub, String mid, String op, long nowSeconds) {
        var c = new LinkedHashMap<String, Object>();
        c.put("iss", "paybridge-console");
        c.put("aud", "paybridge-core");
        if (sub != null) {
            c.put("sub", sub);
        }
        if (mid != null) {
            c.put("mid", mid);
        }
        c.put("op", op);
        c.put("rid", "test-" + UUID.randomUUID().toString().substring(0, 8));
        c.put("jti", UUID.randomUUID().toString());
        c.put("iat", nowSeconds);
        c.put("nbf", nowSeconds);
        c.put("exp", nowSeconds + 30);
        return c;
    }

    public static String sign(Key key, Map<String, Object> claims) {
        return sign(key, Map.of("alg", "RS256", "typ", "JWT", "kid", key.kid()), claims);
    }

    public static String sign(Key key, Map<String, Object> header, Map<String, Object> claims) {
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

    public static String unsigned(Map<String, Object> header, Map<String, Object> claims) {
        return B64.encodeToString(json(header).getBytes(StandardCharsets.UTF_8)) + "."
                + B64.encodeToString(json(claims).getBytes(StandardCharsets.UTF_8)) + ".c2ln";
    }

    private static String json(Map<String, Object> map) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
