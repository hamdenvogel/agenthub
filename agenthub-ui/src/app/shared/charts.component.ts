import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export interface ChartDatum {
  label: string;
  value: number;
  color: string;
  hint?: string;
}

/** Donut em SVG puro (sem bibliotecas de gráfico). */
@Component({
  selector: 'app-donut',
  template: `
    <div class="donut">
      <svg viewBox="0 0 120 120" role="img" [attr.aria-label]="ariaLabel()">
        <circle cx="60" cy="60" [attr.r]="r" fill="none" stroke="var(--border)" stroke-width="14" />
        @for (s of arcs(); track s.label) {
          <circle cx="60" cy="60" [attr.r]="r" fill="none" [attr.stroke]="s.color" stroke-width="14"
                  [attr.stroke-dasharray]="s.dash" [attr.stroke-dashoffset]="s.offset"
                  transform="rotate(-90 60 60)" stroke-linecap="butt">
            <title>{{ s.label }}: {{ s.value }}</title>
          </circle>
        }
        <text x="60" y="57" text-anchor="middle" class="donut-total">{{ total() }}</text>
        <text x="60" y="74" text-anchor="middle" class="donut-caption">{{ caption() }}</text>
      </svg>
      @if (legend()) {
        <ul class="legend">
          @for (d of data(); track d.label) {
            <li [class.muted]="d.value === 0">
              <span class="swatch" [style.background]="d.color"></span>
              <span class="legend-label">{{ d.label }}</span>
              <strong>{{ d.value }}</strong>
            </li>
          }
        </ul>
      }
    </div>`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DonutComponent {
  readonly data = input.required<ChartDatum[]>();
  readonly caption = input<string>('total');
  readonly legend = input<boolean>(true);

  protected readonly r = 48;
  private readonly circumference = 2 * Math.PI * this.r;

  protected readonly total = computed(() => this.data().reduce((acc, d) => acc + d.value, 0));
  protected readonly ariaLabel = computed(() => this.data().map(d => `${d.label}: ${d.value}`).join(', '));

  protected readonly arcs = computed(() => {
    const total = this.total();
    if (total === 0) {
      return [];
    }
    let acc = 0;
    return this.data().filter(d => d.value > 0).map(d => {
      const len = (d.value / total) * this.circumference;
      const arc = { ...d, dash: `${len} ${this.circumference - len}`, offset: -acc };
      acc += len;
      return arc;
    });
  });
}

/** Barras horizontais comparativas. */
@Component({
  selector: 'app-bars',
  template: `
    <ul class="bars">
      @for (d of data(); track d.label) {
        <li>
          <div class="bar-head">
            <span class="bar-label">{{ d.label }}</span>
            <span class="bar-value">{{ d.hint ?? d.value }}</span>
          </div>
          <div class="bar-track"><div class="bar-fill" [style.width.%]="pct(d.value)" [style.background]="d.color"></div></div>
        </li>
      } @empty {
        <li class="muted">Sem dados</li>
      }
    </ul>`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BarsComponent {
  readonly data = input.required<ChartDatum[]>();
  private readonly max = computed(() => Math.max(1, ...this.data().map(d => d.value)));

  protected pct(value: number): number {
    return Math.max(value > 0 ? 2 : 0, (value / this.max()) * 100);
  }
}

/** Barra empilhada compacta (ex.: distribuição de severidades numa linha de tabela). */
@Component({
  selector: 'app-stacked-bar',
  template: `
    <div class="stacked" [attr.title]="title()">
      @for (d of data(); track d.label) {
        @if (d.value > 0) { <span [style.flex-grow]="d.value" [style.background]="d.color"></span> }
      }
      @if (total() === 0) { <span class="empty"></span> }
    </div>`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StackedBarComponent {
  readonly data = input.required<ChartDatum[]>();
  protected readonly total = computed(() => this.data().reduce((a, d) => a + d.value, 0));
  protected readonly title = computed(() => this.data().filter(d => d.value).map(d => `${d.label}: ${d.value}`).join(' · ') || 'Sem achados');
}
