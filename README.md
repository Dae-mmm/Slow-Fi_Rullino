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

## Provarla dal telefono, senza PC

A ogni modifica su `main`, GitHub compila l'app e pubblica l'APK nella pagina
**Releases** del repository (`Rullino-build-N.apk`). Dal telefono: apri Releases,
scarica l'ultima build, installala (consenti l'installazione da browser la prima volta).

La chiave di firma di prova è in `keystore/` ed è la stessa usata da Android Studio,
quindi le build di GitHub e quelle del PC si installano una sopra l'altra.

## Come funziona

| Cartella | Cosa c'è |
|---|---|
| `data/` | Formato `.emulsion` (lettura e verifica), database Room di emulsioni, rullini e pose, client del negozio |
| `develop/` | Sviluppo: lo stesso shader GLSL di Camera Oscura, eseguito in OpenGL ES 3 fuori schermo |
| `ui/` | Schermate Compose: fotocamera (leva, contafotogrammi, finestrella), emulsioni e negozio, rullini e provini |

- Gli scatti restano nella memoria privata dell'app (`files/rolls/<id>/raw`).
- **Scegli un rullino** (macchina vuota): scegli tra quelli in magazzino, 24 o 36 pose; se non ne hai, vai al negozio.
- **Manovella di riavvolgimento** (in alto a sinistra), da girare col dito in senso orario:
  senza **R**, ogni giro torna indietro di una posa (doppia esposizione); con **R** acceso riavvolgi tutta
  la pellicola e il rullino esce dalla macchina, pronto per il laboratorio.
- **Apri il dorso**: metti da parte il rullino per finirlo dopo, oppure eliminalo.
- **Rullini → Vai al laboratorio**: al negozio sviluppi tutti i rullini scaricati in un colpo;
  quando sono pronti il negoziante li riporta da solo in Rullini.
- Le esposizioni sullo stesso fotogramma si fondono sommando la luce (fusione *screen*), come sulla pellicola.
- **Sviluppo** → negativi nella memoria dell'app (`files/rolls/<id>/dev`). In galleria non va niente.
- **Tavolo luminoso** (Rullini): striscia di negativi con perforazioni e scritte di bordo; il lentino mostra il positivo.
  Si stampa una posa o una selezione: solo le stampe vanno in *Immagini/Rullino/<emulsione data #rullino>*.
- **Taglio dei negativi**: tieni le pose scelte, il resto (con le eventuali stampe) viene eliminato.
- La grana usa un seme diverso per ogni posa, come su pellicola vera.
- **Negozio** (Foto Lab anni '80): il negoziante ti regala il **rullino del giorno** (uno al giorno, a scelta tra tutte le emulsioni), vende rullini a **gettoni** (valuta interna, in futuro si vinceranno con i concorsi) e gestisce il **laboratorio**:
  consegni i rullini riavvolti, ti dice a che ora tornare, e ritiri i negativi.
- **Magazzino**: caricare un rullino in macchina ne consuma uno. Un rullino caricato e mai usato torna in magazzino.
- **Il tuo negoziante**: metti il disegno in `app/src/main/assets/shop/negoziante.png` e sostituisce il segnaposto
  (viene ingrandito senza sfocature).
- **Liberatorie**: per ogni posa (in fotocamera sull'ultima posa, o dal lentino sul tavolo luminoso) modulo con nome,
  cognome, contatto, luogo, usi fuori dall'app, minori con genitore/tutore e firma col dito. PDF in Download/Rullino.
  Il testo è un **segnaposto** (`assets/liberatoria/testo-<lingua>.txt`) modificabile anche da Opzioni.
- **Laboratorio**: dopo la consegna i negativi sono pronti tra 1 ora, 3 ore o 1 giorno (Opzioni).
- **Modalità sviluppatore**: in Opzioni tocca 7 volte la versione; poi puoi saltare i tempi del laboratorio.
- Il database è alla versione 4: passando da una versione precedente l'app riparte da zero (rullini di prova inclusi).

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

## iOS (versione provvisoria)

Nella cartella `ios/` c'è una versione SwiftUI dell'app (fotocamera, leva e manovella,
laboratorio, tavolo luminoso, negozio, opzioni, 5 lingue). Mancano per ora: liberatorie,
import di `.emulsion` e catalogo online.

Per installarla sul tuo iPhone:

1. Apri `ios/Rullino.xcodeproj` con Xcode 16 o successivo.
2. Seleziona il target **Rullino** › *Signing & Capabilities* › *Team*: scegli il tuo Apple ID
   (Personal Team). Se Xcode dice che il bundle id è già usato, cambialo (es. `com.tuonome.rullino`).
3. Collega l'iPhone, attiva la *Modalità sviluppatore* (Impostazioni › Privacy e sicurezza) e premi ▶︎.
4. Al primo avvio: Impostazioni › Generali › VPN e gestione dispositivi › fidati del tuo profilo.

Con l'Apple ID gratuito la firma dura 7 giorni: poi basta ripremere ▶︎ in Xcode (i dati restano).
Le stringhe iOS si generano da `tools/i18n/table.py` insieme a quelle Android (`gen.py`).
