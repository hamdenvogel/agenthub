package br.com.hvogel.agenthub.remediation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import br.com.hvogel.agenthub.analysis.AnalysisRepository;
import br.com.hvogel.agenthub.analysis.MarkdownReportRenderer;
import br.com.hvogel.agenthub.analysis.ReportArchive;
import br.com.hvogel.agenthub.analysis.ReportWriter;
import br.com.hvogel.agenthub.claude.CallLimits;
import br.com.hvogel.agenthub.claude.ClaudeResponse;
import br.com.hvogel.agenthub.claude.ClaudeService;
import br.com.hvogel.agenthub.config.AgentHubProperties;
import br.com.hvogel.agenthub.config.AgentHubProperties.Reports;
import br.com.hvogel.agenthub.context.ProjectWorkspace;
import br.com.hvogel.agenthub.exception.ConsentRequiredException;
import br.com.hvogel.agenthub.exception.RemediationConflictException;
import br.com.hvogel.agenthub.exception.RemediationException;
import br.com.hvogel.agenthub.mcp.McpToolRegistry;
import br.com.hvogel.agenthub.model.AgentRole;
import br.com.hvogel.agenthub.model.AgentStatus;
import br.com.hvogel.agenthub.model.AnalysisReport;
import br.com.hvogel.agenthub.model.AnalysisReport.ReportMetadata;
import br.com.hvogel.agenthub.model.Evidence;
import br.com.hvogel.agenthub.model.Finding;
import br.com.hvogel.agenthub.model.Severity;
import br.com.hvogel.agenthub.remediation.RemediationProposal.Status;
import br.com.hvogel.agenthub.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class RemediationServiceTest {

    static final String ANALYSIS_ID = "abcdef12-0000-0000-0000-000000000000";
    static final String SECRET = "SuperSecret123";

    @TempDir
    Path project;
    @TempDir
    Path data;

    private final ClaudeService claude = mock(ClaudeService.class);
    private final ExecutorService executor = mock(ExecutorService.class);
    private ProjectWorkspace workspace;
    private ReportArchive archive;
    private Path properties;

    @BeforeEach
    void setUp() throws Exception {
        properties = project.resolve("src/application.properties");
        Files.createDirectories(properties.getParent());
        Files.writeString(properties, "server.port=8080\njwt.secret=" + SECRET + "\n");
        Files.writeString(project.resolve(".env"), "TOKEN=x\n");

        AgentHubProperties base = Fixtures.properties(project.toString(), Fixtures.orchestrator(1, null, 1));
        AgentHubProperties props = new AgentHubProperties(base.targetProject(), base.orchestrator(), base.planner(),
                base.agents(), base.toolSources(), new Reports(true, data.resolve("reports").toString()));
        workspace = new ProjectWorkspace(props);
        JsonMapper mapper = JsonMapper.builder().build();
        new ReportWriter(props, new MarkdownReportRenderer(), mapper).write(report(workspace.root().toString()));
        archive = new ReportArchive(props, mapper);
        when(executor.submit(any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        });
    }

    private RemediationService service() {
        return new RemediationService(new RemediationProperties(true, data.resolve("remediations").toString(), 10, 4),
                workspace, claude, mock(McpToolRegistry.class), new AnalysisRepository(), archive, executor);
    }

    private static AnalysisReport report(String root) {
        Finding finding = new Finding("SEC-001", "Segredo versionado", "jwt.secret no properties", Severity.CRITICAL,
                "secrets", List.of(new Evidence("src/application.properties", "2", "jwt.secret=" + SECRET)),
                "Use variável de ambiente", AgentRole.SECURITY);
        return new AnalysisReport(ANALYSIS_ID, "seguranca", "proj", Instant.parse("2026-10-02T10:00:00Z"), "Resumo",
                null, null, null, null, null, null, null, List.of(finding), List.of(), List.of(),
                new ReportMetadata(1, 1000, "rules", "rules", Map.of("security", AgentStatus.SUCCESS),
                        Map.of(Severity.CRITICAL, 1L), List.of(), Map.of(), List.of(), root, List.of()), null);
    }

    private void claudeAnswers(FixPlan... plans) {
        var stub = when(claude.structured(anyString(), anyString(), anyList(), eq(FixPlan.class), any(CallLimits.class)));
        for (FixPlan plan : plans) {
            stub = stub.thenReturn(new ClaudeResponse<>(plan, "claude-test", 100, 50, 10));
        }
    }

    private static FixPlan secretFix() {
        return new FixPlan("Troca o segredo por variável de ambiente",
                List.of(new FixPlan.FileChange("src/application.properties", "MODIFY", "Remove o segredo",
                        List.of(new FixPlan.Edit("jwt.secret=***MASKED(14 chars)***", "jwt.secret=${JWT_SECRET}")), null)),
                List.of("Defina JWT_SECRET e rotacione a chave"), List.of("Sem a variável a aplicação não sobe"), 0.9);
    }

    @Test
    void nothingIsGeneratedWithoutConsent() {
        RemediationService service = service();

        assertThat(service.consent().accepted()).isFalse();
        assertThat(service.consent().terms()).isNotEmpty();
        assertThatThrownBy(() -> service.propose(ANALYSIS_ID, "SEC-001")).isInstanceOf(ConsentRequiredException.class);
    }

    @Test
    void proposeReviewApplyAndRevert() throws Exception {
        claudeAnswers(secretFix());
        RemediationService service = service();
        service.acceptConsent();

        String id = service.propose(ANALYSIS_ID, "SEC-001").id();
        RemediationProposal ready = service.get(id);

        assertThat(ready.status()).isEqualTo(Status.READY);
        assertThat(ready.manualSteps()).containsExactly("Defina JWT_SECRET e rotacione a chave");
        assertThat(ready.files()).singleElement().satisfies(f -> {
            assertThat(f.path()).isEqualTo("src/application.properties");
            assertThat(f.diff()).contains("+jwt.secret=${JWT_SECRET}").doesNotContain(SECRET);
            assertThat(f.additions()).isEqualTo(1);
            assertThat(f.deletions()).isEqualTo(1);
        });
        assertThat(Files.readString(properties)).contains(SECRET);

        assertThat(service.apply(id).status()).isEqualTo(Status.APPLIED);
        assertThat(Files.readString(properties)).isEqualTo("server.port=8080\njwt.secret=${JWT_SECRET}\n");
        assertThat(Files.readString(data.resolve("remediations").resolve(id).resolve("backup/src/application.properties")))
                .contains(SECRET);

        assertThat(service.revert(id).status()).isEqualTo(Status.REVERTED);
        assertThat(Files.readString(properties)).isEqualTo("server.port=8080\njwt.secret=" + SECRET + "\n");
    }

    @Test
    void applyIsRefusedWhenTheFileChangedAfterTheProposal() throws Exception {
        claudeAnswers(secretFix());
        RemediationService service = service();
        service.acceptConsent();
        String id = service.propose(ANALYSIS_ID, "SEC-001").id();

        Files.writeString(properties, "server.port=9090\njwt.secret=" + SECRET + "\n");

        assertThatThrownBy(() -> service.apply(id)).isInstanceOf(RemediationConflictException.class)
                .hasMessageContaining("alterado depois da proposta");
        assertThat(Files.readString(properties)).startsWith("server.port=9090");
    }

    @Test
    void invalidPlanIsRetriedOnceThenFails() {
        FixPlan wrong = new FixPlan("x", List.of(new FixPlan.FileChange("src/application.properties", "MODIFY", null,
                List.of(new FixPlan.Edit("nao existe", "y")), null)), List.of(), List.of(), 0.5);
        claudeAnswers(wrong, wrong);
        RemediationService service = service();
        service.acceptConsent();

        RemediationProposal proposal = service.get(service.propose(ANALYSIS_ID, "SEC-001").id());

        assertThat(proposal.status()).isEqualTo(Status.FAILED);
        assertThat(proposal.error()).contains("não encontrado");
        verify(claude, times(2)).structured(anyString(), anyString(), anyList(), eq(FixPlan.class), any(CallLimits.class));
    }

    @Test
    void sensitiveFilesAndPathsOutsideTheProjectAreNeverWritten() throws Exception {
        FixPlan bad = new FixPlan("x", List.of(
                new FixPlan.FileChange(".env", "MODIFY", null, List.of(new FixPlan.Edit("TOKEN=x", "TOKEN=y")), null),
                new FixPlan.FileChange("../fora.txt", "CREATE", null, List.of(), "conteudo")), List.of(), List.of(), 0.5);
        claudeAnswers(bad, bad);
        RemediationService service = service();
        service.acceptConsent();

        RemediationProposal proposal = service.get(service.propose(ANALYSIS_ID, "SEC-001").id());

        assertThat(proposal.status()).isEqualTo(Status.FAILED);
        assertThat(proposal.error()).contains(".env").contains("fora.txt");
        assertThat(Files.readString(project.resolve(".env"))).isEqualTo("TOKEN=x\n");
        assertThat(project.resolveSibling("fora.txt")).doesNotExist();
    }

    @Test
    void manualOnlyFixHasNothingToApply() {
        claudeAnswers(new FixPlan("Rotacionar a chave", List.of(), List.of("Rotacione a chave no provedor"), List.of(), 0.8));
        RemediationService service = service();
        service.acceptConsent();

        String id = service.propose(ANALYSIS_ID, "SEC-001").id();

        assertThat(service.get(id).status()).isEqualTo(Status.MANUAL_ONLY);
        assertThatThrownBy(() -> service.apply(id)).isInstanceOf(RemediationConflictException.class);
    }

    @Test
    void reportOfAnotherProjectCannotBeFixedHere() throws Exception {
        AgentHubProperties base = Fixtures.properties(project.toString(), Fixtures.orchestrator(1, null, 1));
        Path otherReports = data.resolve("other-reports");
        AgentHubProperties props = new AgentHubProperties(base.targetProject(), base.orchestrator(), base.planner(),
                base.agents(), base.toolSources(), new Reports(true, otherReports.toString()));
        JsonMapper mapper = JsonMapper.builder().build();
        new ReportWriter(props, new MarkdownReportRenderer(), mapper).write(report(data.resolve("outro").toString()));
        archive = new ReportArchive(props, mapper);
        RemediationService service = service();
        service.acceptConsent();

        assertThatThrownBy(() -> service.propose(ANALYSIS_ID, "SEC-001")).isInstanceOf(RemediationException.class)
                .hasMessageContaining("Ative esse projeto");
    }

    @Test
    void consentIsPersistedPerProjectAndCanBeRevoked() {
        service().acceptConsent();

        RemediationService restarted = service();
        assertThat(restarted.consent().accepted()).isTrue();
        assertThat(restarted.revokeConsent().accepted()).isFalse();
    }
}
