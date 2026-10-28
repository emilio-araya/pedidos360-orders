package com.pedidos360.orders.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "orders")
public class Order {

    public static final String GUEST_CUSTOMER_PREFIX = "guest:";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "order_number", nullable = false, unique = true, length = 12)
    private String orderNumber;

    @Column(name = "customer_id", nullable = false, length = 255)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "total", nullable = false, precision = 19, scale = 2)
    private BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

    @Convert(converter = NumericBooleanConverter.class)
    @Column(name = "stock_reserved", nullable = false, precision = 1, scale = 0)
    private boolean stockReserved;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "customer_email", length = 254)
    private String customerEmail;

    /** SHA-256 (hex) of the guest tracking token; the raw token is never stored. */
    @Column(name = "guest_token_hash", unique = true, length = 64)
    private String guestTokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    // Una página de pedidos dispara una carga por lote en lugar de una consulta
    // por pedido: sin esto, listar 20 pedidos hacía 20 selects de items.
    @BatchSize(size = 25)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public static Order create(String customerId, String notes) {
        Order order = new Order();
        Instant now = Instant.now();
        order.id = UUID.randomUUID().toString();
        order.orderNumber = generateOrderNumber();
        order.customerId = customerId;
        order.status = OrderStatus.CREADO;
        order.notes = normalizeNotes(notes);
        order.createdAt = now;
        order.updatedAt = now;
        return order;
    }

    /**
     * Creates an order bought without login. The owner is a sentinel derived from
     * the order id: it can never match a JWT oid/sub, so no customer can reach it
     * through the authenticated API.
     */
    public static Order createGuest(String customerEmail, String guestTokenHash, String notes) {
        Order order = new Order();
        Instant now = Instant.now();
        order.id = UUID.randomUUID().toString();
        order.orderNumber = generateOrderNumber();
        order.customerId = GUEST_CUSTOMER_PREFIX + order.id;
        order.customerEmail = customerEmail;
        order.guestTokenHash = guestTokenHash;
        order.status = OrderStatus.CREADO;
        order.notes = normalizeNotes(notes);
        order.createdAt = now;
        order.updatedAt = now;
        return order;
    }

    public void replaceItems(List<OrderItem> replacementItems, String replacementNotes) {
        items.clear();
        replacementItems.forEach(item -> {
            item.attachTo(this);
            items.add(item);
        });
        notes = normalizeNotes(replacementNotes);
        recalculateTotal();
        touch();
    }

    /**
     * Aceptar ya no implica stock reservado: la reserva es asíncrona y se
     * confirma con {@link #markStockReserved()} cuando responde el catálogo.
     */
    public void markAccepted() {
        status = OrderStatus.ACEPTADO;
        touch();
    }

    public void markStockReserved() {
        stockReserved = true;
        touch();
    }

    /** Compensación: el catálogo rechazó la reserva de un pedido aceptado. */
    public void cancelForRejectedStock(String reason) {
        status = OrderStatus.CANCELADO;
        stockReserved = false;
        cancellationReason = truncate(reason, 500);
        touch();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public void markCancelled() {
        status = OrderStatus.CANCELADO;
        stockReserved = false;
        touch();
    }

    public void changeStatus(OrderStatus newStatus) {
        status = newStatus;
        touch();
    }

    private void recalculateTotal() {
        total = items.stream()
                .map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    private static String generateOrderNumber() {
        return "PED-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private static String normalizeNotes(String notes) {
        if (notes == null || notes.isBlank()) {
            return null;
        }
        return notes.trim();
    }

    public String getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public String getCustomerId() {
        return customerId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getNotes() {
        return notes;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public boolean isStockReserved() {
        return stockReserved;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    /** Replaces the tracking credential: the previous link stops working. */
    public void rotateGuestToken(String newHash) {
        this.guestTokenHash = newHash;
        touch();
    }

    public String getGuestTokenHash() {
        return guestTokenHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public List<OrderItem> getItems() {
        return List.copyOf(items);
    }
}
