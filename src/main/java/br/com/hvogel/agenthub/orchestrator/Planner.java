package br.com.hvogel.agenthub.orchestrator;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import br.com.hvogel.agenthub.agents.Agent;
import br.com.hvogel.agenthub.agents.AgentPromptLoader;
import br.com.hvogel.agenthub.agents.AgentRegistry;
import br.com.hvogel.agenthub.claude.ClaudeResponse;
import br.com.hvogel.agenthub.claude.ClaudeService;
import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.context.ContextSelector;
import br.com.hvogel.agenthub.context.ProjectContext;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.model.AgentTask;
import br.com.hvogel.agenthub.model.AnalysisPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * PLAN: entende a solicitação, decompõe em tarefas e escolhe os especialistas.
 * Modo {@code llm}: o Claude decide (sem ferramentas, só com o overview do discovery).
 * Se a chamada falhar ou o plano vier inválido, cai para o plano por regras.
 */
@Component
public class Planner {

    private static final Logger log = LoggerFactory.getLogger(Planner.class);

    private static final Map<AgentRole, List<String>> KEYWORDS = new EnumMap<>(Map.of(
            AgentRole.ARCHITECTURE, List.of("arquitetura", "architecture", "design", "camada", "acoplamento", "ddd",
                    "hexagonal", "clean", "padroes", "pattern", "solid", "revisao", "code review", "qualidade"),
            AgentRole.BACKEND, List.of("backend", "back-end", "java", "spring", "api", "rest", "endpoint", "controller", "service"),
            AgentRole.FRONTEND, List.of("frontend", "front-end", "angular", "typescript", "tela", "componente", "rxjs"),
            AgentRole.DATABASE, List.of("banco", "database", "sql", "query", "consulta", "indice", "jpa", "hibernate",
                    "postgres", "tabela", "migration", "modelagem"),
            AgentRole.SECURITY, List.of("seguranca", "security", "owasp", "jwt", "autenticacao", "autorizacao",
                    "vulnerab", "cors", "secret", "senha", "token"),
            AgentRole.TEST, List.of("teste", "test", "cobertura", "coverage", "junit", "mockito", "jasmine", "karma")));

    private static final List<String> FULL_SCOPE = List.of("completo", "completa", "geral", "tudo", "todo o projeto",
            "full", "consolidado", "relatorio", "analise meu projeto", "analise o projeto");

    private static final Map<AgentRole, List<String>> DEFAULT_FOCUS = new EnumMap<>(Map.of(
            AgentRole.ARCHITECTURE, List.of("organização em camadas e dependências entre pacotes", "acoplamento e coesão",
                    "aderência a SOLID e padrões", "qualidade de código e duplicações relevantes"),
            AgentRole.BACKEND, List.of("controllers REST e contratos", "services e regras de negócio",
                    "tratamento de exceções e validação", "uso de JPA/Hibernate e transações"),
            AgentRole.FRONTEND, List.of("estrutura de módulos e routing", "componentes e services",
                    "uso de RxJS (subscriptions, vazamentos)", "versão do Angular e dependências defasadas"),
            AgentRole.DATABASE, List.of("mapeamento de entidades e relacionamentos", "queries e N+1",
                    "índices e scripts DDL", "estratégia de migrations"),
            AgentRole.SECURITY, List.of("configuração do Spring Security e CORS", "geração/validação de JWT",
                    "segredos em arquivos de configuração", "autorização por endpoint e guards no frontend"),
            AgentRole.TEST, List.of("cobertura dos services e controllers", "qualidade dos testes (asserts, mocks)",
                    "testes de integração", "testes do frontend e pipeline de CI")));

    private final AgentRegistry agents;
    private final ClaudeService claude;
    private final ContextSelector contextSelector;
    private final AgentPromptLoader promptLoader;
    private final AgentHubProperties properties;

    public Planner(AgentRegistry agents, ClaudeService claude, ContextSelector contextSelector,
                   AgentPromptLoader promptLoader, AgentHubProperties properties) {
        this.agents = agents;
        this.claude = claude;
        this.contextSelector = contextSelector;
        this.promptLoader = promptLoader;
        this.properties = properties;
    }

    public AnalysisPlan plan(String request, ProjectContext context, List<AgentRole> requestedRoles) {
        List<AgentRole> available = agents.enabledRoles();
        if (requestedRoles != null && !requestedRoles.isEmpty()) {
            List<AgentRole> roles = requestedRoles.stream().filter(available::contains).distinct().toList();
            return rulesPlan(request, roles, "Agentes informados explicitamente na solicitação.");
        }
        if (properties.planner().useLlm()) {
            try {
                AnalysisPlan plan = llmPlan(request, context, available);
                if (!plan.tasks().isEmpty()) {
                    return plan;
                }
                log.warn("event=planner.llm_empty fallback=rules");
            } catch (RuntimeException e) {
                log.warn("event=planner.llm_failed fallback=rules error={}", e.getMessage());
            }
        }
        return rulesPlan(request, selectByKeywords(request, available), "Seleção por palavras-chave da solicitação.");
    }

    // ----------------------------------------------------------------------- LLM

    public record PlanDraft(String objective, String rationale, List<TaskDraft> tasks) {
    }

    public record TaskDraft(String agent, String objective, List<String> focus, List<String> dependsOn) {
    }

    private AnalysisPlan llmPlan(String request, ProjectContext context, List<AgentRole> available) {
        String agentsCatalog = available.stream()
                .map(role -> agents.find(role).map(Agent::description).map(d -> "- " + role.name() + ": " + d).orElse(""))
                .collect(Collectors.joining("\n"));
        String user = """
                # Solicitação do usuário
                %s

                # Projeto (resultado do discovery)
                %s
                # Agentes disponíveis (use exatamente estes identificadores)
                %s
                """.formatted(request, contextSelector.overview(context), agentsCatalog);
        ClaudeResponse<PlanDraft> response = claude.structured(
                promptLoader.read("classpath:prompts/hub/planner.md"), user, List.of(), PlanDraft.class);
        List<AgentTask> tasks = toTasks(response.entity(), available);
        log.info("event=planner.done source=llm agents={}", tasks.stream().map(t -> t.role().id()).toList());
        PlanDraft draft = response.entity();
        return new AnalysisPlan(draft.objective(), draft.rationale(), tasks, "llm",
                response.inputTokens(), response.outputTokens());
    }

    static List<AgentTask> toTasks(PlanDraft draft, List<AgentRole> available) {
        if (draft == null || draft.tasks() == null) {
            return List.of();
        }
        Map<AgentRole, TaskDraft> byRole = new EnumMap<>(AgentRole.class);
        for (TaskDraft task : draft.tasks()) {
            if (task == null) {
                continue;
            }
            AgentRole.parse(task.agent()).filter(available::contains).ifPresent(role -> byRole.putIfAbsent(role, task));
        }
        List<AgentTask> tasks = new ArrayList<>();
        byRole.forEach((role, task) -> {
            List<AgentRole> deps = task.dependsOn() == null ? List.of() : task.dependsOn().stream()
                    .map(AgentRole::parse)
                    .flatMap(java.util.Optional::stream)
                    .filter(d -> d != role && byRole.containsKey(d))
                    .distinct()
                    .toList();
            String objective = task.objective() != null && !task.objective().isBlank()
                    ? task.objective()
                    : "Analisar a área " + role.reportSection() + " do projeto.";
            tasks.add(AgentTask.initial(role, objective, task.focus(), deps));
        });
        return tasks;
    }

    // --------------------------------------------------------------------- rules

    AnalysisPlan rulesPlan(String request, List<AgentRole> roles, String rationale) {
        Set<AgentRole> selected = new LinkedHashSet<>(roles);
        List<AgentTask> tasks = selected.stream().map(role -> {
            List<AgentRole> deps = role == AgentRole.TEST
                    ? selected.stream().filter(r -> r == AgentRole.BACKEND || r == AgentRole.FRONTEND).toList()
                    : List.of();
            return AgentTask.initial(role,
                    "Analise a área " + role.reportSection() + " do projeto para atender à solicitação: " + request,
                    DEFAULT_FOCUS.getOrDefault(role, List.of()), deps);
        }).toList();
        log.info("event=planner.done source=rules agents={}", tasks.stream().map(t -> t.role().id()).toList());
        return new AnalysisPlan(request, rationale, tasks, "rules", 0, 0);
    }

    static List<AgentRole> selectByKeywords(String request, List<AgentRole> available) {
        String normalized = normalize(request);
        if (FULL_SCOPE.stream().anyMatch(normalized::contains)) {
            return available;
        }
        List<AgentRole> matched = available.stream()
                .filter(role -> KEYWORDS.getOrDefault(role, List.of()).stream()
                        .anyMatch(keyword -> Pattern.compile("\\b" + Pattern.quote(keyword)).matcher(normalized).find()))
                .toList();
        return matched.isEmpty() ? available : matched;
    }

    private static String normalize(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
