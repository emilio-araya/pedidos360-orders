package com.pedidos360.orders.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/**
 * Sin propagación, el catálogo registra otra request y la correlación se corta
 * justo donde más se necesita: en el salto entre servicios.
 */
class RequestIdInterceptorTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void forwardsTheCurrentRequestIdToTheCatalog() throws Exception {
        MDC.put("requestId", "checkout-abc-123");
        ClientHttpRequestInterceptor interceptor = new RequestIdInterceptor();
        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.GET, URI.create("http://catalog.test/api/catalog/products/1"));

        interceptor.intercept(request, new byte[0], (outgoing, body) ->
                new MockClientHttpResponse(new byte[0], 200));

        assertThat(request.getHeaders().getFirst("X-Request-Id")).isEqualTo("checkout-abc-123");
    }

    @Test
    void leavesTheHeaderOutWhenThereIsNoRequestInCourse() throws Exception {
        ClientHttpRequestInterceptor interceptor = new RequestIdInterceptor();
        MockClientHttpRequest request = new MockClientHttpRequest(
                HttpMethod.GET, URI.create("http://catalog.test/api/catalog/products/1"));

        interceptor.intercept(request, new byte[0], (outgoing, body) ->
                new MockClientHttpResponse(new byte[0], 200));

        assertThat(request.getHeaders().getFirst("X-Request-Id")).isNull();
    }
}
