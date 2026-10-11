import { KeyValuePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';

import { AgentHubApi } from '../../core/agenthub-api.service';
import { AGENTS, PROMPT_TEMPLATES, severityData } from '../../core/catalog';
import { DurationPipe, RelativeTimePipe } from '../../core/format.pipes';
import { describeHttpError } from '../../core/http-error';
import { AgentRole, ArchivedReport, PromptSuggestion } from '../../core/models';
import { PromptHistoryStore } from '../../core/prompt-history.store';
import { RunsStore } from '../../core/runs.store';
import { TargetsStore } from '../../core/targets.store';
import { ToastService } from '../../core/toast.service';
import { AgentChipComponent, StatusBadgeComponent } from '../../shared/badges.component';
import { StackedBarComponent } from '../../shared/charts.component';
import { IconComponent } from '../../shared/icon.component';

const MAX_LENGTH = 4000;
/** Ordem de grandeza observada por agente (~45k tokens com Sonnet). Apenas orientativo. */
const COST_PER_AGENT_USD = 0.22;

@Component({
  selector: 'app-new-analysis',
  imports: [FormsModule, RouterLink, KeyValuePipe, IconComponent, StatusBadgeComponent, AgentChipComponent,
    StackedBarComponent, RelativeTimePipe, DurationPipe],
  templateUrl: './new-analysis.component.html',
  styleUrl: './new-analysis.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NewAnalysisComponent {
  private readonly api = inject(AgentHubApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  protected readonly store = inject(RunsStore);
  protected readonly targets = inject(TargetsStore);
  protected readonly history = inject(PromptHistoryStore);

  protected readonly maxLength = MAX_LENGTH;
  protected readonly templates = PROMPT_TEMPLATES;
  protected readonly prompt = signal('');
  protected readonly selected = signal<Set<AgentRole>>(new Set());
  protected readonly submitting = signal(false);
  protected readonly historyOpen = signal(false);
  protected readonly suggesting = signal(false);
  protected readonly suggestions = signal<{ agents: AgentRole[]; items: PromptSuggestion[] } | null>(null);

  private readonly overview = toSignal(this.api.agents().pipe(catchError(() => of(null))), { initialValue: undefined });
  private readonly archived = toSignal(this.api.archivedReports().pipe(catchError(() => of([] as ArchivedReport[]))),
    { initialValue: [] as ArchivedReport[] });

  protected readonly agents = computed(() => {
    const enabled = this.overview()?.agents;
    const sources = this.overview()?.toolSources ?? [];
    return AGENTS
      .filter(a => !enabled || enabled.some(e => e.id === a.id))
      .map(a => {
        const info = enabled?.find(e => e.id === a.id);
        const tools = (info?.toolSources ?? []).map(id => ({
          id, available: sources.find(s => s.id === id)?.available ?? false,
        }));
        return { ...a, tools };
      });
  });

  protected readonly length = computed(() => this.prompt().length);
  protected readonly targetReady = computed(() => this.targets.active()?.available ?? false);
  protected readonly canSubmit = computed(() =>
    this.prompt().trim().length >= 10 && this.length() <= MAX_LENGTH && !this.submitting()
    && this.store.backend() !== 'down' && this.targetReady());
  protected readonly canSuggest = computed(() =>
    !this.suggesting() && this.length() <= MAX_LENGTH && this.store.backend() !== 'down' && this.targetReady());
  protected readonly estimate = computed(() => {
    const n = this.selected().size;
    return n === 0 ? null : { agents: n, usd: n * COST_PER_AGENT_USD };
  });

  protected readonly recentReports = computed(() => this.archived().slice(0, 5));
  protected readonly recentRuns = computed(() => this.store.runs().slice(0, 5));

  protected severity(r: ArchivedReport) {
    return severityData(r.findingsBySeverity);
  }

  protected toggle(role: AgentRole): void {
    this.selected.update(set => {
      const next = new Set(set);
      next.has(role) ? next.delete(role) : next.add(role);
      return next;
    });
  }

  protected useTemplate(t: (typeof PROMPT_TEMPLATES)[number]): void {
    this.prompt.set(t.prompt);
    this.selected.set(new Set(t.agents));
  }

  protected useHistory(text: string): void {
    this.prompt.set(text);
    this.historyOpen.set(false);
  }

  protected suggest(): void {
    if (!this.canSuggest()) {
      return;
    }
    this.suggesting.set(true);
    const agents = [...this.selected()];
    const draft = this.prompt().trim();
    this.api.suggestPrompts({ agents, draft: draft || undefined }).subscribe({
      next: items => {
        this.suggesting.set(false);
        this.suggestions.set({ agents, items });
        if (!items.length) {
          this.toast.error('A IA não retornou sugestões. Tente novamente.');
        }
      },
      error: e => {
        this.suggesting.set(false);
        this.toast.error(describeHttpError(e));
      },
    });
  }

  protected useSuggestion(s: PromptSuggestion): void {
    this.prompt.set(s.prompt);
  }

  protected selectAll(all: boolean): void {
    this.selected.set(all ? new Set(this.agents().map(a => a.role)) : new Set());
  }

  protected onKeydown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
      event.preventDefault();
      this.submit();
    }
  }

  protected submit(): void {
    if (!this.canSubmit()) {
      return;
    }
    this.submitting.set(true);
    const agents = [...this.selected()];
    const request = this.prompt().trim();
    this.history.add(request);
    this.api.startAnalysis({ request, agents: agents.length ? agents : undefined }).subscribe({
      next: run => {
        this.toast.success(`Análise iniciada em ${run.projectName ?? 'projeto ativo'}`);
        this.store.refresh();
        this.router.navigate(['/analysis', run.analysisId]);
      },
      error: e => {
        this.submitting.set(false);
        this.toast.error(describeHttpError(e));
      },
    });
  }
}
