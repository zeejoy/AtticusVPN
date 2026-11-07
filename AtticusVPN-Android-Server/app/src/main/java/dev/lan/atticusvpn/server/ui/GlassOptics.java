/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.ui;

/** Bounded capsule-edge distortion; rebuilt only on size changes, never per frame. */
final class GlassOptics {
    static final int COLUMNS = 48, ROWS = 12;
    private GlassOptics() { }

    static float[] edgeMesh(float width, float height, float edgeWidth) {
        float[] mesh = new float[(COLUMNS + 1) * (ROWS + 1) * 2];
        float radius = height / 2f;
        int index = 0;
        for (int row = 0; row <= ROWS; row++) {
            for (int column = 0; column <= COLUMNS; column++) {
                float x = width * column / COLUMNS, y = height * row / ROWS;
                float axisX = Math.max(radius, Math.min(width - radius, x));
                float dx = x - axisX, dy = y - radius;
                float distance = (float) Math.hypot(dx, dy);
                float depth = radius - distance;
                float displacement = 0;
                if (distance > 0 && edgeWidth > 0 && depth > 0 && depth < edgeWidth) {
                    displacement = edgeWidth * .48f * (float) Math.sin(Math.PI * depth / edgeWidth);
                }
                mesh[index++] = distance == 0 ? x : x + dx / distance * displacement;
                mesh[index++] = distance == 0 ? y : y + dy / distance * displacement;
            }
        }
        return mesh;
    }
}
