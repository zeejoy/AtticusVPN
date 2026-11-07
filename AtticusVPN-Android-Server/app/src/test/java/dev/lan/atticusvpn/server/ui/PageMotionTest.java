/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PageMotionTest {
    @Test public void dragUsesActualContinuousViewportFraction() {
        assertEquals(.25f, PageMotion.drag(0f, -100f, 400f, 0, 2), .0001f);
        assertEquals(.4f, PageMotion.drag(.6f, 80f, 400f, 1, 2), .0001f);
        assertEquals(1.5f, PageMotion.drag(1f, -200f, 400f, 1, 2), .0001f);
    }

    @Test public void edgesAndSingleGestureRangeResistWithoutWrapping() {
        float first = PageMotion.drag(0f, 10000f, 400f, 0, 2);
        float last = PageMotion.drag(2f, -10000f, 400f, 2, 2);
        assertTrue(first < 0f && first >= -.06f);
        assertTrue(last > 2f && last <= 2.06f);
        assertTrue(PageMotion.drag(0f, -5000f, 400f, 0, 2) <= 1.06f);
    }

    @Test public void slowDragsCommitOnlyAfterDirectionalThreshold() {
        assertEquals(1, PageMotion.target(1.21f, 0f, 400, 1, 2, 480));
        assertEquals(2, PageMotion.target(1.23f, 0f, 400, 1, 2, 480));
        assertEquals(0, PageMotion.target(.77f, 0f, 400, 1, 2, 480));
        assertEquals(1, PageMotion.target(.79f, 0f, 400, 1, 2, 480));
    }

    @Test public void quickFlingAndReversalHaveDifferentDestinations() {
        assertEquals(2, PageMotion.target(1.03f, -900f, 400, 1, 2, 480));
        assertEquals(0, PageMotion.target(.97f, 900f, 400, 1, 2, 480));
        assertEquals(1, PageMotion.target(1.4f, 900f, 400, 1, 2, 480));
        assertEquals(1, PageMotion.target(.6f, -900f, 400, 1, 2, 480));
    }

    @Test public void flingCannotSelectOutsideTheExistingPages() {
        assertEquals(0, PageMotion.target(-.02f, 5000, 400, 0, 2, 480));
        assertEquals(2, PageMotion.target(2.02f, -5000, 400, 2, 2, 480));
        assertEquals(0, PageMotion.target(0, -5000, 400, 0, 0, 480));
    }

    @Test public void interruptionStartsFromTheRenderedFraction() {
        float held = .73f;
        assertEquals(held, PageMotion.drag(held, 0, 400, 1, 2), .0001f);
        assertEquals(.98f, PageMotion.drag(held, -100, 400, 1, 2), .0001f);
        assertEquals(.48f, PageMotion.drag(held, 100, 400, 1, 2), .0001f);
    }

    @Test public void boundedTimingsRemainUsableAcrossViewportSizes() {
        assertEquals(0, PageMotion.duration(1, 1, 400, 900));
        assertTrue(PageMotion.duration(.2f, 1, 400, 5000) >= 180);
        assertTrue(PageMotion.duration(0, 2, 400, 0) <= 360);
        assertEquals(PageMotion.duration(.5f, 1, 400, 1000),
                PageMotion.duration(.5f, 1, 800, 2000));
    }

    @Test public void invalidAndUnmeasuredSamplesNeverProduceNonfiniteProgress() {
        assertEquals(1, PageMotion.drag(1, 50, 0, 1, 2), 0);
        assertEquals(1, PageMotion.drag(1, Float.NaN, 400, 1, 2), 0);
        assertEquals(1, PageMotion.target(Float.NaN, 900, 400, 1, 2, 480));
        assertEquals(0, PageMotion.clamp(Float.POSITIVE_INFINITY, 0, 2), 0);
    }
}
