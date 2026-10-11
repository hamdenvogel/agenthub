import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';

import { AgentHubApi } from '../../core/agenthub-api.service';
import { agentMeta } from '../../core/catalog';
import { describeHttpError } from '../../core/http-error';
import { AgentsOverview, ProjectContext } from '../../core/models';
import { IconComponent } from '../../shared/icon.component';

const ARTIFACT_LABELS: Record<string, string> = {
  controllers: 'Controllers', services: 'Services', repositories: 'Repositories', entities: 'Entidades', dtos: 'DTOs',
  configurations: 'Configurações', security: 'Segurança', tests: 'Testes', resources: 'Resources',
  components: 'Componentes', guards: 'Guards', interceptors: 'Interceptors', routing: 'Rotas', modules: 'Módulos',
};

@Component({
  selector: 'app-project',
  imports: [IconComponent, RouterLink],
  templateUrl: './project.component.html',
  styleUrl: './project.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProjectComponent {
  private readonly api = inject(AgentHubApi);

  protected readonly project = signal<ProjectContext | null>(null);
  protected readonly overview = signal<AgentsOverview | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly agentMeta = agentMeta;

  protected readonly infra = computed(() => {
    const i = this.project()?.infrastructure;
    if (!i) {
      return [];
    }
    return [
      { label: 'Bancos de dados', icon: 'database', items: i.databases },
      { label: 'Nuvem / deploy', icon: 'external', items: i.cloud },
      { label: 'Docker', icon: 'layers', items: i.docker },
      { label: 'CI/CD', icon: 'refresh', items: i.ciCd },
      { label: 'Kubernetes', icon: 'hub', items: i.kubernetes },
      { label: 'Migrações', icon: 'git', items: i.migrations },
      { label: 'Scripts SQL', icon: 'file-text', items: i.sqlScripts },
      { label: 'Observabilidade', icon: 'activity', items: i.observability },
    ];
  });

  protected readonly matrix = computed(() => {
    const o = this.overview();
    if (!o) {
      return { sources: [] as string[], rows: [] as { id: string; name: string; access: Record<string, boolean> }[] };
    }
    const sources = o.toolSources.map(s => s.id);
    const rows = o.agents.map(a => ({
      id: a.id, name: a.name,
      access: Object.fromEntries(sources.map(s => [s, a.toolSources.includes(s)])),
    }));
    return { sources, rows };
  });

  constructor() {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    forkJoin({ project: this.api.project(), overview: this.api.agents() }).subscribe({
      next: ({ project, overview }) => {
        this.project.set(project);
        this.overview.set(overview);
        this.loading.set(false);
      },
      error: e => {
        this.error.set(describeHttpError(e));
        this.loading.set(false);
      },
    });
  }

  protected artifacts(map: Record<string, string[]>): { label: string; count: number }[] {
    return Object.entries(map ?? {})
      .filter(([, files]) => files.length > 0)
      .map(([key, files]) => ({ label: ARTIFACT_LABELS[key] ?? key, count: files.length }))
      .sort((a, b) => b.count - a.count);
  }

  protected sourceAvailable(id: string): boolean {
    return this.overview()?.toolSources.find(s => s.id === id)?.available ?? false;
  }
}
