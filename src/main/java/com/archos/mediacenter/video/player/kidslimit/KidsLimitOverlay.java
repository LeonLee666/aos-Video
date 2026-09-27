package com.archos.mediacenter.video.player.kidslimit;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Full-screen blocking overlay shown on top of the video surface while the
 * kids watching limit is active (rest countdown / blocked for today).
 */
public class KidsLimitOverlay {

    public interface Listener {
        /** Called when the user presses the (allowed) play/continue affordance after a rest. */
        void onPlayRequested();
    }

    private final FrameLayout mRoot;
    private final TextView mTitle;
    private final TextView mMessage;
    private final TextView mCountdown;
    private boolean mShowing = false;
    private Listener mListener;

    public KidsLimitOverlay(Context context) {
        mRoot = new FrameLayout(context);
        mRoot.setBackgroundColor(0xEE101418);
        mRoot.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mRoot.setClickable(true);
        mRoot.setFocusable(true);

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(context, 32), dp(context, 32), dp(context, 32), dp(context, 32));
        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        mRoot.addView(box, boxLp);

        mTitle = new TextView(context);
        mTitle.setText("⏸");
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
        mTitle.setGravity(Gravity.CENTER);
        box.addView(mTitle);

        mMessage = new TextView(context);
        mMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        mMessage.setTextColor(Color.WHITE);
        mMessage.setGravity(Gravity.CENTER);
        mMessage.setPadding(0, dp(context, 12), 0, dp(context, 4));
        box.addView(mMessage);

        mCountdown = new TextView(context);
        mCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        mCountdown.setTextColor(0xFFFFC857);
        mCountdown.setGravity(Gravity.CENTER);
        mCountdown.setPadding(0, dp(context, 4), 0, dp(context, 8));
        box.addView(mCountdown);

        mRoot.setOnClickListener(v -> {
            WatchTimeManager.State st = WatchTimeManager.getInstance(context).computeCurrentState();
            if (st == WatchTimeManager.State.NORMAL && mListener != null) {
                mListener.onPlayRequested();
            }
        });
    }

    public void setListener(Listener l) { mListener = l; }

    public View getView() { return mRoot; }

    public boolean isShowing() { return mShowing; }

    /** Show / refresh the overlay for the given state. */
    public void update(WatchTimeManager.State state, long restRemainingMs, int session, int sessionsPerDay) {
        if (state == WatchTimeManager.State.NORMAL) {
            hide();
            return;
        }
        mShowing = true;
        if (mRoot.getParent() == null) {
            // caller must have added it already; defensive add below
        }
        mRoot.setVisibility(View.VISIBLE);
        mRoot.bringToFront();
        mRoot.requestFocus();
        if (state == WatchTimeManager.State.RESTING) {
            long sec = Math.max(0, (restRemainingMs + 999) / 1000);
            long m = sec / 60, s = sec % 60;
            mCountdown.setText(String.format(Locale.US, "%d:%02d", m, s));
            mMessage.setText("休息一下，保护眼睛～\n休息结束后点击屏幕继续观看");
            mTitle.setText("⏳");
        } else { // BLOCKED_FOR_TODAY
            mCountdown.setText("");
            mMessage.setText("今天的观看时间已经用完啦，明天再来吧！\n（本轮 " + session + "/" + sessionsPerDay + " 已结束）");
            mTitle.setText("🌙");
        }
    }

    public void hide() {
        mShowing = false;
        mRoot.setVisibility(View.GONE);
    }

    private static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }
}
