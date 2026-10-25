package com.centry.model;

/** Where a category stands against its monthly budget. The names are part of the webhook and Kafka payload. */
public enum BudgetLevel {
    OK,
    WARNING_EXCEEDED,
    LIMIT_EXCEEDED
}
