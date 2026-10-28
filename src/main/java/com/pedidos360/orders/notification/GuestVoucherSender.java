package com.pedidos360.orders.notification;

import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the voucher after the order is committed, so an SMTP failure can never
 * roll back or delay a sale. Runs on a small bounded executor; the failure is
 * logged without the recipient or the link.
 */
@Component
public class GuestVoucherSender {

    private static final Logger log = LoggerFactory.getLogger(GuestVoucherSender.class);

    private final GuestVoucherMailer mailer;
    private final Executor executor;

    public GuestVoucherSender(GuestVoucherMailer mailer, Executor guestVoucherExecutor) {
        this.mailer = mailer;
        this.executor = guestVoucherExecutor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVoucherRequested(GuestVoucherRequested event) {
        executor.execute(() -> sendSafely(event.voucher()));
    }

    private void sendSafely(GuestVoucher voucher) {
        try {
            mailer.send(voucher);
        } catch (RuntimeException exception) {
            log.warn("No se pudo enviar el correo del pedido {}: {}",
                    voucher.orderNumber(), exception.getMessage());
        }
    }
}
