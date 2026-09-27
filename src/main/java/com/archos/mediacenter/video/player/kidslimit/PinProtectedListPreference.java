package com.archos.mediacenter.video.player.kidslimit;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.ListPreference;

/**
 * A ListPreference that requires the parent PIN before opening the value chooser.
 * Suitable for TV (dpad-friendly radio list instead of a seekbar).
 * The value is only persisted through the standard dialog after PIN verification.
 */
public class PinProtectedListPreference extends ListPreference {

    public PinProtectedListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PinProtectedListPreference(Context context) {
        super(context);
    }

    @Override
    protected void onClick() {
        Context ctx = getContext();
        if (!PinDialogHelper.isPinSet(ctx)) {
            // No PIN set yet (limit not enabled): behave like a normal list
            super.onClick();
            return;
        }
        PinDialogHelper.showVerifyPinDialog(ctx, super::onClick);
    }
}
