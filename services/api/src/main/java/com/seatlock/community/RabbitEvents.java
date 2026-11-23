package com.seatlock.community;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "seatlock.rabbit.enabled", havingValue = "true")
class RabbitEvents {
    static final String EXCHANGE = "seatlock.events";
    static final String QUEUE = "seatlock.notifications";
    static final String DEAD = QUEUE + ".dead";
    private final OutboxConsumer consumer;

    RabbitEvents(OutboxConsumer consumer) {
        this.consumer = consumer;
    }

    @Bean
    Declarables bookingQueues() {
        var exchange = new DirectExchange(EXCHANGE);
        var queue = QueueBuilder.durable(QUEUE).deadLetterExchange("").deadLetterRoutingKey(DEAD).build();
        var dead = QueueBuilder.durable(DEAD).build();
        return new Declarables(exchange, queue, dead, BindingBuilder.bind(queue).to(exchange).with(QUEUE));
    }

    @RabbitListener(queues = QUEUE)
    void receive(String event) {
        consumer.process(Long.parseLong(event));
    }
}
