package com.pedidos360.orders.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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

    private MockRestServiceServer server;
    private HttpCatalogClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpCatalogClient(
                builder.build(),
                new BearerTokenProvider()
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
    void propagatesBearerToProductCalls() {
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

        CatalogClient.CatalogProduct product = client.getProduct(PRODUCT_ID);

        assertThat(product.name()).isEqualTo("Mouse");
        server.verify();
    }

    @Test
    void readsPublicProductsWithoutAuthorizationWhenNoJwtIsPresent() {
        // Guest checkout runs without a JWT; the catalog product read is public.
        SecurityContextHolder.clearContext();
        server.expect(requestTo("http://catalog.test/api/catalog/products/" + PRODUCT_ID))
                .andExpect(method(GET))
                .andExpect(request -> assertThat(request.getHeaders().containsKey(AUTHORIZATION)).isFalse())
                .andRespond(withSuccess("""
                        {
                          "id": "%s",
                          "name": "Mouse",
                          "price": 19990.00,
                          "stock": 10,
                          "active": true
                        }
                        """.formatted(PRODUCT_ID), MediaType.APPLICATION_JSON));

        CatalogClient.CatalogProduct product = client.getProduct(PRODUCT_ID);

        assertThat(product.name()).isEqualTo("Mouse");
        server.verify();
    }
}
