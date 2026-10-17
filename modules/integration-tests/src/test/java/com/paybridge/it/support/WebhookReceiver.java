package com.paybridge.it.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** A stand-in for the core's inbox route: records every delivery and answers with scripted statuses. */
public final class WebhookReceiver implements AutoCloseable {
    public record Delivery(String eventId, String timestamp, String signature, String rawBody) {}

    private final HttpServer server;
    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Integer> scripted = new ConcurrentLinkedQueue<>();
    private final AtomicInteger defaultStatus = new AtomicInteger(200);

    public WebhookReceiver() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/internal/webhooks/simulator", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            deliveries.add(new Delivery(exchange.getRequestHeaders().getFirst("X-PB-Event-Id"),
                    exchange.getRequestHeaders().getFirst("X-PB-Timestamp"),
                    exchange.getRequestHeaders().getFirst("X-PB-Signature"), body));
            Integer next = scripted.poll();
            exchange.sendResponseHeaders(next != null ? next : defaultStatus.get(), -1);
            exchange.close();
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/webhooks/simulator";
    }

    /** The next deliveries are answered with these statuses, in order; afterwards the default applies. */
    public void script(Integer... statuses) {
        scripted.clear();
        scripted.addAll(List.of(statuses));
    }

    public void answerWith(int status) {
        defaultStatus.set(status);
    }

    public List<Delivery> deliveries() {
        return List.copyOf(deliveries);
    }

    public List<Delivery> deliveriesFor(String providerKey) {
        return deliveries.stream().filter(d -> d.rawBody().contains("\"providerKey\":\"" + providerKey + "\"")).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
