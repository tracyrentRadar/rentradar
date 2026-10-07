package com.rentradar.backend.domain.type;

import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money in a specific currency.
 * Amount and currency are inseparable: no bare decimal is ever treated as money.
 */
public record MonetaryAmount(
        @Field(targetType = FieldType.DECIMAL128) BigDecimal amount,
        String currency
) implements Comparable<MonetaryAmount> {

    public MonetaryAmount {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        // Throws IllegalArgumentException if not a valid ISO 4217 code.
        Currency.getInstance(currency);
    }

    public static MonetaryAmount of(BigDecimal amount, String currency) {
        return new MonetaryAmount(amount, currency);
    }

    public static MonetaryAmount of(String amount, String currency) {
        return new MonetaryAmount(new BigDecimal(amount), currency);
    }

    public Currency currencyUnit() {
        return Currency.getInstance(currency);
    }

    public MonetaryAmount add(MonetaryAmount other) {
        requireSameCurrency(other);
        return new MonetaryAmount(amount.add(other.amount), currency);
    }

    public MonetaryAmount subtract(MonetaryAmount other) {
        requireSameCurrency(other);
        return new MonetaryAmount(amount.subtract(other.amount), currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    @Override
    public int compareTo(MonetaryAmount other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(MonetaryAmount other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Cannot operate across currencies: " + currency + " and " + other.currency);
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}