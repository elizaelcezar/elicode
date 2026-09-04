package br.com.elicode.setup;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** Wizard: checa pré-requisitos, executa o setup no Termux e testa o servidor. */
public class MainActivity extends Activity {

    private TextView tvChecks, tvLog, tvCreds, tvFix;
    private Button btnStart, btnHealth, btnBattery, btnTermux, btnBoot, btnCopyFix;

    /** Comando único que libera apps externos no Termux (rodar UMA vez lá dentro). */
    private static final String FIX_CMD =
            "mkdir -p ~/.termux && printf 'allow-external-apps = true\n' >> ~/.termux/termux.properties";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();

    private List<SetupSteps.Step> steps;
    private int stepIndex = -1;
    private boolean running = false;
    private String pairPassword = "";
    private Runnable watchdog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvChecks = findViewById(R.id.tv_checks);
        tvLog = findViewById(R.id.tv_log);
        tvCreds = findViewById(R.id.tv_creds);
        btnStart = findViewById(R.id.btn_start);
        btnHealth = findViewById(R.id.btn_health);
        btnBattery = findViewById(R.id.btn_fix_battery);
        btnTermux = findViewById(R.id.btn_install_termux);
        btnBoot = findViewById(R.id.btn_install_boot);
        tvFix = findViewById(R.id.tv_fix);
        btnCopyFix = findViewById(R.id.btn_copy_fix);

        SharedPreferences p = getSharedPreferences("elicode_setup", MODE_PRIVATE);
        pairPassword = p.getString("pair_password", "");
        if (pairPassword.isEmpty()) {
            pairPassword = SetupSteps.newPassword(24);
            p.edit().putString("pair_password", pairPassword).apply();
        }

        TermuxResultReceiver.listener = this::onTermuxResult;

        refreshChecks();
        if (!getSharedPreferences("elicode_setup", MODE_PRIVATE).getBoolean("termux_ok", false)) {
            showFixCard();
        }
        btnStart.setOnClickListener(v -> startSetup());
        btnHealth.setOnClickListener(v -> checkHealth());
        findViewById(R.id.btn_console).setOnClickListener(v ->
                startActivity(new Intent(this, ConsoleActivity.class)));
        btnBattery.setOnClickListener(v -> requestBatteryOff());
        btnTermux.setOnClickListener(v -> openFdroid("com.termux"));
        btnBoot.setOnClickListener(v -> openFdroid("com.termux.boot"));
        btnCopyFix.setOnClickListener(v -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("elicode", FIX_CMD));
                Toast.makeText(this, "Comando copiado — cole no Termux", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, FIX_CMD, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (TermuxResultReceiver.listener != null) TermuxResultReceiver.listener = null;
        super.onDestroy();
    }

    // ---------- pré-requisitos ----------

    private void refreshChecks() {
        boolean termux = TermuxBridge.isInstalled(this, "com.termux");
        boolean boot = TermuxBridge.isInstalled(this, "com.termux.boot");
        boolean x64 = TermuxBridge.is64Bit();
        boolean battOk = isBatteryOff();
        long ramGb = totalRamGb();

        StringBuilder sb = new StringBuilder();
        sb.append(check(termux, "Termux instalado (via F-Droid)"));
        if (termux) sb.append("  fonte: ").append(installerOf("com.termux")).append("\n");
        else sb.append("\n  → instale em https://f-droid.org/packages/com.termux");
        sb.append(check(boot, "Termux:Boot instalado"));
        if (termux && !boot) sb.append("  ⚠ use a MESMA loja do Termux (F-Droid) ou dá conflito\n");
        sb.append(check(hasRunCommandPerm(), "Permissão de pilotar o Termux"));
        sb.append(check(x64, "Aparelho 64 bits"));
        sb.append(check(ramGb >= 4, String.format(Locale.getDefault(), "RAM: %d GB (ideal 4+)", ramGb)));
        sb.append(check(battOk, "Bateria liberada p/ o Termux"));
        tvChecks.setText(sb.toString());
        btnBattery.setVisibility(battOk ? View.GONE : View.VISIBLE);
        btnTermux.setVisibility(termux ? View.GONE : View.VISIBLE);
        btnBoot.setVisibility(boot ? View.GONE : View.VISIBLE);
        btnStart.setEnabled(termux && x64);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Volta da loja com o app instalado: recheca sem precisar reiniciar.
        if (!running) refreshChecks();
    }

    private void showFixCard() {
        tvFix.setText("ANTES: no Termux, execute isto UMA vez:\n" + FIX_CMD
                + "\nDepois feche o Termux por completo e abra de novo.");
        tvFix.setVisibility(View.VISIBLE);
        btnCopyFix.setVisibility(View.VISIBLE);
    }

    private void hideFixCard() {
        tvFix.setVisibility(View.GONE);
        btnCopyFix.setVisibility(View.GONE);
    }

    private void openFdroid(String pkg) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://f-droid.org/packages/" + pkg)));
        } catch (Exception e) {
            Toast.makeText(this, "Abra o F-Droid e busque por " + pkg, Toast.LENGTH_LONG).show();
        }
    }

    private String check(boolean ok, String label) {
        return (ok ? "✅ " : "❌ ") + label + "\n";
    }

    private static final String PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";
    private static final int REQ_RUN_COMMAND = 4001;

    private boolean hasRunCommandPerm() {
        try {
            return checkSelfPermission(PERM_RUN_COMMAND)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    private void requestRunCommandPerm() {
        try {
            requestPermissions(new String[]{PERM_RUN_COMMAND}, REQ_RUN_COMMAND);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível pedir a permissão do Termux", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RUN_COMMAND) {
            boolean ok = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, ok ? "Permissão concedida — toque em Configurar"
                    : "Sem essa permissão o app não pilota o Termux", Toast.LENGTH_LONG).show();
            refreshChecks();
        }
    }

    private String installerOf(String pkg) {
        try {
            String who;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                who = getPackageManager().getInstallSourceInfo(pkg).getInstallingPackageName();
            } else {
                who = getPackageManager().getInstallerPackageName(pkg);
            }
            if (who == null) return "desconhecida";
            if (who.contains("fdroid")) return "F-Droid ✅";
            if (who.contains("vending") || who.contains("play")) return "Play Store ⚠";
            return who;
        } catch (Exception e) {
            return "desconhecida";
        }
    }

    private boolean isBatteryOff() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations("com.termux");
        } catch (Exception e) {
            return false;
        }
    }

    private long totalRamGb() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem / (1024L * 1024L * 1024L);
        } catch (Exception e) {
            return 0;
        }
    }

    private void requestBatteryOff() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:com.termux"));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Abra as configs e libere a bateria do Termux", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- execução sequencial ----------

    private void startSetup() {
        if (running) return;
        hideFixCard();
        if (!hasRunCommandPerm()) {
            Toast.makeText(this, "Permita pilotar o Termux no próximo diálogo", Toast.LENGTH_LONG).show();
            requestRunCommandPerm();
            return;
        }
        steps = SetupSteps.build(pairPassword);
        stepIndex = -1;
        running = true;
        btnStart.setEnabled(false);
        log.setLength(0);
        tvCreds.setVisibility(View.GONE);
        appendLog("Iniciando configuração…");
        nextStep();
    }

    private void nextStep() {
        stepIndex++;
        if (stepIndex >= steps.size()) {
            running = false;
            btnStart.setEnabled(true);
            appendLog("✔ Configuração concluída. Teste o servidor abaixo.");
            showCreds();
            return;
        }
        SetupSteps.Step s = steps.get(stepIndex);
        appendLog("▶ [" + (stepIndex + 1) + "/" + steps.size() + "] " + s.title);
        if (s.hint != null) appendLog("  (" + s.hint + ")");
        armWatchdog(s);
        try {
            TermuxBridge.run(this, "elicode: " + s.title, s.command, 1000 + stepIndex);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            appendLog("✖ Falha ao chamar o Termux: " + msg);
            if (msg.contains("without permission")) {
                appendLog("→ Correção: este app foi instalado ANTES do Termux e ficou sem a permissão. "
                        + "Desinstale o elicode-setup e instale de novo (com o Termux já instalado).");
            }
            running = false;
            btnStart.setEnabled(true);
        }
    }

    private void armWatchdog(SetupSteps.Step s) {
        cancelWatchdog();
        long timeout = (s.id == 2) ? 25 * 60 * 1000L : 10 * 60 * 1000L;
        watchdog = () -> {
            if (running) appendLog("… ainda trabalhando em '" + s.title + "' (aguarde, passos longos baixam centenas de MB)");
        };
        handler.postDelayed(watchdog, timeout);
    }

    private void cancelWatchdog() {
        if (watchdog != null) handler.removeCallbacks(watchdog);
        watchdog = null;
    }

    private void onTermuxResult(int requestId, int exitCode, String stdout, String stderr, String err) {
        handler.post(() -> {
            if (!running) return;
            cancelWatchdog();
            int idx = requestId - 1000;
            if (idx != stepIndex || idx < 0 || idx >= steps.size()) return;
            SetupSteps.Step s = steps.get(idx);
            String out = (stdout == null ? "" : stdout).trim();
            appendLog("  exit=" + exitCode + (out.isEmpty() ? "" : " :: " + tail(out, 300)));
            boolean ok = exitCode == 0 && (s.marker == null || out.contains(s.marker));
            if (s.id == 6) { // auth: só avisa
                if (out.contains("AUTH_OK")) appendLog("  Auth dos modelos encontrada ✔");
                else appendLog("  ⚠ Auth dos modelos ausente — copie seu opencode.json depois (não bloqueia).");
                nextStep();
                return;
            }
            if (ok) {
                appendLog("  ✔ ok");
                if (s.id == 0) {
                    getSharedPreferences("elicode_setup", MODE_PRIVATE)
                            .edit().putBoolean("termux_ok", true).apply();
                    hideFixCard();
                }
                nextStep();
            } else {
                appendLog("  ✖ falhou" + (stderr != null && !stderr.trim().isEmpty() ? " :: " + tail(stderr.trim(), 300) : ""));
                String blob = (stdout == null ? "" : stdout) + "\n"
                        + (stderr == null ? "" : stderr) + "\n" + err;
                // O Termux quase nunca devolve o motivo (falha pré-execução):
                // no passo 0, presuma o bloqueio e mostre o cartão sempre.
                if (s.id == 0 || blob.contains("allow-external-apps")) {
                    appendLog("  → Causa provável: o Termux bloqueia apps externos até liberar (uma vez só).");
                    appendLog("  1) Copie o comando do cartão amarelo e execute no Termux");
                    appendLog("  2) Feche o Termux por completo e abra de novo");
                    appendLog("  3) Toque em Configurar servidor aqui");
                    showFixCard();
                } else {
                    appendLog("  Abra o Termux e confira o erro; depois toque em Configurar de novo.");
                }
                running = false;
                btnStart.setEnabled(true);
            }
        });
    }

    // ---------- saúde + credenciais ----------

    private void checkHealth() {
        appendLog("Testando http://127.0.0.1:4096/global/health …");
        new Thread(() -> {
            String res;
            try {
                URL url = new URL("http://127.0.0.1:4096/global/health");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                String basic = "opencode:" + pairPassword;
                String encoded;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    encoded = Base64.getEncoder().encodeToString(basic.getBytes("UTF-8"));
                } else {
                    encoded = android.util.Base64.encodeToString(basic.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
                }
                c.setRequestProperty("Authorization", "Basic " + encoded);
                int code = c.getResponseCode();
                BufferedReader br = new BufferedReader(new InputStreamReader(
                        code < 400 ? c.getInputStream() : c.getErrorStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                res = "HTTP " + code + " " + sb;
            } catch (Exception e) {
                res = "ERRO: " + e.getMessage();
            }
            final String msg = res;
            handler.post(() -> {
                appendLog(msg.contains("healthy") ? "✔ Servidor no ar: " + msg : "Resposta: " + msg);
                if (msg.contains("healthy")) showCreds();
            });
        }).start();
    }

    private void showCreds() {
        tvCreds.setVisibility(View.VISIBLE);
        tvCreds.setText("Servidor pronto ✅\n"
                + "URL (neste tablet): http://127.0.0.1:4096\n"
                + "Usuário: opencode\n"
                + "Senha: " + pairPassword + "\n\n"
                + "Falta só: Tailscale neste tablet + copiar seu opencode.json de modelos. "
                + "No celular, use o IP Tailscale do tablet.");
    }

    private void appendLog(String s) {
        log.append(s).append("\n");
        tvLog.setText(log.toString());
    }

    private String tail(String s, int n) {
        if (s.length() <= n) return s.replace("\n", " | ");
        return ("…" + s.substring(s.length() - n)).replace("\n", " | ");
    }
}
