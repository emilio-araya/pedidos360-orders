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

@Entity
@Table(name = "orders")
public class Order {

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
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

    public void markAccepted() {
        status = OrderStatus.ACEPTADO;
        stockReserved = true;
        touch();
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
