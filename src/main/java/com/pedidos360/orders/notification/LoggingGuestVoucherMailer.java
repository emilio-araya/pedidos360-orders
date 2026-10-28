package com.pedidos360.orders.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Used when no SMTP host is configured. It logs the order number only, never
 * the recipient or the tracking link.
 */
public class LoggingGuestVoucherMailer implements GuestVoucherMailer {

    private static final Logger log = LoggerFactory.getLogger(LoggingGuestVoucherMailer.class);

    @Override
    public void send(GuestVoucher voucher) {
        log.info("Correo del pedido {} no enviado: SMTP no configurado", voucher.orderNumber());
    }
}
