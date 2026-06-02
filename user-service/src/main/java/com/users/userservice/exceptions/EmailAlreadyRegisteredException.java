package com.users.userservice.exceptions;

public class EmailAlreadyRegisteredException extends RuntimeException {
    public EmailAlreadyRegisteredException(String email) {
        super("Email já cadastrado: " + email);
    }
}
