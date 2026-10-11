package br.com.hvogel.agenthub.exception;

public class ReportNotReadyException extends RuntimeException {

    public ReportNotReadyException(String analysisId, String status) {
        super("Relatório da análise " + analysisId + " ainda não disponível (status " + status + ").");
    }
}
