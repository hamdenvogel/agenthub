package br.com.hvogel.agenthub.exception;

/** A operação não cabe no estado atual da proposta ou dos arquivos (ex.: arquivo alterado depois da proposta). */
public class RemediationConflictException extends RuntimeException {

    public RemediationConflictException(String message) {
        super(message);
    }
}
