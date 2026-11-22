package com.pedidos360.orders.notification;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Registro idempotente de entregas; `event_id` es su clave. */
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, String> {

    Optional<NotificationDelivery> findByEventId(String eventId);

    /**
     * Serializa la reclamación y el cierre de una entrega existente entre
     * instancias de Orders. La transacción debe permanecer abierta al evaluar
     * y cambiar el estado.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select delivery from NotificationDelivery delivery where delivery.eventId = :eventId")
    Optional<NotificationDelivery> findByEventIdForUpdate(@Param("eventId") String eventId);
}
