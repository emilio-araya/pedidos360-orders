package com.pedidos360.orders.catalog;

import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Propaga el id de correlación de la request actual hacia el catálogo.
 *
 * <p>Sin esto la cadena se corta en el salto entre servicios: orders y catalog
 * registran ids distintos para la misma operación.
 */
class RequestIdInterceptor implements ClientHttpRequestInterceptor {

    static final String HEADER = "X-Request-Id";
    static final String MDC_KEY = "requestId";

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution
    ) throws IOException {
        String requestId = MDC.get(MDC_KEY);
        if (requestId != null) {
            request.getHeaders().set(HEADER, requestId);
        }
        return execution.execute(request, body);
    }
}
