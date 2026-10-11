import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Subscription, switchMap, takeWhile, timer } from 'rxjs';

import { AgentHubApi } from '../../core/agenthub-api.service';
import { AGENTS, AgentMeta, PHASES } from '../../core/catalog';
import { DurationPipe } from '../../core/format.pipes';
import { describeHttpError } from '../../core/http-error';
import { AgentStatus, AnalysisReport, AnalysisResponse } from '../../core/models';
import { RunsStore } from '../../core/runs.store';
import { ToastService } from '../../core/toast.service';
import { ReportViewerComponent } from '../../report/report-viewer.component';
import { AgentChipComponent, StatusBadgeComponent } from '../../shared/badges.component';
import { IconComponent } from '../../shared/icon.component';

type LiveAgentState = 'running' | AgentStatus;

const POLL_MS = 2000;

@Component({
  selector: 'app-analysis-run',
  imports: [RouterLink, DatePipe, IconComponent, StatusBadgeComponent, AgentChipComponent, ReportViewerComponent, DurationPipe],
  templateUrl: './analysis-run.component.html',
  styleUrl: './analysis-run.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AnalysisRunComponent {
  private readonly api = inject(AgentHubApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly store = inject(RunsStore);

  /** Parâmetro de rota (withComponentInputBinding). */
  readonly id = input.required<string>();

  protected readonly run = signal<AnalysisResponse | null>(null);
  protected readonly report = signal<AnalysisReport | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly notFound = signal(false);
  protected readonly now = signal(Date.now());
  protected readonly showLog = signal(true);
  protected readonly phases = PHASES;

  protected readonly markdown = () => this.api.reportMarkdown(this.id());

  protected readonly finished = computed(() => {
    const s = this.run()?.status;
    return s === 'COMPLETED' || s === 'FAILED' || s === 'CANCELLED';
  });

  protected readonly elapsedMs = computed(() => {
    const r = this.run();
    if (!r?.startedAt) {
      return 0;
    }
    const end = r.finishedAt ? new Date(r.finishedAt).getTime() : this.now();
    return end - new Date(r.startedAt).getTime();
  });

  /** Fases já alcançadas e a fase corrente, a partir dos eventos de progresso. */
  protected readonly phaseState = computed(() => {
    const events = this.run()?.events ?? [];
    const keys = PHASES.map(p => p.key);
    let furthest = -1;
    for (const e of events) {
      furthest = Math.max(furthest, keys.indexOf(e.phase));
    }
    const status = this.run()?.status;
    return PHASES.map((p, i) => ({
      ...p,
      state: status === 'COMPLETED' || i < furthest ? 'done'
        : i === furthest ? (this.finished() ? 'stopped' : 'current')
        : 'todo',
    }));
  });

  /** Estado ao vivo de cada agente, derivado dos eventos DELEGATE/OBSERVE. */
  protected readonly liveAgents = computed(() => {
    const state = new Map<AgentMeta, LiveAgentState>();
    for (const e of this.run()?.events ?? []) {
      const msg = e.message.toLowerCase();
      if (e.phase === 'DELEGATE') {
        AGENTS.filter(a => msg.includes(`${a.id} agent`)).forEach(a => state.set(a, 'running'));
      } else if (e.phase === 'OBSERVE') {
        const agent = AGENTS.find(a => msg.startsWith(`${a.id} agent`));
        const status = /:\s*(SUCCESS|FAILED|TIMEOUT|CANCELLED)/.exec(e.message)?.[1] as AgentStatus | undefined;
        if (agent && status) {
          state.set(agent, status);
        }
      }
    }
    return [...state.entries()].map(([meta, status]) => ({ meta, status }));
  });

  protected readonly startedAtMs = computed(() => {
    const r = this.run();
    return r ? new Date(r.startedAt ?? r.createdAt).getTime() : 0;
  });

  private poll?: Subscription;

  constructor() {
    const destroyRef = inject(DestroyRef);
    const clock = setInterval(() => {
      if (!this.finished()) {
        this.now.set(Date.now());
      }
    }, 1000);
    destroyRef.onDestroy(() => {
      clearInterval(clock);
      this.poll?.unsubscribe();
    });

    effect(() => {
      const id = this.id();
      untracked(() => this.start(id));
    });
  }

  protected offset(at: string): string {
    const s = Math.max(0, Math.round((new Date(at).getTime() - this.startedAtMs()) / 1000));
    return `+${Math.floor(s / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}`;
  }

  protected cancel(): void {
    if (!confirm('Cancelar esta análise? Os agentes em execução serão interrompidos.')) {
      return;
    }
    this.api.cancel(this.id()).subscribe({
      next: run => {
        this.run.set(run);
        this.toast.info('Cancelamento solicitado');
        this.store.refresh();
      },
      error: e => this.toast.error(describeHttpError(e)),
    });
  }

  protected rerun(): void {
    const r = this.run();
    if (!r) {
      return;
    }
    this.api.startAnalysis({ request: r.request, agents: r.requestedAgents ?? undefined }).subscribe({
      next: next => {
        this.toast.success('Nova execução iniciada');
        this.store.refresh();
        this.router.navigate(['/analysis', next.analysisId]);
      },
      error: e => this.toast.error(describeHttpError(e)),
    });
  }

  private start(id: string): void {
    this.poll?.unsubscribe();
    this.run.set(null);
    this.report.set(null);
    this.error.set(null);
    this.notFound.set(false);

    this.poll = timer(0, POLL_MS).pipe(
      switchMap(() => this.api.analysis(id)),
      takeWhile(run => run.status === 'PENDING' || run.status === 'RUNNING', true),
    ).subscribe({
      next: run => {
        this.run.set(run);
        this.now.set(Date.now());
        if (run.status === 'COMPLETED' && run.reportAvailable && !this.report()) {
          this.loadReport(id);
          this.store.refresh();
        } else if (run.status === 'FAILED' || run.status === 'CANCELLED') {
          this.store.refresh();
        }
      },
      error: e => {
        if (e instanceof HttpErrorResponse && e.status === 404) {
          this.notFound.set(true);
        } else {
          this.error.set(describeHttpError(e));
        }
      },
    });
  }

  private loadReport(id: string): void {
    this.api.report(id).subscribe({
      next: report => this.report.set(report),
      error: e => this.error.set(describeHttpError(e)),
    });
  }
}
