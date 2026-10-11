package br.com.hvogel.agenthub.model;

/**
 * Evidência concreta de um achado no projeto analisado.
 *
 * @param file    caminho relativo à raiz do projeto
 * @param lines   linha ou intervalo (ex.: {@code 42} ou {@code 10-25}), quando conhecido
 * @param excerpt trecho curto que sustenta o achado
 */
public record Evidence(String file, String lines, String excerpt) {

    public boolean hasFile() {
        return file != null && !file.isBlank();
    }
}
