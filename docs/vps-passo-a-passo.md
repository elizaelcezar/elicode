# Passo a passo — VPS Oracle Free (R$ 0)

Tempo estimado: 40–60 min (maioria esperando validação da conta). Faça uma
vez só.

## Parte A — Conta Oracle (console, ~15 min + espera)

1. Acesse https://cloud.oracle.com e clique **Sign Up for Free Tier**.
   Tenha em mãos: e-mail, **cartão de crédito internacional** (cobra US$ 1
   de verificação e estorna) e celular para SMS.
2. Preencha e aguarde o e-mail de ativação (pode levar de minutos a horas).
3. Entre no console, anote a **região** (ex.: São Paulo `sa-saopaulo-1` —
   prefira a mais perto de você).

## Parte B — Instância Always Free (~10 min)

1. Console → **Compute → Instances → Create instance**.
2. Nome: `elicode`. Compartimento: o padrão.
3. **Image**: Ubuntu 24.04 Minimal aarch64 (ARM).
4. **Shape**: `VM.Standard.A1.Flex` (Ampere ARM, Always Free):
   * OCPUs: **2**, Memory: **8 GB** (dentro da cota free de 4/24GB).
5. **Networking**: VCN nova (padrão) com IP público; deixe SSH (porta 22)
   liberado.
6. **Add SSH keys**: cole sua chave pública (`~/.ssh/id_ed25519.pub` do PC;
   se não tem: `ssh-keygen -t ed25519` no prompt do Windows).
7. **Boot volume**: 50 GB (dentro da cota free).
8. Create. Anote o **IP público**. Aguarde ficar `Running` (verde).

> Capacidade ARM gratuita esgota às vezes ("Out of capacity"): tente outra
> hora ou a região Ashburn/Phoenix. Alternativa paga imediata: Hetzner
> CX23 (~R$ 32/mês) — o resto do passo a passo é idêntico.

## Parte C — Base na máquina (~10 min, no prompt do PC)

```bat
scp server\setup-vps.sh ubuntu@SEU-IP:~
ssh ubuntu@SEU-IP
sudo bash ~/setup-vps.sh
```

Anote ao final: **usuário `opencode` + senha gerada** (é o pareamento do APK).

## Parte D — Tailscale (~5 min)

1. Na VPS: `curl -fsSL https://tailscale.com/install.sh | sh` e
   `sudo tailscale up`. No celular: app Tailscale, mesma conta.
2. No admin do Tailscale, anote o **IP 100.x** da VPS.

## Parte E — Modelos free na VPS (~5 min)

Copie sua autenticação de modelos do PC para a VPS:

```bat
scp %USERPROFILE%\.config\opencode\opencode.json ubuntu@SEU-IP:~/.config/opencode/opencode.json
```

> Caminho do auth pode variar (`auth.json`); confira no PC com
> `dir %USERPROFILE%\.config\opencode` e copie o que existir.

Reinicie o serviço: `sudo systemctl restart elicode-serve`.

## Parte F — Teste (prova de vida)

Na VPS:

```bash
curl -u opencode:SUA-SENHA http://127.0.0.1:4096/global/health
# esperado: {"healthy":true,"version":"..."}
```

No PC (via Tailscale, troque o IP):

```bat
curl -u opencode:SUA-SENHA http://100.x.y.z:4096/global/health
```

Respondeu `healthy:true` nos dois? **Servidor pronto.** O app da fase 1
conecta com URL base + usuário/senha.

## Custos e limites (Oracle Free)

* R$ 0 enquanto couber na cota Always Free (2 OCPU/8GB usados de 4/24GB).
* Sem SLA: em caso raríssimo a Oracle pode retomar capacidade (avisa com
  30 dias). Backup do `~/elicode/work`: `git push` frequente resolve.
* Quando virar uso diário pesado: migrar para Hetzner (€5,49/mês) é só
  repetir a Parte C em diante.
