package com.pedidos360.orders.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pedidos360.orders.catalog.exception.CatalogServiceException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * El catálogo puede dejar de responder sin cerrar la conexión. Sin timeout de
 * lectura, la llamada bloquea el hilo del request de forma indefinida y la
 * falla se propaga al resto de la cadena. Estos tests fijan ese contrato.
 */
class CatalogClientTimeoutTest {

    private static final UUID PRODUCT_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final long SERVER_DELAY_MS = 2_000;
    private static final long READ_TIMEOUT_MS = 300;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void failsFastWhenTheCatalogStopsRespondingInsteadOfHangingForever() throws IOException {
        String baseUrl = startSlowCatalog();
        HttpCatalogClient client = new HttpCatalogClient(
                CatalogClientConfiguration.restClient(baseUrl, 1_000, READ_TIMEOUT_MS),
                bearerTokenProvider()
        );

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> client.getProduct(PRODUCT_ID))
                .isInstanceOf(CatalogServiceException.class)
                .hasMessageContaining(String.valueOf(PRODUCT_ID));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        // El servidor tarda 2 s en contestar: si el cliente espera, el test
        // termina tarde y falla. Debe cortar cerca del timeout configurado.
        assertThat(elapsedMs)
                .as("la llamada debe cortarse por timeout, no esperar los %d ms del servidor", SERVER_DELAY_MS)
                .isLessThan(SERVER_DELAY_MS - 500);
    }

    @Test
    void rejectsTimeoutsThatWouldSilentlyDisableTheTimeout() {
        assertThatThrownBy(() -> CatalogClientConfiguration.restClient("http://catalog.test", 0, 10_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect-timeout-ms");

        assertThatThrownBy(() -> CatalogClientConfiguration.restClient("http://catalog.test", 1_000, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read-timeout-ms");
    }

    private String startSlowCatalog() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/catalog/products/", exchange -> {
            sleep(SERVER_DELAY_MS);
            respond(exchange);
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange) throws IOException {
        byte[] body = """
                {"id":"%s","name":"Mouse","price":19990.00,"stock":10,"active":true}
                """.formatted(PRODUCT_ID).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private BearerTokenProvider bearerTokenProvider() {
        Jwt jwt = Jwt.withTokenValue("incoming-bearer-token")
                .header("alg", "HS256")
                .claim("sub", "customer")
                .build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt));
        SecurityContextHolder.setContext(context);
        return new BearerTokenProvider();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
