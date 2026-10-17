package com.paybridge.core.seed;

import com.paybridge.application.port.IdentityStore;
import com.paybridge.application.port.SeedStore;
import com.paybridge.core.config.Roles;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Role seed: runs the idempotent seed once (profile base or demo) and exits with code 0 even when all rows existed. */
@Configuration(proxyBeanMethods = false)
public class SeedRunner {
    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);
    private static final String SA_DIR = "/var/run/secrets/kubernetes.io/serviceaccount/";

    @Bean
    @Lazy
    CredentialSink credentialSink(Environment env,
                                  @Value("${paybridge.seed.credentials.sink:kubernetes}") String sink,
                                  @Value("${paybridge.seed.credentials.file:}") String file,
                                  @Value("${paybridge.seed.credentials.secret-name:paybridge-demo-accounts}") String secret) {
        return switch (sink) {
            case "file" -> new FileCredentialSink(Path.of(file));
            case "kubernetes" -> kubernetesSink(secret);
            default -> throw new IllegalArgumentException("paybridge.seed.credentials.sink must be file or kubernetes");
        };
    }

    private static CredentialSink kubernetesSink(String secret) {
        String host = System.getenv("KUBERNETES_SERVICE_HOST");
        String port = System.getenv().getOrDefault("KUBERNETES_SERVICE_PORT", "443");
        if (host == null) {
            // Not inside a cluster: fail only when a password actually has to be stored.
            return passwords -> {
                if (!passwords.isEmpty()) {
                    throw new IllegalStateException("no Kubernetes API available; set paybridge.seed.credentials.sink=file");
                }
            };
        }
        try {
            var tokenPath = Path.of(SA_DIR, "token");
            String namespace = Files.readString(Path.of(SA_DIR, "namespace")).strip();
            var store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            try (var in = Files.newInputStream(Path.of(SA_DIR, "ca.crt"))) {
                store.setCertificateEntry("kube-ca", CertificateFactory.getInstance("X.509").generateCertificate(in));
            }
            var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            var tls = SSLContext.getInstance("TLS");
            tls.init(null, tmf.getTrustManagers(), null);
            return new KubernetesSecretSink(URI.create("https://" + host + ":" + port), namespace, secret,
                    () -> {
                        try {
                            return Files.readString(tokenPath).strip();
                        } catch (java.io.IOException e) {
                            throw new IllegalStateException("cannot read the service account token");
                        }
                    }, tls);
        } catch (Exception e) {
            throw new IllegalStateException("cannot set up the Kubernetes client: " + e.getClass().getSimpleName());
        }
    }

    @Bean
    @Lazy
    DemoSeeder demoSeeder(SeedStore seedStore, IdentityStore identityStore, PasswordEncoder encoder,
                          CredentialSink sink) {
        return new DemoSeeder(seedStore, identityStore, encoder, sink);
    }

    @Bean
    ApplicationRunner seedApplicationRunner(Environment env, ObjectProvider<DemoSeeder> seeders,
                                            @Value("${paybridge.seed.profile:demo}") String profile,
                                            @Value("${paybridge.seed.bootstrap-token:}") String bootstrapToken,
                                            @Value("${paybridge.security.bootstrap.token-ttl-hours:24}") int ttlHours) {
        return (ApplicationArguments args) -> {
            if (!Roles.from(env).has("seed")) {
                return;
            }
            if (!profile.equals("base") && !profile.equals("demo")) {
                throw new IllegalArgumentException("paybridge.seed.profile must be base or demo");
            }
            var report = seeders.getObject().run(new DemoSeeder.Options(profile.equals("demo"), bootstrapToken, ttlHours));
            log.info("seed finished: lock {}, bootstrap token rearmed {}", report.lockObtained() ? "obtained" : "busy",
                    report.bootstrapRearmed() == 1);
        };
    }
}
