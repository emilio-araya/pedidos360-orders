package com.pedidos360.orders.order.web;

import com.pedidos360.orders.order.dto.ChangeOrderStatusRequest;
import com.pedidos360.orders.order.dto.CreateOrderRequest;
import com.pedidos360.orders.order.dto.OrderResponse;
import com.pedidos360.orders.order.dto.UpdateOrderRequest;
import com.pedidos360.orders.order.service.OrderService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/orders")
@PreAuthorize("isAuthenticated()")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public List<OrderResponse> list(Authentication authentication) {
        return orderService.list(authentication);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable String id, Authentication authentication) {
        return orderService.get(id, authentication);
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @Valid @RequestBody CreateOrderRequest request,
            Authentication authentication
    ) {
        OrderResponse created = orderService.create(request, authentication);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public OrderResponse update(
            @PathVariable String id,
            @Valid @RequestBody UpdateOrderRequest request,
            Authentication authentication
    ) {
        return orderService.update(id, request, authentication);
    }

    @DeleteMapping("/{id}")
    public OrderResponse delete(@PathVariable String id, Authentication authentication) {
        return orderService.delete(id, authentication);
    }

    @PatchMapping("/{id}/status")
    public OrderResponse changeStatus(
            @PathVariable String id,
            @Valid @RequestBody ChangeOrderStatusRequest request,
            Authentication authentication
    ) {
        return orderService.changeStatus(id, request.status(), authentication);
    }
}
