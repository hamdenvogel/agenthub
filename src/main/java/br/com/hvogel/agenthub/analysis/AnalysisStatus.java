package br.com.hvogel.agenthub.analysis;

public enum AnalysisStatus {

    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED;

    public boolean isFinished() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
