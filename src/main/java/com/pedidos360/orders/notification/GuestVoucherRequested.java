package com.pedidos360.orders.notification;

/** Raised after the order commits; it carries everything the email needs. */
public record GuestVoucherRequested(GuestVoucher voucher) {
}
