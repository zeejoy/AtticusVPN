/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * 按透明素材的有效内容等比显示，不把生成画布的透明留白计入图标大小。
 * 仅绘制原 PNG，不修改素材或访问服务器配置；保留 alpha 和文字原有渐变。
 */
final class UiArtwork extends Drawable {
    private final Bitmap bitmap;
    private final Rect content;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    static Drawable load(Context context, int resource) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        final Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), resource, options);
        if (bitmap == null) throw new IllegalArgumentException("Missing UI artwork resource");
        return new UiArtwork(bitmap);
    }

    private UiArtwork(Bitmap image) {
        bitmap = image;
        int left = image.getWidth(), top = image.getHeight(), right = -1, bottom = -1;
        final int[] row = new int[image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int x = 0; x < row.length; x++) {
                // 极淡的透明边缘不决定画布尺寸；有效内容仍使用原 alpha 抗锯齿绘制。
                if ((row[x] >>> 24) < 16) continue;
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
            }
        }
        content = right < left ? new Rect(0, 0, image.getWidth(), image.getHeight())
                : new Rect(Math.max(0, left - 1), Math.max(0, top - 1),
                        Math.min(image.getWidth(), right + 2), Math.min(image.getHeight(), bottom + 2));
    }

    @Override public void draw(Canvas canvas) { canvas.drawBitmap(bitmap, content, getBounds(), paint); }
    @Override public int getIntrinsicWidth() { return content.width(); }
    @Override public int getIntrinsicHeight() { return content.height(); }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @SuppressWarnings("deprecation")
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
