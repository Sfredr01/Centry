package com.centry.service;

/** A transaction id was replayed with a payload that differs from the stored expense. */
public class ExpenseConflictException extends RuntimeException {

    public ExpenseConflictException() {
        super("transactionId already used for a different expense");
    }
}
