-- Executar no SQL Editor do projeto Supabase. Não apaga dados existentes.
create table if not exists public.lpsm_state (
  id text primary key,
  data jsonb not null,
  updated_at timestamptz not null default now()
);
alter table public.lpsm_state enable row level security;
revoke all on table public.lpsm_state from anon, authenticated;
grant all on table public.lpsm_state to service_role;
