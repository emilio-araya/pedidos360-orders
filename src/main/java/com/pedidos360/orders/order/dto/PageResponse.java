package com.pedidos360.orders.order.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Envelope estable de paginación.
 *
 * <p>No se serializa {@link Page} de Spring directamente: su JSON es un detalle
 * de implementación que cambió entre versiones. Este record fija el contrato
 * público de la API.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static <T> PageResponse<T> from(Page<T> source) {
        return new PageResponse<>(
                source.getContent(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages()
        );
    }
}
