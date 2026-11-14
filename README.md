# Rullino

Fotocamera analogica per Android: carichi un rullino di un'emulsione, scatti senza vedere le foto,
carichi la leva dopo ogni posa e, finite le 24 o 36 pose, sviluppi il rullino.

Le emulsioni si creano con **Camera Oscura** (l'editor web) e si esportano come file `.emulsion`.

## Come provarla

1. Apri la cartella in **Android Studio** (Ladybug o più recente) e lascia che sincronizzi Gradle.
2. Collega il telefono con il debug USB attivo e premi **Run**.
3. Al primo avvio trovi già 6 emulsioni di base. Per aggiungerne altre: tab *Emulsioni → Importa .emulsion*,
   oppure apri un file `.emulsion` da file manager o chat.

Requisiti: Android 10 (API 29) o più recente, fotocamera posteriore.

## Come funziona

| Cartella | Cosa c'è |
|---|---|
| `data/` | Formato `.emulsion` (lettura e verifica), database Room di emulsioni, rullini e pose, client del negozio |
| `develop/` | Sviluppo: lo stesso shader GLSL di Camera Oscura, eseguito in OpenGL ES 3 fuori schermo |
| `ui/` | Schermate Compose: fotocamera (leva, contafotogrammi, finestrella), emulsioni e negozio, rullini e provini |

- Gli scatti restano nella memoria privata dell'app (`files/rolls/<id>/`) finché il rullino non è sviluppato.
- Lo sviluppo salva le foto in *Immagini/Rullino/<emulsione data #rullino>*.
- La grana usa un seme diverso per ogni posa, come su pellicola vera.

## Formato `.emulsion` (versione 1)

È uno ZIP con estensione propria:

```
mimetype          application/x-emulsion
manifest.json     id, nome, ISO, pose, tipo, elenco file, sha256 di ogni file
preset.json       parametri dell'emulsione (come li esporta Camera Oscura)
box.webp          scatola (facoltativa)
canister.webp     rullino visto dalla finestrella del dorso (facoltativa)
demo/1.jpg, 2.jpg foto di esempio (facoltative)
lut.cube          LUT 33³ solo colore, per usi esterni (facoltativa)
```

L'app rifiuta file con `formatVersion` più nuovo di quello che conosce o con hash che non tornano.

## Negozio

1. Crea un progetto Supabase ed esegui `supabase/schema.sql`.
2. Carica i file `.emulsion` (ed eventualmente le scatole) nel bucket pubblico `emulsions`.
3. Aggiungi una riga nella tabella `emulsions` per ognuno, con `published = true`.
4. Copia `local.properties.example` in `local.properties`, metti URL e chiave pubblicabile, ricompila.

Le emulsioni a pagamento (`price_cents > 0`) per ora compaiono come "presto": l'acquisto una tantum
arriverà con Google Play Billing.

## Shader

`develop/FilmShader.kt` è una copia esatta dello shader di Camera Oscura. Se cambi lo shader nell'editor,
aggiorna anche questo file, altrimenti le foto sviluppate non coincidono con l'anteprima.
