package com.pedidos360.orders.order.web;

import com.pedidos360.orders.catalog.exception.CatalogConflictException;
import com.pedidos360.orders.catalog.exception.CatalogServiceException;
import com.pedidos360.orders.catalog.exception.ProductNotFoundException;
import com.pedidos360.orders.order.exception.ForbiddenOperationException;
import com.pedidos360.orders.order.exception.InvalidOrderTransitionException;
import com.pedidos360.orders.order.exception.OrderBusinessException;
import com.pedidos360.orders.order.exception.OrderNotFoundException;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PessimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(OrderNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(ProductNotFoundException.class)
    ResponseEntity<ProblemDetail> productNotFound(
            ProductNotFoundException exception,
            HttpServletRequest request
    ) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    ResponseEntity<ProblemDetail> forbidden(
            ForbiddenOperationException exception,
            HttpServletRequest request
    ) {
        return problem(HttpStatus.FORBIDDEN, exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidOrderTransitionException.class)
    ResponseEntity<ProblemDetail> transitionConflict(
            InvalidOrderTransitionException exception,
            HttpServletRequest request
    ) {
        return problem(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler({OrderBusinessException.class, CatalogConflictException.class})
    ResponseEntity<ProblemDetail> businessConflict(RuntimeException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(CatalogServiceException.class)
    ResponseEntity<ProblemDetail> catalogUnavailable(
            CatalogServiceException exception,
            HttpServletRequest request
    ) {
        LOGGER.warn("Fallo al invocar el catálogo: {}", exception.getMessage());
        return problem(
                HttpStatus.BAD_GATEWAY,
                "El servicio de catálogo no está disponible o rechazó la operación",
                request
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        ProblemDetail detail = createProblem(
                HttpStatus.BAD_REQUEST,
                "La solicitud contiene datos inválidos",
                request
        );
        List<FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toViolation)
                .toList();
        detail.setProperty("errors", violations);
        return ResponseEntity.badRequest().body(detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> malformedBody(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return problem(HttpStatus.BAD_REQUEST, "El cuerpo JSON es inválido o no es compatible", request);
    }

    @ExceptionHandler({
            LockTimeoutException.class,
            PessimisticLockException.class,
            OptimisticLockException.class
    })
    ResponseEntity<ProblemDetail> concurrentUpdate(Exception exception, HttpServletRequest request) {
        return problem(
                HttpStatus.CONFLICT,
                "El pedido fue actualizado concurrentemente; intente nuevamente",
                request
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> dataConflict(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {
        return problem(HttpStatus.CONFLICT, "La operación infringe una restricción de datos", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> routeNotFound(
            NoResourceFoundException exception,
            HttpServletRequest request
    ) {
        return problem(HttpStatus.NOT_FOUND, "No existe el recurso solicitado", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("Error no controlado en {}", request.getRequestURI(), exception);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Ocurrió un error interno",
                request
        );
    }

    private FieldViolation toViolation(FieldError error) {
        return new FieldViolation(error.getField(), error.getDefaultMessage());
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpStatus status,
            String message,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(status).body(createProblem(status, message, request));
    }

    private ProblemDetail createProblem(
            HttpStatus status,
            String message,
            HttpServletRequest request
    ) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setTitle(status.getReasonPhrase());
        detail.setType(URI.create("https://pedidos360.com/problems/" + status.value()));
        detail.setInstance(URI.create(request.getRequestURI()));
        detail.setProperty("timestamp", Instant.now());
        return detail;
    }

    private record FieldViolation(String field, String message) {
    }
}
