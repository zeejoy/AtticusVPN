/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public final class GlassPixelsTest {
    @Test public void uniformBackdropRemainsUniformEvenAtClampedEdges() {
        int[] data=new int[63],scratch=new int[63];Arrays.fill(data,0xff456789);
        GlassPixels.blur(data,scratch,9,7,3);
        for(int color:data)assertEquals(0xff456789,color);
    }
    @Test public void edgeIsSmoothedWithoutExposingOutOfBoundsPixels() {
        int[] data=new int[21],scratch=new int[21];
        for(int i=0;i<data.length;i++)data[i]=i%7<3?0xffff0000:0xff0000ff;
        GlassPixels.blur(data,scratch,7,3,2);
        int center=data[3];assertTrue(((center>>>16)&255)>0);assertTrue((center&255)>0);
        for(int color:data)assertEquals(255,color>>>24);
    }
    @Test public void invalidOrAliasedBuffersAreRejected() {
        int[] data=new int[4];
        assertThrows(IllegalArgumentException.class,()->GlassPixels.blur(data,data,2,2,2));
        assertThrows(IllegalArgumentException.class,()->GlassPixels.blur(data,new int[4],Integer.MAX_VALUE,2,2));
        assertThrows(IllegalArgumentException.class,()->GlassPixels.blur(data,new int[2],2,2,2));
    }
    @Test public void iconColorsInterpolateAndRejectNonfiniteProgress() {
        assertEquals(0xff808080,GlassPixels.mix(0xff000000,0xffffffff,.5f));
        assertEquals(0xff000000,GlassPixels.mix(0xff000000,0xffffffff,Float.NaN));
        assertEquals(0xffffffff,GlassPixels.mix(0xff000000,0xffffffff,3));
    }
}
