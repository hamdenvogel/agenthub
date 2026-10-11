package br.com.hvogel.agenthub.remediation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class LineDiffTest {

    @Test
    void unifiedDiffShowsOnlyTheChangedRegionWithContext() {
        String before = IntStream.rangeClosed(1, 20).mapToObj(i -> "line " + i).collect(Collectors.joining("\n", "", "\n"));
        String after = before.replace("line 10\n", "line 10 changed\nline 10b\n");

        LineDiff.Result diff = LineDiff.unified("src/A.java", before, after);

        assertThat(diff.additions()).isEqualTo(2);
        assertThat(diff.deletions()).isEqualTo(1);
        assertThat(diff.unified()).startsWith("--- a/src/A.java\n+++ b/src/A.java\n@@ -7,7 +7,8 @@\n")
                .contains(" line 9\n-line 10\n+line 10 changed\n+line 10b\n line 11\n")
                .doesNotContain("line 3\n")
                .doesNotContain("line 15");
    }

    @Test
    void createdFileIsAllAdditions() {
        LineDiff.Result diff = LineDiff.unified("New.java", null, "a\nb\n");

        assertThat(diff.unified()).isEqualTo("--- /dev/null\n+++ b/New.java\n@@ -0,0 +1,2 @@\n+a\n+b\n");
        assertThat(diff.additions()).isEqualTo(2);
        assertThat(diff.deletions()).isZero();
    }

    @Test
    void distantChangesBecomeSeparateHunks() {
        String before = IntStream.rangeClosed(1, 30).mapToObj(i -> "l" + i).collect(Collectors.joining("\n", "", "\n"));
        String after = before.replace("l2\n", "L2\n").replace("l28\n", "L28\n");

        assertThat(LineDiff.unified("f", before, after).unified().lines().filter(l -> l.startsWith("@@")).count()).isEqualTo(2);
    }
}
