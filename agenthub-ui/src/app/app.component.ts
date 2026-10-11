import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { filter } from 'rxjs';

import { RunsStore } from './core/runs.store';
import { TargetsStore } from './core/targets.store';
import { ThemeService } from './core/theme.service';
import { ToastService } from './core/toast.service';
import { IconComponent } from './shared/icon.component';
import { StackInfoComponent } from './shared/stack-info.component';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, IconComponent, StackInfoComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppComponent {
  protected readonly theme = inject(ThemeService);
  protected readonly store = inject(RunsStore);
  protected readonly targets = inject(TargetsStore);
  protected readonly toasts = inject(ToastService);
  protected readonly menuOpen = signal(false);
  protected readonly stackOpen = signal(false);

  protected readonly nav = [
    { path: '/', label: 'Nova análise', icon: 'sparkles', exact: true },
    { path: '/diagnostics', label: 'Último diagnóstico', icon: 'gauge', exact: false },
    { path: '/targets', label: 'Projetos', icon: 'folder', exact: false },
    { path: '/history', label: 'Histórico', icon: 'history', exact: false },
    { path: '/project', label: 'Discovery & agentes', icon: 'layers', exact: false },
    { path: '/schemas', label: 'JSON Schemas', icon: 'braces', exact: false },
  ];

  constructor() {
    inject(Router).events
      .pipe(filter(e => e instanceof NavigationEnd), takeUntilDestroyed())
      .subscribe(() => this.menuOpen.set(false));
  }
}
