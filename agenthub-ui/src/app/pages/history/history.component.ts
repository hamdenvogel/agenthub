import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { AgentHubApi } from '../../core/agenthub-api.service';
import { severityData } from '../../core/catalog';
import { DurationPipe, RelativeTimePipe } from '../../core/format.pipes';
import { describeHttpError } from '../../core/http-error';
import { AnalysisStatus, ArchivedReport, Severity } from '../../core/models';
import { RunsStore } from '../../core/runs.store';
import { AgentChipComponent, StatusBadgeComponent } from '../../shared/badges.component';
import { StackedBarComponent } from '../../shared/charts.component';
import { IconComponent } from '../../shared/icon.component';

interface HistoryRow {
  key: string;
  link: string[];
  request: string;
  project: string | null;
  scope: string[];
  status: AnalysisStatus;
  at: string;
  agents: string[];
  findings: number | null;
  durationMs: number | null;
  severity: Partial<Record<Severity, number>> | null;
  persisted: boolean;
}

@Component({
  selector: 'app-history',
  imports: [FormsModule, RouterLink, DatePipe, IconComponent, StatusBadgeComponent, AgentChipComponent, StackedBarComponent,
    DurationPipe, RelativeTimePipe],
  templateUrl: './history.component.html',
  styleUrl: './history.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HistoryComponent {
  private readonly api = inject(AgentHubApi);
  protected readonly store = inject(RunsStore);

  protected readonly archived = signal<ArchivedReport[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly search = signal('');
  protected readonly statusFilter = signal<'' | AnalysisStatus>('');

  /** Execuções em memória (inclusive em andamento) + relatórios gravados, sem duplicar a mesma análise. */
  protected readonly rows = computed<HistoryRow[]>(() => {
    const byAnalysis = new Map<string, HistoryRow>();
    for (const r of this.archived()) {
      byAnalysis.set(r.analysisId, {
        key: r.reportId, link: ['/reports', r.reportId], request: r.request, project: r.projectName, scope: r.scope ?? [],
        status: 'COMPLETED', at: r.generatedAt,
        agents: Object.keys(r.agents ?? {}), findings: r.findings, durationMs: r.durationMs,
        severity: r.findingsBySeverity, persisted: true,
      });
    }
    for (const run of this.store.runs()) {
      if (byAnalysis.has(run.analysisId) && run.status === 'COMPLETED') {
        continue;
      }
      const duration = run.startedAt && run.finishedAt
        ? new Date(run.finishedAt).getTime() - new Date(run.startedAt).getTime() : null;
      byAnalysis.set(run.analysisId, {
        key: run.analysisId, link: ['/analysis', run.analysisId], request: run.request, project: run.projectName,
        scope: run.scope ?? [], status: run.status, at: run.createdAt,
        agents: (run.requestedAgents ?? []).map(a => a.toLowerCase()), findings: null, durationMs: duration,
        severity: null, persisted: false,
      });
    }
    return [...byAnalysis.values()].sort((a, b) => b.at.localeCompare(a.at));
  });

  protected readonly filtered = computed(() => {
    const q = this.search().toLowerCase().trim();
    const status = this.statusFilter();
    return this.rows().filter(r => (!status || r.status === status)
      && (!q || r.request.toLowerCase().includes(q) || (r.project ?? '').toLowerCase().includes(q)));
  });

  protected readonly totals = computed(() => {
    const reports = this.archived();
    return {
      reports: reports.length,
      findings: reports.reduce((a, r) => a + r.findings, 0),
      critical: reports.reduce((a, r) => a + (r.findingsBySeverity?.CRITICAL ?? 0) + (r.findingsBySeverity?.HIGH ?? 0), 0),
      avgDuration: reports.length ? reports.reduce((a, r) => a + r.durationMs, 0) / reports.length : 0,
    };
  });

  constructor() {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.store.refresh();
    this.api.archivedReports().subscribe({
      next: list => {
        this.archived.set(list);
        this.loading.set(false);
      },
      error: e => {
        this.error.set(describeHttpError(e));
        this.loading.set(false);
      },
    });
  }

  protected severity(row: HistoryRow) {
    return severityData(row.severity);
  }
}
