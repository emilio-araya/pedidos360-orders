package com.pedidos360.orders.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Sends the voucher over SMTP. Local Mailpit and Amazon SES share this
 * adapter; only the connection properties change.
 */
public class SmtpGuestVoucherMailer implements GuestVoucherMailer {

    private static final Logger log = LoggerFactory.getLogger(SmtpGuestVoucherMailer.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpGuestVoucherMailer(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(GuestVoucher voucher) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(voucher.to());
        message.setSubject(GuestVoucherRenderer.subject(voucher));
        message.setText(GuestVoucherRenderer.body(voucher));
        try {
            mailSender.send(message);
        } catch (MailException exception) {
            // The link must never reach the logs: only the order number and the cause.
            log.warn("No se pudo enviar el correo del pedido {}: {}",
                    voucher.orderNumber(), exception.getMessage());
        }
    }
}
