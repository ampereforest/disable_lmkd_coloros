package com.android.server.display.color;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.SurfaceControlHdrLayerInfoListener;
import com.android.server.LocalServices;
import com.android.server.display.DisplayControl;
import java.util.ArrayList;
import java.util.Arrays;

/** Undeployed candidate. Handler owns lifecycle/topology; ingress has its own fence.
 * This is conservative display-wide desaturation, NOT frame-atomic HDR exclusion.
 * No native callback is trusted across a user, power, topology or SF generation.
 */
public final class InlineSdrSaturationController {
    static final String KEY = "inline_sdr_saturation";
    static final String KEY_HDR_PEAK = "oplus_customize_hdr_peak_level";
    static final String KEY_SDR2HDR_EN = "oplus_customize_sdr2hdr_boost_enable";
    static final String KEY_SDR2HDR_LVL = "oplus_customize_sdr2hdr_boost_level";
    static final String PROP_HDR_PEAK = "persist.sys.oplus.hdr_peak_level";
    static final String PROP_SDR2HDR_EN = "persist.sys.oplus.sdr2hdr_enable";
    static final String PROP_SDR2HDR_LVL = "persist.sys.oplus.sdr2hdr_level";
    static final String KEY_DISABLE_MEM_KILLER = "oplus_disable_memory_killer";
    static final String KEY_MEM_KILLER_FLOOR_MB = "oplus_memory_killer_min_free_mb";
    static final String PROP_DISABLE_MEM_KILLER = "persist.sys.oplus.disable_mem_killer";
    static final String PROP_MEM_KILLER_FLOOR_MB = "persist.sys.oplus.mem_killer_floor_mb";
    static final int LEVEL = 151; // after global saturation150, before grayscale200
    private static final String TAG = "InlineSdrSaturation";
    private static final long HEALTH_MS = 1000;
    private final Context context;
    private final ContentResolver resolver;
    private final Handler handler;
    private final ArrayList<HdrListener> listeners = new ArrayList<>();
    private DisplayTransformManager dtm;
    private DisplayManager displays;
    private PowerManager power;
    private ContentObserver observer;
    private DisplayManager.DisplayListener displayListener;
    private BroadcastReceiver receiver;
    private IBinder flinger;
    private final InlineSdrIngress ingress = new InlineSdrIngress();
    private boolean retryScheduled;
    private final Runnable neutralRetry = new Runnable() {
        @Override public void run() {
            retryScheduled = false;
            clearSlot(); // debt is independent of active/user/session/slot presence
        }
    };
    private IBinder.DeathRecipient deathRecipient;
    private long generation;
    private long session;
    private int user = -10000;
    private int level = 100;
    private int hdrPeakLevel = 100;
    private int sdr2HdrEnable = 0;
    private int sdr2HdrLevel = 50;
    private boolean active;
    private boolean ready;
    private boolean failed;
    private boolean screenBlocked;
    private Snapshot snapshot;
    private Runnable health;

    public InlineSdrSaturationController(Context context, Handler handler) {
        this.context = context;
        this.resolver = context.getContentResolver();
        this.handler = handler;
    }

    private void assertHandler() {
        if (android.os.Looper.myLooper() != handler.getLooper()) {
            throw new IllegalStateException("CDS handler required");
        }
    }

    public void setUp(int currentUser) {
        assertHandler();
        tearDown(); // clear owned slot BEFORE acquiring a new user/session
        if (currentUser < 0) return;
        final long thisSession = session;
        user = currentUser;
        active = true;
        failed = false;
        try {
            dtm = LocalServices.getService(DisplayTransformManager.class);
            if (dtm == null) throw new IllegalStateException("DTM unavailable");
            displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (displays == null || power == null) {
                throw new IllegalStateException("display services unavailable");
            }
            screenBlocked = !power.isInteractive();
            final Uri satUri = Settings.System.getUriFor(KEY);
            final Uri hdrPeakUri = Settings.System.getUriFor(KEY_HDR_PEAK);
            final Uri sdr2HdrEnUri = Settings.System.getUriFor(KEY_SDR2HDR_EN);
            final Uri sdr2HdrLvlUri = Settings.System.getUriFor(KEY_SDR2HDR_LVL);
            final Uri memKillerUri = Settings.Global.getUriFor(KEY_DISABLE_MEM_KILLER);
            final Uri memFloorUri = Settings.Global.getUriFor(KEY_MEM_KILLER_FLOOR_MB);
            observer = new ContentObserver(handler) {
                @Override public void onChange(boolean self, Uri uri) {
                    if (!active || session != thisSession) return;
                    boolean memOnly = uri != null && (uri.equals(memKillerUri) || uri.equals(memFloorUri));
                    syncHdrProperties();
                    if (memOnly) return;
                    readLevel();
                    if (wantsMatrix() && (snapshot == null || listeners.size() != snapshot.tokens.length)) rebuild();
                    evaluate();
                    scheduleHealth();
                }
            };
            resolver.registerContentObserver(satUri, false, observer, user);
            resolver.registerContentObserver(hdrPeakUri, false, observer, user);
            resolver.registerContentObserver(sdr2HdrEnUri, false, observer, user);
            resolver.registerContentObserver(sdr2HdrLvlUri, false, observer, user);
            resolver.registerContentObserver(memKillerUri, false, observer);
            resolver.registerContentObserver(memFloorUri, false, observer);
            displayListener = new DisplayManager.DisplayListener() {
                private void changed() {
                    if (active && session == thisSession) rebuild();
                }
                @Override public void onDisplayAdded(int id) { changed(); }
                @Override public void onDisplayRemoved(int id) { changed(); }
                @Override public void onDisplayChanged(int id) { changed(); }
            };
            displays.registerDisplayListener(displayListener, handler);
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    if (!active || session != thisSession) return;
                    // SCREEN_OFF is authoritative even if PM's snapshot still says ON.
                    // SCREEN_ON merely permits fresh native/logical + HDR confirmation.
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                        screenBlocked = true;
                    } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                        screenBlocked = false;
                    } else {
                        screenBlocked = true;
                    }
                    rebuild();
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            context.registerReceiver(receiver, filter, null, handler);
            syncHdrProperties();
            readLevel();
            ready = true;
            if (wantsMatrix()) rebuild();
        } catch (Exception | LinkageError e) {
            failed = true;
            clearSlot();
            Slog.w(TAG, "setup failed; neutral", e);
        }
        health = new Runnable() {
            @Override public void run() {
                if (!active || session != thisSession) return;
                try {
                    Snapshot now = capture();
                    if (!ready || failed || snapshot == null || !snapshot.same(now)
                            || (wantsMatrix() && listeners.size() != now.tokens.length)) {
                        if (ready) rebuild();
                        else clearSlot(); // incomplete setup cannot silently recover/apply
                    } else {
                        evaluate();
                    }
                } catch (Exception | LinkageError e) {
                    invalidateDisplays();
                    Slog.w(TAG, "display state unknown; neutral", e);
                }
                scheduleHealth();
            }
        };
        scheduleHealth();
    }

    public void tearDown() {
        assertHandler();
        // Invalidate first; unregister can deliver queued/reentrant callbacks.
        ++session;
        active = false;
        ready = false;
        clearSlot();
        invalidateDisplays();
        if (health != null) handler.removeCallbacks(health);
        health = null;
        try { if (observer != null) resolver.unregisterContentObserver(observer); }
        catch (RuntimeException e) { Slog.w(TAG, "observer cleanup", e); }
        observer = null;
        try { if (displayListener != null) displays.unregisterDisplayListener(displayListener); }
        catch (RuntimeException e) { Slog.w(TAG, "display cleanup", e); }
        displayListener = null;
        try { if (receiver != null) context.unregisterReceiver(receiver); }
        catch (RuntimeException e) { Slog.w(TAG, "power cleanup", e); }
        receiver = null;
        user = -10000;
        level = 100;
        hdrPeakLevel = 100;
        sdr2HdrEnable = 0;
        sdr2HdrLevel = 50;
        screenBlocked = true;
        // Never cancel neutralRetry: transport debt must survive inactive lifecycle.
    }

    private boolean wantsMatrix() {
        return level != 100 || hdrPeakLevel > 100 || (sdr2HdrEnable == 1 && sdr2HdrLevel > 0);
    }

    private void readLevel() {
        try { level = parseLevel(Settings.System.getStringForUser(resolver, KEY, user)); }
        catch (RuntimeException e) { level = 100; Slog.w(TAG, "setting unavailable; neutral", e); }
    }

    static int parseRange(String value, int min, int max, int defVal) {
        if (value == null) return defVal;
        try { return Math.max(min, Math.min(max, Integer.parseInt(value.trim()))); }
        catch (NumberFormatException e) { return defVal; }
    }

    private void syncHdrProperties() {
        if (user < 0) return;
        try {
            int peak = parseRange(Settings.System.getStringForUser(resolver, KEY_HDR_PEAK, user), 50, 125, 100);
            int en = parseRange(Settings.System.getStringForUser(resolver, KEY_SDR2HDR_EN, user), 0, 1, 0);
            int lvl = parseRange(Settings.System.getStringForUser(resolver, KEY_SDR2HDR_LVL, user), 0, 100, 50);
            int mkEn = parseRange(Settings.Global.getString(resolver, KEY_DISABLE_MEM_KILLER), 0, 1, 0);
            int mkFloor = parseRange(Settings.Global.getString(resolver, KEY_MEM_KILLER_FLOOR_MB), 0, 500, 500);
            hdrPeakLevel = peak;
            sdr2HdrEnable = en;
            sdr2HdrLevel = lvl;
            SystemProperties.set(PROP_HDR_PEAK, Integer.toString(peak));
            SystemProperties.set(PROP_SDR2HDR_EN, Integer.toString(en));
            SystemProperties.set(PROP_SDR2HDR_LVL, Integer.toString(lvl));
            SystemProperties.set(PROP_DISABLE_MEM_KILLER, Integer.toString(mkEn));
            SystemProperties.set(PROP_MEM_KILLER_FLOOR_MB, Integer.toString(mkFloor));
        } catch (RuntimeException | LinkageError e) {
            Slog.w(TAG, "HDR property sync failed", e);
        }
    }

    static int parseLevel(String value) {
        if (value == null) return 100;
        try { return Math.max(0, Math.min(200, Integer.parseInt(value.trim()))); }
        catch (NumberFormatException e) { return 100; }
    }

    static float[] matrix(int value) {
        return composedMatrix(value, 100, 0, 0, false);
    }

    static float[] composedMatrix(int satValue, int peakLevel, int boostEn, int boostLvl, boolean hasHdrLayers) {
        int clampedSat = Math.max(0, Math.min(200, satValue));
        int clampedPeak = Math.max(50, Math.min(125, peakLevel));
        int clampedBoost = (boostEn == 1) ? Math.max(0, Math.min(100, boostLvl)) : 0;

        float s = hasHdrLayers ? 1.0f : (clampedSat / 100.0f);
        float boostNorm = (!hasHdrLayers && clampedBoost > 0) ? (clampedBoost / 100.0f) : 0.0f;
        float peakNorm = (clampedPeak > 100) ? ((clampedPeak - 100) / 25.0f) : 0.0f;

        float vibBoost = 1.0f + 0.22f * peakNorm + 0.36f * boostNorm;
        float effSat = (s == 0.0f) ? 0.0f : Math.min(2.35f, s * vibBoost);
        float contrast = 1.0f + 0.14f * peakNorm + 0.22f * boostNorm;
        float offset = (1.0f - contrast) * 0.20f;

        if (Math.abs(effSat - 1.0f) < 1e-5f
                && Math.abs(contrast - 1.0f) < 1e-5f
                && Math.abs(offset) < 1e-5f) {
            return null; // never contribute a stored identity matrix
        }
        float d = 1.0f - effSat;
        float r = 0.2126f * d, g = 0.7152f * d, b = 0.0722f * d;
        // Column-major 4x4 Rec.709 saturation composed with highlight contrast & tone gain.
        return new float[] {
            contrast * (r + effSat), contrast * r, contrast * r, 0f,
            contrast * g, contrast * (g + effSat), contrast * g, 0f,
            contrast * b, contrast * b, contrast * (b + effSat), 0f,
            offset, offset, offset, 1f
        };
    }

    private void clearSlot() {
        if (dtm == null) return;
        try {
            dtm.applyInlineSdrMatrix(null, ingress);
        }
        catch (RuntimeException | LinkageError e) {
            Slog.w(TAG, "owned neutral transport not acknowledged", e);
        }
        if (dtm.isInlineSdrNeutralPending() && !retryScheduled) {
            retryScheduled = true;
            handler.postDelayed(neutralRetry, HEALTH_MS);
        }
    }

    private void scheduleHealth() {
        if (active && health != null && (wantsMatrix() || dtm != null && dtm.isInlineSdrNeutralPending())) {
            handler.removeCallbacks(health);
            handler.postDelayed(health, HEALTH_MS);
        }
    }

    private void invalidateDisplays() {
        ++generation;
        ingress.reset(generation); // rejects stale admission BEFORE unregister/clear
        snapshot = null;
        clearSlot();
        for (HdrListener listener : listeners) {
            try { listener.unregisterAll(); }
            catch (RuntimeException | LinkageError e) { Slog.w(TAG, "HDR cleanup", e); }
        }
        listeners.clear();
        try {
            if (flinger != null && deathRecipient != null) {
                flinger.unlinkToDeath(deathRecipient, 0);
            }
        } catch (RuntimeException e) { Slog.w(TAG, "SF cleanup", e); }
        flinger = null;
        deathRecipient = null;
    }

    private void rebuild() {
        assertHandler();
        invalidateDisplays();
        if (!active || !ready) return;
        failed = false;
        try {
            final Snapshot next = capture();
            final long gen = generation;
            flinger = next.sf;
            deathRecipient = new IBinder.DeathRecipient() {
                @Override public void binderDied() {
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (active && generation == gen) invalidateDisplays();
                        }
                    });
                }
            };
            flinger.linkToDeath(deathRecipient, 0);
            snapshot = next;
            // Off/doze/unknown or unmatched physical displays remain entirely neutral.
            if (!next.eligible || !wantsMatrix()) return;
            for (int i = 0; i < next.tokens.length; i++) {
                HdrListener listener = new HdrListener(gen, next.tokens[i]);
                listeners.add(listener);
                listener.register(listener.token);
            }
            // Once all physical display listeners are registered, initial unreported state is 0 HDR layers.
            evaluate();
        } catch (Exception | LinkageError e) {
            failed = true;
            invalidateDisplays();
            Slog.w(TAG, "HDR registration failed; neutral", e);
        }
    }

    private final class HdrListener extends SurfaceControlHdrLayerInfoListener {
        final long gen;
        final IBinder token;
        int count = 0; // registered listener defaults to 0 until callback reports >0 or error
        HdrListener(long gen, IBinder token) {
            this.gen = gen; this.token = token;
            ingress.register(gen, this);
        }
        @Override public void onHdrInfoChanged(final IBinder callbackToken, final int count,
                int maxW, int maxH, int flags, float ratio) {
            final boolean tokenMatches = callbackToken != null && callbackToken.equals(token);
            final int admittedCount = tokenMatches ? count : -1;
            final long serial = ingress.enter(gen, this, admittedCount);
            if (serial < 0) return;
            handler.post(new Runnable() {
                @Override public void run() {
                    if (!active || gen != generation || !listeners.contains(HdrListener.this)) return;
                    if (!ingress.consume(gen, HdrListener.this, serial, admittedCount)) {
                        evaluate(); // stale zero cannot override newer admitted positive
                        return;
                    }
                    if (!tokenMatches || count < 0) {
                        Slog.w(TAG, "HDR invalid callback token=" + callbackToken + " expected=" + token + " count=" + count, null);
                        invalidateDisplays();
                        return;
                    }
                    HdrListener.this.count = count;
                    evaluate();
                }
            });
        }
    }

    private void evaluate() {
        assertHandler();
        if (!active || !ready || failed || !wantsMatrix() || snapshot == null
                || !snapshot.eligible || listeners.size() != snapshot.tokens.length) {
            clearSlot();
            return;
        }
        for (HdrListener listener : listeners) {
            if (listener.gen != generation || listener.count != 0) {
                clearSlot();
                return;
            }
        }
        if (!ingress.allowsWrite()) {
            clearSlot(); // positive->zero debt requires checked neutral acceptance
            return;
        }
        try {
            if (!snapshot.same(capture())) {
                rebuild();
                return;
            }
            float[] mat = composedMatrix(level, hdrPeakLevel, sdr2HdrEnable, sdr2HdrLevel, false);
            if (mat == null || !dtm.applyInlineSdrMatrix(mat, ingress)) clearSlot();
        } catch (Exception | LinkageError e) {
            failed = true;
            invalidateDisplays();
            Slog.w(TAG, "apply/state check failed; neutral", e);
        }
    }

    private static Long physicalDisplayId(DisplayAddress address) {
        if (address instanceof DisplayAddress.Physical) {
            return ((DisplayAddress.Physical) address).getPhysicalDisplayId();
        }
        if (address instanceof DisplayAddress.StablePhysical) {
            return ((DisplayAddress.StablePhysical) address).getPhysicalDisplayId();
        }
        return null;
    }

    private Snapshot capture() {
        IBinder sf = ServiceManager.getService("SurfaceFlinger");
        if (sf == null || !sf.isBinderAlive() || !sf.pingBinder()) {
            throw new IllegalStateException("SurfaceFlinger unavailable");
        }
        long[] ids = DisplayControl.getPhysicalDisplayIds();
        if (ids == null || ids.length == 0) throw new IllegalStateException("no physical display");
        ids = ids.clone();
        Arrays.sort(ids);
        IBinder[] tokens = new IBinder[ids.length];
        Display[] logical = displays.getDisplays();
        if (logical == null) throw new IllegalStateException("logical displays unknown");
        boolean eligible = !screenBlocked && power.isInteractive();
        String[] logicalIdentity = new String[logical.length];
        java.util.HashSet<Integer> logicalIds = new java.util.HashSet<>();
        java.util.HashSet<String> logicalUniqueIds = new java.util.HashSet<>();
        java.util.HashSet<Long> logicalPhysicalIds = new java.util.HashSet<>();
        for (int i = 0; i < logical.length; i++) {
            Display display = logical[i];
            if (display == null) throw new IllegalStateException("null logical display");
            DisplayAddress address = display.getAddress();
            int type = display.getType(), state = display.getState(), id = display.getDisplayId();
            String unique = display.getUniqueId();
            logicalIdentity[i] = id+"|"+(unique == null ? "null" : unique.length()+":"+unique)+"|"+type+"|"+state+"|"
                    +(address == null ? "null" : address.getClass().getName()+":"+address.toString());
            if (!logicalIds.add(id) || unique == null || unique.length() == 0 || !logicalUniqueIds.add(unique)) eligible = false;
            Long physicalId = physicalDisplayId(address);
            if (physicalId == null
                    || (type != Display.TYPE_INTERNAL && type != Display.TYPE_EXTERNAL)
                    || state != Display.STATE_ON) {
                eligible = false; // includes virtual/network/cast/unknown even if OFF
                continue;
            }
            if (!logicalPhysicalIds.add(physicalId) || Arrays.binarySearch(ids, physicalId) < 0) eligible = false;
        }
        Arrays.sort(logicalIdentity);
        for (int i = 0; i < ids.length; i++) {
            if (i > 0 && ids[i] == ids[i-1]) throw new IllegalStateException("duplicate display");
            tokens[i] = DisplayControl.getPhysicalDisplayToken(ids[i]);
            if (tokens[i] == null) throw new IllegalStateException("missing display token");
            int matches = 0;
            for (Display display : logical) {
                if (display == null) throw new IllegalStateException("null logical display");
                Long physicalId = physicalDisplayId(display.getAddress());
                if (physicalId != null && physicalId == ids[i]) {
                    ++matches;
                    eligible &= display.getState() == Display.STATE_ON;
                }
            }
            // Never omit a powered-off or unknown physical display from gating.
            if (matches != 1) eligible = false;
        }
        return new Snapshot(sf, ids, tokens, logicalIdentity, eligible);
    }

    private static final class Snapshot {
        final IBinder sf;
        final long[] ids;
        final IBinder[] tokens;
        final boolean eligible;
        final String[] logicalIdentity;
        Snapshot(IBinder sf, long[] ids, IBinder[] tokens, String[] logicalIdentity, boolean eligible) {
            this.sf = sf; this.ids = ids; this.tokens = tokens;
            this.logicalIdentity = logicalIdentity; this.eligible = eligible;
        }
        boolean same(Snapshot other) {
            if (sf != other.sf || eligible != other.eligible || !Arrays.equals(ids, other.ids)
                    || !Arrays.equals(logicalIdentity, other.logicalIdentity)) return false;
            for (int i = 0; i < tokens.length; i++) if (tokens[i] != other.tokens[i]) return false;
            return true;
        }
    }
}
