package com.paybridge.core;

import com.paybridge.core.config.Roles;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Composition root. Worker roles and DGS resolvers are added by later tasks. */
@SpringBootApplication(scanBasePackageClasses = CoreApplication.class)
public class CoreApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(CoreApplication.class, args);
        // migrate and seed run once and exit; the exit code is zero unless a runner failed.
        if (Roles.from(context.getEnvironment()).oneShot()) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
