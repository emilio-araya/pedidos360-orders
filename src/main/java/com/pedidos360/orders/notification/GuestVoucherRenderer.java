package com.pedidos360.orders.notification;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/** Plain-text voucher, in neutral Spanish, with the secret tracking link. */
public final class GuestVoucherRenderer {

    private static final Locale CHILE = Locale.forLanguageTag("es-CL");

    private GuestVoucherRenderer() {
    }

    public static String subject(GuestVoucher voucher) {
        return "Pedido " + voucher.orderNumber() + " confirmado";
    }

    public static String body(GuestVoucher voucher) {
        StringBuilder body = new StringBuilder()
                .append("Hola,\n\n")
                .append("Recibimos tu pedido ").append(voucher.orderNumber())
                .append(". Estos son los datos:\n\n");
        for (GuestVoucher.Item item : voucher.items()) {
            body.append("- ").append(item.productName())
                    .append(" x").append(item.quantity())
                    .append(" (").append(money(item.unitPrice())).append(" c/u)")
                    .append(": ").append(money(item.subtotal())).append('\n');
        }
        body.append("\nTotal: ").append(money(voucher.total()))
                .append("\nEstado: ").append(voucher.status())
                .append("\n\nPuedes ver, seguir o cancelar tu pedido con este enlace:\n")
                .append(voucher.trackingLink())
                .append("\n\nGuarda el enlace: es privado y no lo volveremos a mostrar.\n")
                .append('\n')
                .append("Pedidos360");
        return body.toString();
    }

    private static String money(BigDecimal amount) {
        NumberFormat format = NumberFormat.getCurrencyInstance(CHILE);
        format.setMaximumFractionDigits(0);
        return format.format(amount);
    }
}
