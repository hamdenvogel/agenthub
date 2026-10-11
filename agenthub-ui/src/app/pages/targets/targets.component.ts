import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';

import { describeHttpError } from '../../core/http-error';
import { TargetStatus } from '../../core/models';
import { TargetsStore } from '../../core/targets.store';
import { ToastService } from '../../core/toast.service';
import { FolderBrowserComponent, isInside } from '../../shared/folder-browser.component';
import { IconComponent } from '../../shared/icon.component';

interface Draft {
  id: string | null;
  name: string;
  root: string;
  includes: string[];
  databaseUri: string;
}

function separator(root: string): string {
  return root.includes('\\') ? '\\' : '/';
}

/** Caminho absoluto dentro da raiz -> relativo com "/" (formato aceito pelo backend). */
function toRelative(absolute: string, root: string): string {
  return absolute.slice(root.replace(/[\\/]+$/, '').length).replace(/^[\\/]+/, '').replace(/\\/g, '/');
}

@Component({
  selector: 'app-targets',
  imports: [FormsModule, RouterLink, DatePipe, NgTemplateOutlet, IconComponent, FolderBrowserComponent],
  templateUrl: './targets.component.html',
  styleUrl: './targets.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TargetsComponent {
  protected readonly store = inject(TargetsStore);
  private readonly toast = inject(ToastService);

  protected readonly draft = signal<Draft | null>(null);
  protected readonly browser = signal<'root' | 'scope' | null>(null);
  protected readonly saving = signal(false);
  protected readonly working = signal<string | null>(null);

  protected readonly others = computed(() => this.store.targets().filter(t => !t.active));
  protected readonly draftValid = computed(() => !!this.draft()?.root.trim());
  protected readonly scopeSelection = computed(() => {
    const d = this.draft();
    if (!d?.root) {
      return [];
    }
    const sep = separator(d.root);
    return d.includes.map(i => d.root.replace(/[\\/]+$/, '') + sep + i.replace(/\//g, sep));
  });

  constructor() {
    this.store.refresh();
  }

  protected newTarget(): void {
    this.draft.set({ id: null, name: '', root: '', includes: [], databaseUri: '' });
  }

  protected edit(t: TargetStatus): void {
    this.draft.set({ id: t.id, name: t.name, root: t.root, includes: [...t.includes], databaseUri: t.databaseUri ?? '' });
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  /** "postgresql://u:***@host:5435/banco?x" -> "host:5435/banco". */
  protected dbLabel(uri: string): string {
    return uri.replace(/^[^:]+:\/\/([^@/]*@)?/, '').replace(/\?.*$/, '');
  }

  protected patch(change: Partial<Draft>): void {
    this.draft.update(d => d ? { ...d, ...change } : d);
  }

  protected onRootPicked(path: string): void {
    const d = this.draft();
    const sameRoot = d && isInside(path, d.root) && isInside(d.root, path);
    const name = d?.name || path.split(/[\\/]+/).filter(Boolean).pop() || '';
    this.patch({ root: path, name, includes: sameRoot ? d!.includes : [] });
    this.browser.set(null);
  }

  protected onScopePicked(paths: string[]): void {
    const d = this.draft();
    if (d) {
      const includes = paths.filter(p => isInside(p, d.root)).map(p => toRelative(p, d.root)).filter(Boolean);
      this.patch({ includes: [...new Set(includes)].sort() });
    }
    this.browser.set(null);
  }

  protected removeInclude(include: string): void {
    this.draft.update(d => d ? { ...d, includes: d.includes.filter(i => i !== include) } : d);
  }

  protected save(activate: boolean): void {
    const d = this.draft();
    if (!d || !this.draftValid()) {
      return;
    }
    this.saving.set(true);
    const input = { name: d.name.trim(), root: d.root.trim(), includes: d.includes, databaseUri: d.databaseUri.trim() };
    const request: Observable<TargetStatus> = d.id ? this.store.update(d.id, input) : this.store.create(input, activate);
    request.subscribe({
      next: saved => {
        if (d.id && activate && !saved.active) {
          this.activate(saved);
        } else {
          this.toast.success(saved.active ? `Projeto ativo: ${saved.name}` : 'Projeto salvo');
        }
        this.saving.set(false);
        this.draft.set(null);
      },
      error: e => {
        this.saving.set(false);
        this.toast.error(describeHttpError(e));
      },
    });
  }

  protected activate(t: TargetStatus): void {
    this.working.set(t.id);
    this.store.activate(t.id).subscribe({
      next: a => {
        this.working.set(null);
        this.toast.success(`Projeto ativo: ${a.name}`);
      },
      error: e => {
        this.working.set(null);
        this.toast.error(describeHttpError(e));
      },
    });
  }

  protected remove(t: TargetStatus): void {
    if (!confirm(`Remover o projeto "${t.name}" da lista? Os arquivos do projeto não são alterados.`)) {
      return;
    }
    this.working.set(t.id);
    this.store.remove(t.id).subscribe({
      next: () => {
        this.working.set(null);
        this.toast.success('Projeto removido');
      },
      error: e => {
        this.working.set(null);
        this.toast.error(describeHttpError(e));
      },
    });
  }
}
