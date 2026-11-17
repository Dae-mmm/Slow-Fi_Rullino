// Rigenera il catalogo leggendo i file .emulsion del bucket "emulsions".
// Parte da sola (trigger su storage.objects) ogni volta che aggiungi, sostituisci o togli un file.
// Non usa nulla della richiesta: chiunque la chiami ottiene solo una nuova sincronizzazione.
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";
import { unzipSync, strFromU8 } from "npm:fflate@0.8.2";

const BUCKET = "emulsions";
const MIMETYPE = "application/x-emulsion";

async function sha256(buf: ArrayBuffer): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", buf);
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function listAll(sb: any, prefix = ""): Promise<string[]> {
  const out: string[] = [];
  const { data, error } = await sb.storage.from(BUCKET).list(prefix, { limit: 1000 });
  if (error) throw error;
  for (const o of data ?? []) {
    const path = prefix ? `${prefix}/${o.name}` : o.name;
    if (o.id === null) { if (o.name !== "boxes") out.push(...(await listAll(sb, path))); }
    else if (o.name.toLowerCase().endsWith(".emulsion")) out.push(path);
  }
  return out;
}

Deno.serve(async (req: Request) => {
  const sb = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const report: Record<string, string> = {};
  try {
    const paths = await listAll(sb);
    const { data: rows } = await sb.from("emulsions").select("id,version,file_sha,file_path");
    const byPath = new Map((rows ?? []).map((r: any) => [r.file_path, r]));
    const byId = new Map((rows ?? []).map((r: any) => [r.id, r]));
    const seen = new Set<string>();

    for (const path of paths) {
      try {
        const { data: blob, error } = await sb.storage.from(BUCKET).download(path);
        if (error || !blob) throw new Error("download");
        const buf = await blob.arrayBuffer();
        const sha = await sha256(buf);
        const prev: any = byPath.get(path);
        if (prev && prev.file_sha === sha) { seen.add(prev.id); report[path] = "invariato"; continue; }

        const files = unzipSync(new Uint8Array(buf));
        if (!files["mimetype"] || strFromU8(files["mimetype"]).trim() !== MIMETYPE) throw new Error("non è un'emulsione");
        if (!files["manifest.json"]) throw new Error("manca manifest.json");
        const m = JSON.parse(strFromU8(files["manifest.json"]));
        const f = m.files ?? {};
        const presetBytes = files[f.preset ?? "preset.json"];
        if (!presetBytes) throw new Error("manca il preset");
        const p = JSON.parse(strFromU8(presetBytes));
        const id = String(m.id || p.id || "").trim();
        if (!id) throw new Error("manca l'id");

        const old: any = byId.get(id) ?? prev;
        const version = old ? (old.file_sha === sha ? old.version : (old.version ?? 0) + 1) : 1;

        let box_url: string | null = null;
        if (f.box && files[f.box]) {
          const ext = String(f.box).split(".").pop()!.toLowerCase();
          const boxPath = `boxes/${id}.${ext}`;
          await sb.storage.from(BUCKET).upload(boxPath, files[f.box], {
            upsert: true, contentType: ext === "png" ? "image/png" : "image/jpeg",
          });
          box_url = sb.storage.from(BUCKET).getPublicUrl(boxPath).data.publicUrl + `?v=${version}`;
        }
        const mono = !!p.params?.mono?.enabled;
        const row = {
          id,
          name: m.name || p.name || id,
          iso: m.iso ?? p.iso ?? 400,
          exposures: m.exposures ?? p.exposures ?? 36,
          type: mono || m.type === "bw" ? "bw" : "color",
          note: m.note || p.note || "",
          version,
          size_bytes: buf.byteLength,
          file_url: sb.storage.from(BUCKET).getPublicUrl(path).data.publicUrl + `?v=${version}`,
          file_sha: sha,
          file_path: path,
          box_url,
          published: true,
          updated_at: new Date().toISOString(),
        };
        const { error: upErr } = await sb.from("emulsions").upsert(row);
        if (upErr) throw upErr;
        seen.add(id);
        report[path] = `pubblicata ${id} v${version}`;
      } catch (e) {
        report[path] = `scartata: ${(e as Error).message}`;
      }
    }

    // File tolti dal bucket: l'emulsione sparisce dal catalogo (chi l'ha già la tiene).
    for (const r of rows ?? []) {
      if (r.file_path && !seen.has(r.id) && !paths.includes(r.file_path)) {
        await sb.from("emulsions").update({ published: false }).eq("id", r.id);
        report[r.file_path] = `ritirata ${r.id}`;
      }
    }
    return Response.json({ ok: true, report });
  } catch (e) {
    return Response.json({ ok: false, error: (e as Error).message, report }, { status: 500 });
  }
});
