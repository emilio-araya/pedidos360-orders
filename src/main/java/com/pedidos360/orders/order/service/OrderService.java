package com.pedidos360.orders.order.service;

import com.pedidos360.orders.catalog.CatalogClient;
import com.pedidos360.orders.order.domain.Order;
import com.pedidos360.orders.order.domain.OrderItem;
import com.pedidos360.orders.order.domain.OrderStatus;
import com.pedidos360.orders.order.domain.OrderTransitionPolicy;
import com.pedidos360.orders.order.dto.CreateOrderRequest;
import com.pedidos360.orders.order.dto.OrderItemRequest;
import com.pedidos360.orders.order.dto.OrderResponse;
import com.pedidos360.orders.order.dto.PageResponse;
import com.pedidos360.orders.order.dto.UpdateOrderRequest;
import com.pedidos360.orders.order.exception.InvalidPageRequestException;
import com.pedidos360.orders.order.exception.OrderBusinessException;
import com.pedidos360.orders.order.exception.OrderNotFoundException;
import com.pedidos360.orders.order.repository.OrderRepository;
import com.pedidos360.orders.outbox.OrderEventPayload;
import com.pedidos360.orders.outbox.OutboxWriter;
import com.pedidos360.orders.outbox.RabbitConfiguration;
import com.pedidos360.orders.security.OrderAuthorizationService;
import com.pedidos360.orders.security.OrderPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final CatalogClient catalogClient;
    private final OrderTransitionPolicy transitionPolicy;
    private final OrderAuthorizationService authorizationService;
    private final OutboxWriter outboxWriter;

    public OrderService(
            OrderRepository orderRepository,
            CatalogClient catalogClient,
            OrderTransitionPolicy transitionPolicy,
            OrderAuthorizationService authorizationService,
            OutboxWriter outboxWriter
    ) {
        this.orderRepository = orderRepository;
        this.catalogClient = catalogClient;
        this.transitionPolicy = transitionPolicy;
        this.authorizationService = authorizationService;
        this.outboxWriter = outboxWriter;
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> list(int page, int size, Authentication authentication) {
        authorizationService.requireKnownRole(authentication);
        PageRequest pageRequest = pageRequest(page, size);
        Page<Order> orders;
        if (authorizationService.isStaff(authentication)) {
            orders = orderRepository.findAllByOrderByCreatedAtDesc(pageRequest);
        } else {
            String customerId = OrderPrincipal.from(authentication).customerId();
            orders = orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageRequest);
        }
        return PageResponse.from(orders.map(OrderResponse::from));
    }

    private PageRequest pageRequest(int page, int size) {
        if (page < 0) {
            throw new InvalidPageRequestException("El número de página no puede ser negativo");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidPageRequestException(
                    "El tamaño de página debe estar entre 1 y " + MAX_PAGE_SIZE);
        }
        return PageRequest.of(page, size);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(String id, Authentication authentication) {
        Order order = findDetailed(id);
        authorizationService.requireView(order, authentication);
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request, Authentication authentication) {
        authorizationService.requireKnownRole(authentication);
        String customerId = OrderPrincipal.from(authentication).customerId();
        List<OrderItem> items = snapshotItems(request.items());
        Order order = Order.create(customerId, request.notes());
        order.replaceItems(items, request.notes());
        return OrderResponse.from(orderRepository.save(order));
    }

    @Transactional
    public OrderResponse update(
            String id,
            UpdateOrderRequest request,
            Authentication authentication
    ) {
        Order order = findForUpdate(id);
        authorizationService.requireEdit(order, authentication);
        if (order.getStatus() != OrderStatus.CREADO) {
            throw new OrderBusinessException("Solo se puede editar un pedido en estado CREADO");
        }
        List<OrderItem> replacement = snapshotItems(request.items());
        order.replaceItems(replacement, request.notes());
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse delete(String id, Authentication authentication) {
        Order order = findForUpdate(id);
        authorizationService.requireDeleteCancellation(order, authentication);
        transitionPolicy.requireTransition(order.getStatus(), OrderStatus.CANCELADO);
        order.markCancelled();
        recordCancelledEvent(order);
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse changeStatus(
            String id,
            OrderStatus target,
            Authentication authentication
    ) {
        Order order = findForUpdate(id);
        authorizationService.requireStatusChange(order, target, authentication);
        transitionPolicy.requireTransition(order.getStatus(), target);

        if (target == OrderStatus.ACEPTADO) {
            order.markAccepted();
            recordAcceptedEvent(order);
        } else if (target == OrderStatus.CANCELADO) {
            order.markCancelled();
            recordCancelledEvent(order);
        } else {
            if (target == OrderStatus.EN_PREPARACION && !order.isStockReserved()) {
                // Con la reserva síncrona, aceptar ya implicaba stock reservado.
                // Ahora la confirmación llega después y no se prepara sin ella.
                throw new OrderBusinessException("El stock del pedido todavía no está confirmado");
            }
            order.changeStatus(target);
        }
        return OrderResponse.from(order);
    }

    /**
     * Aplica la confirmación de reserva que publica el catálogo. Es idempotente:
     * una reentrega no cambia nada. Si el pedido se canceló antes de que llegara
     * la confirmación, la reserva quedó huérfana y se pide liberarla una vez.
     */
    @Transactional
    public void applyStockReserved(UUID orderId) {
        Order order = findForUpdate(orderId.toString());
        if (order.getStatus() == OrderStatus.CANCELADO) {
            log.warn("Reserva confirmada para el pedido cancelado {}: se pide liberarla", orderId);
            outboxWriter.writeIfAbsent(
                    orderId,
                    RabbitConfiguration.ORPHAN_ROUTING_KEY,
                    new OrderEventPayload.Cancelled(orderId));
            return;
        }
        if (!order.isStockReserved()) {
            order.markStockReserved();
        }
    }

    /**
     * Aplica el rechazo de reserva que publica el catálogo: cancela el pedido como
     * compensación y guarda el motivo. Sólo afecta a un pedido aceptado sin stock
     * confirmado; en cualquier otro estado el rechazo llega tarde y se ignora.
     */
    @Transactional
    public void applyStockRejected(UUID orderId, String errorCode, String reason) {
        Order order = findForUpdate(orderId.toString());
        if (order.getStatus() != OrderStatus.ACEPTADO || order.isStockReserved()) {
            log.warn("Rechazo de reserva ignorado para el pedido {} en estado {} (stockReserved={}): {}",
                    orderId, order.getStatus(), order.isStockReserved(), errorCode);
            return;
        }
        order.cancelForRejectedStock(reason != null ? reason : errorCode);
        recordCancelledEvent(order);
    }

    private void recordAcceptedEvent(Order order) {
        UUID orderId = UUID.fromString(order.getId());
        List<OrderEventPayload.Item> items = order.getItems().stream()
                .map(item -> new OrderEventPayload.Item(
                        UUID.fromString(item.getProductId()),
                        item.getQuantity()))
                .toList();
        outboxWriter.write(
                orderId,
                RabbitConfiguration.ACCEPTED_ROUTING_KEY,
                new OrderEventPayload.Accepted(orderId, items));
    }

    /**
     * Cancelar siempre pide liberar, aunque la reserva no esté confirmada: el
     * resultado puede seguir en vuelo, y liberar en el catálogo es idempotente
     * (no hace nada si no hay reserva).
     */
    private void recordCancelledEvent(Order order) {
        UUID orderId = UUID.fromString(order.getId());
        outboxWriter.write(
                orderId,
                RabbitConfiguration.CANCELLED_ROUTING_KEY,
                new OrderEventPayload.Cancelled(orderId));
    }

    private List<OrderItem> snapshotItems(List<OrderItemRequest> requests) {
        Set<UUID> uniqueProducts = new HashSet<>();
        List<OrderItem> items = new ArrayList<>(requests.size());
        for (OrderItemRequest request : requests) {
            if (!uniqueProducts.add(request.productId())) {
                throw new OrderBusinessException("No se puede repetir el producto " + request.productId());
            }
            CatalogClient.CatalogProduct product = catalogClient.getProduct(request.productId());
            if (product == null) {
                throw new OrderBusinessException("El catálogo no devolvió el producto " + request.productId());
            }
            if (!request.productId().equals(product.id())) {
                throw new OrderBusinessException("El catálogo devolvió un producto distinto al solicitado");
            }
            if (!product.active()) {
                throw new OrderBusinessException("El producto " + request.productId() + " no está activo");
            }
            if (product.name() == null || product.name().isBlank()) {
                throw new OrderBusinessException("El producto " + request.productId() + " no tiene un nombre válido");
            }
            if (product.name().trim().length() > 300) {
                throw new OrderBusinessException("El nombre del producto " + request.productId() + " es demasiado largo");
            }
            if (product.price() == null || product.price().compareTo(BigDecimal.ZERO) <= 0) {
                throw new OrderBusinessException("El producto " + request.productId() + " no tiene un precio válido");
            }
            BigDecimal normalizedPrice = product.price().setScale(2, RoundingMode.HALF_UP);
            if (normalizedPrice.compareTo(BigDecimal.ZERO) <= 0) {
                throw new OrderBusinessException("El precio del producto " + request.productId() + " es inválido");
            }
            if (normalizedPrice.precision() - normalizedPrice.scale() > 17) {
                throw new OrderBusinessException("El precio del producto " + request.productId() + " excede el máximo");
            }
            items.add(new OrderItem(
                    product.id().toString(),
                    product.name().trim(),
                    request.quantity(),
                    normalizedPrice
            ));
        }
        BigDecimal total = items.stream()
                .map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.precision() - total.scale() > 17) {
            throw new OrderBusinessException("El total del pedido excede el máximo permitido");
        }
        return items;
    }

    private Order findDetailed(String id) {
        return orderRepository.findDetailedById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    private Order findForUpdate(String id) {
        return orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }
}
