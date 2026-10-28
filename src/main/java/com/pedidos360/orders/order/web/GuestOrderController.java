package com.pedidos360.orders.order.web;

import com.pedidos360.orders.order.dto.CreateGuestOrderRequest;
import com.pedidos360.orders.order.dto.GuestOrderResponse;
import com.pedidos360.orders.order.service.GuestOrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public guest checkout routes, only under {@code /api}. They are permitted
 * without a JWT in {@code SecurityConfiguration}; the tracking token in the path
 * is the credential.
 */
@RestController
@RequestMapping("/api/guest-orders")
public class GuestOrderController {

    private final GuestOrderService guestOrderService;

    public GuestOrderController(GuestOrderService guestOrderService) {
        this.guestOrderService = guestOrderService;
    }

    /** No Location header: it would carry the secret token into proxy and gateway logs. */
    @PostMapping
    public ResponseEntity<GuestOrderResponse> create(@Valid @RequestBody CreateGuestOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(guestOrderService.create(request));
    }

    @GetMapping("/{token}")
    public GuestOrderResponse get(@PathVariable String token) {
        return guestOrderService.get(token);
    }

    @PostMapping("/{token}/cancel")
    public GuestOrderResponse cancel(@PathVariable String token) {
        return guestOrderService.cancel(token);
    }
}
