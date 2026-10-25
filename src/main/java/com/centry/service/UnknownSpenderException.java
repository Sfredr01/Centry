package com.centry.service;

/** An expense names a {@code spentBy} user id that does not exist. */
public class UnknownSpenderException extends RuntimeException {

    public UnknownSpenderException(long spentBy) {
        super("no household member with id " + spentBy);
    }
}
