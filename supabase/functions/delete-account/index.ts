// Cancella l'account di chi la chiama (diritto all'oblio; Apple lo richiede per le app con registrazione).
// Profilo e abbonamento spariscono a cascata con l'utente.
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return new Response("method not allowed", { status: 405 });
  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const { data, error } = await admin.auth.getUser(token);
  if (error || !data.user) return Response.json({ ok: false, error: "unauthorized" }, { status: 401 });
  const { error: delErr } = await admin.auth.admin.deleteUser(data.user.id);
  if (delErr) return Response.json({ ok: false, error: delErr.message }, { status: 500 });
  return Response.json({ ok: true });
});
