package com.oplus.settings.feature.othersettings.development.memkiller;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.preference.PreferenceViewHolder;
import com.coui.appcompat.cardlist.COUICardListHelper;
import com.coui.appcompat.preference.COUIPreference;
import com.coui.appcompat.seekbar.COUISeekBar;
import com.oplus.settings.feature.display.hdr.HdrLevelSeekBar;

/** COUI seekbar preference for Settings.Global oplus_memory_killer_min_free_mb (0..500 MB). */
public final class MemKillerSliderPreference extends COUIPreference
        implements COUISeekBar.OnSeekBarChangeListener {
    public static final String KEY_MIN_FREE_MB = "oplus_memory_killer_min_free_mb";
    public static final int MIN_MB = 0;
    public static final int MAX_MB = 500;
    public static final int STEP_MB = 50;
    public static final int DEFAULT_MB = 500;

    private static final int LAYOUT_SLIDER_CARD = 0x7f0e0111;
    private static final int ID_SEEKBAR = 0x7f0b0d26;
    private static final int ID_TITLE = 0x7f0b0fdd;
    private static final int ID_VALUE = 0x7f0b0fd1;
    private static final int STYLE_TITLE = 0x7f160281;
    private static final int DIMEN_TITLE_SIZE = 0x7f071438;

    private final ContentResolver mResolver;
    private final String mTitle;
    private int mValue;
    private boolean mBinding;
    private COUISeekBar mSeekBar;
    private TextView mValueView;

    public MemKillerSliderPreference(Context context) {
        super(context);
        mResolver = context.getContentResolver();
        mTitle = "Minimum free RAM floor";
        setKey(KEY_MIN_FREE_MB);
        setTitle(mTitle);
        setPersistent(false);
        setSelectable(false);
        setLayoutResource(LAYOUT_SLIDER_CARD);
        mValue = readGlobalValue();
    }

    public static int clampAndSnap(int v) {
        int clamped = Math.max(MIN_MB, Math.min(MAX_MB, v));
        int offset = ((clamped - MIN_MB + (STEP_MB / 2)) / STEP_MB) * STEP_MB;
        return Math.max(MIN_MB, Math.min(MAX_MB, MIN_MB + offset));
    }

    private int readGlobalValue() {
        try {
            return clampAndSnap(Settings.Global.getInt(mResolver, KEY_MIN_FREE_MB, DEFAULT_MB));
        } catch (RuntimeException ignored) {
            return DEFAULT_MB;
        }
    }

    private void writeGlobalValue(int v) {
        try {
            Settings.Global.putInt(mResolver, KEY_MIN_FREE_MB, v);
        } catch (RuntimeException ignored) {
        }
    }

    private static String formatLabel(int v) {
        return v == 0 ? "0 MB (Fully Disabled)" : (v + " MB");
    }

    private void updateLabelAndA11y() {
        String label = formatLabel(mValue);
        if (mValueView != null) {
            mValueView.setText(label);
        }
        if (mSeekBar != null) {
            mSeekBar.setProgressContentDescription(mTitle + ", " + label);
        }
    }

    public void refreshFromSystem() {
        mValue = readGlobalValue();
        mBinding = true;
        if (mSeekBar != null) {
            mSeekBar.setProgress(mValue - MIN_MB, false, false);
        }
        mBinding = false;
        updateLabelAndA11y();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        mBinding = true;
        if (mSeekBar != null) {
            mSeekBar.setOnSeekBarChangeListener(null);
        }

        View rawSeek = holder.itemView.findViewById(ID_SEEKBAR);
        if (!(rawSeek instanceof HdrLevelSeekBar) && rawSeek != null
                && rawSeek.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) rawSeek.getParent();
            int idx = parent.indexOfChild(rawSeek);
            ViewGroup.LayoutParams lp = rawSeek.getLayoutParams();
            parent.removeView(rawSeek);
            HdrLevelSeekBar custom = new HdrLevelSeekBar(getContext());
            custom.setId(ID_SEEKBAR);
            parent.addView(custom, idx, lp);
            rawSeek = custom;
        }

        if (rawSeek instanceof COUISeekBar) {
            COUISeekBar sb = (COUISeekBar) rawSeek;
            mSeekBar = sb;
            sb.setMin(0);
            sb.setMax(MAX_MB - MIN_MB);
            sb.setIncrement(STEP_MB);
            sb.setEnabled(isEnabled());
            sb.setContentDescription(mTitle);
            sb.setProgress(mValue - MIN_MB, false, false);
            sb.setOnSeekBarChangeListener(this);
        }

        View tvTitle = holder.findViewById(ID_TITLE);
        if (tvTitle instanceof TextView) {
            TextView tv = (TextView) tvTitle;
            tv.setText(mTitle);
            tv.setTextAppearance(STYLE_TITLE);
            tv.setTextSize(0, getContext().getResources().getDimension(DIMEN_TITLE_SIZE));
        }

        View tvVal = holder.findViewById(ID_VALUE);
        mValueView = (tvVal instanceof TextView) ? (TextView) tvVal : null;

        int pos = COUICardListHelper.getPositionInGroup(this);
        if (pos <= 0) {
            pos = 2; // middle of dev_top card between switch and Bug report
        }
        COUICardListHelper.setItemCardBackground(holder.itemView, pos);
        holder.setDividerAllowedAbove(false);
        holder.setDividerAllowedBelow(false);

        mBinding = false;
        refreshFromSystem();
    }

    @Override
    public void onProgressChanged(COUISeekBar seekBar, int progress, boolean fromUser) {
        if (!fromUser || mBinding || seekBar != mSeekBar) {
            return;
        }
        int next = clampAndSnap(MIN_MB + progress);
        if (next != mValue) {
            mValue = next;
            updateLabelAndA11y();
            writeGlobalValue(next);
        }
    }

    @Override
    public void onStartTrackingTouch(COUISeekBar seekBar) {
    }

    @Override
    public void onStopTrackingTouch(COUISeekBar seekBar) {
        if (seekBar != mSeekBar) {
            return;
        }
        mBinding = true;
        mSeekBar.setProgress(mValue - MIN_MB, false, false);
        mBinding = false;
        updateLabelAndA11y();
        writeGlobalValue(mValue);
    }
}
