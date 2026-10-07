package com.rentradar.backend.domain.type;

import java.math.BigDecimal;

/**
 * Units of floor area. Conversion factors are exact by definition
 * (1 foot = 0.3048 metres exactly, so 1 sq ft = 0.09290304 sq m exactly).
 */
public enum AreaUnit {

    SQM(BigDecimal.ONE),
    SQFT(new BigDecimal("0.09290304"));

    private final BigDecimal toSquareMetres;

    AreaUnit(BigDecimal toSquareMetres) {
        this.toSquareMetres = toSquareMetres;
    }

    public BigDecimal factorToSquareMetres() {
        return toSquareMetres;
    }
}