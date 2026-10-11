package br.com.hvogel.agenthub.exception;

public class AnalysisNotFoundException extends RuntimeException {

    public AnalysisNotFoundException(String analysisId) {
        super("Análise não encontrada: " + analysisId);
    }
}
