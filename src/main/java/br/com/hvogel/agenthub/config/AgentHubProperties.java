package br.com.hvogel.agenthub.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Configuração externa do AgentHub (prefixo {@code agenthub}).
 *
 * @param targetProject projeto analisado (somente leitura)
 * @param orchestrator  limites do agentic loop
 * @param planner       estratégia de planejamento
 * @param agents        configuração por agente (chave = id do papel, ex.: {@code backend})
 * @param toolSources   fontes de ferramentas (chave = id da fonte, ex.: {@code git})
 * @param reports       persistência dos relatórios
 */
@ConfigurationProperties("agenthub")
public record AgentHubProperties(
        TargetProject targetProject,
        Orchestrator orchestrator,
        Planner planner,
        Map<String, AgentProperties> agents,
        Map<String, ToolSourceProperties> toolSources,
        Reports reports) {

    public AgentHubProperties {
        targetProject = targetProject != null ? targetProject : new TargetProject(null, null, null, 0);
        orchestrator = orchestrator != null ? orchestrator : new Orchestrator(0, null, null, 0, null, 0, 0, null);
        planner = planner != null ? planner : new Planner(null);
        agents = agents != null ? Map.copyOf(agents) : Map.of();
        toolSources = toolSources != null ? Map.copyOf(toolSources) : Map.of();
        reports = reports != null ? reports : new Reports(true, null);
    }

    public AgentProperties agent(String id) {
        return agents.getOrDefault(id, AgentProperties.DEFAULT);
    }

    /**
     * @param root         raiz do projeto analisado
     * @param name         nome amigável (default: nome da pasta)
     * @param excludedDirs diretórios ignorados no discovery e nas ferramentas de filesystem
     * @param maxFileBytes tamanho máximo de arquivo lido pelas ferramentas
     */
    public record TargetProject(String root, String name, List<String> excludedDirs, long maxFileBytes) {
        public TargetProject {
            root = root != null && !root.isBlank() ? root : "../clientes";
            excludedDirs = excludedDirs != null ? List.copyOf(excludedDirs) : List.of(
                    ".git", "node_modules", "target", "dist", "build", ".angular", ".idea", ".vscode",
                    "coverage", "out", "bin", "java-upgrade", "__pycache__", ".scannerwork");
            maxFileBytes = maxFileBytes > 0 ? maxFileBytes : 200_000;
        }
    }

    /**
     * @param maxIterations          máximo de ciclos DELEGATE/EVALUATE (inclui o primeiro)
     * @param analysisTimeout        tempo máximo da análise inteira
     * @param agentTimeout           tempo máximo de uma execução de agente
     * @param maxAttempts            tentativas por execução de agente (retry controlado)
     * @param retryBackoff           espera base entre tentativas (multiplicada pela tentativa)
     * @param maxParallelAgents      agentes simultâneos (protege rate limit da API)
     * @param minConfidence          confiança mínima para considerar um resultado suficiente
     * @param maxFollowUpsPerAgent   re-delegações permitidas por agente
     */
    public record Orchestrator(
            int maxIterations,
            Duration analysisTimeout,
            Duration agentTimeout,
            int maxAttempts,
            Duration retryBackoff,
            int maxParallelAgents,
            double minConfidence,
            Integer maxFollowUpsPerAgent) {
        public Orchestrator {
            maxIterations = maxIterations > 0 ? maxIterations : 2;
            analysisTimeout = analysisTimeout != null ? analysisTimeout : Duration.ofMinutes(30);
            agentTimeout = agentTimeout != null ? agentTimeout : Duration.ofMinutes(8);
            maxAttempts = maxAttempts > 0 ? maxAttempts : 2;
            retryBackoff = retryBackoff != null ? retryBackoff : Duration.ofSeconds(5);
            maxParallelAgents = maxParallelAgents > 0 ? maxParallelAgents : 3;
            minConfidence = minConfidence > 0 ? minConfidence : 0.5;
            maxFollowUpsPerAgent = maxFollowUpsPerAgent != null ? Math.max(maxFollowUpsPerAgent, 0) : 1;
        }
    }

    /**
     * @param mode {@code llm} (Claude decompõe a tarefa, com fallback por regras) ou {@code rules}
     */
    public record Planner(String mode) {
        public Planner {
            mode = mode != null && !mode.isBlank() ? mode : "llm";
        }

        public boolean useLlm() {
            return "llm".equalsIgnoreCase(mode);
        }
    }

    /**
     * @param enabled      agente disponível para o Hub
     * @param toolSources  fontes de ferramentas permitidas (least privilege)
     * @param systemPrompt recurso com o system prompt especializado
     * @param maxToolCalls orçamento de chamadas de ferramenta por execução
     * @param maxOutputTokens     {@code max_tokens} do agente; {@code null} usa {@code agenthub.claude.max-output-tokens}
     * @param contextBudgetTokens context window do agente; {@code null} usa {@code agenthub.claude.context-budget-tokens}
     */
    public record AgentProperties(Boolean enabled, List<String> toolSources, String systemPrompt, int maxToolCalls,
                                  Integer maxOutputTokens, Integer contextBudgetTokens) {
        static final AgentProperties DEFAULT = new AgentProperties(true, List.of(), null, 0);

        @ConstructorBinding
        public AgentProperties {
            enabled = enabled == null || enabled;
            toolSources = toolSources != null ? List.copyOf(toolSources) : List.of();
            maxToolCalls = maxToolCalls > 0 ? maxToolCalls : 30;
        }

        public AgentProperties(Boolean enabled, List<String> toolSources, String systemPrompt, int maxToolCalls) {
            this(enabled, toolSources, systemPrompt, maxToolCalls, null, null);
        }
    }

    /**
     * @param enabled      fonte habilitada
     * @param type         {@code local} (tools Spring AI in-process) ou {@code mcp} (servidor MCP externo)
     * @param connection   nome da conexão em {@code spring.ai.mcp.client.*.connections} (type=mcp)
     * @param allowedTools allowlist de ferramentas; vazio = todas as ferramentas do servidor
     * @param allowWrite   libera ferramentas com nome de operação de escrita/destrutiva (default {@code false})
     * @param pathArguments argumentos das ferramentas que são caminhos e precisam ficar dentro do projeto
     *                      ativo (ex.: {@code repo_path} do servidor git)
     * @param description  finalidade, exibida em {@code /api/agents}
     * @param bindToWorkspace servidor MCP preso ao projeto ativo: o cliente informa a raiz do projeto via MCP Roots
     *                        (atualizada na troca de projeto) e cada argumento-caminho é resolvido pelas regras do
     *                        workspace (raiz, escopo, exclusões, arquivos sensíveis) e reescrito como absoluto
     */
    public record ToolSourceProperties(
            Boolean enabled, String type, String connection, List<String> allowedTools, boolean allowWrite,
            List<String> pathArguments, String description, boolean bindToWorkspace) {
        public ToolSourceProperties {
            enabled = enabled == null || enabled;
            type = type != null ? type.toLowerCase() : "mcp";
            allowedTools = allowedTools != null ? List.copyOf(allowedTools) : List.of();
            pathArguments = pathArguments != null ? List.copyOf(pathArguments) : List.of();
            description = description != null ? description : "";
        }

        public boolean isLocal() {
            return "local".equals(type);
        }

        /** Nome da conexão MCP: {@code connection} ou, sem ela, o id da fonte. */
        public String connectionOr(String sourceId) {
            return connection != null ? connection : sourceId;
        }
    }

    /**
     * @param enabled   grava cada relatório em disco (Markdown + JSON)
     * @param outputDir diretório de saída
     */
    public record Reports(boolean enabled, String outputDir) {
        public Reports {
            outputDir = outputDir != null && !outputDir.isBlank() ? outputDir : "reports";
        }
    }
}
