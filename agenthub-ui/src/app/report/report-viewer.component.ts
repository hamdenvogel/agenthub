import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { KeyValuePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable, firstValueFrom } from 'rxjs';

import { AGENTS, AgentMeta, PRICING, SEVERITY_META, agentMeta, estimateCost, severityData } from '../core/catalog';
import { ExportService } from '../core/export.service';
import { CompactPipe, DurationPipe, formatCompact, formatDuration } from '../core/format.pipes';
import { describeHttpError } from '../core/http-error';
import { AgentMetrics, AnalysisReport, Finding, RemediationStatus, ReportSection, RoadmapItem, SEVERITIES, Severity } from '../core/models';
import { REMEDIATION_STATUS, RemediationStore } from '../core/remediation.store';
import { ToastService } from '../core/toast.service';
import {
  AgentChipComponent, RemediationBadgeComponent, SeverityBadgeComponent, StatusBadgeComponent,
} from '../shared/badges.component';
import { BarsComponent, ChartDatum, DonutComponent } from '../shared/charts.component';
import { IconComponent } from '../shared/icon.component';
import { FindingsGridComponent } from './findings-grid.component';
import { HubConsolidationComponent } from './hub-consolidation.component';
import { RemediationPanelComponent } from './remediation-panel.component';

type Tab = 'overview' | 'findings' | 'areas' | 'consolidation' | 'roadmap' | 'performance';
type FindingsView = 'grid' | 'cards';

const VIEW_KEY = 'agenthub.findingsView';

interface AreaCard {
  key: string;
  meta?: AgentMeta;
  label: string;
  icon: string;
  color: string;
  section: ReportSection | null;
}

@Component({
  selector: 'app-report-viewer',
  imports: [FormsModule, IconComponent, SeverityBadgeComponent, StatusBadgeComponent, AgentChipComponent,
    RemediationBadgeComponent, DonutComponent, BarsComponent, DurationPipe, CompactPipe, KeyValuePipe,
    FindingsGridComponent, HubConsolidationComponent, RemediationPanelComponent],
  templateUrl: './report-viewer.component.html',
  styleUrl: './report-viewer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReportViewerComponent {
  private readonly exporter = inject(ExportService);
  private readonly toast = inject(ToastService);
  protected readonly remediation = inject(RemediationStore);

  readonly report = input.required<AnalysisReport>();
  /** Busca o Markdown renderizado pelo backend (mesmo conteúdo do arquivo em reports/). */
  readonly markdown = input.required<() => Observable<string>>();

  protected readonly tabs: { key: Tab; label: string; icon: string }[] = [
    { key: 'overview', label: 'Visão geral', icon: 'gauge' },
    { key: 'findings', label: 'Achados', icon: 'list' },
    { key: 'areas', label: 'Áreas', icon: 'layers' },
    { key: 'consolidation', label: 'Consolidação', icon: 'hub' },
    { key: 'roadmap', label: 'Roadmap', icon: 'map' },
    { key: 'performance', label: 'Desempenho', icon: 'activity' },
  ];
  protected readonly tab = signal<Tab>('overview');
  protected readonly printing = signal(false);
  protected readonly exportOpen = signal(false);

  // ---- Achados: filtros
  protected readonly severities = SEVERITIES;
  protected readonly severityMeta = SEVERITY_META;
  protected readonly search = signal('');
  protected readonly severityFilter = signal<Set<Severity>>(new Set());
  protected readonly agentFilter = signal<string>('');
  protected readonly categoryFilter = signal<string>('');
  /** '' = todos; 'none' = sem correção; ou um status de correção. */
  protected readonly fixFilter = signal<'' | 'none' | RemediationStatus>('');
  protected readonly expanded = signal<Set<string>>(new Set());
  protected readonly view = signal<FindingsView>(localStorage.getItem(VIEW_KEY) === 'cards' ? 'cards' : 'grid');
  protected readonly remediating = signal<Finding | null>(null);
  protected readonly fixStatuses = Object.entries(REMEDIATION_STATUS) as [RemediationStatus, { label: string }][];
  protected readonly fixes = this.remediation.byFinding;

  constructor() {
    effect(() => this.remediation.load(this.report().analysisId));
  }

  protected readonly findingCategories = computed(() =>
    [...new Set(this.report().findings.map(f => f.category).filter((c): c is string => !!c))].sort());

  protected readonly counts = computed(() => {
    const counts: Partial<Record<Severity, number>> = {};
    for (const f of this.report().findings) {
      counts[f.severity] = (counts[f.severity] ?? 0) + 1;
    }
    return counts;
  });
  protected readonly severityChart = computed(() => severityData(this.counts()));

  protected readonly findingAgents = computed(() =>
    [...new Set(this.report().findings.map(f => f.agent).filter((a): a is NonNullable<typeof a> => !!a))]);

  protected readonly filteredFindings = computed(() => {
    const q = this.normalize(this.search());
    const sev = this.severityFilter();
    const agent = this.agentFilter();
    const category = this.categoryFilter();
    const fix = this.fixFilter();
    const fixes = this.fixes();
    return this.report().findings.filter(f =>
      (sev.size === 0 || sev.has(f.severity))
      && (!agent || f.agent === agent)
      && (!category || f.category === category)
      && (!fix || (fix === 'none' ? !fixes.has(f.id) : fixes.get(f.id)?.status === fix))
      && (!q || this.normalize([f.id, f.title, f.description, f.category, f.recommendation,
        ...f.evidence.map(e => e.file)].join(' ')).includes(q)));
  });

  protected readonly topFindings = computed(() =>
    this.report().findings.filter(f => f.severity === 'CRITICAL' || f.severity === 'HIGH').slice(0, 5));

  // ---- Áreas
  protected readonly areas = computed<AreaCard[]>(() => {
    const r = this.report();
    const byRole: Record<string, ReportSection | null> = {
      architecture: r.architecture, backend: r.backend, frontend: r.frontend,
      database: r.database, security: r.security, test: r.tests,
    };
    const cards: AreaCard[] = AGENTS.map(a => ({
      key: a.id, meta: a, label: a.label, icon: a.icon, color: a.color, section: byRole[a.id] ?? null,
    }));
    cards.push({ key: 'quality', label: 'Code Quality', icon: 'sparkles', color: '#a855f7', section: r.codeQuality });
    return cards;
  });
  protected readonly analyzedAreas = computed(() => this.areas().filter(a => a.section?.analyzed));

  // ---- Roadmap
  protected readonly roadmapPhases = computed(() => {
    const groups = new Map<string, RoadmapItem[]>();
    for (const item of this.report().roadmap) {
      const phase = item.phase || 'Sem fase';
      groups.set(phase, [...(groups.get(phase) ?? []), item]);
    }
    return [...groups.entries()].map(([phase, items]) => ({ phase, items }));
  });

  // ---- Desempenho
  protected readonly meta = computed(() => this.report().metadata);
  protected readonly metrics = computed<AgentMetrics[]>(() => this.meta()?.agentMetrics ?? []);
  protected readonly model = computed(() => this.metrics().find(m => m.model)?.model ?? null);
  protected readonly usage = computed(() => this.meta()?.usage ?? {});
  protected readonly inputTokens = computed(() => this.usage()['inputTokens'] ?? 0);
  protected readonly outputTokens = computed(() => this.usage()['outputTokens'] ?? 0);
  protected readonly cost = computed(() => estimateCost(this.inputTokens(), this.outputTokens()));
  protected readonly pricing = PRICING;
  protected readonly totalToolCalls = computed(() => this.metrics().reduce((a, m) => a + m.toolCalls, 0));
  /** Agentes com os limites da chamada registrados (relatórios antigos não têm). */
  protected readonly callLimits = computed(() => this.metrics().filter(m => (m.maxOutputTokens ?? 0) > 0));

  protected contextPercent(m: AgentMetrics): number {
    const budget = m.contextBudgetTokens ?? 0;
    return budget > 0 ? Math.min(100, Math.round(((m.contextPeakTokens ?? 0) / budget) * 100)) : 0;
  }
  protected readonly agentCount = computed(() => Object.keys(this.meta()?.agents ?? {}).length);
  protected readonly limitations = computed(() => this.meta()?.limitations ?? []);

  protected readonly durationChart = computed<ChartDatum[]>(() => this.metrics().map(m => ({
    label: agentMeta(m.agent)?.label ?? m.name, value: m.durationMs, color: agentMeta(m.agent)?.color ?? 'var(--accent)',
    hint: formatDuration(m.durationMs),
  })));
  protected readonly tokenChart = computed<ChartDatum[]>(() => this.metrics().map(m => ({
    label: agentMeta(m.agent)?.label ?? m.name, value: m.inputTokens + m.outputTokens,
    color: agentMeta(m.agent)?.color ?? 'var(--accent)',
    hint: `${formatCompact(m.inputTokens)} entrada · ${formatCompact(m.outputTokens)} saída`,
  })));
  protected readonly phaseTokenChart = computed<ChartDatum[]>(() => {
    const u = this.usage();
    const agents = this.metrics().reduce((a, m) => a + m.inputTokens + m.outputTokens, 0);
    const planner = (u['plannerInputTokens'] ?? 0) + (u['plannerOutputTokens'] ?? 0);
    const synthesis = (u['synthesisInputTokens'] ?? 0) + (u['synthesisOutputTokens'] ?? 0);
    return [
      { label: 'Planner', value: planner, color: '#64748b', hint: formatCompact(planner) },
      { label: 'Agentes', value: agents, color: 'var(--accent)', hint: formatCompact(agents) },
      { label: 'Síntese', value: synthesis, color: '#a855f7', hint: formatCompact(synthesis) },
    ];
  });
  protected readonly toolSourceChart = computed<ChartDatum[]>(() => {
    const totals = new Map<string, number>();
    for (const m of this.metrics()) {
      for (const [source, n] of Object.entries(m.toolCallsBySource ?? {})) {
        totals.set(source, (totals.get(source) ?? 0) + n);
      }
    }
    const palette = ['#3b82f6', '#f97316', '#10b981', '#eab308', '#ec4899'];
    return [...totals.entries()].map(([label, value], i) => ({ label, value, color: palette[i % palette.length] }));
  });

  // ---- Ações
  protected selectTab(tab: Tab): void {
    this.tab.set(tab);
  }

  protected toggleSeverity(s: Severity): void {
    this.severityFilter.update(set => {
      const next = new Set(set);
      next.has(s) ? next.delete(s) : next.add(s);
      return next;
    });
  }

  protected clearFilters(): void {
    this.search.set('');
    this.severityFilter.set(new Set());
    this.agentFilter.set('');
    this.categoryFilter.set('');
    this.fixFilter.set('');
  }

  protected readonly hasFilters = computed(() => !!this.search() || this.severityFilter().size > 0 || !!this.agentFilter()
    || !!this.categoryFilter() || !!this.fixFilter());

  protected setView(view: FindingsView): void {
    this.view.set(view);
    localStorage.setItem(VIEW_KEY, view);
  }

  protected resolve(finding: Finding): void {
    this.remediating.set(finding);
  }

  protected toggleExpanded(id: string): void {
    this.expanded.update(set => {
      const next = new Set(set);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  }

  protected expandAll(open: boolean): void {
    this.expanded.set(open ? new Set(this.filteredFindings().map(f => f.id)) : new Set());
  }

  protected showFinding(id: string): void {
    this.clearFilters();
    this.search.set(id);
    this.expanded.set(new Set([id]));
    this.setView('cards');
    this.tab.set('findings');
  }

  protected showSeverity(s: Severity): void {
    this.clearFilters();
    this.severityFilter.set(new Set([s]));
    this.tab.set('findings');
  }

  protected agentLabel(agent: string | null): string {
    return agentMeta(agent)?.label ?? agent ?? '—';
  }

  protected confidencePct(section: ReportSection | null): number {
    return Math.round((section?.confidence ?? 0) * 100);
  }

  protected exportJson(): void {
    this.exportOpen.set(false);
    this.exporter.json(this.report());
  }

  protected exportCsv(onlyFiltered = false): void {
    this.exportOpen.set(false);
    this.exporter.findingsCsv(this.report(), onlyFiltered ? this.filteredFindings() : this.report().findings,
      onlyFiltered ? 'achados-filtrados' : 'achados', this.fixes());
  }

  protected exportFindingsJson(onlyFiltered = false): void {
    this.exportOpen.set(false);
    this.exporter.findingsJson(this.report(), onlyFiltered ? this.filteredFindings() : this.report().findings,
      onlyFiltered ? 'achados-filtrados' : 'achados', this.fixes());
  }

  protected async exportMarkdown(): Promise<void> {
    this.exportOpen.set(false);
    try {
      this.exporter.markdown(this.report(), await firstValueFrom(this.markdown()()));
    } catch (e) {
      this.toast.error(describeHttpError(e));
    }
  }

  protected async copyMarkdown(): Promise<void> {
    this.exportOpen.set(false);
    try {
      await this.exporter.copy(await firstValueFrom(this.markdown()()));
      this.toast.success('Relatório em Markdown copiado');
    } catch (e) {
      this.toast.error(describeHttpError(e));
    }
  }

  protected print(): void {
    this.exportOpen.set(false);
    this.printing.set(true);
    const done = () => {
      this.printing.set(false);
      window.removeEventListener('afterprint', done);
    };
    window.addEventListener('afterprint', done);
    setTimeout(() => this.exporter.print(), 50);
  }

  private normalize(value: string): string {
    return value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim();
  }
}
