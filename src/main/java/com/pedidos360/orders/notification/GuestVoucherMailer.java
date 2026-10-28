package com.pedidos360.orders.notification;

/** Outbound port for the guest voucher. Adapters decide between SMTP and a log. */
public interface GuestVoucherMailer {

    void send(GuestVoucher voucher);
}
