package br.com.hvogel.agenthub.exception;

/** Correções só são geradas ou gravadas depois que o usuário aceita os termos para o projeto ativo. */
public class ConsentRequiredException extends RuntimeException {

    public ConsentRequiredException(String projectRoot) {
        super("Aceite os termos da correção assistida para o projeto " + projectRoot + " antes de continuar.");
    }
}
