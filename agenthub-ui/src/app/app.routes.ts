import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    title: 'Nova análise · AgentHub',
    loadComponent: () => import('./pages/new-analysis/new-analysis.component').then(m => m.NewAnalysisComponent),
  },
  {
    path: 'analysis/:id',
    title: 'Análise · AgentHub',
    loadComponent: () => import('./pages/analysis-run/analysis-run.component').then(m => m.AnalysisRunComponent),
  },
  {
    path: 'reports/:reportId',
    title: 'Relatório · AgentHub',
    loadComponent: () => import('./pages/archived-report/archived-report.component').then(m => m.ArchivedReportComponent),
  },
  {
    path: 'diagnostics',
    title: 'Último diagnóstico · AgentHub',
    loadComponent: () => import('./pages/latest-report/latest-report.component').then(m => m.LatestReportComponent),
  },
  {
    path: 'history',
    title: 'Histórico · AgentHub',
    loadComponent: () => import('./pages/history/history.component').then(m => m.HistoryComponent),
  },
  {
    path: 'targets',
    title: 'Projetos · AgentHub',
    loadComponent: () => import('./pages/targets/targets.component').then(m => m.TargetsComponent),
  },
  {
    path: 'project',
    title: 'Discovery & agentes · AgentHub',
    loadComponent: () => import('./pages/project/project.component').then(m => m.ProjectComponent),
  },
  {
    path: 'schemas',
    title: 'JSON Schemas · AgentHub',
    loadComponent: () => import('./pages/schemas/schemas.component').then(m => m.SchemasComponent),
  },
  { path: '**', redirectTo: '' },
];
