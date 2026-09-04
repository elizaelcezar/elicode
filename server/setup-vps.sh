#!/usr/bin/env bash
# Provisiona a VPS (Ubuntu 22.04/24.04) para o elicode — Fase 1.
# Uso: scp este arquivo p/ VPS e rode com sudo:  sudo bash setup-vps.sh
# Ao final: OpenCode serve no ar (127.0.0.1:4096) via systemd + Tailscale.
set -euo pipefail

OPENCODE_PORT="${OPENCODE_PORT:-4096}"
WORKDIR="/opt/elicode/work"
SERVICE_USER="${SUDO_USER:-ubuntu}"
# Senha do pareamento APK <-> servidor (basic auth: usuario `opencode`).
# Gere outra depois com: sudo sed -i "s/^Environment=OPENCODE_SERVER_PASSWORD=.*/Environment=OPENCODE_SERVER_PASSWORD=NOVA/" /etc/systemd/system/elicode-serve.service && sudo systemctl restart elicode-serve
PAIR_PASSWORD="$(openssl rand -base64 24 | tr -d '\n')"

echo "== [1/5] pacotes base =="
apt-get update -qq
apt-get install -y -qq curl git unzip ca-certificates ufw > /dev/null

echo "== [2/5] node 22 (para tooling auxiliar) =="
if ! command -v node >/dev/null 2>&1; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash - >/dev/null
  apt-get install -y -qq nodejs > /dev/null
fi

echo "== [3/5] opencode =="
if ! command -v opencode >/dev/null 2>&1; then
  curl -fsSL https://opencode.ai/install | bash
  ln -sf "$HOME/.opencode/bin/opencode" /usr/local/bin/opencode || true
  ln -sf "/root/.opencode/bin/opencode" /usr/local/bin/opencode || true
fi

echo "== [4/5] diretorio de trabalho + servico systemd =="
OPENCODE_BIN="$(command -v opencode || echo /usr/local/bin/opencode)"
mkdir -p "$WORKDIR"
chown "$SERVICE_USER:$SERVICE_USER" /opt/elicode "$WORKDIR"
cat > /etc/systemd/system/elicode-serve.service <<EOF
[Unit]
Description=elicode - OpenCode serve
After=network-online.target
Wants=network-online.target

[Service]
User=$SERVICE_USER
WorkingDirectory=$WORKDIR
ExecStart=$OPENCODE_BIN serve --port $OPENCODE_PORT --hostname 127.0.0.1
Restart=always
RestartSec=5
Environment=HOME=/home/$SERVICE_USER
Environment=OPENCODE_SERVER_PASSWORD=$PAIR_PASSWORD

[Install]
WantedBy=multi-user.target
EOF

echo "== [5/5] firewall (só SSH; app chega via Tailscale) =="
ufw allow OpenSSH >/dev/null 2>&1 || ufw allow 22 >/dev/null 2>&1 || true
ufw --force enable >/dev/null 2>&1 || true

systemctl daemon-reload
systemctl enable --now elicode-serve.service
chmod 600 /etc/systemd/system/elicode-serve.service

echo
echo "Pronto. Guarde bem (pareamento do APK):"
echo "  usuario : opencode"
echo "  senha   : $PAIR_PASSWORD"
echo
echo "Proximos passos:"
echo "  1. Instale o Tailscale na VPS (https://tailscale.com/download) e no celular; mesma conta."
echo "  2. Copie seu ~/.config/opencode/opencode.json (auth dos modelos) para /home/$SERVICE_USER/.config/opencode/"
echo "  3. Teste: curl -u opencode:SENHA http://127.0.0.1:$OPENCODE_PORT/global/health"
echo "  4. No APK: URL base = http://<ip-tailscale-da-vps>:$OPENCODE_PORT + usuario/senha acima."
echo "  5. Logs: journalctl -u elicode-serve -f"
