-- Usuario somente leitura do AgentHub (fonte "database", Postgres MCP Pro).
-- Idempotente: pode ser executado de novo para trocar a senha ou reaplicar permissoes.
--
-- Uso (como superusuario/dono das tabelas, no banco analisado):
--   psql -h localhost -p 5435 -U postgres -d meusservicos -v pwd='SENHA' -f agenthub-readonly-role.sql
-- Depois, na tela Projetos, campo "Banco de dados" do projeto:
--   postgresql://agenthub_ro:SENHA@localhost:5435/meusservicos

\set ON_ERROR_STOP on
\if :{?pwd}
\else
  \echo 'Informe a senha: -v pwd=...'
  \quit
\endif

SELECT 'CREATE ROLE agenthub_ro LOGIN'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agenthub_ro') \gexec

ALTER ROLE agenthub_ro WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    CONNECTION LIMIT 5 PASSWORD :'pwd';

-- Toda sessao ja comeca read-only e com limite de tempo por consulta.
ALTER ROLE agenthub_ro SET default_transaction_read_only = on;
ALTER ROLE agenthub_ro SET statement_timeout = '30s';
ALTER ROLE agenthub_ro SET search_path = meusservicos, public;

SELECT format('GRANT CONNECT ON DATABASE %I TO agenthub_ro', current_database()) \gexec
REVOKE CREATE ON SCHEMA public FROM agenthub_ro;

GRANT USAGE ON SCHEMA meusservicos TO agenthub_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA meusservicos TO agenthub_ro;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA meusservicos TO agenthub_ro;

-- Tabelas criadas depois pelo dono (postgres) tambem ficam legiveis.
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA meusservicos GRANT SELECT ON TABLES TO agenthub_ro;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA meusservicos GRANT SELECT ON SEQUENCES TO agenthub_ro;

-- Estatisticas (pg_stat_*, tamanhos) para analyze_db_health/get_top_queries; e somente leitura.
GRANT pg_monitor TO agenthub_ro;
