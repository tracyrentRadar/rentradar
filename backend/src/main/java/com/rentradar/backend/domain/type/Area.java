package com.rentradar.backend.domain.type;

import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/**
 * A floor area in a specific unit. The unit as supplied is preserved,
 * so a listing quoted in square feet is never silently rewritten,
 * while comparison normalises to square metres.
 */
public record Area(
        @Field(targetType = FieldType.DECIMAL128) BigDecimal magnitude,
        AreaUnit unit
) implements Comparable<Area> {

    public Area {
        Objects.requireNonNull(magnitude, "magnitude must not be null");
        Objects.requireNonNull(unit, "unit must not be null");
        if (magnitude.signum() <= 0) {
            throw new IllegalArgumentException("area must be positive, was " + magnitude);
        }
    }

    public static Area of(BigDecimal magnitude, AreaUnit unit) {
        return new Area(magnitude, unit);
    }

    public static Area ofSquareMetres(String magnitude) {
        return new Area(new BigDecimal(magnitude), AreaUnit.SQM);
    }

    /** The magnitude expressed in square metres, for comparison and model features. */
    public BigDecimal inSquareMetres() {
        return magnitude.multiply(unit.factorToSquareMetres(), MathContext.DECIMAL64);
    }

    @Override
    public int compareTo(Area other) {
        Objects.requireNonNull(other, "other must not be null");
        return inSquareMetres().compareTo(other.inSquareMetres());
    }

    @Override
    public String toString() {
        return magnitude.toPlainString() + " " + (unit == AreaUnit.SQM ? "m²" : "ft²");
    }
}