package com.oplus.settings.feature.othersettings.development.memkiller;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import com.oplus.settings.widget.preference.SettingSwitchPreference;

/** Inserts "Disable memory killer" + 500-100 MB slider directly below "Memory" in Developer Options. */
public final class DevMemKillerUi {
    private static final String TAG = "DevMemKillerUi";
    private static final String KEY_DEV_TOP = "dev_top";
    private static final String KEY_MEMORY = "memory";
    public static final String KEY_DISABLE_MEM_KILLER = "oplus_disable_memory_killer";

    private DevMemKillerUi() {
    }

    private static boolean readEnabled(ContentResolver cr) {
        try {
            return Settings.Global.getInt(cr, KEY_DISABLE_MEM_KILLER, 0) == 1;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void ensureDefaultFloor(ContentResolver cr) {
        try {
            if (Settings.Global.getInt(cr, MemKillerSliderPreference.KEY_MIN_FREE_MB, -1) < 0) {
                Settings.Global.putInt(cr, MemKillerSliderPreference.KEY_MIN_FREE_MB, MemKillerSliderPreference.DEFAULT_MB);
            }
        } catch (RuntimeException ignored) {
        }
    }

    public static void install(PreferenceFragmentCompat fragment) {
        if (fragment == null) {
            return;
        }
        try {
            Preference existing = fragment.findPreference(KEY_DISABLE_MEM_KILLER);
            if (existing != null) {
                sync(fragment);
                DevCutoutUi.install(fragment);
                return;
            }
            Preference topRaw = fragment.findPreference(KEY_DEV_TOP);
            Preference memPref = fragment.findPreference(KEY_MEMORY);
            PreferenceGroup parent = (topRaw instanceof PreferenceGroup) ? (PreferenceGroup) topRaw : null;
            if (parent == null && memPref != null) {
                parent = memPref.getParent();
            }
            if (parent == null) {
                parent = fragment.getPreferenceScreen();
            }
            if (parent == null) {
                return;
            }
            final Context ctx = parent.getContext();
            final ContentResolver cr = ctx.getContentResolver();
            final boolean enabled = readEnabled(cr);
            int baseOrder = (memPref != null && memPref.getOrder() != Integer.MAX_VALUE) ? memPref.getOrder() : -85;

            final SettingSwitchPreference sw = new SettingSwitchPreference(ctx);
            sw.setKey(KEY_DISABLE_MEM_KILLER);
            sw.setPersistent(false);
            sw.setTitle("Disable memory killer");
            sw.setChecked(enabled);
            sw.setOrder(baseOrder + 1); // -84, directly below Memory (-85) and above Bug report (-80)

            final MemKillerSliderPreference slider = new MemKillerSliderPreference(ctx);
            slider.setOrder(baseOrder + 2); // -83, directly below toggle and above Bug report (-80)
            slider.setVisible(enabled);

            sw.setOnPreferenceChangeListener((pref, newValue) -> {
                if (!(newValue instanceof Boolean)) {
                    return false;
                }
                boolean en = (Boolean) newValue;
                try {
                    if (en) {
                        ensureDefaultFloor(cr);
                    }
                    Settings.Global.putInt(cr, KEY_DISABLE_MEM_KILLER, en ? 1 : 0);
                } catch (RuntimeException ignored) {
                }
                sw.setChecked(en);
                slider.setVisible(en);
                if (en) {
                    slider.refreshFromSystem();
                }
                return true;
            });

            parent.addPreference(sw);
            parent.addPreference(slider);
            DevCutoutUi.install(fragment);
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "Failed to install Disable memory killer UI", e);
        }
    }

    public static void sync(PreferenceFragmentCompat fragment) {
        if (fragment == null) {
            return;
        }
        try {
            Preference pSw = fragment.findPreference(KEY_DISABLE_MEM_KILLER);
            Preference pSl = fragment.findPreference(MemKillerSliderPreference.KEY_MIN_FREE_MB);
            if (pSw instanceof SettingSwitchPreference) {
                SettingSwitchPreference sw = (SettingSwitchPreference) pSw;
                boolean en = readEnabled(sw.getContext().getContentResolver());
                sw.setChecked(en);
                if (pSl instanceof MemKillerSliderPreference) {
                    MemKillerSliderPreference slider = (MemKillerSliderPreference) pSl;
                    slider.setVisible(en);
                    slider.refreshFromSystem();
                }
            }
            DevCutoutUi.sync(fragment);
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "Failed to sync Disable memory killer UI", e);
        }
    }
}
