package com.pedidos360.orders.notification;

import com.pedidos360.orders.events.NotificationRequested;

/** Renderers for the guest status-change email. Pure functions, no I/O. */
public final class OrderStatusMailTemplate {

    private OrderStatusMailTemplate() {
    }

    public static String subject(NotificationRequested notification) {
        return "Tu pedido " + notification.orderNumber() + " cambió a " + notification.status();
    }

    public static String body(NotificationRequested notification, String frontendBaseUrl) {
        String base = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                : frontendBaseUrl;
        return String.join("\n",
                "Hola,",
                "",
                "Tu pedido " + notification.orderNumber() + " pasó de "
                        + notification.previousStatus() + " a " + notification.status() + ".",
                "",
                "Puedes consultar su estado en " + base + "/seguimiento",
                "con el número de pedido y el correo con el que lo registraste.",
                "",
                "Pedidos360");
    }
}
