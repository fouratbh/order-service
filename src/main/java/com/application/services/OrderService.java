package com.application.services;

import com.application.dto.*;
import com.application.entities.Order;
import com.application.entities.OrderItem;
import com.application.enums.OrderStatus;
import com.application.events.OrderConfirmedEvent;
import com.application.kafka.OrderEventProducer;
import com.application.repos.OrderRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventProducer eventProducer;
    private final WebClient inventoryWebClient;
    private final ObjectMapper objectMapper;

    public OrderDto createOrder(OrderCreateDto dto, String username, Long userId) {
        List<OrderItem> items = dto.items().stream().map(itemDto -> {
            ProductSnapshotDto product = getProductSnapshot(itemDto.productId());

            if (product == null) {
                throw new RuntimeException("Produit introuvable ID: " + itemDto.productId());
            }

            Integer stock = product.quantityInStock();
            if (stock == null) {
                log.warn("Stock NULL pour produit {}, on considère 0", product.name());
                stock = 0;
            }

            log.info("Produit {} — Nom={}, Stock={}",
                itemDto.productId(), product.name(), stock);

            if (stock < itemDto.quantity()) {
                throw new IllegalArgumentException(
                    "Stock insuffisant pour : " + product.name());
            }

            return OrderItem.builder()
                .productId(product.id())
                .productName(product.name())
                .productCode(product.code())
                .unitPrice(product.sellingPrice())
                .quantity(itemDto.quantity())
                .subtotal(product.sellingPrice()
                    .multiply(BigDecimal.valueOf(itemDto.quantity())))
                .build();
        }).toList();

        BigDecimal total = items.stream()
            .map(OrderItem::getSubtotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order order = Order.builder()
            .orderNumber("ORD-" + UUID.randomUUID().toString()
                .substring(0, 8).toUpperCase())
            .customerId(userId)
            .customerUsername(username)
            .status(OrderStatus.EN_ATTENTE)
            .totalAmount(total)
            .notes(dto.notes())
            .build();

        items.forEach(item -> item.setOrder(order));
        order.setItems(items);

        Order saved = orderRepository.save(order);
        log.info("Commande créée : {}", saved.getOrderNumber());
        return toDto(saved);
    }

    public OrderDto confirmOrder(Long orderId) {
        Order order = getOrderOrThrow(orderId);

        if (order.getStatus() != OrderStatus.EN_ATTENTE) {
            throw new IllegalStateException(
                "Seules les commandes EN_ATTENTE peuvent être confirmées");
        }

        order.setStatus(OrderStatus.CONFIRMEE);
        order.setConfirmedAt(LocalDateTime.now());
        orderRepository.save(order);

        OrderConfirmedEvent event = buildEvent(order);
        eventProducer.sendOrderConfirmed(event);

        log.info("Commande confirmée + Kafka : {}", order.getOrderNumber());
        return toDto(order);
    }

    public OrderDto updateStatus(Long orderId, OrderStatus newStatus) {
        Order order = getOrderOrThrow(orderId);
        validateTransition(order.getStatus(), newStatus);

        order.setStatus(newStatus);
        if (newStatus == OrderStatus.LIVREE) {
            order.setDeliveredAt(LocalDateTime.now());
        }

        if (newStatus == OrderStatus.ANNULEE
                && order.getStatus() == OrderStatus.CONFIRMEE) {
            eventProducer.sendOrderCancelled(buildEvent(order));
        }

        return toDto(orderRepository.save(order));
    }

    public OrderDto cancelOrder(Long orderId) {
        Order order = getOrderOrThrow(orderId);

        if (order.getStatus() == OrderStatus.LIVREE) {
            throw new IllegalStateException(
                "Impossible d'annuler une commande déjà livrée");
        }

        boolean wasConfirmed = order.getStatus() == OrderStatus.CONFIRMEE
            || order.getStatus() == OrderStatus.EN_PREPARATION;

        order.setStatus(OrderStatus.ANNULEE);
        orderRepository.save(order);

        if (wasConfirmed) {
            eventProducer.sendOrderCancelled(buildEvent(order));
        }

        return toDto(order);
    }

    @Transactional(readOnly = true)
    public List<OrderDto> getMyOrders(String username) {
        return orderRepository.findByCustomerUsernameOrderByCreatedAtDesc(username)
            .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<OrderDto> getAllOrders() {
        return orderRepository.findAll().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public OrderDto getOrderById(Long id) {
        return toDto(getOrderOrThrow(id));
    }

    // ---- Privé ----

    private Order getOrderOrThrow(Long id) {
        return orderRepository.findByIdWithItems(id)
            .orElseThrow(() -> new RuntimeException("Commande non trouvée ID: " + id));
    }

    private String getJwtToken() {
        Object principal = SecurityContextHolder.getContext()
            .getAuthentication().getPrincipal();
        if (principal instanceof Jwt jwt) {
            return jwt.getTokenValue();
        }
        throw new IllegalStateException("JWT introuvable dans le contexte de sécurité");
    }

    /**
     * Appel REST vers Inventory Service.
     * La réponse est : { success: true, data: { id, name, ... } }
     * On unwrap le champ "data" avant de désérialiser en ProductSnapshotDto.
     */
    private ProductSnapshotDto getProductSnapshot(Long productId) {
        try {
            // Récupérer la réponse brute sous forme de Map
            Map<String, Object> response = inventoryWebClient.get()
                .uri("/products/{id}", productId)
                .header("Authorization", "Bearer " + getJwtToken())
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            if (response == null) {
                log.error("Réponse null de l'Inventory pour produit {}", productId);
                return null;
            }

            log.debug("Réponse brute Inventory : {}", response);

            // Vérifier le succès
            Boolean success = (Boolean) response.get("success");
            if (Boolean.FALSE.equals(success)) {
                log.error("Inventory retourne success=false pour produit {}", productId);
                return null;
            }

            // Unwrap le champ "data"
            Object data = response.get("data");
            if (data == null) {
                log.error("Champ 'data' null pour produit {}", productId);
                return null;
            }

            // Convertir la Map en ProductSnapshotDto
            ProductSnapshotDto snapshot = objectMapper.convertValue(
                data, ProductSnapshotDto.class);

            log.info("Produit récupéré — id={}, nom={}, stock={}",
                snapshot.id(), snapshot.name(), snapshot.quantityInStock());

            return snapshot;

        } catch (Exception e) {
            log.error("Erreur appel Inventory pour produit {} : {}",
                productId, e.getMessage(), e);
            throw new RuntimeException(
                "Impossible de récupérer le produit " + productId + 
                " depuis l'Inventory Service", e);
        }
    }

    private OrderConfirmedEvent buildEvent(Order order) {
        return OrderConfirmedEvent.builder()
            .orderId(order.getId())
            .orderNumber(order.getOrderNumber())
            .items(order.getItems().stream()
                .map(i -> OrderConfirmedEvent.OrderItemEvent.builder()
                    .productId(i.getProductId())
                    .quantity(i.getQuantity())
                    .build())
                .toList())
            .build();
    }

    private void validateTransition(OrderStatus current, OrderStatus next) {
        boolean valid = switch (current) {
            case EN_ATTENTE ->
                next == OrderStatus.CONFIRMEE || next == OrderStatus.ANNULEE;
            case CONFIRMEE ->
                next == OrderStatus.EN_PREPARATION || next == OrderStatus.ANNULEE;
            case EN_PREPARATION ->
                next == OrderStatus.EXPEDIEE || next == OrderStatus.ANNULEE;
            case EXPEDIEE ->
                next == OrderStatus.LIVREE || next == OrderStatus.ANNULEE;
            default -> false;
        };
        if (!valid) throw new IllegalStateException(
            "Transition impossible : " + current + " → " + next);
    }

    private OrderDto toDto(Order order) {
        return new OrderDto(
            order.getId(),
            order.getOrderNumber(),
            order.getCustomerUsername(),
            order.getStatus(),
            order.getTotalAmount(),
            order.getNotes(),
            order.getCreatedAt(),
            order.getConfirmedAt(),
            order.getItems().stream().map(i -> new OrderItemDto(
                i.getProductId(),
                i.getProductName(),
                i.getProductCode(),
                i.getUnitPrice(),
                i.getQuantity(),
                i.getSubtotal()
            )).toList()
        );
    }
}