import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';

import { AgentHubApi } from './agenthub-api.service';
import { AnalysisResponse } from './models';

export type BackendState = 'unknown' | 'up' | 'down';

/**
 * Estado compartilhado: análises em memória no backend e saúde da API.
 * Faz polling mais frequente enquanto houver análise em execução.
 */
@Injectable({ providedIn: 'root' })
export class RunsStore {
  private readonly api = inject(AgentHubApi);
  readonly runs = signal<AnalysisResponse[]>([]);
  readonly backend = signal<BackendState>('unknown');
  readonly running = computed(() => this.runs().filter(r => r.status === 'RUNNING' || r.status === 'PENDING'));

  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.timer));
    this.refresh();
  }

  refresh(): void {
    clearTimeout(this.timer);
    this.api.listAnalyses().subscribe({
      next: runs => {
        this.runs.set([...runs].sort((a, b) => b.createdAt.localeCompare(a.createdAt)));
        this.backend.set('up');
        this.schedule();
      },
      error: () => {
        this.backend.set('down');
        this.schedule();
      },
    });
  }

  private schedule(): void {
    const delay = this.running().length > 0 ? 3000 : this.backend() === 'down' ? 10000 : 30000;
    this.timer = setTimeout(() => this.refresh(), delay);
  }
}
