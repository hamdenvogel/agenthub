package br.com.hvogel.agenthub.support;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import br.com.hvogel.agenthub.agents.Agent;
import br.com.hvogel.agenthub.agents.AgentExecutionContext;
import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.config.AgentHubProperties.AgentProperties;
import br.com.hvogel.agenthub.config.AgentHubProperties.Orchestrator;
import br.com.hvogel.agenthub.config.AgentHubProperties.Planner;
import br.com.hvogel.agenthub.config.AgentHubProperties.Reports;
import br.com.hvogel.agenthub.config.AgentHubProperties.TargetProject;
import br.com.hvogel.agenthub.config.AgentHubProperties.ToolSourceProperties;
import br.com.hvogel.agenthub.context.ProjectContext;
import br.com.hvogel.agenthub.model.AgentAnalysis;
import br.com.hvogel.agenthub.model.AgentAnalysis.FindingDraft;
import br.com.hvogel.agenthub.model.AgentResult;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.model.AgentTask;
import br.com.hvogel.agenthub.model.Evidence;
import br.com.hvogel.agenthub.model.Severity;

public final class Fixtures {

    private Fixtures() {
    }

    public static Orchestrator orchestrator(int maxIterations, Duration agentTimeout, int maxAttempts) {
        return new Orchestrator(maxIterations, Duration.ofMinutes(5), agentTimeout, maxAttempts,
                Duration.ofMillis(10), 3, 0.5, 1);
    }

    public static AgentHubProperties properties(String root, Orchestrator orchestrator) {
        Map<String, AgentProperties> agents = new HashMap<>();
        for (AgentRole role : AgentRole.values()) {
            agents.put(role.id(), new AgentProperties(true, List.of("filesystem"), null, 10));
        }
        agents.put("database", new AgentProperties(true, List.of("filesystem", "database"), null, 10));
        agents.put("security", new AgentProperties(true, List.of("filesystem", "git"), null, 10));
        Map<String, ToolSourceProperties> sources = Map.of(
                "filesystem", new ToolSourceProperties(true, "local", null, List.of(), false, List.of(), "fs", false),
                "git", new ToolSourceProperties(true, "mcp", "git", List.of("git_log", "git_status"), false,
                        List.of("repo_path"), "git", false),
                "files", new ToolSourceProperties(true, "mcp", "files", List.of("read_text_file", "read_multiple_files", "list_directory", "search_files", "write_file"),
                        false, List.of("path", "paths"), "files", true),
                "database", new ToolSourceProperties(false, "mcp", "postgres", List.of(), false, List.of(), "db", false));
        return new AgentHubProperties(new TargetProject(root, "demo", null, 0), orchestrator,
                new Planner("rules"), agents, sources, new Reports(false, null));
    }

    public static AgentHubProperties properties() {
        return properties(".", orchestrator(2, Duration.ofSeconds(5), 2));
    }

    public static ProjectContext emptyProject() {
        return new ProjectContext("/tmp/demo", "demo", List.of(), List.of(), List.of(),
                new ProjectContext.Infrastructure(null, null, null, null, null, null, null, null, null),
                List.of(), 0, List.of());
    }

    public static AgentAnalysis analysis(double confidence, FindingDraft... findings) {
        return new AgentAnalysis("Resumo da área", List.of(findings), List.of("Recomendação geral"), confidence);
    }

    public static FindingDraft finding(String title, Severity severity, String file) {
        List<Evidence> evidence = file != null ? List.of(new Evidence(file, "10", "trecho")) : List.of();
        return new FindingDraft(title, "Descrição de " + title, severity, "backend", evidence, "Corrigir " + title);
    }

    public static AgentResult success(AgentRole role, double confidence, FindingDraft... findings) {
        return AgentResult.fromAnalysis(AgentTask.initial(role, "objetivo", List.of(), List.of()),
                analysis(confidence, findings), Map.of("toolCalls", 3, "toolsAvailable", List.of("fs_read_file")));
    }

    /** Agente fake programável para testes do Hub. */
    public static Agent agent(AgentRole role, BiFunction<AgentTask, AgentExecutionContext, AgentResult> behavior) {
        return new Agent() {
            @Override
            public AgentRole role() {
                return role;
            }

            @Override
            public String description() {
                return "fake " + role.id();
            }

            @Override
            public String instructions() {
                return "fake";
            }

            @Override
            public List<String> toolSources() {
                return List.of();
            }

            @Override
            public AgentResult execute(AgentTask task, AgentExecutionContext context) {
                return behavior.apply(task, context);
            }
        };
    }
}
