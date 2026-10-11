package br.com.hvogel.agenthub.mcp;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Servidor MCP do banco do projeto ativo (prefixo {@code agenthub.project-database}). Não fica em
 * {@code spring.ai.mcp.client.*} porque a URI muda com o projeto: o processo é recriado a cada banco.
 *
 * @param connection     nome da conexão usado pela fonte de ferramentas ({@code agenthub.tool-sources.<id>.connection})
 * @param command        executável do servidor (ex.: {@code uvx})
 * @param args           argumentos do servidor
 * @param uriVariable           variável de ambiente em que o servidor recebe a URI do banco
 * @param initializationTimeout espera pelo {@code initialize} (uvx no Windows costuma passar dos 20s do SDK)
 * @param requestTimeout        tempo máximo de cada requisição MCP
 */
@ConfigurationProperties("agenthub.project-database")
public record ProjectDatabaseProperties(String connection, String command, List<String> args, String uriVariable,
                                        Duration initializationTimeout, Duration requestTimeout) {

    public ProjectDatabaseProperties {
        connection = connection != null && !connection.isBlank() ? connection : "postgres";
        command = command != null && !command.isBlank() ? command : "uvx";
        args = args != null ? List.copyOf(args) : List.of();
        uriVariable = uriVariable != null && !uriVariable.isBlank() ? uriVariable : "DATABASE_URI";
        initializationTimeout = initializationTimeout != null ? initializationTimeout : Duration.ofSeconds(90);
        requestTimeout = requestTimeout != null ? requestTimeout : Duration.ofSeconds(60);
    }
}
