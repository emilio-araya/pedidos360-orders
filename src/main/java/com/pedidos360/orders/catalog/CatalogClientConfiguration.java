package com.pedidos360.orders.catalog;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Construye el cliente HTTP hacia el catálogo con timeouts explícitos.
 *
 * Sin timeout de lectura, una respuesta que nunca llega retiene el hilo del
 * request de forma indefinida: unas pocas llamadas colgadas agotan el pool de
 * Tomcat y orders deja de responder, incluido /actuator/health.
 */
@Configuration(proxyBeanMethods = false)
public class CatalogClientConfiguration {

    @Bean
    RestClient catalogRestClient(
            @Value("${app.catalog.base-url}") String baseUrl,
            @Value("${app.catalog.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${app.catalog.read-timeout-ms:10000}") long readTimeoutMs
    ) {
        return restClient(baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    static RestClient restClient(String baseUrl, long connectTimeoutMs, long readTimeoutMs) {
        requirePositive(connectTimeoutMs, "app.catalog.connect-timeout-ms");
        requirePositive(readTimeoutMs, "app.catalog.read-timeout-ms");

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    private static void requirePositive(long value, String property) {
        if (value <= 0) {
            // En el cliente JDK, Duration.ZERO significa "sin timeout": un valor
            // no positivo reactivaría el cuelgue indefinido que este bean evita.
            throw new IllegalArgumentException(
                    property + " debe ser mayor que cero; un valor no positivo desactiva el timeout");
        }
    }
}
