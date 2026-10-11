# AgentHub UI

Interface web do AgentHub (Angular 19.2, standalone components + signals, sem bibliotecas de UI/gráficos).

## Executar

Requer Node 20.12+ e o backend AgentHub em `http://localhost:8090`.

```powershell
& "C:\Program Files\nodejs\npm.cmd" ci
& "C:\Program Files\nodejs\npx.cmd" ng serve      # http://localhost:4200 (proxy /api em proxy.conf.json)
& "C:\Program Files\nodejs\npx.cmd" ng build      # produção em dist/
& "C:\Program Files\nodejs\npx.cmd" ng test --watch=false --browsers=ChromeHeadless
```

## Estrutura

```text
src/app
├── core/     Modelos da API, serviço HTTP, store de execuções, catálogo, exportação, tema, toasts, pipes
├── shared/   Ícones, badges e gráficos SVG
├── report/   Visualizador de relatório (visão geral, achados, áreas, roadmap, desempenho, exportação)
└── pages/    Nova análise, execução ao vivo, relatório arquivado, histórico, projeto
```
