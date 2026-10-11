import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { AGENT_STATUS_META, ANALYSIS_STATUS_META, SEVERITY_META, agentMeta } from '../core/catalog';
import { AgentStatus, AnalysisStatus, RemediationStatus, Severity } from '../core/models';
import { REMEDIATION_STATUS } from '../core/remediation.store';
import { IconComponent } from './icon.component';

@Component({
  selector: 'app-severity-badge',
  template: `<span class="badge sev" [style.--c]="meta().color">{{ meta().label }}</span>`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SeverityBadgeComponent {
  readonly severity = input.required<Severity>();
  protected readonly meta = computed(() => SEVERITY_META[this.severity()] ?? SEVERITY_META.INFO);
}

@Component({
  selector: 'app-status-badge',
  template: `<span class="badge tone-{{ meta().tone }}">
    @if (pulse()) { <span class="pulse-dot"></span> }
    {{ meta().label }}</span>`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StatusBadgeComponent {
  readonly status = input.required<AnalysisStatus | AgentStatus | null>();

  protected readonly meta = computed(() => {
    const s = this.status();
    if (!s) {
      return { label: 'Não executado', tone: 'neutral' };
    }
    return (ANALYSIS_STATUS_META as Record<string, { label: string; tone: string }>)[s]
      ?? (AGENT_STATUS_META as Record<string, { label: string; tone: string }>)[s]
      ?? { label: s, tone: 'neutral' };
  });
  protected readonly pulse = computed(() => this.status() === 'RUNNING' || this.status() === 'PENDING');
}

@Component({
  selector: 'app-remediation-badge',
  template: `<span class="badge tone-{{ meta().tone }}">
    @if (status() === 'GENERATING') { <span class="pulse-dot"></span> }
    {{ meta().label }}</span>`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RemediationBadgeComponent {
  readonly status = input.required<RemediationStatus>();
  protected readonly meta = computed(() => REMEDIATION_STATUS[this.status()]);
}

@Component({
  selector: 'app-agent-chip',
  imports: [IconComponent],
  template: `@if (meta(); as m) {
    <span class="agent-chip" [style.--c]="m.color"><app-icon [name]="m.icon" [size]="14" />{{ m.label }}</span>
  } @else {
    <span class="agent-chip">{{ agent() }}</span>
  }`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AgentChipComponent {
  readonly agent = input.required<string>();
  protected readonly meta = computed(() => agentMeta(this.agent()));
}
