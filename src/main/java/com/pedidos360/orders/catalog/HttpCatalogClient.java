package com.pedidos360.orders.catalog;

import com.pedidos360.orders.catalog.exception.CatalogConflictException;
import com.pedidos360.orders.catalog.exception.CatalogServiceException;
import com.pedidos360.orders.catalog.exception.ProductNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
            RestClient.Builder builder,
            BearerTokenProvider bearerTokenProvider,
            @Value("${app.catalog.base-url}") String baseUrl
    ) {
        this.restClient = builder.baseUrl(baseUrl).build();
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

    @Override
    public void reserveStock(UUID orderId, List<StockItem> items) {
        StockReservation reservation = new StockReservation(orderId, items);
        try {
            restClient.post()
                    .uri(bearerTokenProvider.currentInternalPrefix() + "/catalog/stock/reservations")
                    .headers(this::propagateBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(reservation)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict exception) {
            throw new CatalogConflictException("El catálogo no pudo reservar el stock del pedido", exception);
        } catch (RestClientResponseException exception) {
            throw new CatalogServiceException(
                    "El catálogo rechazó la reserva con estado " + exception.getStatusCode().value(),
                    exception
            );
        } catch (RestClientException exception) {
            throw new CatalogServiceException("No se pudo reservar el stock del pedido", exception);
        }
    }

    @Override
    public void releaseStock(UUID orderId) {
        try {
            restClient.delete()
                    .uri(bearerTokenProvider.currentInternalPrefix() + "/catalog/stock/reservations/{orderId}", orderId)
                    .headers(this::propagateBearer)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw new CatalogServiceException(
                    "El catálogo rechazó la liberación con estado " + exception.getStatusCode().value(),
                    exception
            );
        } catch (RestClientException exception) {
            throw new CatalogServiceException("No se pudo liberar el stock del pedido", exception);
        }
    }

    private void propagateBearer(HttpHeaders headers) {
        headers.setBearerAuth(bearerTokenProvider.currentToken());
    }

    private record StockReservation(UUID orderId, List<StockItem> items) {
    }
}
