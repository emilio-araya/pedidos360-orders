package com.pedidos360.orders.notification;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Picks the voucher adapter: SMTP when {@code MAIL_HOST} has a value, a log
 * adapter otherwise. Gating on the property value instead of
 * {@code @ConditionalOnProperty} avoids Boot creating a live mail sender for an
 * empty host.
 */
@Configuration
public class GuestMailConfiguration {

    @Bean
    GuestVoucherMailer guestVoucherMailer(
            Environment environment,
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${app.guest.mail.from}") String from
    ) {
        String host = environment.getProperty("spring.mail.host", "");
        JavaMailSender sender = mailSender.getIfAvailable();
        if (host.isBlank() || sender == null) {
            return new LoggingGuestVoucherMailer();
        }
        return new SmtpGuestVoucherMailer(sender, from);
    }

    /**
     * A synchronous executor when {@code executor-threads} is 0 keeps tests
     * deterministic without waiting on threads.
     */
    @Bean
    Executor guestVoucherExecutor(@Value("${app.guest.mail.executor-threads}") int threads) {
        if (threads <= 0) {
            return new SyncTaskExecutor();
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("guest-voucher-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
