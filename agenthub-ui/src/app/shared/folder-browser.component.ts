import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { AgentHubApi } from '../core/agenthub-api.service';
import { describeHttpError } from '../core/http-error';
import { BrowseEntry, BrowseResult } from '../core/models';
import { IconComponent } from './icon.component';

const MARKER_LABEL: Record<string, string> = {
  git: 'git', maven: 'Maven', gradle: 'Gradle', angular: 'Angular', node: 'Node', java: 'Java',
};

/** Normaliza caminhos Windows/Unix para comparação (case-insensitive, separador único). */
function norm(path: string): string {
  return path.replace(/[\\/]+/g, '/').replace(/\/$/, '').toLowerCase();
}

export function isInside(path: string, base: string): boolean {
  const p = norm(path);
  const b = norm(base);
  return p === b || p.startsWith(b + '/');
}

/**
 * Navegador de pastas servido pelo backend (o navegador não expõe o caminho absoluto de
 * uma pasta local). Modo {@code root}: escolhe um diretório. Modo {@code scope}: marca
 * subdiretórios/arquivos dentro de {@code limit}.
 */
@Component({
  selector: 'app-folder-browser',
  imports: [FormsModule, IconComponent],
  template: `
    <div class="modal-backdrop" (click)="closed.emit()" (keydown.escape)="closed.emit()">
      <div class="modal" role="dialog" aria-modal="true" [attr.aria-label]="title()" (click)="$event.stopPropagation()">
        <header class="modal-head">
          <app-icon name="folder-open" />
          <h2>{{ title() }}</h2>
          <button type="button" class="btn btn-ghost icon-btn" (click)="closed.emit()" aria-label="Fechar"><app-icon name="x" /></button>
        </header>

        <div class="nav-bar">
          <button type="button" class="btn btn-sm" (click)="up()" [disabled]="!canGoUp() || loading()" title="Pasta acima">
            <app-icon name="arrow-up" [size]="15" />
          </button>
          <div class="search path-input">
            <app-icon name="folder" [size]="15" />
            <input [ngModel]="typed()" (ngModelChange)="typed.set($event)" (keydown.enter)="go(typed())" class="mono"
                   placeholder="Cole um caminho e tecle Enter" aria-label="Caminho" />
          </div>
          <div class="search filter">
            <app-icon name="search" [size]="15" />
            <input [ngModel]="filter()" (ngModelChange)="filter.set($event)" placeholder="Filtrar" aria-label="Filtrar" />
          </div>
        </div>

        @if (crumbs().length) {
          <nav class="crumbs" aria-label="Caminho atual">
            @for (c of crumbs(); track c.path; let last = $last) {
              <button type="button" class="crumb" [disabled]="last || !c.enabled" (click)="go(c.path)">{{ c.label }}</button>
              @if (!last) { <app-icon name="chevron-right" [size]="13" /> }
            }
          </nav>
        }

        <div class="modal-body">
          @if (error(); as e) {
            <div class="alert alert-danger"><app-icon name="alert" /><span>{{ e }}</span></div>
          }
          @if (loading()) {
            @for (i of [1, 2, 3, 4, 5]; track i) { <div class="skeleton entry-skel"></div> }
          } @else {
            <ul class="entries">
              @for (e of visible(); track e.path) {
                <li class="entry" [class.selected]="selection().has(e.path)">
                  @if (mode() === 'scope') {
                    <input type="checkbox" [checked]="selection().has(e.path)" (change)="toggle(e)" [attr.aria-label]="'Incluir ' + e.name" />
                  }
                  <button type="button" class="entry-main" (click)="open(e)" [disabled]="!e.directory && mode() === 'root'">
                    <app-icon [name]="e.directory ? 'folder' : 'file'" [size]="17" [class.dir]="e.directory" />
                    <span class="entry-name">{{ e.name }}</span>
                    @for (m of e.markers; track m) { <span class="marker m-{{ m }}">{{ label(m) }}</span> }
                  </button>
                  @if (e.directory) {
                    <app-icon name="chevron-right" [size]="15" class="muted" />
                  }
                </li>
              } @empty {
                <li class="muted small empty">{{ filter() ? 'Nada corresponde ao filtro.' : 'Pasta vazia.' }}</li>
              }
            </ul>
            @if (result()?.truncated) { <p class="muted small">Listagem limitada a 500 itens; use o filtro ou navegue para uma subpasta.</p> }
          }
        </div>

        <footer class="modal-foot">
          @if (mode() === 'scope') {
            <span class="muted small">{{ selection().size }} item(ns) no escopo</span>
            <span class="spacer"></span>
            <button type="button" class="btn btn-ghost" (click)="closed.emit()">Cancelar</button>
            <button type="button" class="btn btn-primary" (click)="confirmScope()">
              <app-icon name="check" [size]="16" /> Aplicar escopo
            </button>
          } @else {
            <span class="muted small current mono" [title]="result()?.path ?? ''">{{ result()?.path ?? 'Escolha uma pasta-base' }}</span>
            <span class="spacer"></span>
            <button type="button" class="btn btn-ghost" (click)="closed.emit()">Cancelar</button>
            <button type="button" class="btn btn-primary" [disabled]="!result()?.path" (click)="confirmRoot()">
              <app-icon name="check" [size]="16" /> Usar esta pasta
            </button>
          }
        </footer>
      </div>
    </div>
  `,
  styles: `
    .nav-bar { display: flex; gap: 8px; padding: 12px 18px 0; flex-wrap: wrap; }
    .path-input { flex: 2 1 280px; }
    .filter { flex: 1 1 140px; }
    .crumbs { display: flex; flex-wrap: wrap; align-items: center; gap: 2px; padding: 10px 18px 0; color: var(--text-muted); }
    .crumb {
      border: 0; background: transparent; padding: 3px 6px; border-radius: 6px; cursor: pointer; color: var(--accent-strong); font-size: 12.5px;
      &:hover:not(:disabled) { background: var(--surface-2); }
      &:disabled { color: var(--text); cursor: default; font-weight: 600; }
    }
    .entries { list-style: none; margin: 0; padding: 0; }
    .entry {
      display: flex; align-items: center; gap: 8px; padding: 0 8px; border-radius: 9px;
      &:hover { background: var(--surface-2); }
      &.selected { background: var(--accent-soft); }
      input { width: 16px; height: 16px; accent-color: var(--accent); flex-shrink: 0; }
    }
    .entry-main {
      flex: 1; min-width: 0; display: flex; align-items: center; gap: 10px; height: 38px; border: 0; background: transparent;
      text-align: left; cursor: pointer; padding: 0;
      &:disabled { cursor: default; opacity: .55; }
      .dir { color: #f59e0b; }
    }
    .entry-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .marker {
      font-size: 10.5px; font-weight: 700; padding: 1px 7px; border-radius: 99px; color: var(--c, var(--text-muted));
      background: color-mix(in srgb, var(--c, #64748b) 14%, transparent); flex-shrink: 0;
      &.m-git { --c: #f97316; } &.m-maven { --c: #ef4444; } &.m-gradle { --c: #14b8a6; }
      &.m-angular { --c: #e11d48; } &.m-node { --c: #22c55e; } &.m-java { --c: #3b82f6; }
    }
    .entry-skel { height: 34px; margin-bottom: 6px; border-radius: 8px; }
    .empty { padding: 18px 8px; }
    .spacer { flex: 1; }
    .current { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 50%; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FolderBrowserComponent implements OnInit {
  private readonly api = inject(AgentHubApi);

  readonly mode = input<'root' | 'scope'>('root');
  readonly start = input<string | null>(null);
  /** No modo scope: a navegação não sobe acima desta pasta. */
  readonly limit = input<string | null>(null);
  readonly initialSelection = input<string[]>([]);
  /** Pastas-base permitidas pelo backend: a trilha não oferece pastas acima delas. */
  readonly allowedRoots = input<string[]>([]);

  readonly picked = output<string>();
  readonly pickedMany = output<string[]>();
  readonly closed = output<void>();

  protected readonly result = signal<BrowseResult | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly typed = signal('');
  protected readonly filter = signal('');
  protected readonly selection = signal<Set<string>>(new Set());
  private requestSeq = 0;

  protected readonly title = computed(() => this.mode() === 'scope' ? 'Escopo: pastas e arquivos a analisar' : 'Selecionar diretório do projeto');

  protected readonly visible = computed(() => {
    const f = this.filter().trim().toLowerCase();
    const entries = this.result()?.entries ?? [];
    return f ? entries.filter(e => e.name.toLowerCase().includes(f)) : entries;
  });

  protected readonly canGoUp = computed(() => {
    const r = this.result();
    if (!r?.path) {
      return false;
    }
    const limit = this.limit();
    if (this.mode() === 'scope' && limit) {
      return !!r.parent && isInside(r.parent, limit);
    }
    return true;
  });

  protected readonly crumbs = computed(() => {
    const path = this.result()?.path;
    if (!path) {
      return [];
    }
    const parts = path.split(/[\\/]+/).filter(Boolean);
    const sep = path.includes('\\') ? '\\' : '/';
    const leading = path.startsWith('/') ? '/' : '';
    const limit = this.mode() === 'scope' ? this.limit() : null;
    const allowed = this.allowedRoots();
    return parts.map((label, i) => {
      let p = leading + parts.slice(0, i + 1).join(sep);
      if (i === 0 && /^[A-Za-z]:$/.test(label)) {
        p += sep;
      }
      const enabled = (!limit || isInside(p, limit)) && (!allowed.length || allowed.some(a => isInside(p, a)));
      return { label, path: p, enabled };
    });
  });

  ngOnInit(): void {
    this.selection.set(new Set(this.initialSelection()));
    this.go(this.start() ?? this.limit());
  }

  protected go(path: string | null): void {
    const target = path?.trim() || null;
    if (this.mode() === 'scope' && this.limit() && target && !isInside(target, this.limit()!)) {
      this.error.set('No escopo, navegue apenas dentro do diretório do projeto.');
      return;
    }
    const request = ++this.requestSeq;
    this.loading.set(true);
    this.error.set(null);
    this.filter.set('');
    this.api.browse(target).subscribe({
      next: r => {
        if (request !== this.requestSeq) {
          return;
        }
        this.result.set(r);
        this.typed.set(r.path ?? '');
        this.loading.set(false);
      },
      error: e => {
        if (request !== this.requestSeq) {
          return;
        }
        this.error.set(describeHttpError(e));
        this.loading.set(false);
      },
    });
  }

  protected up(): void {
    const r = this.result();
    this.go(r?.parent ?? null);
  }

  protected open(e: BrowseEntry): void {
    if (e.directory) {
      this.go(e.path);
    } else if (this.mode() === 'scope') {
      this.toggle(e);
    }
  }

  protected toggle(e: BrowseEntry): void {
    this.selection.update(s => {
      const next = new Set(s);
      next.has(e.path) ? next.delete(e.path) : next.add(e.path);
      return next;
    });
  }

  protected confirmScope(): void {
    this.pickedMany.emit([...this.selection()]);
  }

  protected confirmRoot(): void {
    const path = this.result()?.path;
    if (path) {
      this.picked.emit(path);
    }
  }

  protected label(marker: string): string {
    return MARKER_LABEL[marker] ?? marker;
  }
}
