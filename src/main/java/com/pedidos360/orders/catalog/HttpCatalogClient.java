package com.pedidos360.orders.catalog;

import com.pedidos360.orders.catalog.exception.CatalogServiceException;
import com.pedidos360.orders.catalog.exception.ProductNotFoundException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpCatalogClient implements CatalogClient {

    private final RestClient restClient;
    private final BearerTokenProvider bearerTokenProvider;

    public HttpCatalogClient(
            @Qualifier("catalogRestClient") RestClient restClient,
            BearerTokenProvider bearerTokenProvider
    ) {
        this.restClient = restClient;
        this.bearerTokenProvider = bearerTokenProvider;
    }

    @Override
    public CatalogProduct getProduct(UUID productId) {
        try {
            CatalogProduct product = restClient.get()
                    .uri(bearerTokenProvider.currentApiPrefix() + "/catalog/products/{productId}", productId)
                    .headers(this::propagateBearer)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(CatalogProduct.class);
            if (product == null) {
                throw new CatalogServiceException("El catálogo devolvió una respuesta vacía para " + productId);
            }
            return product;
        } catch (HttpClientErrorException.NotFound exception) {
            throw new ProductNotFoundException(productId);
        } catch (RestClientResponseException exception) {
            throw new CatalogServiceException(
                    "El catálogo rechazó la consulta del producto " + productId
                            + " con estado " + exception.getStatusCode().value(),
                    exception
            );
        } catch (RestClientException exception) {
            throw new CatalogServiceException("No se pudo consultar el producto " + productId, exception);
        }
    }

    private void propagateBearer(HttpHeaders headers) {
        headers.setBearerAuth(bearerTokenProvider.currentToken());
    }
}
