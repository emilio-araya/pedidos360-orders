package com.pedidos360.orders.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pedidos360.orders.order.exception.InvalidOrderTransitionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OrderTransitionPolicyTest {

    private final OrderTransitionPolicy policy = new OrderTransitionPolicy();

    @ParameterizedTest
    @MethodSource("allowedTransitions")
    void allowsOnlyDefinedTransitions(OrderStatus current, OrderStatus target) {
        assertThat(policy.canTransition(current, target)).isTrue();
        assertThatNoException(() -> policy.requireTransition(current, target));
    }

    @Test
    void rejectsDispatchWithoutPreparation() {
        assertThatThrownBy(() -> policy.requireTransition(OrderStatus.CREADO, OrderStatus.DESPACHADO))
                .isInstanceOf(InvalidOrderTransitionException.class)
                .hasMessageContaining("CREADO -> DESPACHADO");
        assertThatThrownBy(() -> policy.requireTransition(OrderStatus.ACEPTADO, OrderStatus.DESPACHADO))
                .isInstanceOf(InvalidOrderTransitionException.class);
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        for (OrderStatus target : OrderStatus.values()) {
            assertThat(policy.canTransition(OrderStatus.ENTREGADO, target)).isFalse();
            assertThat(policy.canTransition(OrderStatus.CANCELADO, target)).isFalse();
        }
    }

    private static Stream<Arguments> allowedTransitions() {
        return Stream.of(
                Arguments.of(OrderStatus.CREADO, OrderStatus.ACEPTADO),
                Arguments.of(OrderStatus.CREADO, OrderStatus.CANCELADO),
                Arguments.of(OrderStatus.ACEPTADO, OrderStatus.EN_PREPARACION),
                Arguments.of(OrderStatus.ACEPTADO, OrderStatus.CANCELADO),
                Arguments.of(OrderStatus.EN_PREPARACION, OrderStatus.DESPACHADO),
                Arguments.of(OrderStatus.EN_PREPARACION, OrderStatus.CANCELADO),
                Arguments.of(OrderStatus.DESPACHADO, OrderStatus.ENTREGADO)
        );
    }

    private static void assertThatNoException(Runnable action) {
        org.assertj.core.api.Assertions.assertThatCode(action::run).doesNotThrowAnyException();
    }
}
