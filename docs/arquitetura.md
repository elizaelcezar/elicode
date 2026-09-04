# Arquitetura — elicode

## Princípio

Separar **interface (APK)** de **execução (servidor)**. O celular nunca
compila, nunca roda modelo, nunca guarda o repo principal.

## Componentes

### Servidor (Linux x86_64, 2 vCPU / 4 GB sugerido)

* **OpenCode em modo `serve`** (`opencode serve --port 4096 --hostname 127.0.0.1`):
  expõe API HTTP + streaming (SSE) com o loop agente completo.
* **Um diretório de trabalho** (`~/elicode/work`): clone do projeto ativo.
  Um projeto por vez — trocar de projeto = trocar de sessão + re-clonar.
* **Git no servidor**: clone/pull/commit/push via token GitHub (OAuth device
  flow feito uma vez; token guardado com permissão 600).
* **Preview**: `opencode` serve arquivos / processo do projeto (ex.: Vite na
  porta 5173) com acesso via túnel; o APK carrega a URL no WebView.
* **Modelos**: mesma autenticação free já usada no desktop (`opencode.json`
  do usuário; chaves nunca vão para o APK).

### APK (Kotlin, minSdk 26)

* **Chat**: lista de mensagens + streaming SSE, composer, anexos de arquivo.
* **Sessões**: criar/continuar/renomear/encerrar; uma ativa por vez (v1).
* **Arquivos**: navegar na árvore do projeto (via API), ver e editar texto,
  diff antes de aplicar (quando o agente propõe edição manual).
* **Preview**: WebView (local para HTML ou URL do túnel para o resto).
* **GitHub**: conectar conta, escolher repo, clonar no servidor, status,
  commit + push pelo app.
* **Conexão**: URL base configurável + token de pareamento (QR code gerado
  no servidor). Nada de IP fixo: usa Tailscale ou Cloudflare Tunnel.

### Rede (sem abrir porta)

Opção A — **Tailscale** (recomendado p/ começar): VPN mesh, celular e VPS
na mesma rede privada, HTTPS interno. Zero config de firewall.

Opção B — **Cloudflare Tunnel** (`cloudflared`): expõe `https://...` pública
com Access (login) na frente. Bom se um dia houver multi-usuário.

## Protocolos (fase 1)

* `GET /session` / `POST /session` — criar/listar sessões (conforme API do
  `opencode serve`; ajustar ao `GET /event` SSE da versão instalada).
* Mensagens: `POST /session/:id/message` com `parts[]`; resposta via SSE.
* Arquivos: endpoints do servidor elicode (wrapper próprio em Node, fase 2)
  ou comandos via mensagem do agente na fase 1 (MVP usa o próprio agente
  para `git status`, ler arquivos etc. — menos código, mais IA).
* Preview: URL direta do processo + WebView.

## Decisões registradas

1. Um projeto por vez (simplifica MVP; multi-projeto é fase futura).
2. Git operado no servidor (JGit no celular descartado por enquanto).
3. VPS: começar na Oracle Free (R$ 0), migrar p/ Hetzner CX23 (~R$ 32/mês)
   quando virar uso diário.
4. `localhost` (Termux) suportado depois só trocando a URL base — sem mudar
   o app. Ver `docs/fases.md` (fase 4).
