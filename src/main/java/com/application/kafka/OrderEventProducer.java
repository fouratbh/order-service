package com.application.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.application.events.OrderConfirmedEvent;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public static final String ORDER_CONFIRMED_TOPIC = "order.confirmed";
    public static final String ORDER_CANCELLED_TOPIC = "order.cancelled";

    public void sendOrderConfirmed(OrderConfirmedEvent event) {
        kafkaTemplate.send(ORDER_CONFIRMED_TOPIC, event.getOrderNumber(), event);
        log.info("Event order.confirmed envoyé : {}", event.getOrderNumber());
    }

    public void sendOrderCancelled(OrderConfirmedEvent event) {
        kafkaTemplate.send(ORDER_CANCELLED_TOPIC, event.getOrderNumber(), event);
        log.info("Event order.cancelled envoyé : {}", event.getOrderNumber());
    }
}
