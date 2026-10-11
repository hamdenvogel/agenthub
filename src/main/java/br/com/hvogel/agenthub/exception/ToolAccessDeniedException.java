package br.com.hvogel.agenthub.exception;

public class ToolAccessDeniedException extends RuntimeException {

    public ToolAccessDeniedException(String message) {
        super(message);
    }
}
