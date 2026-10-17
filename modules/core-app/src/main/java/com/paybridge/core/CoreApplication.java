package com.paybridge.core;

import com.paybridge.core.config.Roles;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Composition root. Worker roles are added by later tasks. */
@SpringBootApplication(scanBasePackageClasses = CoreApplication.class)
public class CoreApplication {
    public static void main(String[] args) {
        // migrate and seed run once and exit without opening a port; the exit code is zero unless a runner failed.
        boolean oneShot = Roles.from(rolesEnvironment(args)).oneShot();
        var context = new SpringApplicationBuilder(CoreApplication.class)
                .web(oneShot ? WebApplicationType.NONE : WebApplicationType.SERVLET).run(args);
        if (oneShot) {
            System.exit(SpringApplication.exit(context));
        }
    }

    /** Roles come from --paybridge.roles=... or PAYBRIDGE_ROLES, the same sources Spring Boot will bind later. */
    private static org.springframework.core.env.Environment rolesEnvironment(String[] args) {
        String roles = System.getenv("PAYBRIDGE_ROLES");
        for (String arg : args) {
            if (arg.startsWith("--paybridge.roles=")) {
                roles = arg.substring("--paybridge.roles=".length());
            }
        }
        var env = new org.springframework.core.env.StandardEnvironment();
        if (roles != null) {
            env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("roles",
                    java.util.Map.of("paybridge.roles", roles)));
        }
        return env;
    }
}
