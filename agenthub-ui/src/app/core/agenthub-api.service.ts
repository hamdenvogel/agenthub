import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  AgentsOverview,
  AnalysisReport,
  AnalysisRequest,
  AnalysisResponse,
  ArchivedReport,
  BrowseResult,
  ProjectContext,
  PromptSuggestion,
  PromptSuggestionRequest,
  RemediationConsent,
  RemediationProposal,
  SchemaCatalog,
  StackInfo,
  TargetInput,
  TargetOverview,
  TargetStatus,
} from './models';

/** Cliente da API REST do AgentHub (em desenvolvimento, /api é encaminhado pelo proxy do ng serve). */
@Injectable({ providedIn: 'root' })
export class AgentHubApi {
  private readonly http = inject(HttpClient);
  private readonly base = '/api';

  startAnalysis(body: AnalysisRequest): Observable<AnalysisResponse> {
    return this.http.post<AnalysisResponse>(`${this.base}/analysis`, body);
  }

  suggestPrompts(body: PromptSuggestionRequest): Observable<PromptSuggestion[]> {
    return this.http.post<PromptSuggestion[]>(`${this.base}/prompt-suggestions`, body);
  }

  listAnalyses(): Observable<AnalysisResponse[]> {
    return this.http.get<AnalysisResponse[]>(`${this.base}/analysis`);
  }

  analysis(id: string): Observable<AnalysisResponse> {
    return this.http.get<AnalysisResponse>(`${this.base}/analysis/${encodeURIComponent(id)}`);
  }

  report(id: string): Observable<AnalysisReport> {
    return this.http.get<AnalysisReport>(`${this.base}/analysis/${encodeURIComponent(id)}/report`);
  }

  reportMarkdown(id: string): Observable<string> {
    return this.http.get(`${this.base}/analysis/${encodeURIComponent(id)}/report.md`, { responseType: 'text' });
  }

  cancel(id: string): Observable<AnalysisResponse> {
    return this.http.delete<AnalysisResponse>(`${this.base}/analysis/${encodeURIComponent(id)}`);
  }

  archivedReports(): Observable<ArchivedReport[]> {
    return this.http.get<ArchivedReport[]>(`${this.base}/reports`);
  }

  archivedReport(reportId: string): Observable<AnalysisReport> {
    return this.http.get<AnalysisReport>(`${this.base}/reports/${encodeURIComponent(reportId)}`);
  }

  archivedReportMarkdown(reportId: string): Observable<string> {
    return this.http.get(`${this.base}/reports/${encodeURIComponent(reportId)}/report.md`, { responseType: 'text' });
  }

  agents(): Observable<AgentsOverview> {
    return this.http.get<AgentsOverview>(`${this.base}/agents`);
  }

  about(): Observable<StackInfo> {
    return this.http.get<StackInfo>(`${this.base}/about`);
  }

  schemas(): Observable<SchemaCatalog> {
    return this.http.get<SchemaCatalog>(`${this.base}/schemas`);
  }

  project(): Observable<ProjectContext> {
    return this.http.get<ProjectContext>(`${this.base}/project`);
  }

  targets(): Observable<TargetOverview> {
    return this.http.get<TargetOverview>(`${this.base}/targets`);
  }

  createTarget(input: TargetInput, activate: boolean): Observable<TargetStatus> {
    return this.http.post<TargetStatus>(`${this.base}/targets`, input, { params: { activate } });
  }

  updateTarget(id: string, input: TargetInput): Observable<TargetStatus> {
    return this.http.put<TargetStatus>(`${this.base}/targets/${encodeURIComponent(id)}`, input);
  }

  deleteTarget(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/targets/${encodeURIComponent(id)}`);
  }

  activateTarget(id: string): Observable<TargetStatus> {
    return this.http.post<TargetStatus>(`${this.base}/targets/${encodeURIComponent(id)}/activate`, null);
  }

  browse(path?: string | null): Observable<BrowseResult> {
    return this.http.get<BrowseResult>(`${this.base}/targets/browse`, { params: path ? { path } : {} });
  }

  remediationConsent(): Observable<RemediationConsent> {
    return this.http.get<RemediationConsent>(`${this.base}/remediation/consent`);
  }

  acceptRemediationConsent(): Observable<RemediationConsent> {
    return this.http.post<RemediationConsent>(`${this.base}/remediation/consent`, null);
  }

  revokeRemediationConsent(): Observable<RemediationConsent> {
    return this.http.delete<RemediationConsent>(`${this.base}/remediation/consent`);
  }

  remediations(analysisId: string): Observable<RemediationProposal[]> {
    return this.http.get<RemediationProposal[]>(`${this.base}/remediation/proposals`, { params: { analysisId } });
  }

  remediation(id: string): Observable<RemediationProposal> {
    return this.http.get<RemediationProposal>(`${this.base}/remediation/proposals/${encodeURIComponent(id)}`);
  }

  proposeRemediation(analysisId: string, findingId: string): Observable<RemediationProposal> {
    return this.http.post<RemediationProposal>(`${this.base}/remediation/proposals`, { analysisId, findingId });
  }

  applyRemediation(id: string): Observable<RemediationProposal> {
    return this.http.post<RemediationProposal>(`${this.base}/remediation/proposals/${encodeURIComponent(id)}/apply`, { confirm: true });
  }

  revertRemediation(id: string): Observable<RemediationProposal> {
    return this.http.post<RemediationProposal>(`${this.base}/remediation/proposals/${encodeURIComponent(id)}/revert`, null);
  }

  discardRemediation(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/remediation/proposals/${encodeURIComponent(id)}`);
  }
}
