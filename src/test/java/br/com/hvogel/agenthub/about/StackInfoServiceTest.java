package br.com.hvogel.agenthub.about;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.hvogel.agenthub.about.StackInfoService.StackComponent;
import br.com.hvogel.agenthub.about.StackInfoService.StackInfo;
import org.junit.jupiter.api.Test;

class StackInfoServiceTest {

    @Test
    void versionComesFromTheJarFileName() {
        assertThat(StackInfoService.versionFromJarName("/C:/m2/org/springframework/ai/spring-ai-client-chat-2.0.0.jar"))
                .isEqualTo("2.0.0");
        assertThat(StackInfoService.versionFromJarName("/m2/io/modelcontextprotocol/mcp-core-1.1.0-M2.jar"))
                .isEqualTo("1.1.0-M2");
        assertThat(StackInfoService.versionFromJarName("/workspace/target/classes/")).isNull();
    }

    @Test
    void reportsBackendLibrariesAndJavaRuntime() {
        StackInfo info = new StackInfoService("claude-sonnet-4-5").info();

        assertThat(info.backend()).extracting(StackComponent::id)
                .containsExactly("spring-boot", "spring-framework", "spring-ai", "mcp-sdk", "claude-model");
        assertThat(info.backend()).filteredOn(c -> c.id().equals("spring-boot"))
                .extracting(StackComponent::version).doesNotContainNull();
        assertThat(info.backend().getLast().version()).isEqualTo("claude-sonnet-4-5");
        assertThat(info.runtime().getFirst().version()).startsWith(String.valueOf(Runtime.version().feature()));
    }
}
