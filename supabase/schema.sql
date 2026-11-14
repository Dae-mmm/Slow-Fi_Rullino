-- Catalogo del negozio di emulsioni.
-- L'app legge solo le righe pubblicate, con la chiave pubblicabile (sola lettura).

create table if not exists public.emulsions (
  id          text primary key,            -- uguale a manifest.id del file .emulsion
  name        text not null,
  iso         int  not null default 400,
  exposures   int  not null default 36,
  type        text not null default 'color' check (type in ('color', 'bw')),
  note        text not null default '',
  version     int  not null default 1,
  size_bytes  bigint not null default 0,
  price_cents int  not null default 0,     -- 0 = gratis; a pagamento arriverà con Google Play
  box_url     text,                        -- miniatura della scatola (URL pubblico)
  file_url    text not null,               -- URL pubblico del file .emulsion
  sort        int  not null default 100,
  published   boolean not null default false,
  created_at  timestamptz not null default now()
);

alter table public.emulsions enable row level security;

drop policy if exists "catalogo pubblico" on public.emulsions;
create policy "catalogo pubblico" on public.emulsions
  for select to anon, authenticated
  using (published);

-- Bucket pubblico per i file .emulsion e le immagini delle scatole.
insert into storage.buckets (id, name, public)
values ('emulsions', 'emulsions', true)
on conflict (id) do nothing;
