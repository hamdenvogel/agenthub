import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { AgentHubApi } from './agenthub-api.service';
import { RemediationConsent, RemediationProposal, RemediationStatus } from './models';
import { ToastService } from './toast.service';

export const REMEDIATION_STATUS: Record<RemediationStatus, { label: string; tone: string }> = {
  GENERATING: { label: 'Gerando correção', tone: 'info' },
  READY: { label: 'Correção pronta', tone: 'warning' },
  MANUAL_ONLY: { label: 'Ação manual', tone: 'neutral' },
  FAILED: { label: 'Falhou', tone: 'danger' },
  APPLIED: { label: 'Corrigido', tone: 'success' },
  REVERTED: { label: 'Desfeito', tone: 'neutral' },
};

const POLL_MS = 3000;

/** Correções assistidas dos achados do relatório aberto (uma análise por vez). */
@Injectable({ providedIn: 'root' })
export class RemediationStore {
  private readonly api = inject(AgentHubApi);
  private readonly toast = inject(ToastService);

  readonly consent = signal<RemediationConsent | null>(null);
  readonly analysisId = signal<string | null>(null);
  readonly proposals = signal<RemediationProposal[]>([]);

  /** Proposta mais recente por achado. */
  readonly byFinding = computed(() => {
    const map = new Map<string, RemediationProposal>();
    for (const p of [...this.proposals()].sort((a, b) => b.createdAt.localeCompare(a.createdAt))) {
      if (!map.has(p.findingId)) {
        map.set(p.findingId, p);
      }
    }
    return map;
  });
  readonly appliedCount = computed(() => [...this.byFinding().values()].filter(p => p.status === 'APPLIED').length);

  private timer: ReturnType<typeof setInterval> | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stopPolling());
  }

  load(analysisId: string): void {
    if (this.analysisId() !== analysisId) {
      this.proposals.set([]);
    }
    this.analysisId.set(analysisId);
    this.refreshConsent();
    this.api.remediations(analysisId).subscribe({
      next: list => {
        if (this.analysisId() === analysisId) {
          this.proposals.set(list);
          this.schedule();
        }
      },
      error: () => this.proposals.set([]),
    });
  }

  refreshConsent(): void {
    this.api.remediationConsent().subscribe({ next: c => this.consent.set(c), error: () => this.consent.set(null) });
  }

  acceptConsent(): Observable<RemediationConsent> {
    return this.api.acceptRemediationConsent().pipe(tap(c => this.consent.set(c)));
  }

  revokeConsent(): Observable<RemediationConsent> {
    return this.api.revokeRemediationConsent().pipe(tap(c => this.consent.set(c)));
  }

  propose(findingId: string): Observable<RemediationProposal> {
    return this.api.proposeRemediation(this.analysisId()!, findingId).pipe(tap(p => this.upsert(p)));
  }

  apply(id: string): Observable<RemediationProposal> {
    return this.api.applyRemediation(id).pipe(tap(p => this.upsert(p)));
  }

  revert(id: string): Observable<RemediationProposal> {
    return this.api.revertRemediation(id).pipe(tap(p => this.upsert(p)));
  }

  discard(id: string): Observable<void> {
    return this.api.discardRemediation(id).pipe(tap(() => this.proposals.update(list => list.filter(p => p.id !== id))));
  }

  private upsert(proposal: RemediationProposal): void {
    if (proposal.analysisId !== this.analysisId()) {
      return;
    }
    this.proposals.update(list => [proposal, ...list.filter(p => p.id !== proposal.id)]);
    this.schedule();
  }

  private schedule(): void {
    const pending = this.proposals().some(p => p.status === 'GENERATING');
    if (pending && !this.timer) {
      this.timer = setInterval(() => this.poll(), POLL_MS);
    } else if (!pending) {
      this.stopPolling();
    }
  }

  private poll(): void {
    for (const p of this.proposals().filter(x => x.status === 'GENERATING')) {
      this.api.remediation(p.id).subscribe({
        next: updated => {
          if (updated.status === p.status) {
            return;
          }
          this.upsert(updated);
          if (updated.status === 'READY') {
            this.toast.success(`Correção de ${updated.findingId} pronta para revisão`);
          } else if (updated.status === 'MANUAL_ONLY') {
            this.toast.info(`${updated.findingId}: a correção exige ações manuais`);
          } else if (updated.status === 'FAILED') {
            this.toast.error(`Não foi possível gerar a correção de ${updated.findingId}`);
          }
        },
        error: () => this.proposals.update(list => list.filter(x => x.id !== p.id)),
      });
    }
  }

  private stopPolling(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}
