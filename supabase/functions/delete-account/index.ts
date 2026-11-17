// Cancella l'account di chi la chiama (diritto all'oblio; Apple lo richiede per le app con registrazione).
// Profilo, abbonamento e partecipazioni ai concorsi spariscono a cascata con l'utente;
// le foto e le liberatorie mandate ai concorsi si cancellano qui dal bucket privato.
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return new Response("method not allowed", { status: 405 });
  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const { data, error } = await admin.auth.getUser(token);
  if (error || !data.user) return Response.json({ ok: false, error: "unauthorized" }, { status: 401 });
  const uid = data.user.id;

  // File nel bucket dei concorsi: <concorso>/<utente>/photo.jpg|release.pdf
  const { data: entries } = await admin.from("contest_entries").select("photo_path, release_path").eq("user_id", uid);
  const { data: contests } = await admin.from("contests").select("id");
  const paths = new Set<string>();
  for (const e of entries ?? []) {
    if (e.photo_path) paths.add(e.photo_path);
    if (e.release_path) paths.add(e.release_path);
  }
  // Anche i file rimasti senza partecipazione (invio interrotto a metà).
  for (const c of contests ?? []) {
    paths.add(`${c.id}/${uid}/photo.jpg`);
    paths.add(`${c.id}/${uid}/release.pdf`);
  }
  if (paths.size > 0) await admin.storage.from("contest-entries").remove([...paths]);

  const { error: delErr } = await admin.auth.admin.deleteUser(uid);
  if (delErr) return Response.json({ ok: false, error: delErr.message }, { status: 500 });
  return Response.json({ ok: true });
});
