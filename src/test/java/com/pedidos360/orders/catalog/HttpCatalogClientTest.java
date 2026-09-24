package com.pedidos360.orders.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.pedidos360.orders.catalog.CatalogClient.StockItem;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpCatalogClientTest {

    private static final String TOKEN = "incoming-bearer-token";
    private static final UUID PRODUCT_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID ORDER_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    private MockRestServiceServer server;
    private HttpCatalogClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpCatalogClient(
                builder,
                new BearerTokenProvider(),
                "http://catalog.test"
        );
        Jwt jwt = Jwt.withTokenValue(TOKEN)
                .header("alg", "HS256")
                .claim("sub", "customer")
                .build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void propagatesBearerToProductReservationAndReleaseCalls() {
        server.expect(requestTo("http://catalog.test/api/catalog/products/" + PRODUCT_ID))
                .andExpect(method(GET))
                .andExpect(header(AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess("""
                        {
                          "id": "%s",
                          "name": "Mouse",
                          "price": 19990.00,
                          "stock": 10,
                          "active": true
                        }
                        """.formatted(PRODUCT_ID), MediaType.APPLICATION_JSON));

        server.expect(requestTo("http://catalog.test/internal/catalog/stock/reservations"))
                .andExpect(method(POST))
                .andExpect(header(AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.items[0].productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andRespond(withNoContent());

        server.expect(requestTo("http://catalog.test/internal/catalog/stock/reservations/" + ORDER_ID))
                .andExpect(method(DELETE))
                .andExpect(header(AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withNoContent());

        CatalogClient.CatalogProduct product = client.getProduct(PRODUCT_ID);
        client.reserveStock(ORDER_ID, List.of(new StockItem(PRODUCT_ID, 2)));
        client.releaseStock(ORDER_ID);

        assertThat(product.name()).isEqualTo("Mouse");
        server.verify();
    }
}
