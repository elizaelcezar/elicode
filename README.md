# elicode

Programe de qualquer lugar: um APK Android que conversa com um agente de
código (OpenCode) rodando em servidor — chat, preview e sync com o GitHub
no bolso.

## Como funciona (resumo)

```
┌─ APK (Kotlin) ──────────────┐     ┌─ Servidor (Linux) ─────────┐
│ Chat streaming (SSE)        │────▶│ OpenCode serve (API)       │
│ Editor + arquivos           │     │ Modelos (auth já usado)    │
│ Preview (WebView)           │◀────│ Terminal + git + preview   │
└─────────────────────────────┘     └────────────────────────────┘
```

* **Um projeto por vez** (decisão inicial — simplifica sessão e git).
* O APK é o controle remoto; o agente executa no servidor. Tarefa não
  morre se o celular desconectar.
* A mesma API serve para servidor remoto (VPS) ou local experimental
  (Termux/`localhost`) — o app só troca a URL base.

## Documentação

* [`docs/arquitetura.md`](docs/arquitetura.md) — componentes, protocolos, decisões
* [`docs/fases.md`](docs/fases.md) — plano por etapas (MVP primeiro)
* [`server/`](server) — provisionamento da VPS + OpenCode serve
* [`app/`](app) — APK Kotlin (fase 1)

## Status

Fase 0 — estrutura e decisões. Ver `docs/fases.md`.
