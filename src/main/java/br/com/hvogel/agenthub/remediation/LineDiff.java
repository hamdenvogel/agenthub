package br.com.hvogel.agenthub.remediation;

import java.util.ArrayList;
import java.util.List;

/**
 * Diff unificado por linhas (3 linhas de contexto) para revisão das correções na UI.
 * Prefixo e sufixo comuns são descartados antes do LCS, então o custo depende só do trecho alterado.
 */
final class LineDiff {

    static final int CONTEXT = 3;
    /** Acima disso (linhas antigas x novas do trecho alterado), o trecho vira remoção + inclusão. */
    static final long MAX_LCS_CELLS = 2_500_000L;

    record Result(String unified, int additions, int deletions) {
    }

    private enum Kind { EQUAL, DELETE, INSERT }

    private record Op(Kind kind, String line) {
    }

    private LineDiff() {
    }

    static Result unified(String path, String before, String after) {
        List<String> a = before == null ? List.of() : lines(before);
        List<String> b = lines(after);
        List<Op> ops = operations(a, b);

        int additions = 0;
        int deletions = 0;
        for (Op op : ops) {
            if (op.kind() == Kind.INSERT) {
                additions++;
            } else if (op.kind() == Kind.DELETE) {
                deletions++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(before == null ? "/dev/null" : "a/" + path).append('\n');
        sb.append("+++ b/").append(path).append('\n');
        appendHunks(sb, ops);
        return new Result(sb.toString(), additions, deletions);
    }

    private static List<String> lines(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        if (lines.size() > 1 && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        return lines;
    }

    private static List<Op> operations(List<String> a, List<String> b) {
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < prefix; i++) {
            ops.add(new Op(Kind.EQUAL, a.get(i)));
        }
        List<String> midA = a.subList(prefix, a.size() - suffix);
        List<String> midB = b.subList(prefix, b.size() - suffix);
        if ((long) midA.size() * midB.size() > MAX_LCS_CELLS) {
            midA.forEach(l -> ops.add(new Op(Kind.DELETE, l)));
            midB.forEach(l -> ops.add(new Op(Kind.INSERT, l)));
        } else {
            ops.addAll(lcs(midA, midB));
        }
        for (int i = a.size() - suffix; i < a.size(); i++) {
            ops.add(new Op(Kind.EQUAL, a.get(i)));
        }
        return ops;
    }

    private static List<Op> lcs(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int[][] len = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                len[i][j] = a.get(i).equals(b.get(j)) ? len[i + 1][j + 1] + 1 : Math.max(len[i + 1][j], len[i][j + 1]);
            }
        }
        List<Op> ops = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                ops.add(new Op(Kind.EQUAL, a.get(i++)));
                j++;
            } else if (len[i + 1][j] >= len[i][j + 1]) {
                ops.add(new Op(Kind.DELETE, a.get(i++)));
            } else {
                ops.add(new Op(Kind.INSERT, b.get(j++)));
            }
        }
        while (i < n) {
            ops.add(new Op(Kind.DELETE, a.get(i++)));
        }
        while (j < m) {
            ops.add(new Op(Kind.INSERT, b.get(j++)));
        }
        return ops;
    }

    private static void appendHunks(StringBuilder sb, List<Op> ops) {
        int size = ops.size();
        int[] oldLine = new int[size + 1];
        int[] newLine = new int[size + 1];
        for (int k = 0; k < size; k++) {
            Kind kind = ops.get(k).kind();
            oldLine[k + 1] = oldLine[k] + (kind == Kind.INSERT ? 0 : 1);
            newLine[k + 1] = newLine[k] + (kind == Kind.DELETE ? 0 : 1);
        }
        int k = 0;
        while (k < size) {
            if (ops.get(k).kind() == Kind.EQUAL) {
                k++;
                continue;
            }
            int start = Math.max(0, k - CONTEXT);
            int lastChange = k;
            int scan = k + 1;
            while (scan < size && scan - lastChange <= 2 * CONTEXT) {
                if (ops.get(scan).kind() != Kind.EQUAL) {
                    lastChange = scan;
                }
                scan++;
            }
            int end = Math.min(size, lastChange + CONTEXT + 1);
            int oldCount = oldLine[end] - oldLine[start];
            int newCount = newLine[end] - newLine[start];
            sb.append("@@ -").append(oldCount == 0 ? oldLine[start] : oldLine[start] + 1).append(',').append(oldCount)
                    .append(" +").append(newCount == 0 ? newLine[start] : newLine[start] + 1).append(',').append(newCount)
                    .append(" @@\n");
            for (int h = start; h < end; h++) {
                Op op = ops.get(h);
                char prefix = switch (op.kind()) {
                    case EQUAL -> ' ';
                    case DELETE -> '-';
                    case INSERT -> '+';
                };
                sb.append(prefix).append(op.line()).append('\n');
            }
            k = end;
        }
    }
}
