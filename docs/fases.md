# Fases — elicode

## Fase 0 — Base (atual)

* [x] Repo + decisões (VPS, git no servidor, 1 projeto por vez)
* [x] Arquitetura documentada
* [ ] VPS provisionada (Oracle Free) **ou** tablet configurado
  (`docs/tablet-servidor.md`)

## Fase 1 — Chat funcionando (MVP)

Servidor: `server/setup-vps.sh` executado; `opencode serve` no ar via
systemd; pareamento por token.

App: telas Chat + Configurar conexão; criar sessão, enviar mensagem,
receber streaming, trocar de modelo (lista do servidor).

Critério de pronto: programar uma task real pelo celular, com o PC
desligado.

## Fase 2 — GitHub + arquivos

OAuth device flow; escolher repo; clonar no servidor; ver árvore e editar
arquivo de texto; commit + push pelo app; status (`git status` resumido).

## Fase 3 — Preview + polimento

WebView local (HTML) e remota (URL do túnel); notificação "task pronta";
fila offline (mensagem enviada sem rede vai quando voltar); temas.

## Fase 4 — Futuro (pós-MVP)

* Multi-projeto (sessões paralelas).
* Modo local experimental (Termux/`localhost`) — só troca a URL base.
* Publicação (decidir Play vs. sideload).
