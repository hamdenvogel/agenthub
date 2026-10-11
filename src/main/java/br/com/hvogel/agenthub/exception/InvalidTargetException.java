package br.com.hvogel.agenthub.exception;

/** Projeto-alvo inválido: diretório inexistente, fora das pastas permitidas, escopo inválido... */
public class InvalidTargetException extends RuntimeException {

    public InvalidTargetException(String message) {
        super(message);
    }
}
