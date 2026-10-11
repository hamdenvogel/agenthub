import { Injectable, signal } from '@angular/core';

export interface Toast {
  id: number;
  tone: 'success' | 'danger' | 'info';
  message: string;
}

@Injectable({ providedIn: 'root' })
export class ToastService {
  private seq = 0;
  readonly toasts = signal<Toast[]>([]);

  success(message: string): void {
    this.push('success', message);
  }

  error(message: string): void {
    this.push('danger', message, 7000);
  }

  info(message: string): void {
    this.push('info', message);
  }

  dismiss(id: number): void {
    this.toasts.update(list => list.filter(t => t.id !== id));
  }

  private push(tone: Toast['tone'], message: string, ttl = 3500): void {
    const id = ++this.seq;
    this.toasts.update(list => [...list.slice(-3), { id, tone, message }]);
    setTimeout(() => this.dismiss(id), ttl);
  }
}
