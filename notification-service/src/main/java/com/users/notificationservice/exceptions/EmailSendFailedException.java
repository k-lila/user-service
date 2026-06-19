package com.users.notificationservice.exceptions;

// Falha ao enviar o e-mail via SMTP (timeout, autenticação, indisponibilidade do
// servidor, etc). Mapeada para 502 Bad Gateway pelo GlobalExceptionHandler — o
// chamador (user-service) trata qualquer não-2xx marcando o outbox como FAILED.
public class EmailSendFailedException extends RuntimeException {

    public EmailSendFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
