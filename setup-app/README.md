# setup-app — APK que configura o tablet sozinho

Instalado **no tablet** (o futuro servidor). Ele pilota o Termux via
intent oficial `RUN_COMMAND` e executa toda a configuração sem digitar
nada: pacotes, Ubuntu (proot), OpenCode, script do servidor, boot
automático e teste de saúde.

APK pronto: [`apk/elicode-setup.apk`](apk/elicode-setup.apk) (debug).

## Pré-requisitos (o app checa sozinho)

* Termux + Termux:Boot via **F-Droid**
* Android 64 bits, 4 GB+ RAM
* Bateria liberada para o Termux (o app tem botão que abre a tela certa)

## Fluxo

1. Abra o app → confira os ✅ → **Configurar servidor**.
2. Acompanhe o log (o passo do Ubuntu baixa ~300 MB).
3. Ao final: **Testar servidor** → mostra URL/usuário/senha de pareamento.
4. **Abrir console (neste aparelho)** → usa o servidor local na hora, sem
   outro dispositivo (WebView no `opencode web` com login automático).
5. Falta manual só: Tailscale + copiar `opencode.json` de modelos
   (o passo 6 avisa se está ausente).

## Código

* `TermuxBridge.java` — protocolo RUN_COMMAND (constantes do fonte oficial)
* `SetupSteps.java` — os 8 passos (senha gerada no app via SecureRandom)
* `MainActivity.java` — wizard, watchdog, health-check, credenciais
* `TermuxResultReceiver.java` — recebe stdout/exitCode por PendingIntent
