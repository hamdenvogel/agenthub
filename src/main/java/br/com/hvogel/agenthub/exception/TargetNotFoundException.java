package br.com.hvogel.agenthub.exception;

public class TargetNotFoundException extends RuntimeException {

    public TargetNotFoundException(String targetId) {
        super("Projeto não encontrado: " + targetId);
    }
}
