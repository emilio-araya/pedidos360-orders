package com.pedidos360.orders.notification;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Registro idempotente de entregas; `event_id` es su clave. */
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, String> {

    Optional<NotificationDelivery> findByEventId(String eventId);
}
