package br.com.hvogel.agenthub.model;

public enum AgentStatus {

    SUCCESS, FAILED, TIMEOUT, CANCELLED;

    public boolean isSuccess() {
        return this == SUCCESS;
    }
}
