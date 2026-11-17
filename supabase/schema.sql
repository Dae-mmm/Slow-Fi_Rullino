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

-- =====================================================================
-- Account: ruoli (user / judge / op) e abbonamenti "pro" (per ora nascosti nelle app)
-- =====================================================================
do $$ begin
  create type public.app_role as enum ('user', 'judge', 'op');
exception when duplicate_object then null; end $$;

create table if not exists public.profiles (
  id           uuid primary key references auth.users (id) on delete cascade,
  username     text unique check (username ~ '^[A-Za-z0-9_.]{3,24}$'),
  display_name text check (char_length(display_name) <= 60),
  role         public.app_role not null default 'user',
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
alter table public.profiles enable row level security;

create table if not exists public.subscriptions (
  user_id            uuid primary key references auth.users (id) on delete cascade,
  tier               text not null default 'pro',
  status             text not null check (status in ('active', 'trialing', 'grace', 'canceled', 'expired')),
  source             text not null default 'manual' check (source in ('manual', 'google_play', 'app_store', 'promo')),
  current_period_end timestamptz,
  updated_at         timestamptz not null default now()
);
alter table public.subscriptions enable row level security;

create or replace function private.is_op()
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.profiles where id = auth.uid() and role = 'op')
$$;
revoke all on function private.is_op() from public, anon;
grant usage on schema private to authenticated;
grant execute on function private.is_op() to authenticated;

create or replace function private.handle_new_user()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles (id) values (new.id) on conflict do nothing;
  return new;
end; $$;
revoke all on function private.handle_new_user() from public, anon, authenticated;
drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function private.handle_new_user();

-- Solo un OP cambia i ruoli: select public.set_user_role('<uuid>', 'judge');
create or replace function public.set_user_role(target uuid, new_role public.app_role)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if not private.is_op() then raise exception 'solo un OP può cambiare i ruoli' using errcode = '42501'; end if;
  update public.profiles set role = new_role, updated_at = now() where id = target;
end; $$;
revoke all on function public.set_user_role(uuid, public.app_role) from public, anon;
grant execute on function public.set_user_role(uuid, public.app_role) to authenticated;

create or replace function public.update_my_profile(p_username text, p_display_name text)
returns void language sql security invoker set search_path = '' as $$
  update public.profiles
     set username = nullif(trim(p_username), ''), display_name = nullif(trim(p_display_name), ''), updated_at = now()
   where id = auth.uid();
$$;
revoke all on function public.update_my_profile(text, text) from public, anon;
grant execute on function public.update_my_profile(text, text) to authenticated;

drop policy if exists "profili visibili agli utenti" on public.profiles;
create policy "profili visibili agli utenti" on public.profiles for select to authenticated using (true);
drop policy if exists "modifico il mio profilo" on public.profiles;
create policy "modifico il mio profilo" on public.profiles for update to authenticated
  using (id = (select auth.uid())) with check (id = (select auth.uid()));
revoke insert, update, delete on public.profiles from anon, authenticated;
grant select on public.profiles to authenticated;
grant update (username, display_name, updated_at) on public.profiles to authenticated;

drop policy if exists "vedo il mio abbonamento" on public.subscriptions;
create policy "vedo il mio abbonamento" on public.subscriptions for select to authenticated
  using (user_id = (select auth.uid()) or (select private.is_op()));
revoke insert, update, delete on public.subscriptions from anon, authenticated;
grant select on public.subscriptions to authenticated;

drop policy if exists "op gestisce il catalogo" on public.emulsions;
create policy "op gestisce il catalogo" on public.emulsions for all to authenticated
  using ((select private.is_op())) with check ((select private.is_op()));
drop policy if exists "op carica emulsioni" on storage.objects;
create policy "op carica emulsioni" on storage.objects for all to authenticated
  using (bucket_id = 'emulsions' and (select private.is_op()))
  with check (bucket_id = 'emulsions' and (select private.is_op()));
