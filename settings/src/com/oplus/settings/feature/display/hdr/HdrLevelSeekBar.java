package com.oplus.settings.feature.display.hdr;

import android.content.Context;
import android.view.MotionEvent;
import com.coui.appcompat.seekbar.COUISeekBar;
import com.coui.appcompat.seekbar.core.SeekBarState;

/** Continuous COUI seekbar with synchronous tap-to-position and no delayed drag spring. */
public final class HdrLevelSeekBar extends COUISeekBar {
    public HdrLevelSeekBar(Context context) {
        super(context);
    }

    @Override
    public boolean useDragTransitionAnimation() {
        return false;
    }

    @Override
    public void startClickTransitionAnim(MotionEvent event) {
        if (event == null) {
            return;
        }
        float coord = getTouchPrimaryCoordinate(event);
        float len = getSeekBarLength();
        if (len <= 0.0f) {
            return;
        }
        float scale;
        if (isVertical()) {
            scale = (getProgressStart() + len - coord) / len;
        } else {
            scale = primaryToHorizontalAbsoluteScale(coord, len);
        }
        scale = Math.max(interactionFloorScale(), Math.min(1.0f, scale));
        SeekBarState state = getMState();
        int min = state.getMin();
        int max = state.getMax();
        int raw = Math.round(scale * (float) (max - min) + (float) min);
        int target = Math.max(effectiveLowerBound(), Math.min(max, raw));
        setProgress(target, false, true);
        invalidate();
    }
}
