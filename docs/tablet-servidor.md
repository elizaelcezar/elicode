# Servidor em casa — tablet Android (ex.: Redmi Pad 2)

R$ 0, usando um tablet dedicado sempre no carregador. Vale para
experimentar e usar; VPS continua sendo o caminho robusto.

Requisitos: ARM64, **4 GB+ RAM**, Android 9+, 10 GB livres.

## Parte A — Sistema (uma vez)

1. **Opções de desenvolvedor**: Config → Sobre o tablet → toque 7x em
   "Versão do SO". Depois: Config adicionais → Opções do desenvolvedor →
   **Permanecer ativo ao carregar = LIGADO**.
2. **Bateria**: Config → Bateria → economia por app → **Termux: Sem
   restrições**. Config → Apps → **Inicialização automática**: ligue para
   Termux e Termux:Boot.
3. Trave o Termux nos recentes (ícone de cadeado) para a MIUI não fechar.
4. **Wi-Fi**: Config → Wi-Fi → Avançado → **manter ligado durante
   suspensão = Sempre**.

## Parte B — Apps (via F-Droid, NÃO Play Store)

1. Instale o https://f-droid.org e por ele: **Termux**, **Termux:Boot**,
   **Termux:API**. (As versões da Play estão abandonadas.)
2. **Tailscale** pode ser o da Play mesmo. Logue com sua conta.

## Parte C — Linux + OpenCode (no Termux)

```sh
pkg update && pkg upgrade -y
pkg install -y proot-distro git openssh termux-api
proot-distro install ubuntu
proot-distro login ubuntu
```

Dentro do Ubuntu:

```sh
apt update && apt install -y curl git ca-certificates
curl -fsSL https://opencode.ai/install | bash
/root/.opencode/bin/opencode --version   # teste de compatibilidade
mkdir -p ~/elicode/work
```

> Se `--version` falhar: o binário não é compatível com seu aparelho —
> pare aqui e use a VPS. (No ARM64 com glibc costuma funcionar.)

## Parte D — Subir sozinho (serve + boot)

Ainda no Ubuntu, crie `~/elicode-start.sh`:

```sh
#!/bin/bash
export OPENCODE_SERVER_PASSWORD="TROQUE-POR-UMA-SENHA-FORTE"
cd ~/elicode/work
/root/.opencode/bin/opencode serve --port 4096 --hostname 0.0.0.0 &
/root/.opencode/bin/opencode web --port 4097 --hostname 0.0.0.0 &
wait
```

> `serve` (4096) é a API para o futuro app cliente; `web` (4097) é o
> console usável hoje no navegador/WebView do próprio aparelho.

```sh
chmod +x ~/elicode-start.sh
chmod 600 ~/elicode-start.sh
```

No Termux (fora do proot), crie `~/.termux/boot/start-elicode.sh`:

```sh
#!/data/data/com.termux/files/usr/bin/sh
termux-wake-lock
proot-distro login ubuntu -- /root/elicode-start.sh
```

```sh
chmod +x ~/.termux/boot/start-elicode.sh
```

Reinicie o tablet para testar: após boot, o serve sobe sozinho.

## Parte E — Acesso e teste

1. No Tailscale, anote o IP `100.x` do tablet (desative a expiração da
   chave do tablet no painel admin).
2. Copie seu `opencode.json` de modelos para o Ubuntu
   (`~/.config/opencode/`).
3. Do PC: `curl -u opencode:SENHA http://100.x:4096/global/health`
   → `{"healthy":true,...}` = pronto. Mesma URL base vai no APK.

## Limites honestos

* Queda de luz/internet derruba; sem nobreak, sem SLA.
* MIUI pode matar o processo mesmo com tudo liberado — observe nos
  primeiros dias (`uptime` e logs).
* CPU de tablet sofre em task pesada; um projeto por vez aqui é regra,
  não sugestão.
