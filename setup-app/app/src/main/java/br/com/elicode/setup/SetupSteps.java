package br.com.elicode.setup;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Passos da configuração, executados em sequência via Termux. */
public final class SetupSteps {

    public static final class Step {
        public final int id;
        public final String title;
        public final String command;
        /** Marcador esperado no stdout; null = basta exit 0. */
        public final String marker;
        /** Se false, falha aqui só avisa (não interrompe). */
        public final boolean required;
        public final String hint;

        Step(int id, String title, String command, String marker, boolean required, String hint) {
            this.id = id;
            this.title = title;
            this.command = command;
            this.marker = marker;
            this.required = required;
            this.hint = hint;
        }
    }

    /** Senha alfanumérica (segura para embutir nos comandos). */
    public static String newPassword(int len) {
        final String abc = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
        return sb.toString();
    }

    public static List<Step> build(String pairPassword) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(0, "Testando Termux",
                "echo ELICODE_OK",
                "ELICODE_OK", true, null));
        steps.add(new Step(1, "Instalando base (proot, git…)",
                "pkg update -y && pkg install -y proot-distro git openssh termux-api && echo ELICODE_BASE_OK",
                "ELICODE_BASE_OK", true, null));
        steps.add(new Step(2, "Baixando Ubuntu (~300 MB, pode levar 10 min)",
                "proot-distro install ubuntu && echo ELICODE_UBUNTU_OK",
                "ELICODE_UBUNTU_OK", true, null));
        steps.add(new Step(3, "Instalando OpenCode no Ubuntu",
                "proot-distro login ubuntu -- bash -c 'set -e; apt-get update && apt-get install -y curl git ca-certificates && curl -fsSL https://opencode.ai/install | bash && /root/.opencode/bin/opencode --version'",
                null, true, null));
        String startScript =
                "export OPENCODE_SERVER_PASSWORD='" + pairPassword + "'\n"
                + "mkdir -p /root/elicode/work\n"
                + "cd /root/elicode/work\n"
                + "exec /root/.opencode/bin/opencode serve --port 4096 --hostname 0.0.0.0\n";
        steps.add(new Step(4, "Criando script do servidor",
                "proot-distro login ubuntu -- bash -c 'cat > /root/elicode-start.sh <<ELICODE_EOF\n"
                + startScript + "ELICODE_EOF\n"
                + "chmod 600 /root/elicode-start.sh && chmod +x /root/elicode-start.sh && test -x /root/elicode-start.sh && echo ELICODE_SCRIPT_OK'",
                "ELICODE_SCRIPT_OK", true, null));
        String bootScript =
                "#!/data/data/com.termux/files/usr/bin/sh\n"
                + "termux-wake-lock\n"
                + "proot-distro login ubuntu -- /root/elicode-start.sh\n";
        steps.add(new Step(5, "Subir sozinho ao ligar",
                "mkdir -p ~/.termux/boot && cat > ~/.termux/boot/start-elicode.sh <<ELICODE_EOF\n"
                + bootScript + "ELICODE_EOF\n"
                + "chmod +x ~/.termux/boot/start-elicode.sh && echo ELICODE_BOOT_OK",
                "ELICODE_BOOT_OK", true,
                "Exige o app Termux:Boot instalado."));
        steps.add(new Step(6, "Auth dos modelos",
                "proot-distro login ubuntu -- test -f /root/.config/opencode/opencode.json && echo AUTH_OK || echo AUTH_MISSING",
                null, false,
                "Copie seu opencode.json do PC para o Ubuntu depois (scp ou pasta compartilhada)."));
        steps.add(new Step(7, "Ligando o servidor agora",
                "proot-distro login ubuntu -- bash -c 'nohup /root/elicode-start.sh >/root/elicode-serve.log 2>&1 & echo STARTED:$!'",
                "STARTED", true, null));
        return steps;
    }
}
