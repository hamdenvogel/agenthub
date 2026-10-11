package br.com.hvogel.agenthub.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.hvogel.agenthub.claude.ContextBudget;
import br.com.hvogel.agenthub.exception.ToolAccessDeniedException;
import br.com.hvogel.agenthub.model.AgentRole;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class GuardedToolCallbackTest {

    private static ToolCallback returning(String name, java.util.function.Function<String, String> behavior) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return behavior.apply(toolInput);
            }
        };
    }

    @Test
    void budgetLimitsCallsAndTellsModelToConclude() {
        ToolBudget budget = new ToolBudget(2);
        GuardedToolCallback tool = new GuardedToolCallback(returning("fs_read_file", in -> "ok"), "filesystem", AgentRole.BACKEND, budget);

        assertThat(tool.call("{}")).isEqualTo("ok");
        assertThat(tool.call("{}")).isEqualTo("ok");
        assertThat(tool.call("{}")).contains("esgotado");
        assertThat(budget.used()).isEqualTo(2);
        assertThat(budget.usedBySource()).containsEntry("filesystem", 2);
    }

    @Test
    void errorsAreReturnedAsTextInsteadOfBreakingTheAgent() {
        GuardedToolCallback failing = new GuardedToolCallback(returning("git_log", in -> {
            throw new IllegalStateException("repositório inválido");
        }), "git", AgentRole.BACKEND, new ToolBudget(5));
        GuardedToolCallback denied = new GuardedToolCallback(returning("fs_read_file", in -> {
            throw new ToolAccessDeniedException("fora da raiz");
        }), "filesystem", AgentRole.BACKEND, new ToolBudget(5));

        assertThat(failing.call("{}")).startsWith("ERRO").contains("repositório inválido");
        assertThat(denied.call("{}")).startsWith("ACESSO NEGADO").contains("fora da raiz");
    }

    @Test
    void secretsInToolOutputAreMasked() {
        GuardedToolCallback tool = new GuardedToolCallback(
                returning("git_show", in -> "+jwt.secret=minha-chave-super-secreta"), "git", AgentRole.SECURITY, new ToolBudget(5));
        String result = tool.call("{}");
        assertThat(result).doesNotContain("minha-chave-super-secreta").contains("***MASKED");
    }

    @Test
    void argumentsOutsideTheToolInputSchemaAreRejectedBeforeExecution() {
        String schema = """
                {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                 "properties":{"path":{"type":"string"},"maxLines":{"type":"integer"}},
                 "required":["path"],"additionalProperties":false}""";
        java.util.concurrent.atomic.AtomicInteger executed = new java.util.concurrent.atomic.AtomicInteger();
        ToolCallback readFile = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("fs_read_file").description("ler").inputSchema(schema).build();
            }

            @Override
            public String call(String toolInput) {
                executed.incrementAndGet();
                return "conteúdo";
            }
        };
        ToolBudget budget = new ToolBudget(5);
        GuardedToolCallback tool = new GuardedToolCallback(readFile, "filesystem", AgentRole.BACKEND, budget);

        assertThat(tool.call("{\"maxLines\":\"dez\"}")).startsWith("ARGUMENTOS INVÁLIDOS").contains("path");
        assertThat(tool.call("{\"path\":\"A.java\",\"extra\":1}")).startsWith("ARGUMENTOS INVÁLIDOS");
        assertThat(tool.call("{\"path\":\"A.java\",\"maxLines\":10}")).isEqualTo("conteúdo");
        assertThat(executed).hasValue(1);
        assertThat(budget.rejectedInputs()).isEqualTo(2);
    }

    @Test
    void resultsAreCountedInTheContextWindowAndCutWhenItIsFull() {
        ContextBudget context = new ContextBudget(1_000);
        ToolBudget budget = new ToolBudget(10, context);
        GuardedToolCallback tool = new GuardedToolCallback(
                returning("fs_read_file", in -> "a".repeat(2_800)), "filesystem", AgentRole.BACKEND, budget);

        String first = tool.call("{}");
        String second = tool.call("{}");
        String third = tool.call("{}");

        assertThat(first).hasSize(2_800);
        assertThat(second).contains("resultado cortado").hasSizeLessThan(2_800);
        assertThat(context.exhausted()).isTrue();
        assertThat(context.used()).isLessThanOrEqualTo(1_000);
        assertThat(third).contains("Context window desta execução esgotada");
        assertThat(budget.used()).isEqualTo(2);
    }

    @Test
    void largeOutputIsTruncated() {
        GuardedToolCallback tool = new GuardedToolCallback(
                returning("git_diff", in -> "x".repeat(GuardedToolCallback.MAX_RESULT_CHARS + 100)), "git", AgentRole.BACKEND, new ToolBudget(5));
        assertThat(tool.call("{}")).contains("truncado").hasSizeLessThan(GuardedToolCallback.MAX_RESULT_CHARS + 100);
    }
}
