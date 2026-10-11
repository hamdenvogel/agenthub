import { Injectable, signal } from '@angular/core';

const KEY = 'agenthub.prompt-mru';
const MAX_ITEMS = 20;

/** Solicitações usadas recentemente (MRU), persistidas no navegador. A mais recente fica no topo. */
@Injectable({ providedIn: 'root' })
export class PromptHistoryStore {
  readonly items = signal<string[]>(this.load());

  add(text: string): void {
    const value = text.trim();
    if (!value) {
      return;
    }
    this.save([value, ...this.items().filter(i => i !== value)].slice(0, MAX_ITEMS));
  }

  remove(text: string): void {
    this.save(this.items().filter(i => i !== text));
  }

  clear(): void {
    this.save([]);
  }

  private save(items: string[]): void {
    this.items.set(items);
    try {
      localStorage.setItem(KEY, JSON.stringify(items));
    } catch {
      // armazenamento indisponível (modo privado/cota): mantém só em memória
    }
  }

  private load(): string[] {
    try {
      const parsed: unknown = JSON.parse(localStorage.getItem(KEY) ?? '[]');
      return Array.isArray(parsed) ? parsed.filter((i): i is string => typeof i === 'string').slice(0, MAX_ITEMS) : [];
    } catch {
      return [];
    }
  }
}
