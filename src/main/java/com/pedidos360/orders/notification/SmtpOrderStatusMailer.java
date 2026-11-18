package com.pedidos360.orders.notification;

import com.pedidos360.orders.events.NotificationRequested;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;

/** Delivers the guest status-change email over SMTP. */
public class SmtpOrderStatusMailer implements OrderStatusMailer {

    private final MailSender mailSender;
    private final String from;
    private final String frontendBaseUrl;

    public SmtpOrderStatusMailer(MailSender mailSender, String from, String frontendBaseUrl) {
        this.mailSender = mailSender;
        this.from = from;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Override
    public void send(NotificationRequested notification) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(notification.email());
        message.setSubject(OrderStatusMailTemplate.subject(notification));
        message.setText(OrderStatusMailTemplate.body(notification, frontendBaseUrl));
        mailSender.send(message);
    }
}
