package br.com.hvogel.agenthub.exception;

public class AgentExecutionException extends RuntimeException {

    public AgentExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
