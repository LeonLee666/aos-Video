package com.archos.mediacenter.video.player.kidslimit;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Children watching time limiter.
 *
 * Daily state machine (all playback paths must go through {@link #onPlaybackStarted()}/
 * {@link #onPlaybackStopped()}):
 *
 *   WATCHING (session #1) --accumulated daily limit reached--> RESTING (forced break)
 *        ^                                                        |  overlay shown, play blocked
 *        |                                                   --rest elapsed--
 *        |                                                        v
 *        +----(only for session #1)                        WATCHING (session #2)
 *             user presses play again                        --limit reached--> BLOCKED_FOR_TODAY
 *                                                                              | next midnight resets
 *                                                                              v
 *                                                                        WATCHING (fresh day)
 *
 * Key properties:
 *  - Only wall-clock time while actually playing is counted (pause/buffering excluded).
 *  - The forced break countdown is wall-clock based (survives app kill / reboot via timestamp).
 *  - Lazy daily reset: any read detects a date change and clears the counters.
 *  - Basic anti-tamper: wall clock is cross-checked with elapsedRealtime; if wall clock
 *    jumps backwards more than a tolerance while a rest/block is active, the restriction
 *    is kept based on the monotonic delta.
 */
public class WatchTimeManager {

    private static final Logger log = LoggerFactory.getLogger(WatchTimeManager.class);

    public static final String PREFS_FILE = "nova_kids_limit";

    public static final String KEY_ENABLED         = "kids_limit_enabled";
    public static final String KEY_DAILY_LIMIT_MIN = "kids_daily_limit_minutes";   // default 30
    public static final String KEY_REST_MINUTES    = "kids_rest_minutes";          // default 5
    public static final String KEY_SESSIONS_PER_DAY= "kids_sessions_per_day";     // default 2

    // state file keys
    private static final String K_DATE             = "state_date";          // yyyy-MM-dd of current accounting
    private static final String K_USED_MS          = "state_used_ms";       // accumulated play time today
    private static final String K_SESSION          = "state_session";       // 1-based session number
    private static final String K_REST_UNTIL_WALL  = "state_rest_until_wall"; // wall clock ms when rest ends
    private static final String K_REST_UNTIL_ELAP  = "state_rest_until_elapsed"; // elapsedRealtime when rest ends
    private static final String K_SAVED_AT_WALL    = "state_saved_at_wall";
    private static final String K_SAVED_AT_ELAP    = "state_saved_at_elapsed";
    private static final String K_LAST_TICK_ELAP   = "state_last_tick_elapsed";

    public enum State { NORMAL, RESTING, BLOCKED_FOR_TODAY }

    public interface Listener {
        /** Called when state changes or remaining rest time changes (1s cadence while resting). */
        void onKidsLimitStateChanged(State state, long restRemainingMs, long usedTodayMs, int session, int sessionsPerDay);
    }

    private static volatile WatchTimeManager sInstance;

    private final Context mAppContext;
    private final SharedPreferences mPrefs;        // settings (shared with preference UI)
    private final SharedPreferences mState;        // runtime state, separate file
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private boolean mPlaying = false;              // is playback currently active
    private long mPlayStartWall = 0L;              // wall clock of current playing stretch
    private long mPlayStartElapsed = 0L;
    private long mUsedTodayMs = 0L;                // accumulated watched ms today
    private int mSession = 1;                      // current session (1..sessionsPerDay)
    private Long mRestUntilWall = null;            // non-null while resting
    private long mRestUntilElapsed = 0L;
    private String mAccountingDate = "";

    private Listener mListener;

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            if (mPlaying) {
                long nowElapsed = SystemClock.elapsedRealtime();
                long delta = nowElapsed - safeLastTick();
                if (delta > 0 && delta < 10_000) { // sanity: ignore absurd gaps (device sleep etc. handled at stop)
                    mUsedTodayMs += delta;
                }
                persistState();
            }
            State st = computeCurrentState();
            // notify on any state change (e.g. RESTING -> NORMAL hides the overlay),
            // and every second while RESTING (countdown refresh)
            if (st != mLastNotified || st == State.RESTING) {
                notifyListener(st);
            }
            mLastNotified = st;
            mMainHandler.postDelayed(this, 1000);
        }
    };
    private State mLastNotified = null;
    private long mLastTickElapsed = 0L;

    public static WatchTimeManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (WatchTimeManager.class) {
                if (sInstance == null) {
                    sInstance = new WatchTimeManager(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private WatchTimeManager(Context appContext) {
        mAppContext = appContext;
        mPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext);
        mState = appContext.getSharedPreferences(PREFS_FILE + "_state", Context.MODE_PRIVATE);
        loadState();
        // Keep the tick loop alive only when needed; started on demand.
    }

    //------------------------------------------------------------------------------------------
    // Settings accessors
    //------------------------------------------------------------------------------------------

    public boolean isEnabled() {
        return mPrefs.getBoolean(KEY_ENABLED, false);
    }

    public int getDailyLimitMinutes() {
        return readIntSetting(KEY_DAILY_LIMIT_MIN, 30);
    }

    public int getRestMinutes() {
        return readIntSetting(KEY_REST_MINUTES, 5);
    }

    public int getSessionsPerDay() {
        return readIntSetting(KEY_SESSIONS_PER_DAY, 2);
    }

    /** ListPreference persists values as strings; read int-or-string safely. */
    private int readIntSetting(String key, int def) {
        try {
            return mPrefs.getInt(key, def);
        } catch (ClassCastException e) {
            try {
                return Integer.parseInt(mPrefs.getString(key, String.valueOf(def)));
            } catch (NumberFormatException nfe) {
                return def;
            }
        }
    }

    /** For settings UI. */
    public void resetTodayStats() {
        mUsedTodayMs = 0;
        mSession = 1;
        mRestUntilWall = null;
        mAccountingDate = today();
        persistState();
    }

    //------------------------------------------------------------------------------------------
    // Playback hooks (called from Player.start/pause paths on any thread; posts to main)
    //------------------------------------------------------------------------------------------

    public void onPlaybackStarted() {
        mMainHandler.post(() -> {
            if (mPlaying) return;
            // Check restriction before allowing the stretch to be counted / continue
            State st = computeCurrentState();
            if (st != State.NORMAL) {
                log.debug("onPlaybackStarted: blocked in state {}", st);
                notifyListener(st); // let UI show overlay; Player layer will pause back
                return;
            }
            mPlaying = true;
            mPlayStartWall = System.currentTimeMillis();
            mPlayStartElapsed = SystemClock.elapsedRealtime();
            mLastTickElapsed = mPlayStartElapsed;
            ensureTicking();
            log.debug("watch stretch started");
        });
    }

    public void onPlaybackStopped() {
        mMainHandler.post(() -> {
            if (!mPlaying) return;
            mPlaying = false;
            // The stretch was already accumulated by the 1s tick loop; only add the
            // sub-second remainder since the last tick to avoid double counting.
            long nowElapsed = SystemClock.elapsedRealtime();
            long remainder = nowElapsed - mLastTickElapsed;
            if (remainder > 0 && remainder < 10_000) mUsedTodayMs += remainder;
            mLastTickElapsed = nowElapsed;
            persistState();
            log.debug("watch stretch stopped, used today {} ms", mUsedTodayMs);
        });
    }

    //------------------------------------------------------------------------------------------
    // State computation
    //------------------------------------------------------------------------------------------

    /** Fresh evaluation, includes lazy day rollover. */
    public State computeCurrentState() {
        rolloverIfNeeded();
        if (!isEnabled()) return State.NORMAL;
        if (mSession > getSessionsPerDay()) {
            return State.BLOCKED_FOR_TODAY;
        }
        if (mRestUntilWall != null) {
            long restUntil = effectiveRestUntil();
            if (System.currentTimeMillis() < restUntil) {
                return State.RESTING;
            }
            // Rest finished. mSession was already incremented when entering the rest,
            // so the limit check below now compares against the *next* session quota
            // and naturally returns NORMAL (play must be user-triggered via overlay).
            mRestUntilWall = null;
            mRestUntilElapsed = 0;
            persistState();
        }
        long limitMs = getDailyLimitMinutes() * 60_000L;
        if (mUsedTodayMs >= mSession * limitMs) {
            if (mSession < getSessionsPerDay()) {
                enterRest();
                return State.RESTING;
            } else {
                mSession = getSessionsPerDay() + 1; // exhausted
                persistState();
                return State.BLOCKED_FOR_TODAY;
            }
        }
        return State.NORMAL;
    }

    /** The wall-clock timestamp at which the current rest ends (Long.MAX_VALUE when blocked, 0 when none). */
    public long getRestEndsAt() {
        if (computeCurrentState() == State.BLOCKED_FOR_TODAY) return Long.MAX_VALUE;
        if (computeCurrentState() == State.RESTING) return effectiveRestUntil();
        return 0;
    }

    public long getUsedTodayMs() { rolloverIfNeeded(); return mUsedTodayMs; }
    public int getSession() { rolloverIfNeeded(); return mSession; }

    private void enterRest() {
        long restMs = getRestMinutes() * 60_000L;
        long nowWall = System.currentTimeMillis();
        long nowElapsed = SystemClock.elapsedRealtime();
        mRestUntilWall = nowWall + restMs;
        mRestUntilElapsed = nowElapsed + restMs;
        // Advance the session right away: when the rest ends, the limit check
        // uses the next session's quota (mSession * limit), so playback is
        // allowed again only after the user presses play.
        mSession++;
        if (mPlaying) {
            // close the current stretch (the Player layer will also pause for real)
            onPlaybackStoppedInternal();
        }
        persistState();
        log.info("enter rest of {} min until {} (next session {})", getRestMinutes(), new Date(mRestUntilWall), mSession);
    }

    private void onPlaybackStoppedInternal() {
        if (!mPlaying) return;
        // accumulation is done by the tick loop; only catch the remainder
        long nowElapsed = SystemClock.elapsedRealtime();
        long remainder = nowElapsed - mLastTickElapsed;
        if (remainder > 0 && remainder < 10_000) mUsedTodayMs += remainder;
        mPlaying = false;
        mLastTickElapsed = nowElapsed;
    }

    /** Rest end considering wall-clock tampering (device clock moved backwards). */
    private long effectiveRestUntil() {
        if (mRestUntilWall == null) return 0;
        long wall = mRestUntilWall;
        // If the elapsedRealtime deadline is still in the future but the wall clock now
        // claims the rest is over, suspect a clock rollback: keep the restriction until
        // the monotonic deadline too.
        long nowElapsed = SystemClock.elapsedRealtime();
        if (mRestUntilElapsed > 0 && nowElapsed < mRestUntilElapsed && System.currentTimeMillis() < wall) {
            // Both say still resting; next check fires when wall catches up or elapsed passes.
            long remainByElapsed = mRestUntilElapsed - nowElapsed;
            long remainByWall = wall - System.currentTimeMillis();
            return System.currentTimeMillis() + Math.max(remainByElapsed, 0);
        }
        if (mRestUntilElapsed > 0 && nowElapsed < mRestUntilElapsed && System.currentTimeMillis() >= wall) {
            // Wall says done but monotonic says not: clock was rolled back during rest.
            long remainByElapsed = mRestUntilElapsed - nowElapsed;
            return System.currentTimeMillis() + Math.max(remainByElapsed, 0);
        }
        return wall;
    }

    //------------------------------------------------------------------------------------------
    // Listener / ticking
    //------------------------------------------------------------------------------------------

    public void setListener(Listener l) {
        mListener = l;
        if (l != null) {
            ensureTicking();
            notifyListener(computeCurrentState());
        }
    }

    private void ensureTicking() {
        mMainHandler.removeCallbacks(mTick);
        mMainHandler.post(mTick);
    }

    private void notifyListener(State st) {
        Listener l = mListener;
        if (l != null) {
            long restRemain = 0;
            if (st == State.RESTING) restRemain = Math.max(0, effectiveRestUntil() - System.currentTimeMillis());
            l.onKidsLimitStateChanged(st, restRemain, mUsedTodayMs, mSession, getSessionsPerDay());
        }
    }

    private long safeLastTick() {
        long now = SystemClock.elapsedRealtime();
        long v = mLastTickElapsed == 0 ? now : mLastTickElapsed;
        mLastTickElapsed = now;
        return v;
    }

    //------------------------------------------------------------------------------------------
    // Persistence
    //------------------------------------------------------------------------------------------

    private String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private void rolloverIfNeeded() {
        String t = today();
        if (!t.equals(mAccountingDate)) {
            log.info("day rollover {} -> {}", mAccountingDate, t);
            mUsedTodayMs = 0;
            mSession = 1;
            mRestUntilWall = null;
            mRestUntilElapsed = 0;
            mAccountingDate = t;
            persistState();
        }
    }

    private void loadState() {
        mAccountingDate = mState.getString(K_DATE, "");
        mUsedTodayMs = mState.getLong(K_USED_MS, 0);
        mSession = mState.getInt(K_SESSION, 1);
        long rw = mState.getLong(K_REST_UNTIL_WALL, 0);
        mRestUntilWall = rw > 0 ? rw : null;
        mRestUntilElapsed = mState.getLong(K_REST_UNTIL_ELAP, 0);
        rolloverIfNeeded();
    }

    private void persistState() {
        // rollover check is cheap and keeps midnight reset even if only written
        String t = today();
        if (!t.equals(mAccountingDate)) {
            mUsedTodayMs = 0;
            mSession = 1;
            mRestUntilWall = null;
            mRestUntilElapsed = 0;
            mAccountingDate = t;
        }
        mState.edit()
                .putString(K_DATE, mAccountingDate)
                .putLong(K_USED_MS, mUsedTodayMs)
                .putInt(K_SESSION, mSession)
                .putLong(K_REST_UNTIL_WALL, mRestUntilWall != null ? mRestUntilWall : 0)
                .putLong(K_REST_UNTIL_ELAP, mRestUntilElapsed)
                .putLong(K_SAVED_AT_WALL, System.currentTimeMillis())
                .putLong(K_SAVED_AT_ELAP, SystemClock.elapsedRealtime())
                .apply();
    }
}
