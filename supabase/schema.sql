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

-- Sincronizzazione automatica del catalogo (vedi functions/sync-catalog).
alter table public.emulsions
  add column if not exists file_sha text,
  add column if not exists file_path text,
  add column if not exists updated_at timestamptz not null default now();

create extension if not exists pg_net with schema extensions;
create schema if not exists private;

create or replace function private.emulsions_bucket_changed()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if coalesce(new.bucket_id, old.bucket_id) = 'emulsions'
     and lower(coalesce(new.name, old.name)) like '%.emulsion' then
    perform net.http_post(
      url := 'https://voxkoarjawgabrljvvon.supabase.co/functions/v1/sync-catalog',
      body := '{}'::jsonb,
      headers := '{"Content-Type": "application/json"}'::jsonb
    );
  end if;
  return null;
end; $$;
revoke all on function private.emulsions_bucket_changed() from public, anon, authenticated;

drop trigger if exists emulsions_bucket_changed on storage.objects;
create trigger emulsions_bucket_changed
  after insert or update or delete on storage.objects
  for each row execute function private.emulsions_bucket_changed();
