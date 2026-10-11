export type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO';
export type AgentStatus = 'SUCCESS' | 'FAILED' | 'TIMEOUT' | 'CANCELLED';
export type AnalysisStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type AgentRole = 'ARCHITECTURE' | 'BACKEND' | 'FRONTEND' | 'DATABASE' | 'SECURITY' | 'TEST';

export const SEVERITIES: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'];
export const AGENT_ROLES: AgentRole[] = ['ARCHITECTURE', 'BACKEND', 'FRONTEND', 'DATABASE', 'SECURITY', 'TEST'];

export interface AnalysisRequest {
  request: string;
  agents?: AgentRole[];
}

export interface StackComponent {
  id: string;
  name: string;
  version: string | null;
  detail: string | null;
}

export interface StackInfo {
  backend: StackComponent[];
  runtime: StackComponent[];
  generatedAt: string;
}

export interface PromptSuggestionRequest {
  agents?: AgentRole[];
  draft?: string;
}

export interface PromptSuggestion {
  title: string;
  prompt: string;
  agents: AgentRole[];
  rationale: string | null;
}

export interface ProgressEvent {
  at: string;
  phase: string;
  message: string;
}

export interface AnalysisResponse {
  analysisId: string;
  status: AnalysisStatus;
  request: string;
  requestedAgents: AgentRole[] | null;
  projectName: string | null;
  projectRoot: string | null;
  scope: string[];
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  error: string | null;
  reportAvailable: boolean;
  reportFile: string | null;
  events: ProgressEvent[];
}

export interface Evidence {
  file: string | null;
  lines: string | null;
  excerpt: string | null;
}

export interface Finding {
  id: string;
  title: string;
  description: string | null;
  severity: Severity;
  category: string | null;
  evidence: Evidence[];
  recommendation: string | null;
  agent: AgentRole | null;
}

export interface ReportSection {
  title: string;
  analyzed: boolean;
  status: AgentStatus | null;
  summary: string | null;
  findings: Finding[];
  confidence: number;
}

export interface RoadmapItem {
  phase: string;
  priority: Severity | null;
  action: string;
  rationale: string | null;
  relatedFindings: string[];
}

export interface AgentMetrics {
  agent: string;
  name: string;
  status: AgentStatus;
  round: number;
  attempts: number;
  durationMs: number;
  llmDurationMs: number;
  inputTokens: number;
  outputTokens: number;
  toolCalls: number;
  toolCallsBySource: Record<string, number>;
  findings: number;
  confidence: number;
  model: string | null;
  error: string | null;
  /** Limites da chamada (ausentes em relatórios antigos): max_tokens, context window e JSON Schema. */
  maxOutputTokens?: number;
  stopReason?: string | null;
  contextBudgetTokens?: number;
  contextPeakTokens?: number;
  contextExhausted?: boolean;
  toolInputRejected?: number;
  schemaViolations?: string[];
}

export interface ReportMetadata {
  iterations: number;
  durationMs: number;
  plannerSource: string;
  synthesisSource: string;
  agents: Record<string, AgentStatus>;
  findingsBySeverity: Partial<Record<Severity, number>>;
  limitations: string[];
  usage: Record<string, number>;
  agentMetrics?: AgentMetrics[];
  projectRoot?: string | null;
  scope?: string[];
}

export interface AnalysisReport {
  analysisId: string;
  request: string;
  projectName: string;
  generatedAt: string;
  executiveSummary: string | null;
  architecture: ReportSection | null;
  backend: ReportSection | null;
  frontend: ReportSection | null;
  database: ReportSection | null;
  security: ReportSection | null;
  tests: ReportSection | null;
  codeQuality: ReportSection | null;
  findings: Finding[];
  recommendations: string[];
  roadmap: RoadmapItem[];
  metadata: ReportMetadata | null;
  /** Ausente em relatórios gerados antes da consolidação existir. */
  consolidation?: HubConsolidation | null;
}

/** JSON Schema (draft 2020-12) como objeto. */
export type JsonSchema = Record<string, unknown>;

export interface StructuredOutputSchema {
  name: string;
  usedBy: string;
  purpose: string;
  /** A saída é entregue ao agrupador (Hub). */
  feedsHub: boolean;
  schema: JsonSchema;
}

export interface ToolSchema {
  name: string;
  description: string;
  inputSchema: JsonSchema;
}

export interface ToolSourceSchemas {
  id: string;
  type: string;
  connection: string | null;
  enabled: boolean;
  available: boolean;
  detail: string | null;
  agents: string[];
  tools: ToolSchema[];
  /** Publicadas pela fonte e não expostas aos agentes (fora da allowlist ou de escrita). */
  blockedTools: string[];
}

export interface SchemaCatalog {
  generatedAt: string;
  structuredOutputs: StructuredOutputSchema[];
  toolInputs: ToolSourceSchemas[];
}

/** O que cada agente entregou ao agrupador (Hub) e o destino de cada item. */
export interface HubConsolidation {
  totals: ConsolidationTotals;
  agents: AgentOutput[];
  items: ConsolidationItem[];
}

export interface ConsolidationTotals {
  agents: number;
  succeeded: number;
  failed: number;
  findingsReceived: number;
  findingsKept: number;
  findingsMerged: number;
  recommendationsReceived: number;
  recommendationsKept: number;
  evidenceFiles: number;
}

export interface AgentOutput {
  agent: string;
  name: string;
  status: AgentStatus;
  round: number;
  /** Record do Structured Output entregue ao Hub; null quando o agente não concluiu. */
  outputSchema: string | null;
  summary: string | null;
  confidence: number;
  severity: Severity;
  findingsReceived: number;
  findingsKept: number;
  findingsMerged: number;
  recommendations: number;
  evidenceFiles: number;
  schemaViolations: number;
  error: string | null;
}

export type ConsolidationItemType = 'FINDING' | 'RECOMMENDATION';
export type ConsolidationOutcome = 'KEPT' | 'DUPLICATE';

export interface ConsolidationItem {
  type: ConsolidationItemType;
  agent: string;
  agentName: string;
  severity: Severity | null;
  title: string;
  category: string | null;
  file: string | null;
  outcome: ConsolidationOutcome;
  /** Id no relatório; para achados duplicados, o id do achado que o absorveu. */
  findingId: string | null;
}

export interface ArchivedReport {
  reportId: string;
  analysisId: string;
  request: string;
  projectName: string;
  generatedAt: string;
  durationMs: number;
  findings: number;
  findingsBySeverity: Partial<Record<Severity, number>>;
  agents: Record<string, AgentStatus>;
  projectRoot?: string | null;
  scope?: string[];
}

/** Projeto-alvo cadastrado (gravado pelo backend em data/targets.json). */
export interface TargetStatus {
  id: string;
  name: string;
  root: string;
  includes: string[];
  createdAt: string;
  updatedAt: string;
  active: boolean;
  available: boolean;
  problem: string | null;
  /** Banco do projeto com a senha trocada por ***; null = projeto sem banco. */
  databaseUri: string | null;
}

export interface TargetOverview {
  activeId: string | null;
  targets: TargetStatus[];
  allowedRoots: string[];
  runningAnalyses: number;
  storeFile: string;
}

export interface TargetInput {
  name: string;
  root: string;
  includes: string[];
  /** Omitido mantém o banco atual; vazio remove; a versão mascarada recebida mantém a senha gravada. */
  databaseUri?: string;
}

export interface BrowseEntry {
  name: string;
  path: string;
  directory: boolean;
  markers: string[];
}

export interface BrowseResult {
  path: string | null;
  parent: string | null;
  entries: BrowseEntry[];
  truncated: boolean;
}

/** Correção assistida: consentimento por projeto (data/remediations/consents.json). */
export interface RemediationConsent {
  enabled: boolean;
  accepted: boolean;
  acceptedAt: string | null;
  projectName: string;
  projectRoot: string;
  termsVersion: number;
  terms: string[];
}

export type RemediationStatus = 'GENERATING' | 'READY' | 'MANUAL_ONLY' | 'FAILED' | 'APPLIED' | 'REVERTED';

export interface ProposedFile {
  path: string;
  created: boolean;
  explanation: string | null;
  diff: string;
  additions: number;
  deletions: number;
}

export interface RemediationProposal {
  id: string;
  analysisId: string;
  findingId: string;
  findingTitle: string;
  severity: Severity;
  projectRoot: string;
  status: RemediationStatus;
  createdAt: string;
  updatedAt: string;
  summary: string | null;
  files: ProposedFile[];
  manualSteps: string[];
  risks: string[];
  confidence: number;
  error: string | null;
  model: string | null;
  inputTokens: number;
  outputTokens: number;
  toolCalls: number;
}

export interface AgentInfo {
  id: string;
  name: string;
  description: string;
  toolSources: string[];
}

export interface ToolSourceStatus {
  id: string;
  type: string;
  connection: string | null;
  enabled: boolean;
  available: boolean;
  tools: string[];
  detail: string | null;
}

export interface AgentsOverview {
  agents: AgentInfo[];
  toolSources: ToolSourceStatus[];
}

export interface BackendModule {
  path: string;
  buildTool: string;
  artifactId: string;
  javaVersion: string | null;
  springBootVersion: string | null;
  dependencies: string[];
  artifacts: Record<string, string[]>;
}

export interface FrontendModule {
  path: string;
  angularVersion: string | null;
  typescriptVersion: string | null;
  rxjsVersion: string | null;
  packageManager: string | null;
  usesSignals: boolean;
  stateManagement: string | null;
  dependencies: string[];
  artifacts: Record<string, string[]>;
}

export interface Infrastructure {
  docker: string[];
  ciCd: string[];
  kubernetes: string[];
  cloud: string[];
  databases: string[];
  migrations: string[];
  sqlScripts: string[];
  configFiles: string[];
  observability: string[];
}

export interface ProjectContext {
  root: string;
  name: string;
  scope: string[];
  backendModules: BackendModule[];
  frontendModules: FrontendModule[];
  infrastructure: Infrastructure;
  gitRepositories: string[];
  totalFiles: number;
}
