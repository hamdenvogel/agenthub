package br.com.hvogel.agenthub.exception;

public class ClaudeException extends RuntimeException {

    public ClaudeException(String message) {
        super(message);
    }

    public ClaudeException(String message, Throwable cause) {
        super(message, cause);
    }
}
