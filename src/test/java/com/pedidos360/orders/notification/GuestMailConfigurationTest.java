package com.pedidos360.orders.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/**
 * The voucher adapter is chosen from {@code spring.mail.host}. The property
 * lives in {@code application.yml}, and a misplaced block there (for example
 * nested under {@code server:}) leaves it unbound: the application silently
 * falls back to the logging adapter and no mail is ever sent, with no error
 * anywhere. This test loads the real configuration file to catch that shape.
 */
@SpringBootTest(properties = "MAIL_HOST=127.0.0.1")
@ActiveProfiles("local")
class GuestMailConfigurationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private GuestVoucherMailer mailer;

    @Test
    void bindsTheMailHostFromTheConfigurationFile() {
        assertThat(environment.getProperty("spring.mail.host")).isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("spring.mail.port")).isEqualTo("1025");
    }

    @Test
    void picksTheSmtpAdapterWhenTheMailHostIsConfigured() {
        assertThat(mailer).isInstanceOf(SmtpGuestVoucherMailer.class);
    }
}
