package com.paybridge.it.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** One Redis per test JVM, the digest pinned in ops/toolchain.lock.yaml. */
public final class RedisSupport {
    private static final GenericContainer<?> REDIS = start();

    private RedisSupport() {}

    private static GenericContainer<?> start() {
        var c = new GenericContainer<>(DockerImageName.parse(MysqlSupport.pinnedImage("redis")).asCompatibleSubstituteFor("redis"))
                .withExposedPorts(6379);
        c.start();
        return c;
    }

    public static String host() {
        return REDIS.getHost();
    }

    public static int port() {
        return REDIS.getMappedPort(6379);
    }

    public static void stop() {
        REDIS.stop();
    }

    /** Restarts a stopped container is not supported by Testcontainers; tests that stop Redis use {@link #pause}. */
    public static void pause() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    public static void unpause() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }
}
