package br.com.hvogel.agenthub.remediation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import br.com.hvogel.agenthub.analysis.AnalysisRepository;
import br.com.hvogel.agenthub.analysis.AnalysisRun;
import br.com.hvogel.agenthub.analysis.ReportArchive;
import br.com.hvogel.agenthub.claude.CallLimits;
import br.com.hvogel.agenthub.claude.ClaudeResponse;
import br.com.hvogel.agenthub.claude.ClaudeService;
import br.com.hvogel.agenthub.context.ProjectWorkspace;
import br.com.hvogel.agenthub.context.SecretMasker;
import br.com.hvogel.agenthub.context.WorkspaceTarget;
import br.com.hvogel.agenthub.exception.AnalysisNotFoundException;
import br.com.hvogel.agenthub.exception.ConsentRequiredException;
import br.com.hvogel.agenthub.exception.RemediationConflictException;
import br.com.hvogel.agenthub.exception.RemediationException;
import br.com.hvogel.agenthub.exception.RemediationNotFoundException;
import br.com.hvogel.agenthub.exception.ToolAccessDeniedException;
import br.com.hvogel.agenthub.mcp.McpToolRegistry;
import br.com.hvogel.agenthub.mcp.ToolBudget;
import br.com.hvogel.agenthub.model.AnalysisReport;
import br.com.hvogel.agenthub.model.Evidence;
import br.com.hvogel.agenthub.model.Finding;
import br.com.hvogel.agenthub.remediation.RemediationProposal.ProposedFile;
import br.com.hvogel.agenthub.remediation.RemediationProposal.Status;
import br.com.hvogel.agenthub.remediation.RemediationStore.Consent;
import br.com.hvogel.agenthub.remediation.RemediationStore.ConsentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Correção assistida dos achados, sempre com o usuário no controle:
 * <ol>
 *   <li>consentimento explícito por projeto ({@link #acceptConsent()});</li>
 *   <li>o agente de remediação lê o projeto (somente leitura, segredos mascarados) e propõe edições;</li>
 *   <li>o usuário revisa o diff e aprova a gravação ({@link #apply(String)}), que guarda backup dos originais;</li>
 *   <li>a correção pode ser desfeita enquanto os arquivos não forem alterados depois ({@link #revert(String)}).</li>
 * </ol>
 * Gravar e desfazer sincronizam no {@link ProjectWorkspace}, como a troca de projeto e o início de análises.
 */
@Service
public class RemediationService {

    private static final Logger log = LoggerFactory.getLogger(RemediationService.class);

    static final int TERMS_VERSION = 1;
    static final String AGENT_ID = "remediation";
    /** Leitura e busca no projeto; as mesmas fontes dos agentes de análise (somente leitura). */
    static final List<String> TOOL_SOURCES = List.of("filesystem", "code-search");

    static final String SYSTEM_PROMPT = """
            Você é o agente de remediação do AgentHub. Recebe UM achado de uma análise e propõe a menor correção
            de código segura e completa para ele, no projeto indicado.

            # Como trabalhar
            - Leia os arquivos envolvidos com as ferramentas antes de propor qualquer edição.
            - Corrija somente o achado informado. Preserve o estilo, a indentação e as convenções do projeto.
            - O código precisa continuar compilando: ajuste imports, chamadas e testes afetados pela mudança.
            - Não atualize versões major de dependências nem faça refatorações amplas; se forem necessárias,
              descreva-as em manualSteps.
            - No máximo %d arquivos por correção.

            # Formato das alterações
            - action MODIFY: edits com pares search/replace. search é uma cópia EXATA de um trecho contíguo do
              arquivo atual (como retornado pela leitura do arquivo, SEM prefixos de número de linha, se houver),
              com indentação idêntica e único no arquivo; inclua 1 a 3 linhas de contexto para garantir isso.
              As edições de um arquivo são aplicadas em ordem, cada uma sobre o resultado da anterior.
            - action CREATE: content com o conteúdo completo do arquivo novo (o arquivo não pode existir).
            - Use um único item em changes por arquivo.

            # Segredos
            - Valores ***MASKED(n chars)*** foram ocultados pelo Hub. Você pode usá-los em search (o Hub casa
              com o valor real), mas NUNCA em replace ou content.
            - Para remover um segredo do código ou da configuração, troque o valor por um placeholder de variável
              de ambiente (ex.: ${JWT_SECRET} no Spring) e inclua em manualSteps: definir a variável em cada
              ambiente e rotacionar o segredo exposto.

            # Quando não alterar código
            - Se a correção depender de ações fora do código (rotacionar chaves, limpar o histórico do Git,
              mudar infraestrutura) ou for arriscada demais para aplicar automaticamente, devolva changes vazio
              e explique os passos em manualSteps.

            # Saída
            - Responda em português do Brasil, somente no formato estruturado pedido.
            - summary: o que a correção faz, em 1 a 3 frases. risks: efeitos colaterais e o que testar.
            - confidence (0.0 a 1.0): o quanto você verificou que a correção resolve o achado sem quebrar nada.
            """;

    /**
     * @param enabled      recurso habilitado
     * @param accepted     termos aceitos para o projeto ativo
     * @param acceptedAt   quando foram aceitos
     * @param projectName  projeto ativo
     * @param projectRoot  raiz do projeto ativo
     * @param termsVersion versão dos termos
     * @param terms        termos exibidos ao usuário
     */
    public record ConsentStatus(boolean enabled, boolean accepted, Instant acceptedAt, String projectName,
                                String projectRoot, int termsVersion, List<String> terms) {
    }

    private record Prepared(List<ProposedFile> files, List<String> problems) {
    }

    private final RemediationProperties properties;
    private final ProjectWorkspace workspace;
    private final ClaudeService claude;
    private final McpToolRegistry toolRegistry;
    private final AnalysisRepository analyses;
    private final ReportArchive archive;
    private final ExecutorService executor;
    private final RemediationStore store;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final Map<String, RemediationProposal> proposals = new ConcurrentHashMap<>();

    public RemediationService(RemediationProperties properties, ProjectWorkspace workspace, ClaudeService claude,
                              McpToolRegistry toolRegistry, AnalysisRepository analyses, ReportArchive archive,
                              ExecutorService agentHubExecutor) {
        this.properties = properties;
        this.workspace = workspace;
        this.claude = claude;
        this.toolRegistry = toolRegistry;
        this.analyses = analyses;
        this.archive = archive;
        this.executor = agentHubExecutor;
        this.store = new RemediationStore(Path.of(properties.storeDir()));
        for (RemediationProposal p : store.loadAll()) {
            RemediationProposal loaded = p.status() == Status.GENERATING
                    ? p.failed("Geração interrompida pelo reinício do AgentHub. Gere novamente.")
                    : p;
            if (loaded != p) {
                store.save(loaded);
            }
            proposals.put(loaded.id(), loaded);
        }
    }

    // ------------------------------------------------------------------ consentimento

    public ConsentStatus consent() {
        WorkspaceTarget target = workspace.target();
        Optional<Consent> consent = currentConsent(target.root());
        return new ConsentStatus(properties.enabled(), consent.isPresent(),
                consent.map(Consent::acceptedAt).orElse(null), target.name(), target.root().toString(), TERMS_VERSION,
                terms(target));
    }

    public synchronized ConsentStatus acceptConsent() {
        requireEnabled();
        String root = workspace.root().toString();
        List<Consent> consents = new ArrayList<>(store.loadConsents().consents().stream()
                .filter(c -> !samePath(c.projectRoot(), root)).toList());
        consents.add(new Consent(root, Instant.now(), TERMS_VERSION));
        store.saveConsents(new ConsentState(consents));
        log.info("event=remediation.consent_accepted root={}", root);
        return consent();
    }

    public synchronized ConsentStatus revokeConsent() {
        String root = workspace.root().toString();
        store.saveConsents(new ConsentState(store.loadConsents().consents().stream()
                .filter(c -> !samePath(c.projectRoot(), root)).toList()));
        log.info("event=remediation.consent_revoked root={}", root);
        return consent();
    }

    // ------------------------------------------------------------------ propostas

    public List<RemediationProposal> list(String analysisId) {
        return proposals.values().stream()
                .filter(p -> analysisId == null || analysisId.equals(p.analysisId()))
                .sorted(Comparator.comparing(RemediationProposal::createdAt).reversed())
                .toList();
    }

    public RemediationProposal get(String id) {
        RemediationProposal proposal = RemediationStore.isValidId(id) ? proposals.get(id) : null;
        if (proposal == null) {
            throw new RemediationNotFoundException("Proposta de correção não encontrada: " + id);
        }
        return proposal;
    }

    /** Pede uma correção para o achado; a geração roda em segundo plano (acompanhe por {@link #get(String)}). */
    public RemediationProposal propose(String analysisId, String findingId) {
        requireEnabled();
        if (analysisId == null || analysisId.isBlank() || findingId == null || findingId.isBlank()) {
            throw new RemediationException("Informe a análise e o achado.");
        }
        WorkspaceTarget target = workspace.target();
        requireConsent(target);
        AnalysisReport report = findReport(analysisId);
        Finding finding = report.findings().stream().filter(f -> findingId.equals(f.id())).findFirst()
                .orElseThrow(() -> new RemediationNotFoundException("Achado " + findingId + " não encontrado na análise."));
        requireSameProject(report, target);

        synchronized (this) {
            Optional<RemediationProposal> existing = proposals.values().stream()
                    .filter(p -> analysisId.equals(p.analysisId()) && findingId.equals(p.findingId()) && p.status().isActive())
                    .findFirst();
            if (existing.isPresent()) {
                return existing.get();
            }
            RemediationProposal proposal = RemediationProposal.generating(newId(), analysisId, findingId,
                    finding.title(), finding.severity(), target.root().toString());
            save(proposal);
            log.info("event=remediation.requested id={} analysisId={} finding={} root={}",
                    proposal.id(), analysisId, findingId, target.root());
            executor.submit(() -> generate(proposal, finding, target));
            return proposal;
        }
    }

    /** Grava a correção revisada pelo usuário, com backup dos originais. */
    public RemediationProposal apply(String id) {
        synchronized (workspace) {
            RemediationProposal proposal = get(id);
            if (proposal.status() != Status.READY) {
                throw new RemediationConflictException("Só correções prontas para revisão podem ser aplicadas (status: "
                        + proposal.status() + ").");
            }
            WorkspaceTarget target = workspace.target();
            requireConsent(target);
            requireIdle();
            requireSameRoot(proposal, target);

            List<String> originals = new ArrayList<>();
            List<String> contents = new ArrayList<>();
            List<Path> paths = new ArrayList<>();
            for (ProposedFile file : proposal.files()) {
                Path path = resolveForWrite(file.path());
                String original = null;
                String content;
                if (file.created()) {
                    if (Files.exists(path)) {
                        throw new RemediationConflictException(file.path() + " passou a existir depois da proposta. Gere uma nova proposta.");
                    }
                    content = file.content();
                } else {
                    original = read(path, file.path());
                    if (!sha256(original).equals(file.originalSha256())) {
                        throw new RemediationConflictException(file.path() + " foi alterado depois da proposta. Gere uma nova proposta.");
                    }
                    content = EditApplier.apply(original, file.edits());
                }
                if (!sha256(content).equals(file.newSha256())) {
                    throw new RemediationConflictException("O resultado de " + file.path() + " não confere com o diff revisado. Gere uma nova proposta.");
                }
                paths.add(path);
                originals.add(original);
                contents.add(content);
            }

            for (int i = 0; i < paths.size(); i++) {
                if (originals.get(i) != null) {
                    RemediationStore.writeAtomic(store.backupFile(id, proposal.files().get(i).path()), originals.get(i));
                }
            }
            int written = 0;
            try {
                for (; written < paths.size(); written++) {
                    RemediationStore.writeAtomic(paths.get(written), contents.get(written));
                }
            } catch (RuntimeException e) {
                restore(paths.subList(0, written), originals);
                throw new RemediationException("Falha ao gravar " + proposal.files().get(written).path() + ": "
                        + e.getMessage() + ". Os arquivos já gravados foram restaurados.");
            }
            RemediationProposal applied = proposal.withStatus(Status.APPLIED);
            save(applied);
            log.info("event=remediation.applied id={} finding={} files={}", id, proposal.findingId(),
                    proposal.files().stream().map(ProposedFile::path).toList());
            return applied;
        }
    }

    /** Restaura os originais (ou remove os arquivos criados) se ninguém alterou os arquivos depois. */
    public RemediationProposal revert(String id) {
        synchronized (workspace) {
            RemediationProposal proposal = get(id);
            if (proposal.status() != Status.APPLIED) {
                throw new RemediationConflictException("Só correções aplicadas podem ser desfeitas (status: " + proposal.status() + ").");
            }
            WorkspaceTarget target = workspace.target();
            requireIdle();
            requireSameRoot(proposal, target);
            List<Path> paths = new ArrayList<>();
            List<String> originals = new ArrayList<>();
            for (ProposedFile file : proposal.files()) {
                Path path = resolveForWrite(file.path());
                if (!Files.isRegularFile(path) || !sha256(read(path, file.path())).equals(file.newSha256())) {
                    throw new RemediationConflictException(file.path() + " foi alterado depois da correção; desfaça manualmente"
                            + (file.created() ? "." : " (original em " + store.backupFile(id, file.path()) + ")."));
                }
                paths.add(path);
                originals.add(file.created() ? null : read(store.backupFile(id, file.path()), file.path()));
            }
            restore(paths, originals);
            RemediationProposal reverted = proposal.withStatus(Status.REVERTED);
            save(reverted);
            log.info("event=remediation.reverted id={} finding={}", id, proposal.findingId());
            return reverted;
        }
    }

    /** Remove uma proposta que não está aplicada (para gerar outra). */
    public void discard(String id) {
        synchronized (workspace) {
            RemediationProposal proposal = get(id);
            if (proposal.status() == Status.GENERATING || proposal.status() == Status.APPLIED) {
                throw new RemediationConflictException(proposal.status() == Status.APPLIED
                        ? "Desfaça a correção antes de descartá-la."
                        : "Aguarde o fim da geração para descartar.");
            }
            proposals.remove(id);
            store.delete(id);
            log.info("event=remediation.discarded id={}", id);
        }
    }

    // ------------------------------------------------------------------ geração

    void generate(RemediationProposal proposal, Finding finding, WorkspaceTarget target) {
        try {
            String system = SYSTEM_PROMPT.formatted(properties.maxFiles());
            String user = userPrompt(finding, target);
            CallLimits limits = CallLimits.defaults();
            ToolBudget budget = new ToolBudget(properties.maxToolCalls(), limits.context());
            ClaudeResponse<FixPlan> response = claude.structured(system, user, tools(budget), FixPlan.class, limits);
            long inputTokens = response.inputTokens();
            long outputTokens = response.outputTokens();
            int toolCalls = budget.used();
            FixPlan plan = response.entity();
            Prepared prepared = prepare(plan);

            if (!prepared.problems().isEmpty()) {
                log.info("event=remediation.retry id={} problems={}", proposal.id(), prepared.problems());
                CallLimits retryLimits = CallLimits.defaults();
                ToolBudget retryBudget = new ToolBudget(Math.max(5, properties.maxToolCalls() / 2), retryLimits.context());
                ClaudeResponse<FixPlan> retry = claude.structured(system,
                        user + retrySection(plan, prepared.problems()), tools(retryBudget), FixPlan.class, retryLimits);
                inputTokens += retry.inputTokens();
                outputTokens += retry.outputTokens();
                toolCalls += retryBudget.used();
                plan = retry.entity();
                prepared = prepare(plan);
            }
            if (!prepared.problems().isEmpty()) {
                save(proposal.failed("A correção proposta não pôde ser validada: " + String.join(" | ", prepared.problems())));
                return;
            }
            if (!target.root().equals(workspace.root())) {
                save(proposal.failed("O projeto ativo mudou durante a geração. Gere novamente."));
                return;
            }
            RemediationProposal ready = proposal.planned(plan, prepared.files(), response.model(), inputTokens,
                    outputTokens, toolCalls);
            save(ready);
            log.info("event=remediation.ready id={} status={} files={} inputTokens={} outputTokens={} toolCalls={}",
                    proposal.id(), ready.status(), ready.files().size(), inputTokens, outputTokens, toolCalls);
        } catch (RuntimeException e) {
            log.warn("event=remediation.failed id={} error={}", proposal.id(), e.getMessage());
            save(proposal.failed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /** Valida o plano contra os arquivos atuais e monta os diffs; problemas voltam ao agente numa nova tentativa. */
    private Prepared prepare(FixPlan plan) {
        List<ProposedFile> files = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if (plan.changes().size() > properties.maxFiles()) {
            problems.add("A correção altera " + plan.changes().size() + " arquivos; o máximo é " + properties.maxFiles() + ".");
            return new Prepared(files, problems);
        }
        Set<String> seen = new HashSet<>();
        for (FixPlan.FileChange change : plan.changes()) {
            String label = change.path() != null ? change.path() : "(sem caminho)";
            try {
                if (change.path() == null || change.path().isBlank()) {
                    throw new RemediationException("alteração sem 'path'.");
                }
                Path path = resolveForWrite(change.path());
                String relative = workspace.relativize(path);
                label = relative;
                if (!seen.add(relative)) {
                    throw new RemediationException("arquivo repetido em changes; junte as edições num único item.");
                }
                if (change.isCreate()) {
                    files.add(created(path, relative, change));
                } else {
                    modified(path, relative, change).ifPresent(files::add);
                }
            } catch (RemediationException | ToolAccessDeniedException e) {
                problems.add(label + ": " + e.getMessage());
            }
        }
        return new Prepared(files, problems);
    }

    private ProposedFile created(Path path, String relative, FixPlan.FileChange change) {
        if (Files.exists(path)) {
            throw new RemediationException("o arquivo já existe; use action MODIFY.");
        }
        String content = change.content();
        if (content == null || content.isBlank()) {
            throw new RemediationException("CREATE sem 'content'.");
        }
        if (content.contains("***MASKED")) {
            throw new RemediationException("o conteúdo contém valor mascarado; use placeholders de variável de ambiente.");
        }
        LineDiff.Result diff = LineDiff.unified(relative, null, content);
        return new ProposedFile(relative, true, change.explanation(), List.of(), content, null, sha256(content),
                SecretMasker.mask(diff.unified()), diff.additions(), diff.deletions());
    }

    private Optional<ProposedFile> modified(Path path, String relative, FixPlan.FileChange change) {
        if (!Files.isRegularFile(path)) {
            throw new RemediationException("arquivo não encontrado; para arquivo novo use action CREATE.");
        }
        if (change.edits().isEmpty()) {
            throw new RemediationException("MODIFY sem 'edits'.");
        }
        String original = read(path, relative);
        String updated = EditApplier.apply(original, change.edits());
        if (updated.equals(original)) {
            return Optional.empty();
        }
        LineDiff.Result diff = LineDiff.unified(relative, original, updated);
        return Optional.of(new ProposedFile(relative, false, change.explanation(), change.edits(), null,
                sha256(original), sha256(updated), SecretMasker.mask(diff.unified()), diff.additions(), diff.deletions()));
    }

    private List<ToolCallback> tools(ToolBudget budget) {
        return toolRegistry.toolsFor(AGENT_ID, TOOL_SOURCES, budget);
    }

    static String userPrompt(Finding finding, WorkspaceTarget target) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Achado a corrigir\n");
        sb.append("- ID: ").append(finding.id()).append('\n');
        sb.append("- Título: ").append(finding.title()).append('\n');
        sb.append("- Severidade: ").append(finding.severity()).append('\n');
        if (finding.category() != null) {
            sb.append("- Categoria: ").append(finding.category()).append('\n');
        }
        if (finding.description() != null) {
            sb.append("\n## Descrição\n").append(finding.description()).append('\n');
        }
        if (!finding.evidence().isEmpty()) {
            sb.append("\n## Evidências\n");
            for (Evidence e : finding.evidence()) {
                sb.append("- ").append(Objects.toString(e.file(), "(sem arquivo)"));
                if (e.lines() != null) {
                    sb.append(':').append(e.lines());
                }
                sb.append('\n');
                if (e.excerpt() != null && !e.excerpt().isBlank()) {
                    sb.append("```\n").append(SecretMasker.mask(e.excerpt().strip())).append("\n```\n");
                }
            }
        }
        if (finding.recommendation() != null) {
            sb.append("\n## Recomendação da análise\n").append(finding.recommendation()).append('\n');
        }
        sb.append("\n# Projeto\n- Nome: ").append(target.name()).append('\n');
        sb.append("- Caminhos relativos à raiz do projeto (as evidências já usam esse formato).\n");
        if (target.scoped()) {
            sb.append("- Escopo (só estes caminhos podem ser lidos e alterados): ")
                    .append(String.join(", ", target.includes())).append('\n');
        }
        sb.append("\nUse as ferramentas de arquivo (leitura, listagem, busca por nome e fs_search_text para conteúdo)"
                + " para ler o código antes de propor a correção.\n");
        return sb.toString();
    }

    private String retrySection(FixPlan previous, List<String> problems) {
        return "\n# Sua proposta anterior foi recusada pelo Hub\n"
                + problems.stream().map(p -> "- " + p).collect(Collectors.joining("\n"))
                + "\n\nProposta anterior (JSON):\n" + jsonMapper.writeValueAsString(previous)
                + "\n\nReleia os arquivos e devolva a correção completa outra vez, corrigindo esses problemas.\n";
    }

    // ------------------------------------------------------------------ apoio

    private List<String> terms(WorkspaceTarget target) {
        String scope = target.scoped() ? " (escopo: " + String.join(", ", target.includes()) + ")" : "";
        return List.of(
                "O AgentHub poderá gravar alterações nos arquivos do projeto " + target.root() + scope
                        + ". Arquivos sensíveis (.env, chaves, keystores) e diretórios excluídos continuam bloqueados.",
                "Cada correção é gerada pelo Claude a partir de um achado e mostrada como diff. Nada é gravado sem que "
                        + "você revise e confirme a aplicação daquela correção.",
                "Antes de gravar, uma cópia dos originais é guardada em " + store.dir()
                        + ", e a correção pode ser desfeita enquanto os arquivos não forem alterados depois.",
                "Correções geradas por IA podem conter erros: compile, rode os testes e revise antes de fazer commit. "
                        + "Recomenda-se commitar o trabalho atual no Git antes de aplicar.",
                "Ações fora do código (rotacionar segredos, limpar o histórico do Git, configurar variáveis de ambiente) "
                        + "não são executadas: aparecem como passos manuais.",
                "Este consentimento vale só para este projeto e pode ser revogado a qualquer momento.");
    }

    private AnalysisReport findReport(String analysisId) {
        return analyses.find(analysisId).map(AnalysisRun::report).filter(Objects::nonNull)
                .orElseGet(() -> archive.list().stream()
                        .filter(r -> analysisId.equals(r.analysisId()))
                        .findFirst()
                        .map(r -> archive.load(r.reportId()))
                        .orElseThrow(() -> new AnalysisNotFoundException(analysisId)));
    }

    private void requireSameProject(AnalysisReport report, WorkspaceTarget target) {
        String reportRoot = report.metadata() != null ? report.metadata().projectRoot() : null;
        boolean same = reportRoot != null
                ? samePath(reportRoot, target.root().toString())
                : Objects.equals(report.projectName(), target.name());
        if (!same) {
            throw new RemediationException("Este relatório é do projeto " + Objects.toString(reportRoot, report.projectName())
                    + ". Ative esse projeto na tela Projetos para corrigir os achados dele.");
        }
    }

    private void requireSameRoot(RemediationProposal proposal, WorkspaceTarget target) {
        if (!samePath(proposal.projectRoot(), target.root().toString())) {
            throw new RemediationConflictException("A correção foi gerada para " + proposal.projectRoot()
                    + ". Ative esse projeto para aplicá-la ou desfazê-la.");
        }
    }

    private void requireConsent(WorkspaceTarget target) {
        if (currentConsent(target.root()).isEmpty()) {
            throw new ConsentRequiredException(target.root().toString());
        }
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new RemediationException("A correção assistida está desabilitada (agenthub.remediation.enabled=false).");
        }
    }

    private void requireIdle() {
        long running = analyses.findAll().stream().filter(r -> !r.status().isFinished()).count();
        if (running > 0) {
            throw new RemediationConflictException("Há " + running + " análise(s) em andamento. Aguarde a conclusão para gravar arquivos.");
        }
    }

    private Optional<Consent> currentConsent(Path root) {
        return store.loadConsents().consents().stream()
                .filter(c -> c.termsVersion() == TERMS_VERSION && samePath(c.projectRoot(), root.toString()))
                .findFirst();
    }

    private Path resolveForWrite(String relative) {
        try {
            return workspace.resolveForWrite(relative);
        } catch (ToolAccessDeniedException e) {
            throw new RemediationException(e.getMessage());
        }
    }

    private String read(Path file, String label) {
        try {
            return file.startsWith(store.dir()) ? Files.readString(file) : workspace.readText(file);
        } catch (IOException | ToolAccessDeniedException e) {
            throw new RemediationException("Não foi possível ler " + label + ": " + e.getMessage());
        }
    }

    private void restore(List<Path> paths, List<String> originals) {
        for (int i = 0; i < paths.size(); i++) {
            try {
                if (originals.get(i) == null) {
                    Files.deleteIfExists(paths.get(i));
                } else {
                    RemediationStore.writeAtomic(paths.get(i), originals.get(i));
                }
            } catch (IOException | RuntimeException e) {
                log.error("event=remediation.restore_failed file={} error={}", paths.get(i), e.getMessage());
            }
        }
    }

    private void save(RemediationProposal proposal) {
        store.save(proposal);
        proposals.put(proposal.id(), proposal);
    }

    private static boolean samePath(String a, String b) {
        return a != null && b != null && Path.of(a).toAbsolutePath().normalize().equals(Path.of(b).toAbsolutePath().normalize());
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
