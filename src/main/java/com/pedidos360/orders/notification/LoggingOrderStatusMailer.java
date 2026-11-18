package com.pedidos360.orders.notification;

import com.pedidos360.orders.events.NotificationRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Used when no SMTP host is configured. It logs the order number only, never
 * the recipient.
 */
public class LoggingOrderStatusMailer implements OrderStatusMailer {

    private static final Logger log = LoggerFactory.getLogger(LoggingOrderStatusMailer.class);

    @Override
    public void send(NotificationRequested notification) {
        log.info("Correo de cambio de estado del pedido {} ({}) no enviado: SMTP no configurado",
                notification.orderNumber(), notification.status());
    }
}
