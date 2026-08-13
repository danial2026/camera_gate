package com.danials.cameragate.core.face;

/**
 * Minimal integer rectangle, dependency-free so the cascade engine can run
 * in plain Java (unit-tested on the host) as well as on Android API 16.
 */
public final class IntRect {

    public int x;
    public int y;
    public int width;
    public int height;

    public IntRect() {
    }

    public IntRect(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public IntRect(IntRect o) {
        set(o);
    }

    public IntRect set(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        return this;
    }

    public IntRect set(IntRect o) {
        return set(o.x, o.y, o.width, o.height);
    }

    public boolean isEmpty() {
        return width <= 0 || height <= 0;
    }

    public int area() {
        return width * height;
    }

    public IntRect intersect(IntRect r) {
        int x1 = Math.max(x, r.x);
        int y1 = Math.max(y, r.y);
        int x2 = Math.min(x + width, r.x + r.width);
        int y2 = Math.min(y + height, r.y + r.height);
        return new IntRect(x1, y1, Math.max(0, x2 - x1), Math.max(0, y2 - y1));
    }

    public boolean contains(IntRect r) {
        return x <= r.x && y <= r.y
                && x + width >= r.x + r.width
                && y + height >= r.y + r.height;
    }

    @Override
    public String toString() {
        return "[" + x + "," + y + " " + width + "x" + height + "]";
    }
}