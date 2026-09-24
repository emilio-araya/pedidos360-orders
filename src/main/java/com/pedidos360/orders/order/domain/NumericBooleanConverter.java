package com.pedidos360.orders.order.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.math.BigDecimal;

@Converter
public class NumericBooleanConverter implements AttributeConverter<Boolean, BigDecimal> {

    private static final BigDecimal TRUE = BigDecimal.ONE;
    private static final BigDecimal FALSE = BigDecimal.ZERO;

    @Override
    public BigDecimal convertToDatabaseColumn(Boolean attribute) {
        return Boolean.TRUE.equals(attribute) ? TRUE : FALSE;
    }

    @Override
    public Boolean convertToEntityAttribute(BigDecimal databaseValue) {
        return databaseValue != null && databaseValue.compareTo(BigDecimal.ZERO) != 0;
    }
}
