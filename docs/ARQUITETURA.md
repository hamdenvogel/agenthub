# AgentHub — Documento de Arquitetura


|                  |                                                                                                                                                                                            |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Sistema**      | AgentHub — orquestração multiagente para análise e correção assistida de código                                                                                                            |
| **Versão**       | 0.1.0-SNAPSHOT                                                                                                                                                                             |
| **Stack**        | Java 25 · Spring Boot 4.1 · Spring AI 2.0 · MCP Java SDK 2.0 · Jackson 3 · Angular 19.2                                                                                                    |
| **Modelo de IA** | Claude (Anthropic), padrão `claude-sonnet-4-5`                                                                                                                                             |
| **Referências**  | `[README.md](../README.md)` (operação) · `[Projeto_Orquestracao_Multiagente_Claude_SpringAI_MCP.docx](Projeto_Orquestracao_Multiagente_Claude_SpringAI_MCP.docx)` (especificação original) |


Estrutura baseada no [arc42](https://arc42.org) e diagramas no estilo [C4](https://c4model.com) (contexto, contêineres e
componentes).

---



## Sumário

- [1. Introdução e objetivos](#1-introdução-e-objetivos)
- [2. Restrições](#2-restrições)
- [3. Contexto do sistema (C4 nível 1)](#3-contexto-do-sistema-c4-nível-1)
- [4. Estratégia da solução](#4-estratégia-da-solução)
- [5. Visão de blocos](#5-visão-de-blocos)
- [6. Visão de execução](#6-visão-de-execução)
- [7. Visão de implantação](#7-visão-de-implantação)
- [8. Conceitos transversais](#8-conceitos-transversais)
- [9. Decisões de arquitetura (ADRs resumidos)](#9-decisões-de-arquitetura-adrs-resumidos)
- [10. Cenários de qualidade](#10-cenários-de-qualidade)
- [11. Riscos e dívidas técnicas](#11-riscos-e-dívidas-técnicas)
- [12. Glossário](#12-glossário)
- [13. MCP (Model Context Protocol)](#13-mcp-model-context-protocol)
  - [13.1 Como o AgentHub usa o MCP](#131-como-o-agenthub-usa-o-mcp)
  - [13.2 Topologia agentes × fontes de ferramentas](#132-topologia-agentes--fontes-de-ferramentas)
  - [13.3 Filesystem MCP (fontes `filesystem` e `code-search`)](#133-filesystem-mcp-fontes-filesystem-e-code-search)
  - [13.4 Git MCP (fonte `git`)](#134-git-mcp-fonte-git)
  - [13.5 PostgreSQL MCP (fonte `database`)](#135-postgresql-mcp-fonte-database)
  - [13.6 SonarQube MCP (fonte `sonarqube`, desabilitada)](#136-sonarqube-mcp-fonte-sonarqube-desabilitada)
  - [13.7 Adicionar ou habilitar um servidor MCP](#137-adicionar-ou-habilitar-um-servidor-mcp)
- [14. JSON Schema](#14-json-schema)
  - [14.1 Conceitos: Tool Input × Structured Output](#141-conceitos-tool-input--structured-output)
  - [14.2 Visão geral](#142-visão-geral)
  - [14.3 Matriz: quem usa quais schemas](#143-matriz-quem-usa-quais-schemas)
  - [14.4 Structured Outputs](#144-structured-outputs)
  - [14.5 Tool Input Schemas](#145-tool-input-schemas)
  - [14.6 Ciclo de vida de um schema numa chamada](#146-ciclo-de-vida-de-um-schema-numa-chamada)
  - [14.7 Manutenção](#147-manutenção)

---

## 1. Introdução e objetivos

O AgentHub analisa projetos de software (backend Spring Boot, frontend Angular, banco, segurança e testes) com uma
equipe de agentes especialistas baseados no Claude, coordenados por um Hub central. O resultado é um relatório
estruturado com achados fundamentados em evidências (arquivo, linhas e trecho). Opcionalmente, e só com o
consentimento do usuário, o AgentHub propõe e aplica correções para os achados.

### 1.1 Requisitos principais


| ID    | Requisito                                                                                             |
| ----- | ----------------------------------------------------------------------------------------------------- |
| RF-01 | Receber uma solicitação em linguagem natural e analisar o projeto ativo.                              |
| RF-02 | Planejar quais especialistas executar e em que ordem (dependências).                                  |
| RF-03 | Executar especialistas em paralelo, com acesso a ferramentas de leitura do código (filesystem e Git). |
| RF-04 | Avaliar a qualidade de cada resultado e re-delegar quando insuficiente.                               |
| RF-05 | Consolidar achados (deduplicação, severidade, IDs) e gerar relatório JSON e Markdown.                 |
| RF-06 | Permitir cadastrar e trocar o projeto analisado em tempo de execução, com escopo opcional.            |
| RF-07 | Acompanhar a análise ao vivo, consultar histórico, filtrar e exportar achados (UI web).               |
| RF-08 | Propor correções por achado e aplicá-las somente após consentimento e revisão do diff.                |




### 1.2 Objetivos de qualidade


| Prioridade | Atributo                       | Cenário                                                                                                                                           |
| ---------- | ------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1          | **Segurança**                  | Nenhum agente consegue ler fora do projeto ativo, ler arquivos sensíveis, ver segredos em claro ou gravar no projeto.                             |
| 2          | **Confiabilidade dos achados** | Todo achado relevante tem evidência verificável; o Hub rejeita resultados sem evidência ou com baixa confiança.                                   |
| 3          | **Resiliência**                | Falha do Claude, de um agente ou de um servidor MCP não derruba a análise: há retry, timeout e fallback, e a falha vira "limitação" no relatório. |
| 4          | **Custo controlado**           | Orçamento de chamadas de ferramenta por agente, contexto seletivo por papel e paralelismo limitado.                                               |
| 5          | **Extensibilidade**            | Adicionar um agente ou uma fonte MCP exige só configuração, um prompt e uma classe pequena.                                                       |




### 1.3 Partes interessadas


| Papel                     | Interesse                                                                                                      |
| ------------------------- | -------------------------------------------------------------------------------------------------------------- |
| Desenvolvedor / tech lead | Diagnóstico rápido do projeto e correção assistida dos achados.                                                |
| Segurança                 | Garantia de que segredos não vazam para o modelo nem para logs e de que o projeto não é alterado sem controle. |
| Mantenedor do AgentHub    | Código modular, testável e fácil de estender.                                                                  |


---



## 2. Restrições


| Tipo           | Restrição                                                                                                              |
| -------------- | ---------------------------------------------------------------------------------------------------------------------- |
| Técnica        | Java 25, Spring Boot 4.1, Spring AI 2.0 (MCP Client), Jackson 3.                                                       |
| Técnica        | UI em Angular 19.2: última versão compatível com o Node 20.12 instalado (Angular 20+ exige Node ≥ 20.19).              |
| Segurança      | A chave da API existe só na variável de ambiente `ANTHROPIC_API_KEY`; nunca em código, configuração versionada ou log. |
| Segurança      | Projetos analisáveis apenas dentro de `C:\Hamden\Sistemas` (`agenthub.targets.allowed-roots`).                         |
| Segurança      | Agentes de análise são somente leitura; a escrita existe só no fluxo de correção assistida, com consentimento.         |
| Organizacional | Sem banco de dados: estado em memória e arquivos JSON locais (`data/`, `reports/`, ambos no `.gitignore`).             |
| Organizacional | Não registrar servidores MCP "falsos": fontes sem infraestrutura real ficam declaradas e desabilitadas.                |


---



## 3. Contexto do sistema (C4 nível 1)

```mermaid
flowchart LR
    DEV([Desenvolvedor]) -->|navegador| AH[AgentHub]
    AH -->|HTTPS · Messages API| CLAUDE[(Anthropic Claude)]
    AH -->|leitura e, com consentimento, escrita| PRJ[(Projeto analisado<br/>C:\Hamden\Sistemas\...)]
    AH -->|stdio · MCP| GIT[mcp-server-git<br/>via uvx]
    AH -->|stdio · MCP + Roots| FSS[server-filesystem<br/>via npx]
    GIT -->|leitura| PRJ
    FSS -->|leitura| PRJ
    AH -->|stdio · MCP| PGM[postgres-mcp<br/>via uvx · restrito]
    PGM -->|leitura| DB[(PostgreSQL 16<br/>meusservicos :5435)]
    AH -.desabilitado.-> SQ[(SonarQube<br/>MCP)]
```




| Sistema externo               | Interface                                             | Uso                                                                           |
| ----------------------------- | ----------------------------------------------------- | ----------------------------------------------------------------------------- |
| Anthropic Claude              | HTTPS (Spring AI `spring-ai-starter-model-anthropic`) | Planejamento, análise pelos especialistas, síntese e geração de correções.    |
| Projeto analisado             | Sistema de arquivos local                             | Fonte da análise; destino das correções aprovadas.                            |
| `mcp-server-git`              | MCP sobre stdio (processo filho iniciado com `uvx`)   | Status, log, diffs e branches do repositório.                                 |
| `server-filesystem` (oficial) | MCP sobre stdio (processo filho iniciado com `npx`)   | Listar, ler e buscar arquivos por nome no projeto ativo.                      |
| PostgreSQL MCP (`postgres-mcp`) | MCP sobre stdio (processo filho iniciado com `uvx`), modo restrito | Schemas, tabelas, índices, EXPLAIN e saúde do banco do projeto, somente leitura (detalhes na §13.5). |
| SonarQube                     | MCP (declarado, desabilitado)                         | Ponto de extensão para quando houver infraestrutura e token.                  |


---



## 4. Estratégia da solução


| Decisão                                                                                    | Motivação                                                                                      |
| ------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------- |
| **Hub-and-spoke**: um Orchestrator central e especialistas que não conversam entre si.     | Controle, rastreabilidade e custo previsíveis; dependências viram contexto repassado pelo Hub. |
| **Loop agêntico** com avaliação objetiva e re-delegação limitada.                          | Qualidade sem custo extra de um "LLM juiz"; critérios auditáveis.                              |
| **Discovery determinístico** antes de qualquer chamada ao modelo.                          | Barato e reprodutível; alimenta o Planner e os *briefs* de cada agente.                        |
| **Least privilege por agente**: fontes e ferramentas por allowlist, orçamento de chamadas. | Limita o raio de ação de cada agente e o custo.                                                |
| **Saída estruturada** (`responseEntity(Class)`) em records Java.                           | Resultado validável e agregável, sem parsing de texto livre.                                   |
| **API assíncrona** (202 + polling).                                                        | Análises completas levam minutos.                                                              |
| **Fallbacks por regras** no Planner e no Synthesizer.                                      | A análise termina mesmo se uma chamada de planejamento ou síntese falhar.                      |
| **Correção assistida** separada da análise, com consentimento, diff e confirmação.         | Mantém a análise somente leitura e coloca o humano no controle de toda escrita.                |


---



## 5. Visão de blocos



### 5.1 Contêineres (C4 nível 2)

```mermaid
flowchart LR
    subgraph Browser
        UI[agenthub-ui<br/>Angular 19.2 SPA<br/>:4200 em dev]
    end
    subgraph JVM[agenthub · Spring Boot 4.1 · :8090]
        API[API REST<br/>/api/*]
        CORE[Hub / Orchestrator<br/>+ agentes]
        REM[Correção assistida]
        TOOLS[Ferramentas<br/>MCP Client + busca local]
    end
    FILES[(data/ e reports/<br/>JSON + Markdown)]
    UI -->|HTTP JSON · proxy /api| API
    API --> CORE
    API --> REM
    CORE --> TOOLS
    REM --> TOOLS
    CORE --> FILES
    REM --> FILES
    TOOLS --> GITP[mcp-server-git<br/>processo filho]
    TOOLS --> FSP[server-filesystem<br/>processo filho]
    GITP & FSP --> PRJ[(projeto ativo<br/>somente leitura)]
    CORE & REM --> CL[(Claude API)]
```




| Contêiner           | Tecnologia                                                      | Responsabilidade                                                                                                         |
| ------------------- | --------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| `agenthub-ui`       | Angular 19.2, standalone components, signals, OnPush            | Nova análise, execução ao vivo, relatório, achados (grade/cartões), exportação, correção assistida, projetos, histórico. |
| `agenthub`          | Spring Boot 4.1 (WebMVC), virtual threads                       | API REST, orquestração, agentes, ferramentas, persistência em arquivos.                                                  |
| `data/`             | JSON (escrita atômica)                                          | `targets.json` (projetos, ativo e URI do banco de cada um) e `remediations/` (propostas, consentimentos, backups).                                |
| `reports/`          | JSON + Markdown                                                 | Relatórios arquivados de cada análise concluída.                                                                         |
| `mcp-server-git`    | Python (`uvx`), MCP stdio                                       | Ferramentas Git somente leitura.                                                                                         |
| `server-filesystem` | Node (`npx @modelcontextprotocol/server-filesystem`), MCP stdio | Listar e ler arquivos do projeto ativo (raiz via MCP Roots); escrita bloqueada pelo Hub. Encerrado junto com o backend.  |




### 5.2 Componentes do backend (C4 nível 3)

```mermaid
flowchart TB
    subgraph api
        AC[AnalysisController]
        RC[ReportController]
        TC[TargetController]
        AGC[AgentController]
        RMC[RemediationController]
        EH[ApiExceptionHandler]
    end
    subgraph analysis
        AS[AnalysisService]
        AR[AnalysisRepository<br/>em memória, até 50]
        RW[ReportWriter + MarkdownReportRenderer]
        RA[ReportArchive]
    end
    subgraph orchestrator
        O[Orchestrator]
        PL[Planner]
        TD[TaskDelegator]
        EV[Evaluator]
        AG[ResultAggregator]
        SY[ReportSynthesizer]
    end
    subgraph agents
        REG[AgentRegistry]
        ABS[AbstractClaudeAgent]
        SPEC[Architecture · Backend · Frontend<br/>Database · Security · Test]
    end
    subgraph context
        PD[ProjectDiscoveryService]
        CS[ContextSelector]
        PW[ProjectWorkspace<br/>sandbox]
        SM[SecretMasker]
    end
    subgraph mcp
        TR[McpToolRegistry]
        GT[GuardedToolCallback]
        LTS[LocalToolSources]
        MCT[McpConnectionTools]
    end
    subgraph tools
        FST[ProjectFilesystemTools<br/>fs_search_text + reserva]
    end
    FSMCP[[server-filesystem oficial<br/>MCP stdio · Roots]]
    GITMCP[[mcp-server-git<br/>MCP stdio]]
    subgraph claude
        CSV[ClaudeService]
    end
    subgraph target
        TS[TargetService]
        TST[TargetStore]
    end
    subgraph remediation
        RS[RemediationService]
        RST[RemediationStore]
        EA[EditApplier]
        LD[LineDiff]
    end

    AC --> AS --> O
    AS --> AR
    AS --> RW
    RC --> RA
    TC --> TS --> TST
    TS --> PW
    O --> PD & PL & TD & EV & AG & SY
    PL & SY --> CSV
    TD --> REG --> SPEC
    SPEC -.extends.-> ABS
    ABS --> CSV
    ABS --> TR
    TD --> CS
    TR --> LTS --> FST --> PW
    TR --> MCT --> FSMCP & GITMCP
    MCT -.raiz do projeto ativo.-> PW
    TR --> GT
    GT --> SM
    GT -.caminhos.-> PW
    RMC --> RS
    RS --> RST & EA & LD & CSV & PW
    RS --> TR
```





### 5.3 Pacotes


| Pacote                 | Responsabilidade                                                                                                                                                                             |
| ---------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `api`                  | Controllers REST, DTOs (`AnalysisRequest`, `AnalysisResponse`, `AgentInfo`) e `ApiExceptionHandler` (ProblemDetail).                                                                         |
| `analysis`             | Ciclo de vida da análise (`AnalysisRun`, `AnalysisStatus`), repositório em memória, gravação e leitura de relatórios.                                                                        |
| `orchestrator`         | Hub: `Orchestrator`, `Planner`, `TaskDelegator`, `Evaluator`, `ResultAggregator`, `ReportSynthesizer`, cancelamento e progresso.                                                             |
| `agents`               | Contrato `Agent`, `AbstractClaudeAgent`, os 6 especialistas, `AgentRegistry` e carregamento de prompts.                                                                                      |
| `claude`               | `ClaudeService` (ChatClient + saída estruturada + métricas de tokens), conversor tolerante de JSON.                                                                                          |
| `context`              | Discovery da stack, *brief* por agente, `ProjectWorkspace` (sandbox) e `SecretMasker`.                                                                                                       |
| `mcp`                  | `McpToolRegistry` (ferramentas por agente), `GuardedToolCallback`, `ToolBudget`, conexões MCP sob demanda.                                                                                   |
| `tools`                | `ProjectFilesystemTools`: `fs_search_text` (fonte `code-search`, busca de conteúdo) e a reserva `filesystem-local` (`fs_list_directory`, `fs_read_file`, `fs_search_text`, `fs_find_files`). |
| `target`               | Projetos cadastrados, projeto ativo, seletor de pastas (`data/targets.json`).                                                                                                                |
| `remediation`          | Correção assistida: consentimento, propostas, aplicação de edições, diff, backup e reversão.                                                                                                 |
| `model`                | Records de domínio: `AgentRole`, `AgentTask`, `AgentResult`, `Finding`, `Evidence`, `AnalysisPlan`, `AnalysisReport`, ...                                                                    |
| `config` / `exception` | Propriedades `agenthub.*` (records `@ConfigurationProperties`), executor de virtual threads e exceções de domínio.                                                                           |




### 5.4 Componentes do frontend


| Camada    | Itens                                                                                                                                                            |
| --------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `core/`   | `AgentHubApi` (cliente HTTP), stores com signals (`RunsStore`, `TargetsStore`, `RemediationStore`), `ExportService` (CSV/JSON/Markdown), modelos, tema e toasts. |
| `pages/`  | `new-analysis`, `analysis-run` (ao vivo), `archived-report`, `latest-report` (atalho `/diagnostics`), `history`, `targets`, `project`.                           |
| `report/` | `report-viewer` (abas e filtros), `findings-grid` (ordenação, paginação, seleção, exportação), `remediation-panel` (consentimento, diff, aplicar/desfazer).      |
| `shared/` | Ícones, badges, gráficos, seletor de pastas.                                                                                                                     |


As rotas são *lazy-loaded*; o estado de execução é atualizado por polling (3 s com análise rodando, 30 s em repouso).

### 5.5 Fontes de ferramentas

Os agentes leem código, histórico Git e o banco do projeto por **fontes de ferramentas** — a maioria servidores MCP
rodando como processos filhos. A topologia agentes × fontes, cada servidor e suas proteções estão na **§13 (MCP)**; o
formato dos argumentos de cada ferramenta e das respostas do Claude, na **§14 (JSON Schema)**.

---



## 6. Visão de execução



### 6.1 Análise (loop agêntico)

```mermaid
sequenceDiagram
    autonumber
    actor U as Usuário (UI)
    participant API as AnalysisController
    participant S as AnalysisService
    participant O as Orchestrator
    participant P as Planner
    participant D as TaskDelegator
    participant A as Agente especialista
    participant C as Claude
    participant T as Ferramentas (fs / git MCP)
    participant E as Evaluator

    U->>API: POST /api/analysis {request, agents?}
    API->>S: start()
    S-->>U: 202 Accepted + Location
    S->>O: run() em virtual thread
    O->>O: DISCOVERY (determinístico)
    O->>P: PLAN
    P->>C: planner.md + discovery
    C-->>P: tarefas + dependências (fallback: regras)
    loop até suficiente, max-iterations (2), timeout (30 min) ou cancelamento
        O->>D: DELEGATE (ondas por dependência, até 3 em paralelo)
        D->>A: execute(tarefa, brief, resultados anteriores)
        A->>C: prompt do papel + ferramentas permitidas
        loop até o orçamento de chamadas
            C->>T: tool call
            T-->>C: resultado (sandbox, mascarado, truncado)
        end
        C-->>A: AgentAnalysis (JSON estruturado)
        A-->>D: AgentResult
        O->>E: EVALUATE
        E-->>O: suficiente ou re-delegar com feedback
    end
    O->>O: AGGREGATE (dedup, severidade, IDs SEC-001...)
    O->>C: SYNTHESIZE (sumário, code quality, roadmap)
    O-->>S: AnalysisReport
    S->>S: grava reports/<data>-<id>.json e .md
    U->>API: GET /api/analysis/{id} (polling)
```



**Detalhes relevantes**

- **Ondas**: o `TaskDelegator` ordena as tarefas topologicamente pelas dependências do plano; cada onda roda em paralelo,
limitada por um semáforo global (`max-parallel-agents: 3`). Os resultados das ondas anteriores entram como contexto.
- **Robustez por agente**: timeout (`agent-timeout: 8 min`), até 2 tentativas com backoff de 5 s e cancelamento
cooperativo (`CancellationToken`).
- **Critérios do Evaluator** (determinísticos): falha ou timeout, resumo ausente, confiança < 0,5, mais da metade dos
achados sem evidência, ou nenhum arquivo inspecionado. Cada agente é re-delegado no máximo 1 vez, com orientação
específica; o que não for resolvido vira "limitação" no relatório.
- **Merge**: um resultado novo só substitui o anterior se tiver sucesso (ou se o anterior também falhou).



### 6.2 Correção assistida

```mermaid
sequenceDiagram
    autonumber
    actor U as Usuário (UI)
    participant R as RemediationController
    participant S as RemediationService
    participant C as Claude
    participant FS as filesystem MCP + code-search (somente leitura)
    participant ST as RemediationStore
    participant P as Projeto

    U->>R: GET /consent
    R-->>U: termos (por projeto)
    U->>R: POST /consent (aceite explícito)
    U->>R: POST /proposals {analysisId, findingId}
    R->>S: propose() — exige consentimento e projeto ativo igual ao da análise
    S-->>U: 202 (GENERATING)
    S->>C: achado + regras + ferramentas de leitura (até 25 chamadas)
    C->>FS: busca trechos (arquivo:linha) e lê arquivos (mascarados)
    C-->>S: FixPlan (edições search/replace, passos manuais, riscos)
    S->>S: valida edições (trecho único), gera diff, SHA-256 (1 retry se inválido)
    S->>ST: proposta READY (diff mascarado)
    U->>U: revisa o diff e marca "Revisei"
    U->>R: POST /proposals/{id}/apply {confirm:true}
    R->>S: apply() — sem análise rodando, SHA dos originais inalterado
    S->>ST: backup dos originais
    S->>P: escrita atômica (rollback se algum arquivo falhar)
    S-->>U: APPLIED
    opt Desfazer
        U->>R: POST /proposals/{id}/revert
        S->>P: restaura backups (só se o arquivo ainda for o aplicado)
    end
```



Estados da proposta: `GENERATING → READY | MANUAL_ONLY | FAILED`, `READY → APPLIED → REVERTED`. Propostas
`GENERATING` encontradas na inicialização (processo interrompido) viram `FAILED`.

### 6.3 Troca do projeto ativo

`POST /api/targets/{id}/activate` valida que o diretório existe e está dentro das pastas permitidas (caminho real, sem
escapar por links simbólicos), recusa com 409 se houver análise em execução e grava `data/targets.json` de forma atômica.
A próxima análise já usa o novo projeto, sem reiniciar processos: o `ProjectWorkspace` e as ferramentas leem o alvo
a cada chamada, e o `repo_path` do MCP Git é validado contra o projeto ativo. O servidor de filesystem continua o
mesmo processo: na próxima obtenção de ferramentas, o `McpConnectionTools` envia a nova raiz (MCP Roots), remove a
anterior e espera o servidor confirmar (`event=mcp.root` no log). O banco acompanha o projeto: se a URI do novo projeto
for outra, o `ProjectDatabaseConnection` encerra o `postgres-mcp` anterior e sobe um com a nova URI; sem URI, encerra
e a fonte `database` fica indisponível (§13.5).

---



## 7. Visão de implantação

```mermaid
flowchart LR
    subgraph PC[Estação Windows do desenvolvedor]
        B[Navegador] -->|:4200| NG[ng serve<br/>proxy /api]
        NG -->|:8090| JAR[java -jar agenthub.jar<br/>Java 25]
        B -.produção: dist/ servido estaticamente.-> JAR
        JAR -->|stdio| UVX[uvx mcp-server-git]
        JAR -->|stdio| NPX[npx server-filesystem]
        JAR -->|stdio| PGM[uvx postgres-mcp]
        PGM -->|URI do projeto · agenthub_ro| PG[(PostgreSQL 16<br/>meusservicos :5435)]
        JAR --> D[(agenthub/data<br/>agenthub/reports)]
        JAR --> PRJ[(C:\Hamden\Sistemas\...<br/>projetos analisados)]
    end
    JAR -->|HTTPS 443| ANT[(api.anthropic.com)]
```



- **Execução**: `.\mvnw.cmd spring-boot:run` ou `java -jar target\agenthub-0.1.0-SNAPSHOT.jar`, a partir da pasta
`agenthub` (os caminhos padrão são relativos a ela). UI: `ng serve` (dev) ou `ng build` (saída em `agenthub-ui/dist/`).
- **Pré-requisitos**: Java 25, `ANTHROPIC_API_KEY` no ambiente, `uv` instalado para o MCP Git (opcional: sem ele, a
fonte `git` fica indisponível e a aplicação sobe normalmente) e Node/`npx` para o MCP de filesystem (mesma
regra; versão fixada por `AGENTHUB_FILESYSTEM_MCP_VERSION`, comando por `AGENTHUB_NPX_COMMAND`). Para o PostgreSQL
MCP, `uv` e a URI do banco cadastrada no projeto, com o usuário `agenthub_ro` (§13.5; mesma regra: sem eles, só a
fonte `database` fica indisponível). Os processos MCP filhos são encerrados no desligamento do backend.
- **Observabilidade**: Actuator (`/actuator/health`, `/actuator/info`); logs estruturados `chave=valor` com
`analysisId` e `agent` no MDC (eventos `tool.call`, `claude.call`, `agent.end`, `orchestrator.done`, ...).
- **Uso previsto**: estação local, usuário único. A API não tem autenticação e não deve ser exposta em rede.

---



## 8. Conceitos transversais



### 8.1 Segurança em camadas

```mermaid
flowchart LR
    C[Claude pede<br/>tool call] --> G1{Fonte permitida<br/>ao agente?}
    G1 -->|não| X[Negado]
    G1 -->|sim| G2{Ferramenta na<br/>allowlist e não-escrita?}
    G2 -->|não| X
    G2 -->|sim| G3{Orçamento<br/>disponível?}
    G3 -->|não| X
    G3 -->|sim| G4{Caminho dentro da raiz,<br/>do escopo, não excluído<br/>e não sensível?}
    G4 -->|não| X
    G4 -->|sim| EXE[Executa]
    EXE --> M[SecretMasker +<br/>truncamento 30k]
    M --> C
```




| Camada                 | Mecanismo                                                                                                                                                                                                                                                                                 |
| ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Fontes por agente      | `agenthub.agents.<id>.tool-sources`; o Database Agent, por exemplo, não recebe Git.                                                                                                                                                                                                       |
| Ferramentas            | `allowed-tools` por fonte; nomes que indicam escrita (`write`, `commit`, `push`, `delete`, `exec`, ...) bloqueados salvo `allow-write`.                                                                                                                                                   |
| Orçamento              | `ToolBudget` por execução de agente (25–30 chamadas).                                                                                                                                                                                                                                     |
| Sandbox                | `ProjectWorkspace`: raiz e escopo do projeto ativo, sem `..`/links que escapem, diretórios excluídos (`.git`, `node_modules`, `target`, ...), arquivos sensíveis (`.env*`, chaves, keystores, `.npmrc`, ...), limite de 200 KB por arquivo.                                               |
| Argumentos MCP         | `path-arguments` validados no Hub antes da chamada: `repo_path` (git) contra o repositório do projeto ativo; `path`/`paths` (filesystem MCP, `bind-to-workspace`) pelas mesmas regras do sandbox e reescritos como absolutos.                                                             |
| Servidor de filesystem | Recebe só a raiz do projeto ativo via MCP Roots (atualizada a cada troca, com confirmação); recusa por conta própria qualquer caminho fora dela. Listagens chegam ao modelo sem diretórios excluídos.                                                                                     |
| Banco de dados         | PostgreSQL MCP em modo restrito, allowlist sem `execute_sql` e usuário `agenthub_ro` (só `SELECT`, sessão read-only, timeout 30s). Detalhes na §13.5.                                                                                                                                       |
| Segredos               | `SecretMasker` substitui valores por `***MASKED(n chars)***` antes de enviar ao Claude, nos logs e nos diffs da UI.                                                                                                                                                                       |
| Chave da API           | Somente `ANTHROPIC_API_KEY`; nunca registrada.                                                                                                                                                                                                                                            |
| Cadastro de projetos   | Somente dentro de `allowed-roots`, comparando caminhos reais.                                                                                                                                                                                                                             |
| Escrita                | Só na correção assistida: consentimento por projeto (versão dos termos), revisão do diff, `confirm=true`, verificação SHA-256, backup, escrita atômica, rollback, `resolveForWrite` (rejeita diretórios e links), nunca em arquivos sensíveis, valores mascarados não podem ser gravados. |




### 8.2 Context window e max_tokens

Toda chamada de agente ao Claude é governada por três limites independentes. Os dois primeiros
ficam aqui; o terceiro, o formato (JSON Schema), tem seção própria: **§14**.

```text
                         CLAUDE AGENT (AbstractClaudeAgent → ClaudeService)
                              │
            ┌─────────────────┼─────────────────┐
            ▼                 ▼                 ▼
     Context Window      max_tokens        JSON Schema
     "quanto cabe"       "quanto pode      "qual é o formato"
            │             responder"              │
            │                 │            ┌──────┴───────┐
            │                 │            ▼              ▼
            │                 │       Tool Input     Structured
            │                 │         Schema         Output
            │                 │            │              │
            └──────────┬──────┴────────────┘              │
                       ▼                                  ▼
     tool calls → MCP Servers (filesystem, git)    AgentAnalysis (record)
                  + busca local (fs_search_text)
                                                          │
                                                          ▼
                                                     AgentResult
```



#### Context window — "quanto cabe" (entrada)

Tudo o que é enviado ao modelo em cada turno disputa a mesma janela (200 mil tokens no Claude Sonnet 4.5):

```text
Context Window
      │
      ├── system prompt ........ prompts/agents/<id>.md + regras de saída comuns
      ├── user prompt .......... solicitação, tarefa do Hub, feedback da rodada anterior,
      │                          resumo dos outros agentes, brief do projeto
      ├── tool schemas ......... JSON Schema de cada ferramenta permitida ao agente
      ├── formato de saída ..... JSON Schema do AgentAnalysis (instrução de formato)
      ├── histórico do turno ... pedidos de ferramenta do modelo + respostas
      └── resultados ........... código lido (filesystem MCP), buscas (fs_search_text) e saídas do git, mascarados
```

No loop interno de *tool calling* do Spring AI, a conversa inteira é reenviada a cada chamada de ferramenta, então o
consumo de entrada cresce a cada arquivo lido. Por isso o AgentHub controla a janela na origem:


| Controle                                                     | Valor                                                                                                                                                                 |
| ------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| *Brief* por papel (`ContextSelector`), não o projeto inteiro | até 40 itens por lista                                                                                                                                                |
| Orçamento de chamadas de ferramenta (`ToolBudget`)           | 25–30 por agente; 25 na correção                                                                                                                                      |
| Leitura de arquivo (`read_text_file`, servidor oficial)      | arquivo inteiro ou `head`/`tail` N linhas; o resultado passa pelo corte de 30.000 caracteres abaixo. o brief orienta usar `fs_search_text` para obter `arquivo:linha` |
| Leitura de arquivo (`fs_read_file`, reserva local)           | até 400 linhas por chamada, linhas cortadas em 300 caracteres                                                                                                         |
| Busca de conteúdo (`fs_search_text`)                         | 80 ocorrências                                                                                                                                                        |
| Ferramentas omitidas                                         | `directory_tree` e `list_directory_with_sizes` fora da allowlist (árvore inteira consome a janela)                                                                    |
| Qualquer resultado de ferramenta (`GuardedToolCallback`)     | truncado em 30.000 caracteres                                                                                                                                         |
| Resultados de outros agentes                                 | resumo + até 8 achados por agente                                                                                                                                     |
| Ferramentas expostas                                         | só as da allowlist do agente (menos schemas = menos tokens fixos por turno)                                                                                           |


Além desses cortes, cada execução tem um **orçamento de contexto** explícito (`ContextBudget`), aplicado no código:


| Configuração                                                        | Padrão | Papel                         |
| ------------------------------------------------------------------- | ------ | ----------------------------- |
| `agenthub.claude.context-window` (`ANTHROPIC_CONTEXT_WINDOW`)       | 200000 | janela do modelo              |
| `agenthub.claude.context-budget-tokens` (`AGENTHUB_CONTEXT_BUDGET`) | 120000 | orçamento padrão por execução |
| `agenthub.agents.<id>.context-budget-tokens`                        | —      | ajuste por agente             |


O limite efetivo é `min(orçamento, context-window − max_tokens)`. A contagem é estimada (`TokenEstimator`, ~3,5
caracteres por token, conservadora):

1. **Parte fixa** (`ClaudeService`): system + user + instrução de formato + tool schemas. Se não couber, a chamada nem é
  feita (`ClaudeException` "Contexto inicial ... excede o orçamento").
2. **Resultados de ferramentas** (`GuardedToolCallback` → `ContextBudget.admit`): cada resposta (inclusive erros e
  "ACESSO NEGADO") é somada; a que ultrapassa o saldo é cortada com aviso ao modelo, e as chamadas seguintes recebem
   "Context window desta execução esgotada", forçando o agente a concluir com o que já leu.
3. O Orchestrator registra a exaustão como limitação, e o Evaluator orienta a próxima rodada a ler trechos menores.

Os tokens consumidos são medidos por chamada (`event=claude.call inputTokens=... outputTokens=... maxTokens=... stopReason=... contextTokens=usado/limite`), somados por agente em `metadata.agentMetrics` e exibidos na UI com custo
estimado e no card **"Limites por chamada ao Claude"** (aba Desempenho do relatório): pico/orçamento de contexto,
max_tokens, motivo de parada, argumentos rejeitados pelo Tool Input Schema e divergências do Structured Output.

#### max_tokens — "quanto pode responder" (saída)

`spring.ai.anthropic.chat.max-tokens` (`ANTHROPIC_MAX_TOKENS`, padrão **8192**) limita cada resposta do modelo,
incluindo a resposta final em JSON. É um teto de saída, separado da janela de entrada.

```text
max_tokens (8192)
      │
      ▼
quanto o Claude pode gerar numa resposta
      │
      ▼
Structured Output
      │
      ▼
JSON obedecendo ao JSON Schema  ──(stopReason=max_tokens)──▶ ClaudeException explícita → retry mais conciso
```

O valor é enviado em cada chamada (`ChatOptions.maxTokens`) e pode ser reduzido por agente com
`agenthub.agents.<id>.max-output-tokens`. As regras de saída pedem "poucos achados relevantes e bem fundamentados", o
que mantém a resposta bem abaixo do teto. Se a resposta parar por `max_tokens` (motivo de parada da API), o
`ClaudeService` não tenta converter o JSON incompleto: lança "Resposta cortada em max_tokens=N", a execução é repetida
(`max-attempts`) e o Evaluator orienta o agente a responder de forma mais concisa.

### 8.3 Prompts

Prompts de sistema em `src/main/resources/prompts/` (`hub/planner.md`, `agents/<id>.md`), acrescidos de regras de
saída comuns (português, evidência obrigatória, não inventar, escala de severidade, confiança proporcional ao inspecionado).
Podem ser sobrescritos sem recompilar: `agenthub.agents.<id>.system-prompt: file:/caminho/prompt.md`.

### 8.4 Persistência


| Dado                           | Onde                                                                | Ciclo de vida                               |
| ------------------------------ | ------------------------------------------------------------------- | ------------------------------------------- |
| Análises em andamento/recentes | Memória (`AnalysisRepository`, até 50; até 200 eventos por análise) | Perdidas ao reiniciar.                      |
| Relatórios                     | `reports/<data>-<id>.json` e `.md`                                  | Permanentes; listados no Histórico.         |
| Projetos e projeto ativo       | `data/targets.json` (até 50 projetos, 30 itens de escopo)           | Permanentes.                                |
| Correções                      | `data/remediations/<id>.json`, `consents.json`, `<id>/backup/`      | Permanentes; backups podem conter segredos. |


Toda escrita de arquivo usa arquivo temporário + `move` atômico. `reports/` e `data/` estão no `.gitignore`.

### 8.5 Concorrência

Virtual threads (`spring.threads.virtual.enabled` e `ExecutorService agentHubExecutor`), semáforo justo para limitar
agentes simultâneos e respeitar o rate limit da Anthropic, MDC propagado para as threads dos agentes. Aplicar/desfazer
correções e trocar o projeto ativo são recusados enquanto houver análise em execução.

### 8.6 Tratamento de erros

`ApiExceptionHandler` converte exceções de domínio em ProblemDetail: 400 (`InvalidTargetException`,
`RemediationException`), 403 (`ConsentRequiredException`), 404 (`AnalysisNotFoundException`, `TargetNotFoundException`,
`RemediationNotFoundException`), 409 (`ReportNotReadyException`, `TargetBusyException`, `RemediationConflictException`).
Dentro da análise, falhas de agentes e de ferramentas não propagam: viram status (`FAILED`, `TIMEOUT`, `CANCELLED`)
e entram nas limitações do relatório.

---



## 9. Decisões de arquitetura (ADRs resumidos)


| #      | Decisão                                                                                              | Alternativas consideradas                            | Consequência                                                                                                                                                                                                                                                                                                                                                                  |
| ------ | ---------------------------------------------------------------------------------------------------- | ---------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ADR-01 | Hub-and-spoke com especialistas isolados                                                             | Agentes conversando entre si (mesh)                  | Mais previsível e barato; colaboração só via contexto do Hub.                                                                                                                                                                                                                                                                                                                 |
| ADR-02 | Filesystem híbrido: servidor MCP oficial (listar/ler) + `fs_search_text` local (busca de conteúdo)   | Só local (decisão anterior); só o servidor oficial   | O servidor oficial é padrão de mercado e aceita MCP Roots, o que permite seguir o projeto ativo sem reiniciar o processo; não busca conteúdo nem devolve linhas, por isso a busca segue local. As regras do workspace (escopo, exclusões, sensíveis) continuam no Hub, antes e depois de cada chamada. A versão totalmente local permanece como reserva (`filesystem-local`). |
| ADR-03 | MCP Git sem `--repository`, com validação de `repo_path`                                             | Um processo por projeto                              | Um único processo atende a qualquer projeto ativo, sem reiniciar.                                                                                                                                                                                                                                                                                                             |
| ADR-04 | Conexões MCP sob demanda (`initialized: false`)                                                      | Inicializar no boot                                  | Servidor MCP ausente não derruba a aplicação; aparece como indisponível.                                                                                                                                                                                                                                                                                                      |
| ADR-05 | Avaliação determinística                                                                             | LLM avaliador                                        | Sem custo de tokens, auditável; não julga a "profundidade" semântica.                                                                                                                                                                                                                                                                                                         |
| ADR-06 | Estado em memória + arquivos JSON                                                                    | Banco relacional                                     | Simples para uso local; histórico de execuções em memória se perde ao reiniciar (relatórios persistem).                                                                                                                                                                                                                                                                       |
| ADR-07 | API assíncrona com polling                                                                           | WebSocket / SSE                                      | Implementação simples e robusta; latência de atualização de até 3 s.                                                                                                                                                                                                                                                                                                          |
| ADR-08 | Correção por edições `search/replace` com trecho único                                               | Arquivo completo reescrito pelo modelo               | Diffs menores e verificáveis; evita sobrescrever partes não relacionadas.                                                                                                                                                                                                                                                                                                     |
| ADR-09 | Máscara de segredo casando por comprimento (`[^\r\n]{n}`)                                            | Enviar o segredo ao modelo                           | O modelo substitui um segredo sem conhecê-lo; nunca pode gravar o valor mascarado.                                                                                                                                                                                                                                                                                            |
| ADR-10 | Angular 19.2                                                                                         | Angular 20+                                          | Compatível com o Node 20.12 disponível.                                                                                                                                                                                                                                                                                                                                       |
| ADR-11 | Saída estruturada via JSON Schema gerado dos records (`BeanOutputConverter`) com conversão tolerante | Texto livre + parsing; decodificação restrita nativa | Contrato tipado e versionado no código; conformidade reforçada por conversão tolerante, validação do Hub e retry.                                                                                                                                                                                                                                                             |
| ADR-12 | Git como fonte transversal + uma fonte especializada por área (Sonar, PostgreSQL)                    | Todas as fontes para todos os agentes                | Menos schemas por turno (menos tokens) e menor raio de ação; fontes especializadas habilitadas só com infraestrutura real.                                                                                                                                                                                                                                                    |


---



## 10. Cenários de qualidade


| Atributo    | Estímulo                                      | Resposta esperada                                                                                                                        |
| ----------- | --------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| Segurança   | Agente tenta ler `../outro-projeto/.env`      | `ACESSO NEGADO`; chamada registrada no log; nada é lido.                                                                                 |
| Segurança   | Arquivo `application.properties` com senha    | O Claude recebe `***MASKED(n chars)***`; o relatório e os logs também.                                                                   |
| Segurança   | Usuário clica em "Corrigir com IA" sem aceite | API responde 403; a UI mostra os termos.                                                                                                 |
| Integridade | Arquivo editado após a proposta ser gerada    | Aplicação recusada (409) por divergência de SHA-256.                                                                                     |
| Resiliência | `uvx` não instalado                           | Aplicação sobe; fonte `git` aparece como indisponível; agentes seguem só com filesystem.                                                 |
| Resiliência | Node/`npx` ausente                            | Fonte `filesystem` indisponível; agentes seguem com `code-search` e git. Para restaurar a leitura sem Node, atribuir `filesystem-local`. |
| Resiliência | Planner do Claude falha                       | Plano por regras/palavras-chave; análise continua.                                                                                       |
| Resiliência | Agente estoura o tempo                        | Status `TIMEOUT`, re-delegação com orientação para ser mais objetivo; se persistir, vira limitação.                                      |
| Custo       | Agente tenta ler o projeto inteiro            | Orçamento de chamadas esgota (`tool.budget_exhausted`); o agente responde com o que tem.                                                 |


---



## 11. Riscos e dívidas técnicas


| Risco / dívida                                        | Impacto                                                                        | Mitigação atual / sugestão                                                                                               |
| ----------------------------------------------------- | ------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------ |
| API sem autenticação                                  | Qualquer processo local pode iniciar análises e aplicar correções consentidas. | Uso local apenas, porta não exposta. Sugestão: autenticação (ex.: token local) antes de qualquer uso em rede.            |
| Backups em `data/remediations` com segredos originais | Vazamento se a pasta for compartilhada.                                        | `.gitignore`; aviso nos termos e no README. Sugestão: expiração/limpeza de backups.                                      |
| Correções geradas por IA podem quebrar o build        | Regressão no projeto alvo.                                                     | Revisão obrigatória do diff, desfazer, recomendação de commit prévio. Sugestão: rodar build/testes do alvo após aplicar. |
| Histórico de execuções só em memória                  | Análises em andamento perdidas ao reiniciar.                                   | Relatórios concluídos persistem em `reports/`.                                                                           |
| Avaliação não semântica                               | Achados superficiais com evidência podem passar.                               | Regras de saída nos prompts e confiança proporcional ao inspecionado.                                                    |
| Fonte `sonarqube` desabilitada                        | Análises de qualidade sem dados do Sonar.                                      | Configurar servidor MCP real com token (ver README, "Adicionar um servidor MCP").                                        |
| Banco cadastrado não corresponde ao projeto           | URI errada no projeto: o Database Agent compara as entidades com outro banco. | URI por projeto na tela Projetos, com o banco visível no cartão; o prompt manda registrar divergência entre banco e entidades. |
| Senha do banco em `data/targets.json`                 | Quem lê o arquivo obtém a credencial.                                          | Arquivo fora do git e local à estação; API, tela, logs e `toString()` só mostram `***`; usuário `agenthub_ro` só com `SELECT`. |
| Credencial do banco                                   | Acesso indevido aos dados se a URI vazar.                                      | Usuário `agenthub_ro` só com `SELECT`, somado ao modo restrito do servidor e à allowlist sem `execute_sql` (§13.5).       |
| Extensões `hypopg`/`pg_stat_statements` ausentes      | Sugestão de índices e top queries do PostgreSQL MCP devolvem erro.             | O agente segue com metadados, `EXPLAIN` e saúde. Sugestão: instalar as extensões no banco de desenvolvimento (§13.5).      |
| Dependência de rate limit/custo da Anthropic          | Análises lentas ou caras.                                                      | Paralelismo limitado, orçamento de ferramentas, escopo por módulos.                                                      |


---



## 12. Glossário


| Termo                         | Significado                                                                                                                                                 |
| ----------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Hub / Orchestrator**        | Componente central que planeja, delega, avalia, agrega e sintetiza.                                                                                         |
| **Agente especialista**       | Componente que analisa uma área (arquitetura, backend, frontend, banco, segurança, testes) usando o Claude e ferramentas.                                   |
| **MCP**                       | Model Context Protocol: protocolo para expor ferramentas a modelos; o AgentHub é o *host*, com um *client* por servidor (§13).                              |
| **JSON Schema**               | Contrato de formato: argumentos das ferramentas (Tool Input) e resposta final do Claude (Structured Output) (§14).                                          |
| **Fonte de ferramentas**      | Conjunto de ferramentas (`filesystem`, `code-search`, `git`, `database`, `sonarqube`; reserva `filesystem-local`) atribuível a agentes.                     |
| **MCP Roots**                 | Recurso do protocolo MCP em que o cliente informa ao servidor as pastas que ele pode acessar; usado para prender o servidor de filesystem ao projeto ativo. |
| **Projeto ativo**             | Projeto atualmente analisado, escolhido na tela Projetos.                                                                                                   |
| **Escopo**                    | Subconjunto de pastas/arquivos do projeto ativo visível aos agentes.                                                                                        |
| **Brief**                     | Resumo do projeto montado por papel a partir do discovery.                                                                                                  |
| **Achado (finding)**          | Problema ou observação com severidade, evidências e recomendação.                                                                                           |
| **Rodada / re-delegação**     | Nova execução de um agente com feedback do Evaluator.                                                                                                       |
| **Limitação**                 | Registro no relatório do que não pôde ser analisado ou concluído.                                                                                           |
| **Correção assistida**        | Fluxo de proposta, revisão e aplicação de correções para achados, com consentimento.                                                                        |


---



## 13. MCP (Model Context Protocol)

O AgentHub dá ferramentas aos agentes por **fontes de ferramentas**. A maioria são servidores MCP externos, rodando
como processos filhos via stdio; duas são locais (in-process). Esta seção reúne tudo sobre elas: como o MCP está montado
no código, quem recebe o quê e o detalhe de cada servidor. O formato dos argumentos de cada ferramenta (Tool Input
Schema) está na §14.5.

| Servidor / fonte                   | Fonte AgentHub              | Processo                         | Estado                        | Detalhes |
| ---------------------------------- | --------------------------- | -------------------------------- | ----------------------------- | -------- |
| Filesystem (oficial)               | `filesystem`                | `npx @modelcontextprotocol/server-filesystem` | Ativa              | §13.3    |
| Busca de conteúdo (local)          | `code-search`               | in-process                       | Ativa                         | §13.3    |
| Git (oficial)                      | `git`                       | `uvx mcp-server-git`             | Ativa                         | §13.4    |
| PostgreSQL MCP (Postgres MCP Pro)  | `database`                  | `uvx postgres-mcp`               | Ativa                         | §13.5    |
| SonarQube                          | `sonarqube`                 | —                                | Desabilitada                  | §13.6    |
| Filesystem local (reserva)         | `filesystem-local`          | in-process                       | Reserva, sem agentes          | §13.3    |

### 13.1 Como o AgentHub usa o MCP

O AgentHub é o *host* MCP: o Spring AI cria um *client* síncrono por conexão declarada, e o Hub decide, por agente,
quais ferramentas desses clientes o Claude pode ver.

```mermaid
flowchart LR
    AG[Agente<br/>AbstractClaudeAgent] --> TR[McpToolRegistry<br/>fontes do agente + allowlist]
    TR --> GT[GuardedToolCallback<br/>schema · orçamento · caminhos · máscara]
    TR --> LTS[LocalToolSources<br/>code-search · filesystem-local]
    TR --> MCT[McpConnectionTools<br/>conexão sob demanda + Roots]
    MCT -->|stdio| FS[server-filesystem<br/>npx]
    MCT -->|stdio| GIT[mcp-server-git<br/>uvx]
    MCT --> PDC[ProjectDatabaseConnection<br/>banco do projeto ativo]
    PDC -->|stdio| PG[postgres-mcp<br/>uvx]
```

| Peça                               | Papel                                                                                                                                                                                                  |
| ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Spring AI MCP Client               | Auto-configure: um cliente por conexão em `spring.ai.mcp.client.stdio.connections`. `toolcallback.enabled: false` — nenhuma ferramenta MCP vai para um provedor global; o acesso é sempre por agente. |
| `McpConnectionTools`               | Inicializa cada conexão no primeiro uso (`initialized: false`, ADR-04), guarda as ferramentas em cache, informa a raiz do projeto ativo (MCP Roots) às fontes `bind-to-workspace` e encerra os processos filhos no desligamento. |
| `ProjectDatabaseConnection`        | Cliente MCP do banco, criado pelo próprio Hub (não pelo Spring AI) com a URI do projeto ativo (`data/targets.json`); recria o processo quando o projeto ou a URI mudam e o encerra quando o projeto não tem banco (§13.5). |
| `McpToolRegistry`                  | Monta as ferramentas de cada agente: fontes de `agenthub.agents.<id>.tool-sources`, filtradas pela `allowed-tools` e pelo padrão de escrita `WRITE_OPERATION`; publica status (`/api/agents`) e schemas (`/api/schemas`). |
| `GuardedToolCallback`              | Envolve toda ferramenta, local ou MCP: valida os argumentos contra o Tool Input Schema, aplica orçamento e context window, verifica argumentos-caminho, mascara segredos, corta em 30.000 caracteres e registra `event=tool.call`. |
| `LocalToolSources`                 | Fontes in-process, sem MCP: `code-search` e `filesystem-local`.                                                                                                                                        |
| `McpWarmUp`                        | Após o startup, inicializa as conexões em background (`event=tool.source.status` no log), para a primeira análise não pagar a subida dos servidores. Desligável com `agenthub.mcp.warm-up=false`.      |

| Configuração                                       | Função                                                                                                                                  |
| -------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| `spring.ai.mcp.client.stdio.connections.<conexão>` | Comando, argumentos e `env` do processo do servidor. Segredos só por `env` + variável de ambiente, nunca nos argumentos.               |
| `agenthub.project-database`                        | Processo do servidor do banco (comando, argumentos, `uri-variable`, timeouts); a URI vem do projeto ativo, não do YAML.              |
| `agenthub.tool-sources.<fonte>`                    | `type` (`mcp`/`local`), `connection`, `enabled`, `allowed-tools`, `allow-write`, `path-arguments`, `bind-to-workspace`, `description`. |
| `agenthub.agents.<id>.tool-sources`                | Quais fontes o agente recebe (least privilege).                                                                                         |
| `AGENTHUB_MCP_ENABLED`                             | Liga/desliga todo o cliente MCP.                                                                                                        |

**Uma chamada de ferramenta, do início ao fim:**

1. Ao iniciar a execução, o agente pede suas ferramentas ao `McpToolRegistry`. Se a conexão ainda não subiu, ela é
   inicializada; servidor fora do ar faz só aquela fonte ser ignorada (`event=tool.source.unavailable`) e o agente
   segue com as demais.
2. Os Tool Input Schemas das ferramentas permitidas vão no parâmetro `tools` da Messages API.
3. O Claude pede uma *tool call*; o `GuardedToolCallback` valida os argumentos, confere orçamento e caminhos e repassa
   ao cliente MCP (ou ao método local).
4. O resultado volta filtrado, mascarado e contabilizado na context window da execução.

### 13.2 Topologia agentes × fontes de ferramentas

Cada agente recebe só as fontes declaradas em `agenthub.agents.<id>.tool-sources`. O Git é a fonte transversal
(histórico e diffs); cada área ganha a fonte especializada que faz sentido para ela.

```text
Orchestrator (Hub)
│
├── ArchitectureAgent ── Filesystem MCP · code-search · Git MCP · Sonar MCP*
├── BackendAgent ─────── Filesystem MCP · code-search · Git MCP · Sonar MCP*
├── FrontendAgent ────── Filesystem MCP · code-search · Git MCP · Sonar MCP*
├── DatabaseAgent ────── Filesystem MCP · code-search · Git MCP · PostgreSQL MCP
├── SecurityAgent ────── Filesystem MCP · code-search · Git MCP · Sonar MCP* (regras de segurança / hotspots)
└── TestAgent ────────── Filesystem MCP · code-search · Git MCP

* declarada e desabilitada até existir servidor real e credencial somente leitura
```


| Fonte              | Tipo                                                                                            | Estado                           | Agentes                                   |
| ------------------ | ----------------------------------------------------------------------------------------------- | -------------------------------- | ----------------------------------------- |
| `filesystem`       | MCP stdio (`npx @modelcontextprotocol/server-filesystem`), preso ao projeto ativo via MCP Roots | Ativa (5 ferramentas de leitura) | Todos + correção assistida                |
| `code-search`      | Local (in-process, `fs_search_text`)                                                            | Ativa                            | Todos + correção assistida                |
| `filesystem-local` | Local (in-process, 4 `fs_*`)                                                                    | Reserva (sem Node/`npx`)         | Nenhum                                    |
| `git`              | MCP stdio (`uvx mcp-server-git`)                                                                | Ativa                            | Todos                                     |
| `sonarqube`        | MCP (ex.: imagem `mcp/sonarqube` + `SONARQUBE_TOKEN`)                                           | Desabilitada                     | Architecture, Backend, Frontend, Security |
| `database`         | PostgreSQL MCP: MCP stdio (`uvx postgres-mcp`), ver §13.5                                       | Ativa (8 ferramentas de leitura) | Database                                  |


Habilitar uma fonte é só configuração (conexão em `spring.ai.mcp.client.*` + `enabled: true` + `allowed-tools`); a
matriz de acesso já está pronta. Fonte desabilitada não gera ferramentas, aparece como indisponível em `/api/agents` e
entra nas limitações do relatório — nenhum servidor é simulado.

### 13.3 Filesystem MCP (fontes `filesystem` e `code-search`)

Servidor oficial `@modelcontextprotocol/server-filesystem` (Node), iniciado por `npx` com versão fixada
(`AGENTHUB_FILESYSTEM_MCP_VERSION`). Usado pelos 6 especialistas e pela correção assistida; ferramentas na §14.5.

**Filesystem híbrido.** O servidor oficial lista e lê; a busca de conteúdo com `arquivo:linha` fica local
(`code-search`), porque o servidor oficial só busca por nome e não devolve números de linha. As proteções ficam em
camadas:

1. **Roots** — o servidor sobe sem diretório na linha de comando; `McpConnectionTools` envia a raiz do projeto ativo
  antes do `initialize` (`McpClientCustomizer` declara a capability `roots`) e, a cada troca de projeto, adiciona a
   nova raiz, remove a antiga e aguarda o servidor confirmar via `list_allowed_directories` (até 3 s), porque ele
   busca as raízes de forma assíncrona.
2. **Allowlist** — só `list_directory`, `read_text_file`, `read_multiple_files`, `search_files`, `get_file_info`;
  escrita bloqueada também pelo padrão `WRITE_OPERATION`; `directory_tree` fora (estoura a context window).
3. **Argumentos-caminho** — `path`/`paths` passam por `ProjectWorkspace.resolve()` (raiz, escopo, diretórios
  excluídos, arquivos sensíveis) e seguem absolutos; o modelo trabalha com caminhos relativos.
4. **Saída** — listagens sem diretórios excluídos/fora do escopo; todo retorno mascarado e limitado pelo
  `GuardedToolCallback`.

### 13.4 Git MCP (fonte `git`)

Servidor oficial `mcp-server-git` (Python), iniciado por `uvx` **sem** `--repository`: um único processo atende a
qualquer projeto ativo, sem reiniciar (ADR-03). O `repo_path` de cada chamada é verificado contra o projeto ativo
(`path-arguments: [repo_path]`) antes de chegar ao servidor.

- **Allowlist (somente leitura):** `git_status`, `git_log`, `git_show`, `git_diff`, `git_diff_unstaged`,
  `git_diff_staged`, `git_branch`. Bloqueadas: `git_add`, `git_commit`, `git_reset`, `git_checkout`, `git_create_branch`.
- **Quem usa:** os 6 especialistas (fonte transversal, ADR-12).
- **Sem `uv`:** a fonte fica indisponível e os agentes seguem com filesystem e `code-search`.
- Ferramentas e parâmetros na §14.5.

### 13.5 PostgreSQL MCP (fonte `database`)

Dá ao Database Agent acesso ao banco real do projeto ativo, para confrontar entidades JPA e queries com tabelas,
colunas, índices e planos de execução. É a única fonte que fala com um sistema fora do disco; só o Database Agent a
recebe.

Cada projeto informa o próprio banco na tela Projetos (campo **Banco de dados**). Diferente das outras conexões MCP,
esta não é declarada no Spring AI: o `ProjectDatabaseConnection` sobe o `postgres-mcp` com a URI do projeto ativo e,
quando o projeto ou a URI mudam, encerra o processo anterior e sobe outro (`event=mcp.connected` / `mcp.closed` no
log). Projeto sem banco = fonte indisponível, e o agente analisa só o código.

```mermaid
flowchart LR
    DA[Database Agent] -->|tool call| GTC[GuardedToolCallback<br/>allowlist · orçamento · máscara]
    GTC --> PDC[ProjectDatabaseConnection<br/>URI do projeto ativo]
    PDC -->|"stdio · MCP<br/>DATABASE_URI via env"| PGM[postgres-mcp 0.3.0<br/>via uvx · modo restrito]
    PGM -->|"usuário agenthub_ro<br/>(só SELECT)"| DB[(PostgreSQL do projeto<br/>ex.: meusservicos :5435)]
```

| Item              | Valor                                                                                                                  |
| ----------------- | ---------------------------------------------------------------------------------------------------------------------- |
| Servidor          | [Postgres MCP Pro](https://github.com/crystaldba/postgres-mcp) (`postgres-mcp` 0.3.0, Python), iniciado por `uvx`     |
| Processo          | `agenthub.project-database` (`application.yml`): comando, argumentos, variável da URI e timeouts                       |
| Fonte AgentHub    | `agenthub.tool-sources.database` → `connection: postgres`; atribuída só em `agenthub.agents.database.tool-sources`    |
| URI               | Por projeto, em `data/targets.json` (`databaseUri`), com senha. A API e a tela só devolvem a versão mascarada (`***`) |
| Bancos atuais     | Os dois projetos do `clientes` apontam para `meusservicos` (database e schema) em `localhost:5435`, PostgreSQL 16.3    |
| Usuário do banco  | `agenthub_ro`, criado por `docs/sql/agenthub-readonly-role.sql`                                                        |
| Ferramentas       | 8 de leitura (tabela na §14.5); `execute_sql` fora da allowlist                                                        |

**Cadastro da URI.** Formato `postgresql://usuario:senha@host:porta/banco` (caracteres especiais da senha codificados,
ex.: `%40` para `@`). Ao editar, a tela mostra a senha como `***`: manter o valor mascarado preserva a senha gravada;
mudar host, porta, usuário ou banco exige digitar a senha de novo; campo vazio remove o banco. A validação recusa outro
esquema, servidor ou banco ausentes e espaços, sem repetir a URI na mensagem de erro. A senha não vai para logs, para o
`toString()` dos registros, nem para a linha de comando do processo (só para a variável `DATABASE_URI` do filho).

**Por que não o `@modelcontextprotocol/server-postgres`.** O servidor de referência foi arquivado e tem falha conhecida
de escape do modo somente leitura (várias instruções numa chamada). O Postgres MCP Pro valida o SQL e só executa em
transação read-only quando iniciado com `--access-mode=restricted`. A versão 0.3.0 exige `--with "mcp<2"`, porque não
roda com o SDK MCP 2.x do Python.

**Proteções em camadas** (de fora para dentro):

1. **Fonte por agente** — só o Database Agent recebe `database`.
2. **Allowlist** — `list_schemas`, `list_objects`, `get_object_details`, `explain_query`, `analyze_db_health`,
   `analyze_query_indexes`, `analyze_workload_indexes`, `get_top_queries`. `execute_sql` (SQL livre) fica de fora e
   também cairia no filtro `WRITE_OPERATION` (nome contém `execute`).
3. **Hub** — `GuardedToolCallback`: validação contra o Tool Input Schema, orçamento de 25 chamadas, mascaramento de
   segredos e corte em 30.000 caracteres no retorno.
4. **Servidor** — `--access-mode=restricted`: SQL analisado antes de executar e transação read-only.
5. **Banco** — `agenthub_ro`: só `SELECT` em `meusservicos` (inclusive tabelas futuras) e `pg_monitor` para
   estatísticas; sessão com `default_transaction_read_only = on`, `statement_timeout = 30s`, até 5 conexões, sem
   `CREATE` em `public`. Mesmo forçando `BEGIN READ WRITE`, `INSERT`/`UPDATE`/`DELETE`/`CREATE` dão "permissão negada".

**Operação.**

- Projeto ativo sem banco (ou banco fora do ar) = fonte `available=false` em `/api/agents`, com o motivo no detalhe; o
  Database Agent analisa só pelo código, registrando a limitação no relatório.
- Desligar sem mexer no YAML: `AGENTHUB_DATABASE_ENABLED=false`. Trocar a versão: `AGENTHUB_POSTGRES_MCP_VERSION`.
- Trocar a senha: rodar o script de novo com outro `-v pwd=...` e atualizar a URI na tela Projetos.
- A primeira subida do servidor leva ~35 s (download/cache do `uvx`); depois, só na troca de banco.
- Outro banco/usuário: criar um usuário só leitura equivalente ao `agenthub_ro` (o script serve de modelo).

**Limitações conhecidas.**

- `analyze_query_indexes` e `analyze_workload_indexes` (método `dta`) dependem da extensão `hypopg`, e
  `get_top_queries` da `pg_stat_statements`; o `meusservicos` não tem nenhuma das duas, então essas ferramentas
  devolvem erro ao modelo, que segue com as demais. O método `llm` exige chave OpenAI e não é usado.

### 13.6 SonarQube MCP (fonte `sonarqube`, desabilitada)

Declarada em `agenthub.tool-sources.sonarqube` (`enabled: false`, conexão `sonarqube`) e já atribuída a Architecture,
Backend, Frontend e Security. Falta um servidor SonarQube e um token; quando houver, basta declarar a conexão (ex.:
imagem Docker `mcp/sonarqube` com `SONARQUBE_TOKEN`) e seguir a §13.7. Enquanto isso, não gera ferramentas, aparece como
indisponível em `/api/agents` e nenhum servidor é simulado.

### 13.7 Adicionar ou habilitar um servidor MCP

1. Declare a conexão em `spring.ai.mcp.client.stdio.connections.<conexão>` (versão fixada; segredos via `env`).
2. Declare ou habilite a fonte em `agenthub.tool-sources.<fonte>` com `allowed-tools` só de leitura.
3. Inclua a fonte em `agenthub.agents.<id>.tool-sources` dos agentes que devem usá-la.
4. Confira em `/api/agents` (`available`, ferramentas) e em `/api/schemas` (o que foi exposto e o que ficou bloqueado).
5. Documente: subseção nesta §13, tabela de ferramentas na §14.5 e linha na matriz da §14.3.

Exemplos de YAML no README, seção "Adicionar um servidor MCP".

---


## 14. JSON Schema

O JSON Schema define o formato de tudo o que o Claude troca com a aplicação: os argumentos de cada chamada de
ferramenta (**Tool Input Schema**) e a resposta final de cada chamada (**Structured Output Schema**). A §14.1 explica os
conceitos, a validação e a conformidade; da §14.2 em diante está a referência completa — quais schemas cada agente
recebe, todos os campos e o que o Hub faz com eles.

### 14.1 Conceitos: Tool Input × Structured Output

O JSON Schema aparece em dois sentidos opostos:


|              | Tool Input Schema                                                                                       | Structured Output Schema                                                                           |
| ------------ | ------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------- |
| Direção      | modelo → ferramenta (argumentos da chamada)                                                             | modelo → aplicação (resposta final)                                                                |
| Origem       | `@Tool` + `@ToolParam` (ferramentas locais) ou `inputSchema` publicado pelo servidor MCP (`tools/list`) | records Java (`AgentAnalysis`, `PlanDraft`, `SynthesisDraft`, `FixPlan`) via `BeanOutputConverter` |
| Onde vai     | parâmetro `tools` da Messages API                                                                       | instrução de formato anexada ao prompt                                                             |
| Quem consome | Spring AI converte os argumentos nos parâmetros do método / repassa ao MCP Client                       | `LenientOutputConverter` desserializa no record                                                    |


**Tool Input Schema** — publicado pelo servidor MCP em `tools/list` (exemplo real de `read_text_file`, do servidor
oficial de filesystem):

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "properties": {
    "path": { "type": "string" },
    "tail": { "type": "number", "description": "If provided, returns only the last N lines of the file" },
    "head": { "type": "number", "description": "If provided, returns only the first N lines of the file" }
  },
  "required": ["path"]
}
```

Nas ferramentas locais o schema é gerado pelo Spring AI a partir da assinatura do método (`@Tool` + `@ToolParam`), como
em `fs_search_text` (`pattern` obrigatório, `glob` e `directory` opcionais). As ferramentas MCP (`server-filesystem`,
`mcp-server-git`) chegam com o schema definido pelo próprio servidor; o `McpToolRegistry` só filtra quais ferramentas
existem (allowlist). Antes de qualquer execução, o `GuardedToolCallback` valida os argumentos
recebidos contra o `inputSchema` da ferramenta (`JsonSchemaValidator`, draft 2020-12, networknt); se não obedecerem, a
ferramenta não roda e o modelo recebe "ARGUMENTOS INVÁLIDOS ... (não obedecem ao JSON Schema de entrada)" com as
violações (contadas em `toolInputRejected`). Depois disso, valida argumentos de caminho (`repo_path` do git;
`path`/`paths` do filesystem MCP, reescritos como absolutos dentro do projeto ativo). O schema define o formato dos argumentos, não a permissão: autorização é sempre feita no servidor da
aplicação (`ProjectWorkspace`, allowlist, orçamento).

**Structured Output Schema** — gerado a partir do record `AgentAnalysis` (exemplo real, resumido):

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "summary":         { "type": "string" },
    "confidence":      { "type": "number", "format": "double" },
    "recommendations": { "type": "array", "items": { "type": "string" } },
    "findings": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "title":          { "type": "string" },
          "description":    { "type": "string" },
          "severity":       { "type": "string", "enum": ["CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"] },
          "category":       { "type": "string" },
          "recommendation": { "type": "string" },
          "evidence": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "file":    { "type": "string" },
                "lines":   { "type": "string" },
                "excerpt": { "type": "string" }
              },
              "required": ["excerpt", "file", "lines"],
              "additionalProperties": false
            }
          }
        },
        "required": ["category", "description", "evidence", "recommendation", "severity", "title"],
        "additionalProperties": false
      }
    }
  },
  "required": ["confidence", "findings", "recommendations", "summary"],
  "additionalProperties": false
}
```

`id` e `agent` do achado ficam fora do schema de propósito: quem os define é o Hub (`ResultAggregator`), não o modelo.


| Record (schema)                            | Quem pede                | Uso                                                                         |
| ------------------------------------------ | ------------------------ | --------------------------------------------------------------------------- |
| `PlanDraft` (convertido em `AnalysisPlan`) | `Planner`                | tarefas, foco e dependências entre agentes                                  |
| `AgentAnalysis`                            | cada agente especialista | resumo, achados com evidências, recomendações, confiança                    |
| `SynthesisDraft`                           | `ReportSynthesizer`      | sumário executivo, code quality, recomendações-chave, roadmap               |
| `FixPlan`                                  | `RemediationService`     | edições `search/replace`, arquivos novos, passos manuais, riscos, confiança |


**Onde consultar todos os schemas.** `GET /api/schemas` (somente leitura, sem custo de LLM) devolve os quatro
Structured Outputs e, por fonte, os Tool Input Schemas expostos aos agentes. Para cada fonte, a resposta mostra
também quem a usa e quais ferramentas o servidor publicou mas ficaram bloqueadas (fora da allowlist ou de escrita).
A UI mostra o mesmo na página **JSON Schemas**. Uma cópia fica em `docs/schemas/`, com um arquivo por schema; ela é
regenerada a partir do endpoint com o backend rodando, porque os schemas do MCP vêm do servidor em tempo de execução.

**O que chega ao agrupador (Hub).** Dos quatro schemas, só o `AgentAnalysis` alimenta o agrupador. O Tool Input
Schema fica no laço do agente: valida argumentos e não produz saída para o Hub. O `PlanDraft` é anterior à delegação, e
o `SynthesisDraft` e o `FixPlan` são posteriores à consolidação.

```text
AgentAnalysis (JSON validado) ──► AgentResult (+ papel, status, métricas)  ×N agentes
                                         │
                                         ▼
                              ResultAggregator.aggregate
                    ┌────────────────────┼─────────────────────┐
                    ▼                    ▼                     ▼
          seções + findings         recomendações       HubConsolidation
          (dedup, ids SEC-001…)     (sem repetição)     (totals, agents, items)
                    └────────────────────┴──────────┬──────────┘
                                                    ▼
                                AnalysisReport.consolidation ──► UI: aba "Consolidação"
```

O record `HubConsolidation` registra o que cada agente entregou e o destino de cada item:

- `totals`: agentes com e sem resultado, achados recebidos → no relatório → duplicados, recomendações recebidas →
distintas, arquivos citados como evidência.
- `agents`: por agente, o status, a rodada, o record do Structured Output entregue, as contagens, a confiança, as
divergências de schema e o erro.
- `items`: cada achado e recomendação recebidos, com destino `KEPT` (está no relatório) ou `DUPLICATE` (absorvido).
Para achados duplicados, `findingId` aponta para o achado que o absorveu.

Na UI, a aba **Consolidação** do relatório mostra os totais, a tabela por agente e uma grade paginada dos itens, com
filtros por tipo, agente e destino, ordenação por coluna e link para o achado no relatório. O Markdown ganha a seção
"Consolidação do Hub". Relatórios anteriores não têm esse campo (`null`).

**Como a conformidade é garantida.** O `BeanOutputConverter` envia o schema como instrução ("responda somente com JSON
que obedeça a este JSON Schema"); a API não restringe a geração token a token. Por isso há defesas em camadas:

1. `LenientOutputConverter`: tolera texto antes/depois do JSON (extrai do primeiro `{` ao último `}`), evitando
  descartar toda a investigação por causa de um preâmbulo.
2. Validação do Structured Output: o `ClaudeService` valida o JSON extraído contra o schema do record
  (`JsonSchemaValidator`). Divergências toleráveis viram `schemaViolations` (metadado + limitação no relatório); se a
   conversão falhar, o erro cita as violações ("Resposta fora do JSON Schema de X").
3. Records tolerantes: listas nulas viram vazias; `confidence` ausente é tratada no `AgentResult`.
4. Validação semântica pelo Hub: o `Evaluator` reprova resumo vazio, confiança baixa e achados sem evidência; a
  correção assistida valida cada edição contra o arquivo real (`EditApplier`).
5. Retry: falha de conversão repete a execução (agentes: `max-attempts`; correção: 1 nova tentativa com os erros).

Evolução possível: usar o modo nativo de *structured outputs* da Anthropic (decodificação restrita ao schema) quando
estiver disponível no Spring AI para o modelo em uso — reduziria as falhas de formato, mas não substitui a validação
semântica do Hub.

### 14.2 Visão geral


| Tipo                       | Quantidade                                                    | Origem                                                                                 | Direção                                           |
| -------------------------- | ------------------------------------------------------------- | -------------------------------------------------------------------------------------- | ------------------------------------------------- |
| Structured Output          | 4 (`PlanDraft`, `AgentAnalysis`, `SynthesisDraft`, `FixPlan`) | records Java, via `BeanOutputConverter` (draft 2020-12, `additionalProperties: false`) | Claude → aplicação (resposta final)               |
| Tool Input — em uso        | 21 (`filesystem` 5, `code-search` 1, `git` 7, `database` 8)   | servidor MCP (`tools/list`) ou `@Tool`/`@ToolParam`                                    | Claude → ferramenta (argumentos)                  |
| Tool Input — reserva       | 4 (`filesystem-local`)                                        | `@Tool`/`@ToolParam`                                                                   | não atribuídas a agentes                          |
| Tool Input — desabilitadas | 0 (`sonarqube`)                                               | —                                                                                      | sem servidor configurado, nenhum schema publicado |


Fontes da verdade, sempre atualizadas: `GET /api/schemas`, página **JSON Schemas** da UI e a cópia em
`docs/schemas/` (`structured-output/*.schema.json` e `tool-input/<fonte>/*.schema.json`).

### 14.3 Matriz: quem usa quais schemas

Cada linha corresponde a uma chamada ao Claude. A coluna "Tool Input" lista os schemas enviados no parâmetro `tools`
da Messages API; todos entram na context window da chamada a cada turno.


| Chamador           | Structured Output | Tool Input (fontes → ferramentas)                                        | Máx. chamadas de ferramenta | Prefixo dos achados |
| ------------------ | ----------------- | ------------------------------------------------------------------------ | --------------------------- | ------------------- |
| Planner            | `PlanDraft`       | nenhuma                                                                  | —                           | —                   |
| Architecture Agent | `AgentAnalysis`   | `filesystem` (5) · `code-search` (1) · `git` (7) · `sonarqube`* = **13** | 30                          | `ARC-`              |
| Backend Agent      | `AgentAnalysis`   | `filesystem` (5) · `code-search` (1) · `git` (7) · `sonarqube`* = **13** | 30                          | `BCK-`              |
| Frontend Agent     | `AgentAnalysis`   | `filesystem` (5) · `code-search` (1) · `git` (7) · `sonarqube`* = **13** | 30                          | `FRT-`              |
| Database Agent     | `AgentAnalysis`   | `filesystem` (5) · `code-search` (1) · `git` (7) · `database` (8) = **21** | 25                        | `DB-`               |
| Security Agent     | `AgentAnalysis`   | `filesystem` (5) · `code-search` (1) · `git` (7) · `sonarqube`* = **13** | 30                          | `SEC-`              |
| Test Agent         | `AgentAnalysis`   | `filesystem` (5) · `code-search` (1) · `git` (7) = **13**                | 25                          | `TST-`              |
| ReportSynthesizer  | `SynthesisDraft`  | nenhuma                                                                  | —                           | —                   |
| RemediationService | `FixPlan`         | `filesystem` (5) · `code-search` (1) = **6**                             | 25                          | —                   |


 fonte declarada e desabilitada: não contribui com schemas até existir servidor real.

Limites comuns a todas as chamadas: `max_tokens` 8192 (`ANTHROPIC_MAX_TOKENS`, ajustável por agente em
`agenthub.agents.<id>.max-output-tokens`) e orçamento de contexto 120.000 tokens (`AGENTHUB_CONTEXT_BUDGET`, ajustável
em `agenthub.agents.<id>.context-budget-tokens`). Os 6 especialistas fazem até 2 tentativas por execução
(`max-attempts`) e até 2 rodadas (`max-iterations`).

### 14.4 Structured Outputs

Em todos os quatro, todos os campos são `required` e não se aceitam campos extras. Campos de lista ausentes viram
listas vazias no record.

#### `AgentAnalysis` — saída dos 6 especialistas (a única que alimenta o agrupador)

Record `model/AgentAnalysis`. Os campos `id` e `agent` do achado não fazem parte do schema: o Hub os define.


| Campo             | Tipo                    | Regra / uso pelo Hub                                                        |
| ----------------- | ----------------------- | --------------------------------------------------------------------------- |
| `summary`         | string                  | Resumo da área. Vazio reprova no `Evaluator`.                               |
| `findings`        | array de `FindingDraft` | Achados; o `ResultAggregator` deduplica e numera (`SEC-001`, ...).          |
| `recommendations` | array de string         | Recomendações gerais da área; repetidas entre agentes são unificadas.       |
| `confidence`      | number (double)         | 0.0 a 1.0. Abaixo de `min-confidence` (0.5) o `Evaluator` pede nova rodada. |


`FindingDraft`:


| Campo            | Tipo                                             | Regra / uso pelo Hub                                                                                           |
| ---------------- | ------------------------------------------------ | -------------------------------------------------------------------------------------------------------------- |
| `title`          | string                                           | Título; com o arquivo da primeira evidência, forma a chave de deduplicação entre agentes.                      |
| `description`    | string                                           | Problema e impacto.                                                                                            |
| `severity`       | enum `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `INFO` | Ordena o relatório e o roadmap.                                                                                |
| `category`       | string                                           | Livre (ex.: `security`, `maintainability`); exibida na UI e usada para separar achados de qualidade de código. |
| `evidence`       | array de `Evidence`                              | Achado sem evidência conta como fraco no `Evaluator`.                                                          |
| `recommendation` | string                                           | Como corrigir; é a entrada da correção assistida.                                                              |


`Evidence`:


| Campo     | Tipo   | Regra / uso pelo Hub                                                                                                                       |
| --------- | ------ | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `file`    | string | Caminho relativo à raiz do projeto; o da primeira evidência entra na chave de deduplicação; contado em "arquivos citados" na consolidação. |
| `lines`   | string | Ex.: `"42"` ou `"40-55"`; obtido com `fs_search_text`.                                                                                     |
| `excerpt` | string | Trecho citado; segredos são mascarados antes de exibir.                                                                                    |


Destino: `AgentAnalysis` → `AgentResult` (+ papel, status, rodada, métricas) → `ResultAggregator` →
`AnalysisReport` e `HubConsolidation` (ver §14.1).

#### `PlanDraft` — saída do Planner

Record `Planner.PlanDraft`. Só é usado com `agenthub.planner.mode: llm`; se a chamada falhar ou vier vazia, o
Planner monta o plano por regras.


| Campo       | Tipo                 | Regra / uso pelo Hub                                           |
| ----------- | -------------------- | -------------------------------------------------------------- |
| `objective` | string               | Objetivo da análise.                                           |
| `rationale` | string               | Por que esses agentes foram escolhidos (exibido no relatório). |
| `tasks`     | array de `TaskDraft` | Uma tarefa por agente.                                         |


`TaskDraft`:


| Campo       | Tipo            | Regra / uso pelo Hub                                                                                                                                        |
| ----------- | --------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `agent`     | string          | Id do agente (`architecture`, `backend`, `frontend`, `database`, `security`, `test`); desconhecido ou desabilitado é descartado; repetido, vale o primeiro. |
| `objective` | string          | Tarefa enviada ao agente no prompt.                                                                                                                         |
| `focus`     | array de string | Pontos de atenção da tarefa.                                                                                                                                |
| `dependsOn` | array de string | Ids de agentes que precisam terminar antes; ids inválidos são ignorados.                                                                                    |




#### `SynthesisDraft` — saída do ReportSynthesizer

Record `ReportSynthesizer.SynthesisDraft`. Se a chamada falhar, a síntese é montada por regras.


| Campo                | Tipo                   | Regra / uso pelo Hub                    |
| -------------------- | ---------------------- | --------------------------------------- |
| `executiveSummary`   | string                 | Sumário executivo do relatório.         |
| `codeQuality`        | string                 | Avaliação geral da qualidade do código. |
| `roadmap`            | array de `RoadmapItem` | Plano de ação por fases.                |
| `keyRecommendations` | array de string        | Recomendações principais.               |


`RoadmapItem`:


| Campo             | Tipo                                             | Regra / uso pelo Hub                                                             |
| ----------------- | ------------------------------------------------ | -------------------------------------------------------------------------------- |
| `phase`           | string                                           | Fase (ex.: "Imediato", "Curto prazo").                                           |
| `priority`        | enum `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `INFO` | Prioridade da ação.                                                              |
| `action`          | string                                           | O que fazer.                                                                     |
| `rationale`       | string                                           | Por quê.                                                                         |
| `relatedFindings` | array de string                                  | Ids de achados (`SEC-001`, ...); ids que não existem no relatório são removidos. |




#### `FixPlan` — saída da correção assistida

Record `remediation/FixPlan`. Validado contra os arquivos reais antes de virar proposta; com erro, há 1 nova
tentativa informando os problemas ao modelo.


| Campo         | Tipo                  | Regra / uso pelo Hub                                                                        |
| ------------- | --------------------- | ------------------------------------------------------------------------------------------- |
| `summary`     | string                | O que a correção faz.                                                                       |
| `changes`     | array de `FileChange` | No máximo `max-files` (8) arquivos, um item por arquivo; vazio gera proposta `MANUAL_ONLY`. |
| `manualSteps` | array de string       | Ações fora do código (rotacionar segredo, configurar variável...).                          |
| `risks`       | array de string       | Efeitos colaterais e o que testar.                                                          |
| `confidence`  | number (double)       | 0.0 a 1.0, exibida na proposta.                                                             |


`FileChange`:


| Campo         | Tipo            | Regra / uso pelo Hub                                                                                          |
| ------------- | --------------- | ------------------------------------------------------------------------------------------------------------- |
| `path`        | string          | Relativo à raiz; passa por `resolveForWrite` (escopo, excluídos, nunca arquivo sensível, sem diretório/link). |
| `action`      | string          | `MODIFY` (usa `edits`) ou `CREATE` (usa `content`).                                                           |
| `explanation` | string          | Por que o arquivo muda.                                                                                       |
| `edits`       | array de `Edit` | Só em `MODIFY`.                                                                                               |
| `content`     | string          | Conteúdo completo, só em `CREATE`.                                                                            |


`Edit`:


| Campo     | Tipo   | Regra / uso pelo Hub                                                                     |
| --------- | ------ | ---------------------------------------------------------------------------------------- |
| `search`  | string | Trecho exato e **único** no arquivo atual (o `EditApplier` rejeita ausente ou repetido). |
| `replace` | string | Texto substituto; não pode reintroduzir valores mascarados (`***MASKED...***`).          |




### 14.5 Tool Input Schemas

Antes de executar qualquer ferramenta, o `GuardedToolCallback` valida os argumentos contra o schema abaixo
("ARGUMENTOS INVÁLIDOS" ao modelo se não obedecerem) e depois aplica as regras do Hub da coluna "Validação do Hub".

#### Fonte `filesystem` — servidor MCP oficial (`@modelcontextprotocol/server-filesystem`, draft-07)

Usada pelos 6 especialistas e pela correção assistida.


| Ferramenta            | Parâmetros ( obrigatório)                                                             | Validação do Hub                                                                                 |
| --------------------- | ------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| `list_directory`      | `path` string                                                                         | `path` resolvido pelo sandbox e enviado absoluto; saída sem diretórios excluídos/fora do escopo. |
| `read_text_file`      | `path` string · `head` number (primeiras N linhas) · `tail` number (últimas N linhas) | `path` pelo sandbox; arquivo sensível negado; saída mascarada e cortada em 30.000 caracteres.    |
| `read_multiple_files` | `paths` array de string (mín. 1)                                                      | cada item pelo sandbox; um item proibido nega a chamada inteira.                                 |
| `search_files`        | `path` string · `pattern` string (glob por nome) · `excludePatterns` array de string  | `path` pelo sandbox; resultados de diretórios excluídos/fora do escopo removidos.                |
| `get_file_info`       | `path` string                                                                         | `path` pelo sandbox.                                                                             |


Bloqueadas (publicadas pelo servidor, fora da allowlist): `read_file`, `read_media_file`, `write_file`, `edit_file`,
`create_directory`, `move_file`, `directory_tree`, `list_directory_with_sizes`, `list_allowed_directories`.

#### Fonte `code-search` — local (`ProjectFilesystemTools`)

Usada pelos 6 especialistas e pela correção assistida.


| Ferramenta       | Parâmetros ( obrigatório)                                                                                             | Validação do Hub                                                                                               |
| ---------------- | --------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| `fs_search_text` | `pattern` string (texto ou regex Java) · `glob` string (ex.: `**/*.java`) · `directory` string (relativo; padrão `.`) | `directory` pelo sandbox; arquivos sensíveis pulados; até 80 ocorrências no formato `arquivo:linha: conteúdo`. |




#### Fonte `git` — servidor MCP oficial (`mcp-server-git`)

Usada pelos 6 especialistas. O `repo_path` precisa ser a raiz do projeto ativo (ou o repositório git que a contém).


| Ferramenta          | Parâmetros ( obrigatório)                                                                                                       | Validação do Hub                    |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------- |
| `git_status`        | `repo_path` string                                                                                                              | `repo_path` contra o projeto ativo. |
| `git_log`           | `repo_path` · `max_count` integer (padrão 10) · `start_timestamp` / `end_timestamp` string ou null (ISO 8601, "2 weeks ago"...) | idem                                |
| `git_show`          | `repo_path` · `revision` string                                                                                                 | idem                                |
| `git_diff`          | `repo_path` · `target` string (branch/commit) · `context_lines` integer (padrão 3)                                              | idem                                |
| `git_diff_staged`   | `repo_path` · `context_lines` integer (padrão 3)                                                                                | idem                                |
| `git_diff_unstaged` | `repo_path` · `context_lines` integer (padrão 3)                                                                                | idem                                |
| `git_branch`        | `repo_path` · `branch_type` string (`local`, `remote`, `all`) · `contains` / `not_contains` string ou null (sha)                | idem                                |


Bloqueadas: `git_add`, `git_commit`, `git_reset`, `git_checkout`, `git_create_branch`.

#### Fonte `database` — PostgreSQL MCP (`postgres-mcp` 0.3.0, modo restrito)

Usada só pelo Database Agent. Não há argumento-caminho; a proteção vem do modo restrito e do usuário `agenthub_ro`
(§13.5).


| Ferramenta                 | Parâmetros (\* obrigatório)                                                                                          | Observação                                                                              |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| `list_schemas`             | —                                                                                                                   | schemas do banco.                                                                       |
| `list_objects`             | `schema_name`\* string · `object_type` string (padrão `table`; também `view`, `sequence`, `extension`)               | tabelas/views/sequências de um schema.                                                  |
| `get_object_details`       | `schema_name`\* · `object_name`\* string · `object_type` string (padrão `table`)                                      | colunas, tipos, constraints e índices.                                                  |
| `explain_query`            | `sql`\* string · `analyze` boolean (padrão `false`) · `hypothetical_indexes` array                                   | plano de execução; o prompt pede `analyze: false`. Índices hipotéticos exigem `hypopg`. |
| `analyze_db_health`        | `health_type` string (padrão `all`; `index`, `connection`, `vacuum`, `sequence`, `replication`, `buffer`, `constraint`) | índices inválidos/duplicados/inchados, vacuum, sequências perto do limite, cache.       |
| `analyze_query_indexes`    | `queries`\* array (até 10 SQL) · `max_index_size_mb` integer (padrão 10000) · `method` `dta`/`llm` (padrão `dta`)    | sugere índices para as queries. Exige `hypopg` (ausente no `meusservicos`).             |
| `analyze_workload_indexes` | `max_index_size_mb` integer · `method` `dta`/`llm`                                                                  | sugere índices para a carga real. Exige `hypopg` e `pg_stat_statements` (ausentes).     |
| `get_top_queries`          | `sort_by` string (padrão `resources`) · `limit` integer (padrão 10)                                                 | queries mais lentas/pesadas. Exige `pg_stat_statements` (ausente).                      |


Bloqueada: `execute_sql` (fora da allowlist e barrada pelo filtro de escrita).

#### Fonte `filesystem-local` — reserva local (sem Node/`npx`)

Não atribuída a agentes. Para usá-la, troque `filesystem` por `filesystem-local` em `agenthub.agents.<id>.tool-sources`.


| Ferramenta          | Parâmetros ( obrigatório)                                                      | Comportamento                          |
| ------------------- | ------------------------------------------------------------------------------ | -------------------------------------- |
| `fs_list_directory` | `path` string (relativo; `.` para a raiz)                                      | lista só entradas visíveis.            |
| `fs_read_file`      | `path` string · `startLine` integer (padrão 1) · `maxLines` integer (máx. 400) | devolve linhas numeradas e mascaradas. |
| `fs_search_text`    | igual ao de `code-search`                                                      | —                                      |
| `fs_find_files`     | `glob` string                                                                  | até 200 arquivos.                      |




### 14.6 Ciclo de vida de um schema numa chamada


| Etapa          | Tool Input Schema                                             | Structured Output Schema                                           |
| -------------- | ------------------------------------------------------------- | ------------------------------------------------------------------ |
| 1. Geração     | publicado pelo servidor MCP ou gerado da assinatura `@Tool`   | gerado do record pelo `BeanOutputConverter`                        |
| 2. Envio       | parâmetro `tools` da Messages API                             | instrução de formato no fim do prompt                              |
| 3. Custo       | entra na parte fixa da context window a cada turno            | idem                                                               |
| 4. Validação   | `JsonSchemaValidator` antes de executar → `toolInputRejected` | `JsonSchemaValidator` na resposta → `schemaViolations`             |
| 5. Autorização | allowlist, orçamento e sandbox (`ProjectWorkspace`)           | —                                                                  |
| 6. Uso         | resultado mascarado volta ao modelo                           | record desserializado → `AgentResult` / plano / síntese / proposta |
| 7. Observação  | `event=tool.call` no log; métricas na aba Desempenho          | `metadata.schemaViolations`; aba Consolidação                      |




### 14.7 Manutenção

- **Mudou um record de saída** (`AgentAnalysis`, `PlanDraft`, `SynthesisDraft`, `FixPlan`): o schema muda junto; o
`SchemaCatalogTest` confere que os quatro estão no catálogo, que só o `AgentAnalysis` alimenta o Hub e o formato
(draft 2020-12, sem campos extras). Atualize as tabelas da §14.4.
- **Mudou a allowlist ou a versão de um servidor MCP**: os schemas vêm do servidor em tempo de execução; confira em
`/api/schemas` o que foi exposto e o que ficou bloqueado.
- **Atualizar** `docs/schemas/`: com o backend rodando, exporte `GET /api/schemas` (um arquivo por schema). A cópia
é documentação; a aplicação nunca lê essa pasta.
- **Nova fonte ou agente**: inclua a linha na matriz da §14.3 e a tabela de ferramentas na §14.5; se for um
servidor MCP, descreva-o também na §13.

