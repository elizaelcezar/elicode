package br.com.elicode.setup;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Ponte com o Termux via intent oficial RUN_COMMAND (execução em background
 * com resultado devolvido por PendingIntent). Constantes conferidas no
 * código-fonte do Termux (TermuxConstants.RUN_COMMAND_SERVICE).
 */
public final class TermuxBridge {

    public static final String TERMUX_PKG = "com.termux";
    public static final String TERMUX_SERVICE = TERMUX_PKG + ".app.RunCommandService";
    public static final String ACTION_RUN_COMMAND = TERMUX_PKG + ".RUN_COMMAND";

    public static final String EX_PATH = TERMUX_PKG + ".RUN_COMMAND_PATH";
    public static final String EX_ARGUMENTS = TERMUX_PKG + ".RUN_COMMAND_ARGUMENTS";
    public static final String EX_WORKDIR = TERMUX_PKG + ".RUN_COMMAND_WORKDIR";
    public static final String EX_RUNNER = TERMUX_PKG + ".RUN_COMMAND_RUNNER";
    public static final String EX_BACKGROUND = TERMUX_PKG + ".RUN_COMMAND_BACKGROUND";
    public static final String EX_LABEL = TERMUX_PKG + ".RUN_COMMAND_COMMAND_LABEL";
    public static final String EX_PENDING = TERMUX_PKG + ".RUN_COMMAND_PENDING_INTENT";

    public static final String RUNNER_APP_SHELL = "app-shell";
    public static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    public static final String RESULT_ACTION = "br.com.elicode.setup.TERMUX_RESULT";

    private TermuxBridge() {}

    /** Roda `bash -c <command>` no Termux em background; resultado volta no receiver. */
    public static void run(Context ctx, String label, String command, int requestId) {
        Intent i = new Intent(ACTION_RUN_COMMAND);
        i.setClassName(TERMUX_PKG, TERMUX_SERVICE);
        i.putExtra(EX_PATH, TERMUX_BASH);
        i.putExtra(EX_ARGUMENTS, new String[]{"-c", command});
        i.putExtra(EX_RUNNER, RUNNER_APP_SHELL);
        i.putExtra(EX_BACKGROUND, true);
        i.putExtra(EX_LABEL, label);

        Intent result = new Intent(ctx, TermuxResultReceiver.class);
        result.setAction(RESULT_ACTION);
        result.putExtra("requestId", requestId);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) piFlags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, requestId, result, piFlags);
        i.putExtra(EX_PENDING, pi);

        // foregroundService primeiro; se o sistema barrar, tenta startService.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (IllegalStateException e1) {
            try {
                ctx.startService(i);
            } catch (Exception e2) {
                throw new RuntimeException(firstLine(e2) + " / antes: " + firstLine(e1));
            }
        }
    }

    private static String firstLine(Exception e) {
        if (e == null) return "?";
        String m = e.toString();
        int nl = m.indexOf('\n');
        return nl > 0 ? m.substring(0, nl) : m;
    }

    public static boolean isInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean is64Bit() {
        try {
            String[] abis = Build.SUPPORTED_64_BIT_ABIS;
            return abis != null && abis.length > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
