import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { AgentHubApi } from './agenthub-api.service';
import { TargetInput, TargetOverview, TargetStatus } from './models';
import { RunsStore } from './runs.store';

/** Projetos cadastrados e projeto ativo (o que os agentes analisam). */
@Injectable({ providedIn: 'root' })
export class TargetsStore {
  private readonly api = inject(AgentHubApi);
  private readonly runs = inject(RunsStore);

  readonly overview = signal<TargetOverview | null>(null);
  readonly loading = signal(false);
  readonly error = signal(false);

  readonly targets = computed(() => this.overview()?.targets ?? []);
  readonly active = computed(() => this.targets().find(t => t.active) ?? null);
  /** Análise em andamento bloqueia a troca do projeto ativo (o RunsStore faz polling enquanto houver). */
  readonly busy = computed(() => this.runs.running().length > 0);

  constructor() {
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.api.targets().subscribe({
      next: o => {
        this.overview.set(o);
        this.error.set(false);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  create(input: TargetInput, activate: boolean): Observable<TargetStatus> {
    return this.api.createTarget(input, activate).pipe(tap(() => this.refresh()));
  }

  update(id: string, input: TargetInput): Observable<TargetStatus> {
    return this.api.updateTarget(id, input).pipe(tap(() => this.refresh()));
  }

  remove(id: string): Observable<void> {
    return this.api.deleteTarget(id).pipe(tap(() => this.refresh()));
  }

  activate(id: string): Observable<TargetStatus> {
    return this.api.activateTarget(id).pipe(tap(() => this.refresh()));
  }
}
