package com.centry.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Conversions at the persistence boundary: money as integer cents, timestamps as ISO-8601 text. */
final class Cents {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private Cents() {
    }

    /** Null stays null. More than two decimal places throws ArithmeticException; values are never rounded. */
    static Long toCents(BigDecimal amount) {
        return amount == null ? null : amount.movePointRight(2).longValueExact();
    }

    /** Reads a nullable money column as BigDecimal. */
    static BigDecimal money(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : BigDecimal.valueOf(value, 2);
    }

    static String toText(LocalDateTime timestamp) {
        return ISO.format(timestamp);
    }

    static LocalDateTime fromText(String text) {
        return LocalDateTime.parse(text, ISO);
    }
}
