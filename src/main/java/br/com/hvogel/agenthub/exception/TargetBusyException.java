package br.com.hvogel.agenthub.exception;

/** O projeto ativo não pode mudar enquanto houver análise em andamento. */
public class TargetBusyException extends RuntimeException {

    public TargetBusyException(long running) {
        super("Há " + running + " análise(s) em andamento. Aguarde a conclusão ou cancele antes de trocar o projeto ativo.");
    }
}
