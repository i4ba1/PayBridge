package com.paybridge.probe.messaging;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@org.springframework.context.annotation.Profile("!standalone")
@Configuration
public class ProbeMessaging {
    public static final String EXCHANGE = "probe.events";
    public static final String QUEUE = "probe.events.q";

    @Bean
    DirectExchange probeExchange() {
        return new DirectExchange(EXCHANGE);
    }

    /** Quorum queue, as the production topology will use. */
    @Bean
    Queue probeQueue() {
        return QueueBuilder.durable(QUEUE).quorum().build();
    }

    @Bean
    Binding probeBinding(Queue probeQueue, DirectExchange probeExchange) {
        return BindingBuilder.bind(probeQueue).to(probeExchange).with("probe");
    }

    @org.springframework.context.annotation.Profile("!standalone")
    @Component
    public static class Publisher {
        private final RabbitTemplate template;

        public Publisher(RabbitTemplate template) {
            this.template = template;
        }

        public void publish(String body) {
            template.convertAndSend(EXCHANGE, "probe", body);
        }
    }

    @org.springframework.context.annotation.Profile("!standalone")
    @Component
    public static class Consumer {
        private final BlockingQueue<String> received = new LinkedBlockingQueue<>();

        @RabbitListener(queues = QUEUE)
        void on(String body) {
            received.add(body);
        }

        public BlockingQueue<String> received() {
            return received;
        }
    }
}
