# app/ — APK Kotlin (fase 1)

Ainda não iniciado. Escopo da fase 1:

* `MainActivity` + `ChatScreen` (Jetpack Compose): lista de mensagens,
  composer, indicador de "agente trabalhando".
* `ApiClient` (OkHttp + SSE): base URL + basic auth (usuário/senha do
  pareamento); DataStore.
* `SessionList`: criar/continuar sessão (uma ativa por vez no MVP).
* Tela de configuração de conexão (URL + token, com QR opcional).
* Preview WebView entra na fase 3.

Só começar após a VPS da fase 1 responder `GET /` do `opencode serve`.
