# Email di Rullino (Supabase › Authentication › Email Templates)

`codice.html` va incollato **identico** in due modelli:

- **Confirm signup** (primo accesso di un nuovo utente)
- **Magic Link** (accessi successivi)

Oggetto consigliato per entrambi: `Rullino · il tuo codice / your code`

Il testo esce nella lingua del telefono (italiano, francese, spagnolo, tedesco, altrimenti inglese):
le app mandano la lingua in `user_metadata.locale` quando chiedono il codice.
