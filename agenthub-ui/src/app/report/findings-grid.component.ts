import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { SEVERITY_META, agentMeta } from '../core/catalog';
import { ExportService } from '../core/export.service';
import { AnalysisReport, Finding, RemediationProposal, SEVERITIES } from '../core/models';
import { REMEDIATION_STATUS } from '../core/remediation.store';
import { AgentChipComponent, RemediationBadgeComponent, SeverityBadgeComponent } from '../shared/badges.component';
import { IconComponent } from '../shared/icon.component';

type SortKey = 'severity' | 'id' | 'title' | 'agent' | 'category' | 'file' | 'fix';

export const PAGE_SIZES = [10, 25, 50, 100];

/** Grade detalhada dos achados: ordenação, paginação, seleção, detalhe por linha e exportação da seleção. */
@Component({
  selector: 'app-findings-grid',
  imports: [FormsModule, IconComponent, SeverityBadgeComponent, AgentChipComponent, RemediationBadgeComponent],
  templateUrl: './findings-grid.component.html',
  styleUrl: './findings-grid.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FindingsGridComponent {
  private readonly exporter = inject(ExportService);

  readonly report = input.required<AnalysisReport>();
  /** Achados já filtrados pela barra de filtros do relatório. */
  readonly findings = input.required<Finding[]>();
  readonly fixes = input<Map<string, RemediationProposal>>(new Map());
  readonly resolve = output<Finding>();

  protected readonly pageSizes = PAGE_SIZES;
  protected readonly severityMeta = SEVERITY_META;
  protected readonly sortKey = signal<SortKey>('severity');
  protected readonly sortAsc = signal(true);
  protected readonly pageSize = signal(25);
  protected readonly page = signal(0);
  protected readonly selection = signal<Set<string>>(new Set());
  protected readonly openId = signal<string | null>(null);

  protected readonly columns: { key: SortKey; label: string; cls?: string }[] = [
    { key: 'severity', label: 'Severidade' },
    { key: 'id', label: 'ID' },
    { key: 'title', label: 'Título', cls: 'col-title' },
    { key: 'agent', label: 'Área' },
    { key: 'category', label: 'Categoria' },
    { key: 'file', label: 'Arquivo', cls: 'col-file' },
    { key: 'fix', label: 'Correção' },
  ];

  protected readonly sorted = computed(() => {
    const key = this.sortKey();
    const dir = this.sortAsc() ? 1 : -1;
    const fixes = this.fixes();
    const value = (f: Finding): string | number => {
      switch (key) {
        case 'severity': return SEVERITIES.indexOf(f.severity);
        case 'id': return f.id;
        case 'title': return f.title.toLowerCase();
        case 'agent': return agentMeta(f.agent)?.label ?? f.agent ?? '';
        case 'category': return (f.category ?? '').toLowerCase();
        case 'file': return (this.firstFile(f) ?? '').toLowerCase();
        case 'fix': return fixes.get(f.id) ? REMEDIATION_STATUS[fixes.get(f.id)!.status].label : '~';
      }
    };
    return [...this.findings()].sort((a, b) => {
      const va = value(a);
      const vb = value(b);
      const cmp = typeof va === 'number' && typeof vb === 'number' ? va - vb : String(va).localeCompare(String(vb), 'pt-BR', { numeric: true });
      return cmp !== 0 ? cmp * dir : a.id.localeCompare(b.id, 'pt-BR', { numeric: true });
    });
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
    const current = this.pageIndex();
    const from = Math.max(0, Math.min(current - 2, total - 5));
    return Array.from({ length: Math.min(5, total) }, (_, i) => from + i);
  });

  protected readonly selectedFindings = computed(() => this.sorted().filter(f => this.selection().has(f.id)));
  protected readonly pageAllSelected = computed(() => this.rows().length > 0 && this.rows().every(f => this.selection().has(f.id)));
  protected readonly pageSomeSelected = computed(() => !this.pageAllSelected() && this.rows().some(f => this.selection().has(f.id)));

  constructor() {
    // Filtros mudaram: volta para a primeira página e descarta seleção de achados que saíram da lista.
    effect(() => {
      const ids = new Set(this.findings().map(f => f.id));
      this.page.set(0);
      this.selection.update(set => new Set([...set].filter(id => ids.has(id))));
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
    this.openId.set(null);
  }

  protected setPageSize(size: number): void {
    this.pageSize.set(Number(size));
    this.page.set(0);
  }

  protected toggle(id: string): void {
    this.selection.update(set => {
      const next = new Set(set);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  }

  protected togglePage(): void {
    const all = this.pageAllSelected();
    this.selection.update(set => {
      const next = new Set(set);
      this.rows().forEach(f => all ? next.delete(f.id) : next.add(f.id));
      return next;
    });
  }

  protected selectAllFiltered(): void {
    this.selection.set(new Set(this.sorted().map(f => f.id)));
  }

  protected clearSelection(): void {
    this.selection.set(new Set());
  }

  protected toggleOpen(id: string): void {
    this.openId.set(this.openId() === id ? null : id);
  }

  protected exportSelected(format: 'csv' | 'json'): void {
    const list = this.selectedFindings();
    format === 'csv'
      ? this.exporter.findingsCsv(this.report(), list, 'achados-selecionados', this.fixes())
      : this.exporter.findingsJson(this.report(), list, 'achados-selecionados', this.fixes());
  }

  protected firstFile(f: Finding): string | null {
    const e = f.evidence.find(x => x.file);
    return e ? `${e.file}${e.lines ? ':' + e.lines : ''}` : null;
  }

  protected shortFile(f: Finding): string {
    const file = this.firstFile(f);
    if (!file) {
      return '—';
    }
    const parts = file.split('/');
    return parts.length > 2 ? '…/' + parts.slice(-2).join('/') : file;
  }

  protected agentLabel(agent: string | null): string {
    return agentMeta(agent)?.label ?? agent ?? '—';
  }
}
