package com.rentradar.backend.ml;

/**
 * CHAMPION serves live traffic. CHALLENGER is evaluated in shadow mode and is
 * never returned to a caller until it is promoted.
 */
public enum ModelStage {
    CHAMPION,
    CHALLENGER,
    ARCHIVED
}