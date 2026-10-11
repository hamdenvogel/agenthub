import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AgentHubApi } from '../../core/agenthub-api.service';
import { describeHttpError } from '../../core/http-error';
import { AnalysisReport } from '../../core/models';
import { ReportViewerComponent } from '../../report/report-viewer.component';
import { AgentChipComponent } from '../../shared/badges.component';
import { IconComponent } from '../../shared/icon.component';

@Component({
  selector: 'app-archived-report',
  imports: [RouterLink, DatePipe, IconComponent, AgentChipComponent, ReportViewerComponent],
  template: `
    <div class="page">
      @if (report(); as r) {
        <header class="page-head no-print">
          <div class="head-main">
            <a routerLink="/history" class="back small"><app-icon name="chevron-right" [size]="14" class="flip" /> Histórico</a>
            <h1>{{ r.request }}</h1>
            <p class="meta">
              <span class="badge tone-success">Relatório gravado</span>
              <span class="mono small">{{ r.analysisId.slice(0, 8) }}</span>
              <span>· {{ r.generatedAt | date: 'dd/MM/yyyy HH:mm' }}</span>
              @for (a of agentKeys(r); track a) { <app-agent-chip [agent]="a" /> }
            </p>
          </div>
        </header>
        <app-report-viewer [report]="r" [markdown]="markdown" />
      } @else if (error()) {
        <div class="alert alert-danger"><app-icon name="alert" /><div>{{ error() }}</div></div>
      } @else {
        <div class="skeleton" style="height: 90px; margin-bottom: 16px"></div>
        <div class="skeleton" style="height: 320px"></div>
      }
    </div>`,
  styles: `
    .head-main { min-width: 0; }
    .back { display: inline-flex; align-items: center; gap: 4px; color: var(--text-muted); }
    .flip { transform: rotate(180deg); }
    h1 { margin-top: 6px !important; line-height: 1.35; }
    .meta { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ArchivedReportComponent {
  private readonly api = inject(AgentHubApi);

  readonly reportId = input.required<string>();
  protected readonly report = signal<AnalysisReport | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly markdown = () => this.api.archivedReportMarkdown(this.reportId());

  constructor() {
    effect(() => {
      const id = this.reportId();
      untracked(() => {
        this.report.set(null);
        this.error.set(null);
        this.api.archivedReport(id).subscribe({
          next: r => this.report.set(r),
          error: e => this.error.set(describeHttpError(e)),
        });
      });
    });
  }

  protected agentKeys(r: AnalysisReport): string[] {
    return Object.keys(r.metadata?.agents ?? {});
  }
}
