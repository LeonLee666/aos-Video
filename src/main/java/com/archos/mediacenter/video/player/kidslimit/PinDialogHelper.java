package com.archos.mediacenter.video.player.kidslimit;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Parental PIN helpers: set PIN when enabling the restriction, verify PIN to change
 * settings or reset stats.
 */
public class PinDialogHelper {

    public static final String KEY_PIN_HASH = "kids_pin_hash"; // stored in default shared preferences

    public static boolean isPinSet(Context ctx) {
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                .contains(KEY_PIN_HASH);
    }

    public static void setPin(Context ctx, String pin) {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().putString(KEY_PIN_HASH, hash(pin)).apply();
    }

    public static void clearPin(Context ctx) {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().remove(KEY_PIN_HASH).apply();
    }

    public static boolean verifyPin(Context ctx, String pin) {
        String stored = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                .getString(KEY_PIN_HASH, "");
        return !stored.isEmpty() && stored.equals(hash(pin));
    }

    /** Dialog asking to set a new PIN; callback with the entered PIN (already validated 4 digits). */
    public static void showSetPinDialog(Context ctx, OnPinEntered cb) {
        showInputDialog(ctx, "设置家长 PIN 码", "请输入 4 位数字 PIN 码（用于修改观看限制设置）", pin -> {
            if (pin.length() == 4) cb.onPinEntered(pin);
            else Toast.makeText(ctx, "PIN 必须是 4 位数字", Toast.LENGTH_SHORT).show();
        });
    }

    /** Dialog asking to verify the existing PIN. */
    public static void showVerifyPinDialog(Context ctx, Runnable onOk) {
        showInputDialog(ctx, "需要家长验证", "请输入家长 PIN 码", pin -> {
            if (verifyPin(ctx, pin)) onOk.run();
            else Toast.makeText(ctx, "PIN 码错误", Toast.LENGTH_SHORT).show();
        });
    }

    public interface OnPinEntered { void onPinEntered(String pin); }

    private static void showInputDialog(Context ctx, String title, String message, OnPinEntered cb) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * ctx.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText input = new EditText(ctx);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setMaxLines(1);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        box.addView(input);
        input.setOnEditorActionListener((TextView v, int actionId, KeyEvent event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                String pin = input.getText().toString().trim();
                if (pin.matches("\\d{4}")) { cb.onPinEntered(pin); return true; }
            }
            return false;
        });
        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setMessage(message)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> cb.onPinEntered(input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        input.requestFocus();
    }

    private static String hash(String pin) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(("nova-kids:" + pin).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
