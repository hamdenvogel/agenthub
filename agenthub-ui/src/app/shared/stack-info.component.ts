import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, VERSION, computed, inject, output } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';

import pkg from '../../../package.json';
import { AgentHubApi } from '../core/agenthub-api.service';
import { StackComponent, StackInfo } from '../core/models';
import { ToastService } from '../core/toast.service';
import { IconComponent } from './icon.component';

/** Fim do suporte (EOL) por versão major, segundo os calendários oficiais do Node.js e do Angular. */
const END_OF_SUPPORT: Record<string, Record<number, string>> = {
  node: { 18: '2025-04-30', 20: '2026-04-30', 22: '2027-04-30', 24: '2028-04-30' },
  angular: { 18: '2025-11-21', 19: '2026-05-19', 20: '2026-11-28', 21: '2027-05-19' },
};
const JAVA_LTS = [8, 11, 17, 21, 25];
const WARN_DAYS = 90;

const ICONS: Record<string, string> = {
  angular: 'monitor', 'angular-cli': 'zap', typescript: 'braces', rxjs: 'activity', 'zone-js': 'clock',
  'spring-boot': 'server', 'spring-framework': 'layers', 'spring-ai': 'sparkles', 'mcp-sdk': 'hub',
  'claude-model': 'bot', java: 'cpu', node: 'git', os: 'monitor',
};

interface Support { label: string; tone: 'success' | 'warning' | 'danger' | 'neutral' }
interface Row extends StackComponent { icon: string; support: Support | null }
interface Section { title: string; icon: string; color: string; rows: Row[] }

@Component({
  selector: 'app-stack-info',
  imports: [IconComponent, DatePipe],
  template: `
    <div class="modal-backdrop" (click)="closed.emit()" (keydown.escape)="closed.emit()">
      <div class="modal stack" role="dialog" aria-modal="true" aria-label="Stack e versões" (click)="$event.stopPropagation()">
        <header class="modal-head">
          <app-icon name="cpu" />
          <h2>Stack e versões</h2>
          <button type="button" class="btn btn-ghost icon-btn" (click)="closed.emit()" aria-label="Fechar"><app-icon name="x" /></button>
        </header>
        <div class="modal-body">
          @if (info() === null) {
            <div class="alert alert-warning"><app-icon name="alert" /><span>Backend indisponível: só as versões do frontend foram carregadas.</span></div>
          }
          <div class="sections">
            @for (s of sections(); track s.title) {
              <section class="section" [style.--c]="s.color">
                <h3><span class="section-icon"><app-icon [name]="s.icon" [size]="16" /></span>{{ s.title }}</h3>
                @for (r of s.rows; track r.id) {
                  <div class="item">
                    <span class="item-icon"><app-icon [name]="r.icon" [size]="15" /></span>
                    <span class="item-text">
                      <strong>{{ r.name }}</strong>
                      @if (r.detail) { <small>{{ r.detail }}</small> }
                    </span>
                    <span class="item-side">
                      <code class="version" [class.missing]="!r.version">{{ r.version ?? 'não detectado' }}</code>
                      @if (r.support; as sp) { <span class="badge tone-{{ sp.tone }}">{{ sp.label }}</span> }
                    </span>
                  </div>
                }
                @if (s.title === 'Backend' && info() === undefined) {
                  <div class="skeleton" style="height: 120px"></div>
                }
              </section>
            }
          </div>
        </div>
        <footer class="modal-foot">
          <span class="muted small">
            @if (info(); as i) { Consultado em {{ i.generatedAt | date: 'dd/MM/yyyy HH:mm:ss' }} }
          </span>
          <span class="spacer"></span>
          <button type="button" class="btn btn-sm" (click)="copy()"><app-icon name="copy" [size]="14" /> Copiar</button>
          <button type="button" class="btn btn-primary btn-sm" (click)="closed.emit()">Fechar</button>
        </footer>
      </div>
    </div>`,
  styles: `
    .stack { width: min(820px, 100%); }
    .modal-foot .spacer { flex: 1; }
    .sections { display: grid; grid-template-columns: repeat(auto-fit, minmax(340px, 1fr)); gap: 14px; margin-top: 2px; }
    .alert { margin-bottom: 14px; }
    .section {
      display: flex; flex-direction: column; gap: 4px; padding: 14px; border-radius: 12px;
      border: 1px solid color-mix(in srgb, var(--c) 30%, var(--border));
      background: color-mix(in srgb, var(--c) 5%, var(--surface));
      h3 { display: flex; align-items: center; gap: 8px; margin: 0 0 6px; font-size: 14px; }
    }
    .section-icon {
      display: grid; place-items: center; width: 28px; height: 28px; border-radius: 8px;
      color: var(--c); background: color-mix(in srgb, var(--c) 15%, transparent);
    }
    .item {
      display: flex; align-items: center; gap: 10px; padding: 8px 6px; border-radius: 8px;
      & + .item { border-top: 1px solid var(--border); }
    }
    .item-icon { color: var(--text-muted); }
    .item-text {
      flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 1px;
      strong { font-size: 13px; }
      small { color: var(--text-muted); font-size: 11.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    }
    .item-side { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; flex-shrink: 0; }
    .version {
      font-family: var(--mono); font-size: 12.5px; font-weight: 700; padding: 2px 8px; border-radius: 6px;
      color: var(--c); background: color-mix(in srgb, var(--c) 12%, transparent);
      &.missing { color: var(--text-muted); background: var(--surface-2); font-weight: 500; }
    }
    .badge { font-size: 10.5px; height: 20px; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StackInfoComponent {
  private readonly api = inject(AgentHubApi);
  private readonly toast = inject(ToastService);

  readonly closed = output<void>();

  /** undefined = carregando; null = backend indisponível. */
  protected readonly info = toSignal<StackInfo | null>(this.api.about().pipe(catchError(() => of(null))));

  private readonly frontend: StackComponent[] = [
    { id: 'angular', name: 'Angular', version: VERSION.full, detail: 'Framework em execução nesta página' },
    { id: 'angular-cli', name: 'Angular CLI', version: pkg.devDependencies['@angular/cli'], detail: 'Declarado no package.json' },
    { id: 'typescript', name: 'TypeScript', version: pkg.devDependencies.typescript, detail: 'Declarado no package.json' },
    { id: 'rxjs', name: 'RxJS', version: pkg.dependencies.rxjs, detail: 'Declarado no package.json' },
    { id: 'zone-js', name: 'zone.js', version: pkg.dependencies['zone.js'], detail: 'Declarado no package.json' },
  ];

  protected readonly sections = computed<Section[]>(() => {
    const info = this.info();
    return [
      { title: 'Frontend', icon: 'monitor', color: '#06b6d4', rows: this.frontend.map(toRow) },
      { title: 'Backend', icon: 'server', color: '#3b82f6', rows: (info?.backend ?? []).map(toRow) },
      { title: 'Ambiente', icon: 'cpu', color: '#8b5cf6', rows: (info?.runtime ?? []).map(toRow) },
    ].filter(s => s.rows.length || (s.title === 'Backend' && info === undefined));
  });

  protected copy(): void {
    const text = this.sections()
      .map(s => `${s.title}\n` + s.rows.map(r => `  ${r.name}: ${r.version ?? 'não detectado'}${r.support ? ` (${r.support.label})` : ''}`).join('\n'))
      .join('\n\n');
    navigator.clipboard.writeText(text).then(
      () => this.toast.success('Versões copiadas'),
      () => this.toast.error('Não foi possível copiar'));
  }
}

function toRow(c: StackComponent): Row {
  return { ...c, icon: ICONS[c.id] ?? 'layers', support: support(c.id, c.version) };
}

function support(id: string, version: string | null): Support | null {
  const major = parseInt((version ?? '').replace(/^[^\d]*/, ''), 10);
  if (Number.isNaN(major)) {
    return null;
  }
  if (id === 'java') {
    return JAVA_LTS.includes(major) ? { label: 'LTS', tone: 'success' } : { label: 'Não LTS', tone: 'warning' };
  }
  const table = END_OF_SUPPORT[id];
  if (!table) {
    return null;
  }
  if (id === 'node' && major % 2 === 1) {
    return { label: 'Não LTS', tone: 'warning' };
  }
  const eol = table[major];
  if (!eol) {
    return { label: 'Ciclo de suporte não mapeado', tone: 'neutral' };
  }
  const end = new Date(`${eol}T23:59:59`);
  const date = end.toLocaleDateString('pt-BR');
  const days = (end.getTime() - Date.now()) / 86_400_000;
  if (days < 0) {
    return { label: `Sem suporte desde ${date}`, tone: 'danger' };
  }
  return days <= WARN_DAYS
    ? { label: `Suporte até ${date}`, tone: 'warning' }
    : { label: `Suportado até ${date}`, tone: 'success' };
}
