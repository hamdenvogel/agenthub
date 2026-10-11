import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { AgentHubApi } from '../../core/agenthub-api.service';
import { ExportService } from '../../core/export.service';
import { describeHttpError } from '../../core/http-error';
import { JsonSchema, SchemaCatalog, StructuredOutputSchema, ToolSchema, ToolSourceSchemas } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { AgentChipComponent } from '../../shared/badges.component';
import { IconComponent } from '../../shared/icon.component';

interface Param {
  name: string;
  type: string;
  required: boolean;
}

/** Todos os JSON Schemas usados nas chamadas ao Claude: Structured Output e Tool Input Schema (local e MCP). */
@Component({
  selector: 'app-schemas',
  imports: [FormsModule, IconComponent, AgentChipComponent],
  templateUrl: './schemas.component.html',
  styleUrl: './schemas.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SchemasComponent {
  private readonly api = inject(AgentHubApi);
  private readonly exporter = inject(ExportService);
  private readonly toast = inject(ToastService);

  protected readonly catalog = signal<SchemaCatalog | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly search = signal('');
  protected readonly open = signal<Set<string>>(new Set());

  protected readonly outputs = computed(() => {
    const q = this.normalize(this.search());
    return (this.catalog()?.structuredOutputs ?? [])
      .filter(o => !q || this.normalize(`${o.name} ${o.usedBy} ${o.purpose}`).includes(q));
  });

  protected readonly sources = computed(() => {
    const q = this.normalize(this.search());
    return (this.catalog()?.toolInputs ?? []).map(s => ({
      ...s,
      tools: s.tools.filter(t => !q || this.normalize(`${s.id} ${t.name} ${t.description}`).includes(q)),
    })).filter(s => !q || s.tools.length > 0 || this.normalize(s.id).includes(q));
  });

  protected readonly toolCount = computed(() =>
    (this.catalog()?.toolInputs ?? []).reduce((n, s) => n + s.tools.length, 0));
  protected readonly blockedCount = computed(() =>
    (this.catalog()?.toolInputs ?? []).reduce((n, s) => n + s.blockedTools.length, 0));
  protected readonly availableSources = computed(() =>
    (this.catalog()?.toolInputs ?? []).filter(s => s.available).length);

  constructor() {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.schemas().subscribe({
      next: catalog => {
        this.catalog.set(catalog);
        this.loading.set(false);
      },
      error: e => {
        this.error.set(describeHttpError(e));
        this.loading.set(false);
      },
    });
  }

  protected toggle(key: string): void {
    this.open.update(set => {
      const next = new Set(set);
      next.has(key) ? next.delete(key) : next.add(key);
      return next;
    });
  }

  protected params(schema: JsonSchema): Param[] {
    const properties = (schema['properties'] ?? {}) as Record<string, Record<string, unknown>>;
    const required = new Set((schema['required'] ?? []) as string[]);
    return Object.entries(properties).map(([name, p]) => ({
      name,
      type: this.typeOf(p),
      required: required.has(name),
    }));
  }

  protected pretty(schema: JsonSchema): string {
    return JSON.stringify(schema, null, 2);
  }

  protected async copy(schema: JsonSchema): Promise<void> {
    try {
      await this.exporter.copy(this.pretty(schema));
      this.toast.success('JSON Schema copiado');
    } catch (e) {
      this.toast.error(describeHttpError(e));
    }
  }

  protected downloadOutput(o: StructuredOutputSchema): void {
    this.exporter.jsonFile(`structured-output-${o.name}.schema.json`, o.schema);
  }

  protected downloadTool(source: ToolSourceSchemas, t: ToolSchema): void {
    this.exporter.jsonFile(`tool-input-${source.id}-${t.name}.schema.json`, t.inputSchema);
  }

  protected downloadAll(): void {
    const c = this.catalog();
    if (c) {
      this.exporter.jsonFile('agenthub-json-schemas.json', c);
    }
  }

  private typeOf(p: Record<string, unknown>): string {
    const type = p['type'];
    if (Array.isArray(type)) {
      return type.join(' | ');
    }
    if (type === 'array') {
      const items = (p['items'] ?? {}) as Record<string, unknown>;
      return `${this.typeOf(items)}[]`;
    }
    if (p['enum']) {
      return (p['enum'] as unknown[]).join(' | ');
    }
    return (type as string) ?? (p['anyOf'] ? 'anyOf' : '—');
  }

  private normalize(value: string): string {
    return value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim();
  }
}
