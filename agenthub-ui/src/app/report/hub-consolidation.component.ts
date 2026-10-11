import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { SEVERITY_META, agentMeta } from '../core/catalog';
import {
  ConsolidationItem, ConsolidationItemType, ConsolidationOutcome, HubConsolidation, SEVERITIES,
} from '../core/models';
import { AgentChipComponent, SeverityBadgeComponent, StatusBadgeComponent } from '../shared/badges.component';
import { IconComponent } from '../shared/icon.component';
import { PAGE_SIZES } from './findings-grid.component';

type SortKey = 'agent' | 'type' | 'severity' | 'title' | 'category' | 'file' | 'outcome' | 'findingId';

/**
 * Agrupador do Hub: o Structured Output que cada agente entregou e o destino de cada achado e recomendação
 * (no relatório ou absorvido como duplicado), em grade paginada.
 */
@Component({
  selector: 'app-hub-consolidation',
  imports: [FormsModule, IconComponent, AgentChipComponent, SeverityBadgeComponent, StatusBadgeComponent],
  templateUrl: './hub-consolidation.component.html',
  styleUrl: './hub-consolidation.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HubConsolidationComponent {
  readonly consolidation = input.required<HubConsolidation>();
  readonly openFinding = output<string>();

  protected readonly pageSizes = PAGE_SIZES;
  protected readonly severityMeta = SEVERITY_META;
  protected readonly search = signal('');
  protected readonly typeFilter = signal<'' | ConsolidationItemType>('');
  protected readonly agentFilter = signal('');
  protected readonly outcomeFilter = signal<'' | ConsolidationOutcome>('');
  protected readonly sortKey = signal<SortKey>('agent');
  protected readonly sortAsc = signal(true);
  protected readonly pageSize = signal(25);
  protected readonly page = signal(0);

  protected readonly columns: { key: SortKey; label: string; cls?: string }[] = [
    { key: 'agent', label: 'Agente' },
    { key: 'type', label: 'Tipo' },
    { key: 'severity', label: 'Severidade' },
    { key: 'title', label: 'Título / texto', cls: 'col-title' },
    { key: 'category', label: 'Categoria' },
    { key: 'file', label: 'Arquivo', cls: 'col-file' },
    { key: 'outcome', label: 'Destino no Hub' },
    { key: 'findingId', label: 'ID no relatório' },
  ];

  protected readonly totals = computed(() => this.consolidation().totals);
  protected readonly agents = computed(() => this.consolidation().agents);
  protected readonly itemAgents = computed(() => [...new Set(this.consolidation().items.map(i => i.agent))]);

  protected readonly filtered = computed(() => {
    const q = this.normalize(this.search());
    const type = this.typeFilter();
    const agent = this.agentFilter();
    const outcome = this.outcomeFilter();
    return this.consolidation().items.filter(i =>
      (!type || i.type === type)
      && (!agent || i.agent === agent)
      && (!outcome || i.outcome === outcome)
      && (!q || this.normalize([i.title, i.category, i.file, i.findingId, i.agentName].join(' ')).includes(q)));
  });

  protected readonly sorted = computed(() => {
    const key = this.sortKey();
    const dir = this.sortAsc() ? 1 : -1;
    const value = (i: ConsolidationItem): string | number => {
      switch (key) {
        case 'agent': return this.agentLabel(i.agent);
        case 'type': return i.type;
        case 'severity': return i.severity ? SEVERITIES.indexOf(i.severity) : SEVERITIES.length;
        case 'title': return i.title.toLowerCase();
        case 'category': return (i.category ?? '').toLowerCase();
        case 'file': return (i.file ?? '').toLowerCase();
        case 'outcome': return i.outcome;
        case 'findingId': return i.findingId ?? '~';
      }
    };
    return this.filtered()
      .map((item, index) => ({ item, index }))
      .sort((a, b) => {
        const va = value(a.item);
        const vb = value(b.item);
        const cmp = typeof va === 'number' && typeof vb === 'number'
          ? va - vb
          : String(va).localeCompare(String(vb), 'pt-BR', { numeric: true });
        return cmp !== 0 ? cmp * dir : a.index - b.index;
      })
      .map(x => x.item);
  });

  protected readonly totalPages = computed(() => Math.max(1, Math.ceil(this.sorted().length / this.pageSize())));
  protected readonly pageIndex = computed(() => Math.min(this.page(), this.totalPages() - 1));
  protected readonly rows = computed(() => {
    const start = this.pageIndex() * this.pageSize();
    return this.sorted().slice(start, start + this.pageSize());
  });
  protected readonly rangeStart = computed(() => this.sorted().length ? this.pageIndex() * this.pageSize() + 1 : 0);
  protected readonly rangeEnd = computed(() => Math.min(this.sorted().length, (this.pageIndex() + 1) * this.pageSize()));
  protected readonly pageNumbers = computed(() => {
    const total = this.totalPages();
    const from = Math.max(0, Math.min(this.pageIndex() - 2, total - 5));
    return Array.from({ length: Math.min(5, total) }, (_, i) => from + i);
  });

  protected readonly hasFilters = computed(() =>
    !!this.search() || !!this.typeFilter() || !!this.agentFilter() || !!this.outcomeFilter());

  constructor() {
    effect(() => {
      this.filtered();
      this.page.set(0);
    });
  }

  protected sortBy(key: SortKey): void {
    if (this.sortKey() === key) {
      this.sortAsc.set(!this.sortAsc());
    } else {
      this.sortKey.set(key);
      this.sortAsc.set(true);
    }
    this.page.set(0);
  }

  protected goTo(page: number): void {
    this.page.set(Math.max(0, Math.min(page, this.totalPages() - 1)));
  }

  protected setPageSize(size: number): void {
    this.pageSize.set(Number(size));
    this.page.set(0);
  }

  protected clearFilters(): void {
    this.search.set('');
    this.typeFilter.set('');
    this.agentFilter.set('');
    this.outcomeFilter.set('');
  }

  /** Atalho a partir da tabela por agente: mostra só os itens daquele agente. */
  protected showAgent(agent: string): void {
    this.clearFilters();
    this.agentFilter.set(agent);
  }

  protected agentLabel(agent: string | null): string {
    return agentMeta(agent)?.label ?? agent ?? '—';
  }

  protected shortFile(file: string | null): string {
    if (!file) {
      return '—';
    }
    const parts = file.split('/');
    return parts.length > 2 ? '…/' + parts.slice(-2).join('/') : file;
  }

  private normalize(value: string): string {
    return value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim();
  }
}
