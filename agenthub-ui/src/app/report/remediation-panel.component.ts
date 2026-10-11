import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { Observable } from 'rxjs';

import { describeHttpError } from '../core/http-error';
import { Finding, ProposedFile } from '../core/models';
import { RemediationStore } from '../core/remediation.store';
import { ToastService } from '../core/toast.service';
import { RemediationBadgeComponent, SeverityBadgeComponent } from '../shared/badges.component';
import { IconComponent } from '../shared/icon.component';

interface DiffLine {
  kind: 'add' | 'del' | 'hunk' | 'ctx' | 'meta';
  text: string;
}

/** Correção assistida de um achado: termos, geração, revisão do diff, aplicação e reversão. */
@Component({
  selector: 'app-remediation-panel',
  imports: [IconComponent, SeverityBadgeComponent, RemediationBadgeComponent],
  templateUrl: './remediation-panel.component.html',
  styleUrl: './remediation-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RemediationPanelComponent implements OnInit {
  protected readonly store = inject(RemediationStore);
  private readonly toast = inject(ToastService);

  readonly finding = input.required<Finding>();
  readonly closed = output<void>();

  protected readonly agreed = signal(false);
  protected readonly reviewed = signal(false);
  protected readonly busy = signal(false);
  protected readonly collapsed = signal<Set<string>>(new Set());

  protected readonly consent = this.store.consent;
  protected readonly proposal = computed(() => this.store.byFinding().get(this.finding().id) ?? null);
  protected readonly totals = computed(() => (this.proposal()?.files ?? []).reduce(
    (acc, f) => ({ additions: acc.additions + f.additions, deletions: acc.deletions + f.deletions }),
    { additions: 0, deletions: 0 }));

  ngOnInit(): void {
    this.store.refreshConsent();
  }

  protected accept(): void {
    this.run(this.store.acceptConsent(), 'Termos aceitos para este projeto');
  }

  protected revoke(): void {
    this.run(this.store.revokeConsent(), 'Consentimento revogado');
  }

  protected generate(): void {
    this.reviewed.set(false);
    this.run(this.store.propose(this.finding().id));
  }

  protected apply(): void {
    const p = this.proposal();
    if (p) {
      this.run(this.store.apply(p.id), `Correção de ${p.findingId} gravada no projeto`);
    }
  }

  protected revert(): void {
    const p = this.proposal();
    if (p) {
      this.run(this.store.revert(p.id), `Correção de ${p.findingId} desfeita`);
    }
  }

  protected discard(): void {
    const p = this.proposal();
    if (p) {
      this.reviewed.set(false);
      this.run(this.store.discard(p.id), 'Proposta descartada');
    }
  }

  protected toggleFile(path: string): void {
    this.collapsed.update(set => {
      const next = new Set(set);
      next.has(path) ? next.delete(path) : next.add(path);
      return next;
    });
  }

  protected diffLines(file: ProposedFile): DiffLine[] {
    return file.diff.split('\n').filter((l, i, all) => l !== '' || i < all.length - 1).map(text => ({
      text,
      kind: text.startsWith('@@') ? 'hunk'
        : text.startsWith('+++') || text.startsWith('---') ? 'meta'
          : text.startsWith('+') ? 'add'
            : text.startsWith('-') ? 'del' : 'ctx',
    }));
  }

  protected pct(value: number): number {
    return Math.round((value ?? 0) * 100);
  }

  private run<T>(action: Observable<T>, success?: string): void {
    this.busy.set(true);
    action.subscribe({
      next: () => {
        this.busy.set(false);
        if (success) {
          this.toast.success(success);
        }
      },
      error: e => {
        this.busy.set(false);
        this.toast.error(describeHttpError(e));
        this.store.refreshConsent();
      },
    });
  }
}
