import { Pipe, PipeTransform } from '@angular/core';

export function formatDuration(ms: number | null | undefined): string {
  if (ms == null || ms < 0) {
    return '—';
  }
  if (ms < 1000) {
    return `${Math.round(ms)} ms`;
  }
  const totalSeconds = Math.round(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes === 0) {
    return `${(ms / 1000).toFixed(ms < 10_000 ? 1 : 0)} s`;
  }
  const hours = Math.floor(minutes / 60);
  return hours > 0 ? `${hours} h ${minutes % 60} min` : `${minutes} min ${seconds.toString().padStart(2, '0')} s`;
}

export function formatCompact(value: number | null | undefined): string {
  if (value == null) {
    return '—';
  }
  return new Intl.NumberFormat('pt-BR', { notation: 'compact', maximumFractionDigits: 1 }).format(value);
}

@Pipe({ name: 'duration' })
export class DurationPipe implements PipeTransform {
  transform(ms: number | null | undefined): string {
    return formatDuration(ms);
  }
}

@Pipe({ name: 'compact' })
export class CompactPipe implements PipeTransform {
  transform(value: number | null | undefined): string {
    return formatCompact(value);
  }
}

@Pipe({ name: 'relativeTime' })
export class RelativeTimePipe implements PipeTransform {
  private readonly rtf = new Intl.RelativeTimeFormat('pt-BR', { numeric: 'auto' });

  transform(iso: string | null | undefined): string {
    if (!iso) {
      return '—';
    }
    const diffSeconds = Math.round((new Date(iso).getTime() - Date.now()) / 1000);
    const abs = Math.abs(diffSeconds);
    if (abs < 60) {
      return this.rtf.format(diffSeconds, 'second');
    }
    if (abs < 3600) {
      return this.rtf.format(Math.round(diffSeconds / 60), 'minute');
    }
    if (abs < 86400) {
      return this.rtf.format(Math.round(diffSeconds / 3600), 'hour');
    }
    return this.rtf.format(Math.round(diffSeconds / 86400), 'day');
  }
}
