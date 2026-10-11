import { AgentRole, AgentStatus, AnalysisStatus, Severity } from './models';

export interface AgentMeta {
  role: AgentRole;
  id: string;
  label: string;
  icon: string;
  color: string;
  blurb: string;
}

export const AGENTS: AgentMeta[] = [
  { role: 'ARCHITECTURE', id: 'architecture', label: 'Arquitetura', icon: 'layers', color: '#8b5cf6',
    blurb: 'Camadas, acoplamento, padrões e dívida técnica' },
  { role: 'BACKEND', id: 'backend', label: 'Backend', icon: 'server', color: '#3b82f6',
    blurb: 'Spring Boot, APIs REST, serviços e erros' },
  { role: 'FRONTEND', id: 'frontend', label: 'Frontend', icon: 'monitor', color: '#06b6d4',
    blurb: 'Angular, componentes, serviços e RxJS' },
  { role: 'DATABASE', id: 'database', label: 'Banco de dados', icon: 'database', color: '#f59e0b',
    blurb: 'Entidades JPA, repositórios, SQL e migrações' },
  { role: 'SECURITY', id: 'security', label: 'Segurança', icon: 'shield', color: '#ef4444',
    blurb: 'Autenticação, JWT, segredos, CORS e OWASP' },
  { role: 'TEST', id: 'test', label: 'Testes', icon: 'check-circle', color: '#10b981',
    blurb: 'Cobertura, qualidade e lacunas de testes' },
];

export function agentMeta(key: string | null | undefined): AgentMeta | undefined {
  if (!key) {
    return undefined;
  }
  const k = key.toLowerCase();
  return AGENTS.find(a => a.id === k || a.role.toLowerCase() === k);
}

export const SEVERITY_META: Record<Severity, { label: string; color: string; rank: number }> = {
  CRITICAL: { label: 'Crítica', color: 'var(--sev-critical)', rank: 0 },
  HIGH: { label: 'Alta', color: 'var(--sev-high)', rank: 1 },
  MEDIUM: { label: 'Média', color: 'var(--sev-medium)', rank: 2 },
  LOW: { label: 'Baixa', color: 'var(--sev-low)', rank: 3 },
  INFO: { label: 'Info', color: 'var(--sev-info)', rank: 4 },
};

export function severityData(counts: Partial<Record<Severity, number>> | null | undefined) {
  return (Object.keys(SEVERITY_META) as Severity[]).map(s => ({
    label: SEVERITY_META[s].label,
    value: counts?.[s] ?? 0,
    color: SEVERITY_META[s].color,
  }));
}

export const ANALYSIS_STATUS_META: Record<AnalysisStatus, { label: string; tone: string }> = {
  PENDING: { label: 'Na fila', tone: 'neutral' },
  RUNNING: { label: 'Em execução', tone: 'info' },
  COMPLETED: { label: 'Concluída', tone: 'success' },
  FAILED: { label: 'Falhou', tone: 'danger' },
  CANCELLED: { label: 'Cancelada', tone: 'warning' },
};

export const AGENT_STATUS_META: Record<AgentStatus, { label: string; tone: string }> = {
  SUCCESS: { label: 'Sucesso', tone: 'success' },
  FAILED: { label: 'Falhou', tone: 'danger' },
  TIMEOUT: { label: 'Timeout', tone: 'warning' },
  CANCELLED: { label: 'Cancelado', tone: 'neutral' },
};

/** Fases emitidas pelo Orchestrator, na ordem do agentic loop. */
export const PHASES: { key: string; label: string }[] = [
  { key: 'DISCOVERY', label: 'Discovery' },
  { key: 'PLAN', label: 'Planejamento' },
  { key: 'DELEGATE', label: 'Delegação' },
  { key: 'OBSERVE', label: 'Execução' },
  { key: 'EVALUATE', label: 'Avaliação' },
  { key: 'SYNTHESIZE', label: 'Síntese' },
];

export const PROMPT_TEMPLATES: { label: string; prompt: string; agents: AgentRole[] }[] = [
  { label: 'Análise completa', agents: [],
    prompt: 'Faça uma análise completa do projeto: arquitetura, backend, frontend, banco de dados, segurança e testes, com roadmap priorizado.' },
  { label: 'Segurança JWT e segredos', agents: ['SECURITY'],
    prompt: 'Analise a segurança da autenticação JWT, autorização dos endpoints e o tratamento de segredos e configurações sensíveis.' },
  { label: 'Qualidade do backend', agents: ['BACKEND', 'ARCHITECTURE'],
    prompt: 'Avalie a qualidade do backend Spring Boot: organização em camadas, regras de negócio, tratamento de erros, validação e padrões REST.' },
  { label: 'Upgrade do Angular', agents: ['FRONTEND'],
    prompt: 'Avalie o frontend Angular e proponha um plano de atualização para versões recentes, apontando riscos, dependências obsoletas e quebras esperadas.' },
  { label: 'Modelo de dados', agents: ['DATABASE'],
    prompt: 'Revise o modelo de dados: entidades JPA, relacionamentos, consultas, índices, scripts SQL e riscos de desempenho (N+1).' },
  { label: 'Cobertura de testes', agents: ['TEST'],
    prompt: 'Avalie a estratégia e a cobertura de testes do backend e do frontend, apontando lacunas críticas e testes prioritários.' },
];

/**
 * Estimativa de custo em US$ por milhão de tokens (preços públicos do Claude Sonnet).
 * Apenas indicativo: o valor real depende do modelo e do contrato.
 */
export const PRICING = { inputPerMillion: 3, outputPerMillion: 15 };

export function estimateCost(inputTokens: number, outputTokens: number): number {
  return (inputTokens / 1e6) * PRICING.inputPerMillion + (outputTokens / 1e6) * PRICING.outputPerMillion;
}
