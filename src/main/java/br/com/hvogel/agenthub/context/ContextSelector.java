package br.com.hvogel.agenthub.context;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import br.com.hvogel.agenthub.context.ProjectContext.BackendModule;
import br.com.hvogel.agenthub.context.ProjectContext.FrontendModule;
import br.com.hvogel.agenthub.context.ProjectContext.Infrastructure;
import br.com.hvogel.agenthub.model.AgentRole;
import org.springframework.stereotype.Component;

/**
 * Seleciona o contexto relevante para cada especialista. Envia caminhos e metadados,
 * nunca conteúdo de arquivos: o agente lê sob demanda pelas ferramentas permitidas,
 * o que reduz tokens e mantém o least privilege.
 */
@Component
public class ContextSelector {

    static final int MAX_ITEMS_PER_LIST = 40;

    /** Resumo compacto do projeto, usado pelo Planner. */
    public String overview(ProjectContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Projeto: ").append(ctx.name()).append(" (").append(ctx.totalFiles()).append(" arquivos indexados)\n");
        if (!ctx.scope().isEmpty()) {
            sb.append("- Escopo: ").append(String.join(", ", ctx.scope())).append('\n');
        }
        for (BackendModule m : ctx.backendModules()) {
            sb.append("- Backend ").append(m.path()).append(": ").append(m.buildTool())
                    .append(", Java ").append(orUnknown(m.javaVersion()))
                    .append(", Spring Boot ").append(orUnknown(m.springBootVersion()))
                    .append(", ").append(counts(m.artifacts())).append('\n');
        }
        for (FrontendModule m : ctx.frontendModules()) {
            sb.append("- Frontend ").append(m.path()).append(": Angular ").append(orUnknown(m.angularVersion()))
                    .append(", TypeScript ").append(orUnknown(m.typescriptVersion()))
                    .append(", RxJS ").append(orUnknown(m.rxjsVersion()))
                    .append(", ").append(counts(m.artifacts())).append('\n');
        }
        Infrastructure infra = ctx.infrastructure();
        sb.append("- Banco: ").append(join(infra.databases())).append('\n');
        sb.append("- Docker: ").append(join(infra.docker())).append(" | CI/CD: ").append(join(infra.ciCd()))
                .append(" | Nuvem: ").append(join(infra.cloud())).append('\n');
        return sb.toString();
    }

    /** Briefing específico do papel: stack relevante + listas de arquivos candidatos. */
    public String briefFor(AgentRole role, ProjectContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Projeto analisado\n");
        sb.append("Nome: ").append(ctx.name()).append('\n');
        sb.append("Caminhos são relativos à raiz do projeto; use-os em todas as ferramentas de arquivo (o Hub resolve o absoluto).\n");
        sb.append("Para citar números de linha nas evidências, use fs_search_text (retorna arquivo:linha).\n");
        if (!ctx.scope().isEmpty()) {
            sb.append("Escopo desta análise (concentre-se nele; leituras fora dele são negadas): ")
                    .append(String.join(", ", ctx.scope())).append('\n');
        }
        if (!ctx.gitRepositories().isEmpty()) {
            sb.append("Repositórios git (para ferramentas git_*, informe repo_path absoluto): ")
                    .append(ctx.gitRepositories().stream()
                            .map(r -> ".".equals(r) ? ctx.root() : Path.of(r).isAbsolute() ? r : ctx.root() + "/" + r)
                            .map(p -> p.replace('\\', '/'))
                            .collect(Collectors.joining(", ")))
                    .append('\n');
        }
        sb.append('\n');
        switch (role) {
            case BACKEND -> appendBackend(sb, ctx, List.of("controllers", "services", "repositories", "entities", "dtos", "configurations", "resources"), true);
            case FRONTEND -> appendFrontend(sb, ctx, List.of("modules", "routing", "components", "services", "guards", "interceptors"), true);
            case DATABASE -> {
                appendBackend(sb, ctx, List.of("entities", "repositories", "resources"), false);
                appendDatabase(sb, ctx.infrastructure());
            }
            case SECURITY -> {
                appendBackend(sb, ctx, List.of("security", "configurations", "controllers", "resources"), true);
                appendFrontend(sb, ctx, List.of("guards", "interceptors", "services"), true);
                appendList(sb, "Arquivos de configuração", ctx.infrastructure().configFiles());
                appendList(sb, "Arquivos sensíveis presentes (leitura bloqueada; reporte apenas a existência se relevante)",
                        ctx.files().stream().filter(f -> f.matches("(?i).*(^|/)\\.env(\\..*)?$")).toList());
            }
            case TEST -> {
                appendBackend(sb, ctx, List.of("tests", "services", "controllers"), true);
                appendFrontend(sb, ctx, List.of("tests", "services", "components"), true);
                appendList(sb, "CI/CD", ctx.infrastructure().ciCd());
            }
            case ARCHITECTURE -> {
                appendBackend(sb, ctx, List.of("configurations"), true);
                appendFrontend(sb, ctx, List.of("modules", "routing"), false);
                appendPackageTree(sb, ctx);
                appendInfrastructure(sb, ctx.infrastructure());
            }
        }
        return sb.toString();
    }

    private void appendBackend(StringBuilder sb, ProjectContext ctx, List<String> categories, boolean withDependencies) {
        for (BackendModule m : ctx.backendModules()) {
            sb.append("## Backend: ").append(m.path()).append('\n');
            sb.append("Build: ").append(m.buildTool()).append(" | artifactId: ").append(orUnknown(m.artifactId()))
                    .append(" | Java: ").append(orUnknown(m.javaVersion()))
                    .append(" | Spring Boot: ").append(orUnknown(m.springBootVersion())).append('\n');
            sb.append("Contagem: ").append(counts(m.artifacts())).append('\n');
            if (withDependencies) {
                appendList(sb, "Dependências declaradas", m.dependencies());
            }
            for (String category : categories) {
                appendList(sb, capitalize(category), m.artifacts(category));
            }
            sb.append('\n');
        }
    }

    private void appendFrontend(StringBuilder sb, ProjectContext ctx, List<String> categories, boolean withDependencies) {
        for (FrontendModule m : ctx.frontendModules()) {
            sb.append("## Frontend: ").append(m.path()).append('\n');
            sb.append("Angular: ").append(orUnknown(m.angularVersion()))
                    .append(" | TypeScript: ").append(orUnknown(m.typescriptVersion()))
                    .append(" | RxJS: ").append(orUnknown(m.rxjsVersion()))
                    .append(" | Package manager: ").append(m.packageManager())
                    .append(" | Signals: ").append(m.usesSignals() ? "sim" : "não detectado")
                    .append(" | Estado: ").append(m.stateManagement()).append('\n');
            sb.append("Contagem: ").append(counts(m.artifacts())).append('\n');
            if (withDependencies) {
                appendList(sb, "Dependências declaradas", m.dependencies());
            }
            for (String category : categories) {
                appendList(sb, capitalize(category), m.artifacts(category));
            }
            sb.append('\n');
        }
    }

    private void appendDatabase(StringBuilder sb, Infrastructure infra) {
        sb.append("## Banco de dados\n");
        appendList(sb, "Bancos detectados", infra.databases());
        appendList(sb, "Migrations", infra.migrations());
        appendList(sb, "Scripts SQL", infra.sqlScripts());
        sb.append('\n');
    }

    private void appendInfrastructure(StringBuilder sb, Infrastructure infra) {
        sb.append("## Infraestrutura\n");
        appendList(sb, "Docker", infra.docker());
        appendList(sb, "CI/CD", infra.ciCd());
        appendList(sb, "Kubernetes", infra.kubernetes());
        appendList(sb, "Nuvem", infra.cloud());
        appendList(sb, "Bancos", infra.databases());
        appendList(sb, "Observabilidade", infra.observability());
        sb.append('\n');
    }

    /** Pacotes Java com quantidade de classes: dá ao Architecture Agent a forma do código sem ler arquivos. */
    private void appendPackageTree(StringBuilder sb, ProjectContext ctx) {
        Map<String, Long> packages = new TreeMap<>(ctx.files().stream()
                .filter(f -> f.contains("src/main/java/") && f.endsWith(".java"))
                .map(f -> f.substring(0, f.lastIndexOf('/')))
                .collect(Collectors.groupingBy(p -> p, Collectors.counting())));
        if (!packages.isEmpty()) {
            sb.append("## Pacotes Java (classes por pacote)\n");
            packages.forEach((pkg, count) -> sb.append("- ").append(pkg).append(" (").append(count).append(")\n"));
            sb.append('\n');
        }
    }

    static void appendList(StringBuilder sb, String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        sb.append(title).append(" (").append(items.size()).append("):\n");
        items.stream().limit(MAX_ITEMS_PER_LIST).forEach(i -> sb.append("- ").append(i).append('\n'));
        if (items.size() > MAX_ITEMS_PER_LIST) {
            sb.append("- ... +").append(items.size() - MAX_ITEMS_PER_LIST)
                    .append(" (liste o restante com a busca de arquivos por nome)\n");
        }
    }

    private static String counts(Map<String, List<String>> artifacts) {
        return artifacts.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().size())
                .collect(Collectors.joining(", "));
    }

    private static String join(List<String> values) {
        return values.isEmpty() ? "não detectado" : String.join(", ", values);
    }

    private static String orUnknown(String value) {
        return value != null ? value : "não declarado";
    }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
