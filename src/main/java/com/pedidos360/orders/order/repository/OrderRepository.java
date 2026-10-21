package com.pedidos360.orders.order.repository;

import com.pedidos360.orders.order.domain.Order;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, String> {

    @EntityGraph(attributePaths = "items")
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findDetailedById(@Param("id") String id);

    // Sin @EntityGraph a propósito: paginar un fetch de colección hace que
    // Hibernate aplique el límite en memoria (HHH90003004) y traiga la tabla
    // completa. Los items se resuelven por lote gracias a @BatchSize en Order.
    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Order> findByCustomerIdOrderByCreatedAtDesc(String customerId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") String id);
}
