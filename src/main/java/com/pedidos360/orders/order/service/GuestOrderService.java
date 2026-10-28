package com.pedidos360.orders.order.service;

import com.pedidos360.orders.notification.GuestVoucher;
import com.pedidos360.orders.notification.GuestVoucherRequested;
import com.pedidos360.orders.order.domain.Order;
import com.pedidos360.orders.order.domain.OrderItem;
import com.pedidos360.orders.order.domain.OrderStatus;
import com.pedidos360.orders.order.domain.OrderTransitionPolicy;
import com.pedidos360.orders.order.dto.CreateGuestOrderRequest;
import com.pedidos360.orders.order.dto.GuestLinkRequest;
import com.pedidos360.orders.order.dto.GuestOrderResponse;
import com.pedidos360.orders.order.exception.GuestOrderNotFoundException;
import com.pedidos360.orders.order.exception.OrderBusinessException;
import com.pedidos360.orders.order.repository.OrderRepository;
import com.pedidos360.orders.outbox.OrderEventPayload;
import com.pedidos360.orders.outbox.OutboxWriter;
import com.pedidos360.orders.outbox.RabbitConfiguration;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orders bought without login. The secret tracking token is the only
 * credential: whoever holds it can view and cancel the order. The raw token is
 * returned once on creation, sent by email, and is never persisted, published
 * or logged.
 */
@Service
public class GuestOrderService {

    private final OrderRepository orderRepository;
    private final OrderItemSnapshotter itemSnapshotter;
    private final OrderTransitionPolicy transitionPolicy;
    private final OutboxWriter outboxWriter;
    private final GuestTrackingTokens tokens;
    private final GuestTrackingLinks links;
    private final ApplicationEventPublisher events;

    public GuestOrderService(
            OrderRepository orderRepository,
            OrderItemSnapshotter itemSnapshotter,
            OrderTransitionPolicy transitionPolicy,
            OutboxWriter outboxWriter,
            GuestTrackingTokens tokens,
            GuestTrackingLinks links,
            ApplicationEventPublisher events
    ) {
        this.orderRepository = orderRepository;
        this.itemSnapshotter = itemSnapshotter;
        this.transitionPolicy = transitionPolicy;
        this.outboxWriter = outboxWriter;
        this.tokens = tokens;
        this.links = links;
        this.events = events;
    }

    @Transactional
    public GuestOrderResponse create(CreateGuestOrderRequest request) {
        List<OrderItem> items = itemSnapshotter.snapshot(request.items());
        String token = tokens.newToken();
        Order order = Order.createGuest(request.email(), tokens.hash(token), request.notes());
        order.replaceItems(items, request.notes());
        Order saved = orderRepository.save(order);
        requestVoucher(saved, token);
        return GuestOrderResponse.from(saved, token);
    }

    @Transactional(readOnly = true)
    public GuestOrderResponse get(String token) {
        Order order = orderRepository.findDetailedByGuestTokenHash(hashOf(token))
                .orElseThrow(GuestOrderNotFoundException::new);
        return GuestOrderResponse.from(order);
    }

    /** Same rule as a logged-in customer: a guest may only cancel while CREADO. */
    @Transactional
    public GuestOrderResponse cancel(String token) {
        Order order = orderRepository.findByGuestTokenHashForUpdate(hashOf(token))
                .orElseThrow(GuestOrderNotFoundException::new);
        if (order.getStatus() != OrderStatus.CREADO) {
            throw new OrderBusinessException("Solo se puede cancelar un pedido en estado CREADO");
        }
        transitionPolicy.requireTransition(order.getStatus(), OrderStatus.CANCELADO);
        order.markCancelled();
        UUID orderId = UUID.fromString(order.getId());
        outboxWriter.write(
                orderId,
                RabbitConfiguration.CANCELLED_ROUTING_KEY,
                new OrderEventPayload.Cancelled(orderId));
        return GuestOrderResponse.from(order);
    }

    /**
     * Always silent about existence: the caller gets the same answer whether the
     * order matches or not. A match rotates the token, so the previous link dies.
     */
    @Transactional
    public void requestLink(GuestLinkRequest request) {
        orderRepository.findGuestByOrderNumberAndEmailForUpdate(request.orderNumber(), request.email())
                .ifPresent(order -> {
                    String token = tokens.newToken();
                    order.rotateGuestToken(tokens.hash(token));
                    requestVoucher(order, token);
                });
    }

    private void requestVoucher(Order order, String token) {
        events.publishEvent(new GuestVoucherRequested(GuestVoucher.from(order, links.linkFor(token))));
    }

    /** Malformed and unknown tokens end in the same 404. */
    private String hashOf(String token) {
        if (!tokens.isWellFormed(token)) {
            throw new GuestOrderNotFoundException();
        }
        return tokens.hash(token);
    }
}
