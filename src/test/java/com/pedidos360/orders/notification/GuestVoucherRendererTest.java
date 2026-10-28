package com.pedidos360.orders.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuestVoucherRendererTest {

    private static final String LINK = "https://tienda.pedidos360.cl/seguimiento/" + "a".repeat(64);

    @Test
    void writesTheVoucherWithItemsTotalsAndTheTrackingLink() {
        GuestVoucher voucher = new GuestVoucher(
                "luis.perez@duocuc.cl",
                "PED-1A2B3C4D",
                "CREADO",
                new BigDecimal("39980.00"),
                List.of(new GuestVoucher.Item("Mouse inalámbrico", 2, new BigDecimal("19990.00"), new BigDecimal("39980.00"))),
                LINK);

        String body = GuestVoucherRenderer.body(voucher);

        assertThat(GuestVoucherRenderer.subject(voucher)).isEqualTo("Pedido PED-1A2B3C4D confirmado");
        assertThat(body).contains("PED-1A2B3C4D", "Mouse inalámbrico x2", "CREADO", LINK);
        assertThat(body).doesNotContain("luis.perez@duocuc.cl");
    }
}
