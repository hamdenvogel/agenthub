import { Injectable } from '@angular/core';

import { AnalysisReport, Finding, RemediationProposal } from './models';
import { REMEDIATION_STATUS } from './remediation.store';

@Injectable({ providedIn: 'root' })
export class ExportService {

  baseName(report: AnalysisReport): string {
    const date = (report.generatedAt ?? new Date().toISOString()).slice(0, 19).replace(/[-:T]/g, '').replace(/^(\d{8})/, '$1-');
    return `agenthub-${report.projectName ?? 'relatorio'}-${date}-${report.analysisId.slice(0, 8)}`;
  }

  json(report: AnalysisReport): void {
    this.download(`${this.baseName(report)}.json`, JSON.stringify(report, null, 2), 'application/json');
  }

  markdown(report: AnalysisReport, markdown: string): void {
    this.download(`${this.baseName(report)}.md`, markdown, 'text/markdown');
  }

  /**
   * CSV com separador ";" e BOM UTF-8, para abrir direto no Excel em pt-BR.
   * @param suffix  parte final do nome do arquivo (ex.: achados, achados-filtrados)
   * @param fixes   status da correção assistida por achado (coluna "Correção")
   */
  findingsCsv(report: AnalysisReport, findings: Finding[] = report.findings, suffix = 'achados',
              fixes?: Map<string, RemediationProposal>): void {
    const header = ['ID', 'Severidade', 'Área', 'Categoria', 'Título', 'Descrição', 'Evidências', 'Recomendação', 'Correção'];
    const rows = findings.map(f => [
      f.id,
      f.severity,
      f.agent ?? '',
      f.category ?? '',
      f.title,
      f.description ?? '',
      f.evidence.map(e => `${e.file ?? ''}${e.lines ? ':' + e.lines : ''}`).join(' | '),
      f.recommendation ?? '',
      this.fixLabel(fixes?.get(f.id)),
    ]);
    const csv = [header, ...rows].map(r => r.map(cell => this.csvCell(cell)).join(';')).join('\r\n');
    this.download(`${this.baseName(report)}-${suffix}.csv`, '\uFEFF' + csv, 'text/csv');
  }

  /** Achados em JSON (com o status da correção, quando houver). */
  findingsJson(report: AnalysisReport, findings: Finding[], suffix = 'achados', fixes?: Map<string, RemediationProposal>): void {
    const data = {
      analysisId: report.analysisId,
      projectName: report.projectName,
      generatedAt: report.generatedAt,
      exportedAt: new Date().toISOString(),
      total: findings.length,
      findings: findings.map(f => ({ ...f, remediation: fixes?.get(f.id)?.status ?? null })),
    };
    this.download(`${this.baseName(report)}-${suffix}.json`, JSON.stringify(data, null, 2), 'application/json');
  }

  private fixLabel(p: RemediationProposal | undefined): string {
    return p ? REMEDIATION_STATUS[p.status].label : '';
  }

  print(): void {
    window.print();
  }

  jsonFile(fileName: string, data: unknown): void {
    this.download(fileName, JSON.stringify(data, null, 2), 'application/json');
  }

  async copy(text: string): Promise<void> {
    await navigator.clipboard.writeText(text);
  }

  private csvCell(value: string): string {
    const v = value.replace(/\r?\n/g, ' ').trim();
    return /[";]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
  }

  private download(fileName: string, content: string, mime: string): void {
    const blob = new Blob([content], { type: `${mime};charset=utf-8` });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
