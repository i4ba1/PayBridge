package com.paybridge.probe.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.apollographql.apollo.runtime.java.ApolloClient;
import com.paybridge.probe.ProbeApplication;
import com.paybridge.probe.client.CompletionStageAdapter;
import com.paybridge.probe.client.gen.EchoMutation;
import com.paybridge.probe.client.gen.PingQuery;
import com.paybridge.probe.domain.ProbeItem;
import com.paybridge.probe.messaging.ProbeMessaging;
import com.paybridge.probe.mongo.ProbeViewStore;
import com.paybridge.probe.persistence.ProbeItemRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@Tag("integration")
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@Testcontainers
@SpringBootTest(classes = ProbeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
class StackIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");
    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        r.add("spring.rabbitmq.host", RABBIT::getHost);
        r.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        r.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort
    int port;
    @LocalManagementPort
    int managementPort;
    @Autowired
    ProbeItemRepository items;
    @Autowired
    ProbeMessaging.Publisher publisher;
    @Autowired
    ProbeMessaging.Consumer consumer;
    @Autowired
    ProbeViewStore views;
    @Autowired
    SessionRepository<?> sessions;

    @Test
    void flywayMigratesAndJpaPersists() {
        ProbeItem saved = items.save(new ProbeItem("x", 1500L));
        assertThat(items.findById(saved.getId())).get().extracting(ProbeItem::getAmountMinor).isEqualTo(1500L);
    }

    @Test
    void rabbitQuorumQueueRoundTrip() throws Exception {
        publisher.publish("hello");
        assertThat(consumer.received().poll(10, TimeUnit.SECONDS)).isEqualTo("hello");
    }

    @Test
    void mongoUpsertKeepsHighestRevision() {
        views.upsert("v1", "a", 2);
        views.upsert("v1", "b", 1);
        assertThat(views.find("v1").revision()).isEqualTo(2);
    }

    @Test
    void sessionIsStoredInRedis() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/probe/session")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(sessions.findById(r.body())).isNotNull();
    }

    @Test
    void prometheusScrapeServedOnManagementPort() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("jvm_memory_used_bytes");
    }

    @Test
    void apolloCallsDgsWithTypedDataGraphqlErrorAndTransportFailure() throws Exception {
        try (ApolloClient client = new ApolloClient.Builder().serverUrl("http://localhost:" + port + "/graphql").build()) {
            var ping = CompletionStageAdapter.execute(client.query(new PingQuery())).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertThat(ping.data.ping).isEqualTo("pong");

            var echo = CompletionStageAdapter.execute(client.mutation(new EchoMutation("abc"))).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertThat(echo.data.echo.length).isEqualTo(3);

            var failed = CompletionStageAdapter.execute(client.mutation(new EchoMutation("boom"))).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertThat(failed.hasErrors()).isTrue();
            assertThat(failed.exception).isNull();
        }
        try (ApolloClient dead = new ApolloClient.Builder().serverUrl("http://localhost:1/graphql").build()) {
            var transport = CompletionStageAdapter.execute(dead.query(new PingQuery())).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertThat(transport.exception).isNotNull();
            assertThat(transport.data).isNull();
        }
    }
}
