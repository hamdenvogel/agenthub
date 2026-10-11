package br.com.hvogel.agenthub.exception;

/** Pedido de remediação inválido ou correção que não pode ser montada. */
public class RemediationException extends RuntimeException {

    public RemediationException(String message) {
        super(message);
    }
}
