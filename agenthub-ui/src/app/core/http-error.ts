import { HttpErrorResponse } from '@angular/common/http';

/** Mensagem legível a partir de erros HTTP (ProblemDetail do Spring ou falha de rede). */
export function describeHttpError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return 'Não foi possível conectar à API do AgentHub (http://localhost:8090). Verifique se o backend está em execução.';
    }
    const body = error.error;
    if (body && typeof body === 'object') {
      const detail = (body as { detail?: string; title?: string }).detail ?? (body as { title?: string }).title;
      if (detail) {
        return detail;
      }
    }
    if (typeof body === 'string' && body.trim().length > 0 && body.length < 300) {
      return body;
    }
    return `Erro ${error.status}${error.statusText ? ' - ' + error.statusText : ''}`;
  }
  return error instanceof Error ? error.message : 'Erro inesperado';
}
