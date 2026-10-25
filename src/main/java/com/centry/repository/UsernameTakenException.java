package com.centry.repository;

/** A user with this username (compared case-insensitively) already exists. */
public class UsernameTakenException extends RuntimeException {

    public UsernameTakenException(String username) {
        super("Username " + username + " is already taken");
    }
}
