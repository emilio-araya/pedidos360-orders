package com.pedidos360.orders.order.service;

import com.pedidos360.orders.catalog.CatalogClient;
import com.pedidos360.orders.catalog.CatalogClient.StockItem;
import com.pedidos360.orders.order.domain.Order;
import com.pedidos360.orders.order.domain.OrderItem;
import com.pedidos360.orders.order.domain.OrderStatus;
import com.pedidos360.orders.order.domain.OrderTransitionPolicy;
import com.pedidos360.orders.order.dto.CreateOrderRequest;
import com.pedidos360.orders.order.dto.OrderItemRequest;
import com.pedidos360.orders.order.dto.OrderResponse;
import com.pedidos360.orders.order.dto.UpdateOrderRequest;
import com.pedidos360.orders.order.exception.OrderBusinessException;
import com.pedidos360.orders.order.exception.OrderNotFoundException;
import com.pedidos360.orders.order.repository.OrderRepository;
import com.pedidos360.orders.security.OrderAuthorizationService;
import com.pedidos360.orders.security.OrderPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final CatalogClient catalogClient;
    private final OrderTransitionPolicy transitionPolicy;
    private final OrderAuthorizationService authorizationService;

    public OrderService(
            OrderRepository orderRepository,
            CatalogClient catalogClient,
            OrderTransitionPolicy transitionPolicy,
            OrderAuthorizationService authorizationService
    ) {
        this.orderRepository = orderRepository;
        this.catalogClient = catalogClient;
        this.transitionPolicy = transitionPolicy;
        this.authorizationService = authorizationService;
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> list(Authentication authentication) {
        authorizationService.requireKnownRole(authentication);
        List<Order> orders;
        if (authorizationService.isStaff(authentication)) {
            orders = orderRepository.findAllDetailed();
        } else {
            String customerId = OrderPrincipal.from(authentication).customerId();
            orders = orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
        }
        return orders.stream().map(OrderResponse::from).toList();
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
        releaseStockIfReserved(order);
        order.markCancelled();
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
            reserveStock(order);
            order.markAccepted();
        } else if (target == OrderStatus.CANCELADO) {
            releaseStockIfReserved(order);
            order.markCancelled();
        } else {
            order.changeStatus(target);
        }
        return OrderResponse.from(order);
    }

    private void reserveStock(Order order) {
        if (order.isStockReserved()) {
            throw new OrderBusinessException("El stock del pedido ya está reservado");
        }
        List<StockItem> stockItems = order.getItems().stream()
                .map(item -> new StockItem(UUID.fromString(item.getProductId()), item.getQuantity()))
                .toList();
        catalogClient.reserveStock(UUID.fromString(order.getId()), stockItems);
    }

    private void releaseStockIfReserved(Order order) {
        if (order.isStockReserved()) {
            catalogClient.releaseStock(UUID.fromString(order.getId()));
        }
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
