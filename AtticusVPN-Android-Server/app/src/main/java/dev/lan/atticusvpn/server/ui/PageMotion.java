/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

/** Dimension-independent paging decisions. Positive position moves to the next page. */
final class PageMotion {
    private PageMotion() { }

    static float clamp(float value, float low, float high) {
        if (!Float.isFinite(value)) return low;
        return Math.max(low, Math.min(high, value));
    }

    static float drag(float from, float fingerDelta, float width, int anchor, int last) {
        if (!(width > 0f) || !Float.isFinite(fingerDelta)) return clamp(from, 0f, last);
        float position = from - fingerDelta / width;
        float low = Math.max(0, anchor - 1), high = Math.min(last, anchor + 1);
        if (position < low) return low - resistance(low - position);
        if (position > high) return high + resistance(position - high);
        return position;
    }

    private static float resistance(float distance) {
        // Bounded to 6% of a page, regardless of how far the finger leaves it.
        return .18f * distance / (1f + 3f * distance);
    }

    static int target(float position, float fingerVelocity, float width,
                      int anchor, int last, float minimumFlingVelocity) {
        int wanted = anchor;
        if (!Float.isFinite(position)) return Math.max(0, Math.min(last, anchor));
        if (width > 0f && Float.isFinite(fingerVelocity)
                && Math.abs(fingerVelocity) >= minimumFlingVelocity) {
            // Reversed fling first returns to the origin, avoiding two destinations.
            if (fingerVelocity < 0f) wanted = position < anchor - .05f ? anchor : anchor + 1;
            else wanted = position > anchor + .05f ? anchor : anchor - 1;
        } else if (position >= anchor + .22f) wanted = anchor + 1;
        else if (position <= anchor - .22f) wanted = anchor - 1;
        return Math.max(0, Math.min(last, wanted));
    }

    static long duration(float from, float to, float width, float fingerVelocity) {
        float distance = Math.abs(to - from);
        if (distance < .0001f) return 0L;
        if (width > 0f && Float.isFinite(fingerVelocity) && Math.abs(fingerVelocity) > 1f)
            return Math.round(clamp(distance * width / Math.abs(fingerVelocity) * 1200f, 180f, 340f));
        return Math.round(clamp(250f + 80f * distance, 250f, 360f));
    }
}
