package br.com.elicode.setup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Recebe o resultado dos comandos (stdout/stderr/exitCode) e repassa à tela. */
public class TermuxResultReceiver extends BroadcastReceiver {

    public interface Listener {
        void onResult(int requestId, int exitCode, String stdout, String stderr, String err);
    }

    public static volatile Listener listener;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        Bundle b = intent.getExtras();
        int requestId = intent.getIntExtra("requestId", -1);
        int exitCode = -1;
        String stdout = "";
        String stderr = "";
        String err = "";
        if (b != null) {
            try { exitCode = b.getInt("exitCode", -1); } catch (Exception ignored) {}
            try { stdout = String.valueOf(b.get("stdout")); } catch (Exception ignored) {}
            try { stderr = String.valueOf(b.get("stderr")); } catch (Exception ignored) {}
            try { err = String.valueOf(b.get("err")); } catch (Exception ignored) {}
        }
        Listener l = listener;
        if (l != null) l.onResult(requestId, exitCode, stdout, stderr, err);
    }
}
