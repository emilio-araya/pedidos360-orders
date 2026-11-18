package com.pedidos360.orders.notification;

import com.pedidos360.orders.events.NotificationRequested;

/** Outbound port for the guest status-change notification. */
public interface OrderStatusMailer {

    void send(NotificationRequested notification);
}
