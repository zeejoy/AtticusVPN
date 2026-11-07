/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

/** Small opaque backdrop filter. Bounded separable box passes, no image persistence. */
final class GlassPixels {
    private GlassPixels() { }
    static void blur(int[] pixels,int[] scratch,int width,int height,int radius) {
        if(width<=0||height<=0||(long)width*height>pixels.length||scratch.length<pixels.length||scratch==pixels)
            throw new IllegalArgumentException("Invalid glass buffer");
        int r=Math.max(0,Math.min(8,radius));if(r==0)return;
        for(int pass=0;pass<3;pass++){filter(pixels,scratch,width,height,r,true);filter(scratch,pixels,width,height,r,false);}
    }
    private static void filter(int[] source,int[] target,int w,int h,int radius,boolean horizontal) {
        int length=horizontal?w:h,lines=horizontal?h:w,step=horizontal?1:w,window=radius*2+1;
        for(int line=0;line<lines;line++) {
            int origin=horizontal?line*w:line,red=0,green=0,blue=0;
            for(int k=-radius;k<=radius;k++){
                int c=source[origin+Math.max(0,Math.min(length-1,k))*step];red+=(c>>>16)&255;green+=(c>>>8)&255;blue+=c&255;
            }
            for(int index=0;index<length;index++) {
                target[origin+index*step]=0xff000000|(red/window<<16)|(green/window<<8)|blue/window;
                int remove=source[origin+Math.max(0,index-radius)*step],add=source[origin+Math.min(length-1,index+radius+1)*step];
                red+=((add>>>16)&255)-((remove>>>16)&255);green+=((add>>>8)&255)-((remove>>>8)&255);blue+=(add&255)-(remove&255);
            }
        }
    }
    static int mix(int from,int to,float fraction) {
        float f=Float.isFinite(fraction)?Math.max(0,Math.min(1,fraction)):0;int result=0;
        for(int shift=0;shift<=24;shift+=8){int a=(from>>>shift)&255,b=(to>>>shift)&255;result|=Math.round(a+(b-a)*f)<<shift;}
        return result;
    }
}
