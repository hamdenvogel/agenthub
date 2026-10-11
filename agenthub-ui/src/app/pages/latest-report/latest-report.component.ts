import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';

import { AgentHubApi } from '../../core/agenthub-api.service';
import { describeHttpError } from '../../core/http-error';
import { AnalysisResponse, ArchivedReport, TargetOverview } from '../../core/models';
import { IconComponent } from '../../shared/icon.component';

/**
 * Atalho "Último diagnóstico": abre o relatório mais recente do projeto ativo
 * (ou a análise em andamento dele). Sem relatório, mostra como gerar um.
 */
@Component({
  selector: 'app-latest-report',
  imports: [RouterLink, IconComponent],
  template: `
    <div class="page">
      @if (error(); as e) {
        <div class="alert alert-danger"><app-icon name="alert" /><div>{{ e }}</div></div>
      } @else if (empty()) {
        <div class="card empty">
          <app-icon name="gauge" [size]="32" />
          <h2>Nenhum diagnóstico ainda</h2>
          <p class="muted">
            @if (projectName(); as name) {
              O projeto <strong>{{ name }}</strong> ainda não tem relatório gerado.
            } @else {
              Nenhum relatório foi gerado até agora.
            }
            Rode uma análise para ver riscos, achados e correções sugeridas aqui.
          </p>
          <div class="actions">
            <a routerLink="/" class="btn btn-primary"><app-icon name="sparkles" [size]="16" /> Nova análise</a>
            <a routerLink="/history" class="btn btn-ghost"><app-icon name="history" [size]="16" /> Histórico</a>
          </div>
        </div>
      } @else {
        <div class="loading muted"><span class="spinner"></span> Abrindo o último diagnóstico...</div>
      }
    </div>`,
  styles: `
    .loading { display: flex; align-items: center; gap: 10px; padding: 40px 0; }
    .empty { max-width: 560px; margin: 40px auto; padding: 32px; text-align: center; color: var(--text-muted); }
    .empty h2 { margin: 12px 0 6px; color: var(--text); }
    .actions { display: flex; justify-content: center; gap: 8px; margin-top: 18px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LatestReportComponent {
  private readonly api = inject(AgentHubApi);
  private readonly router = inject(Router);

  protected readonly empty = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly projectName = signal<string | null>(null);

  constructor() {
    forkJoin({
      targets: this.api.targets(),
      runs: this.api.listAnalyses(),
      reports: this.api.archivedReports(),
    }).subscribe({
      next: ({ targets, runs, reports }) => this.open(targets, runs, reports),
      error: e => this.error.set(describeHttpError(e)),
    });
  }

  private open(targets: TargetOverview, runs: AnalysisResponse[], reports: ArchivedReport[]): void {
    const active = targets.targets.find(t => t.active) ?? null;
    this.projectName.set(active?.name ?? null);
    const root = active?.root ?? null;
    const sameRoot = (r: string | null | undefined) => !root || samePath(r, root);
    const newest = <T>(items: T[], date: (i: T) => string) =>
      [...items].sort((a, b) => date(b).localeCompare(date(a)))[0];

    const running = newest(
      runs.filter(r => (r.status === 'RUNNING' || r.status === 'PENDING') && sameRoot(r.projectRoot)),
      r => r.createdAt);
    const live = newest(runs.filter(r => r.reportAvailable && sameRoot(r.projectRoot)), r => r.createdAt);
    const archived = newest(reports.filter(r => sameRoot(r.projectRoot)), r => r.generatedAt);

    let target: unknown[] | null = null;
    if (running) {
      target = ['/analysis', running.analysisId];
    } else if (live && (!archived || live.createdAt >= archived.generatedAt || live.analysisId === archived.analysisId)) {
      target = ['/analysis', live.analysisId];
    } else if (archived) {
      target = ['/reports', archived.reportId];
    }

    if (target) {
      this.router.navigate(target, { replaceUrl: true });
    } else {
      this.empty.set(true);
    }
  }
}

function samePath(a: string | null | undefined, b: string): boolean {
  const norm = (p: string) => p.replace(/[\\/]+$/, '').replace(/\//g, '\\').toLowerCase();
  return !!a && norm(a) === norm(b);
}
