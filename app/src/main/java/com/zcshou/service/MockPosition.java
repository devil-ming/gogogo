package com.zcshou.service;

/** Immutable service target in WGS84; timestamps use the monotonic elapsed clock. */
public final class MockPosition {
    public final double longitude, latitude, altitude;
    public final float bearing, speed;
    public final long lastTickMs, lastSystemSuccessMs, lastGmsSuccessMs;

    MockPosition(double lng, double lat, double alt, float bearing, float speed,
                 long tick, long systemSuccess, long gmsSuccess) {
        this.longitude = lng;
        this.latitude = lat;
        this.altitude = alt;
        this.bearing = bearing;
        this.speed = speed;
        this.lastTickMs = tick;
        this.lastSystemSuccessMs = systemSuccess;
        this.lastGmsSuccessMs = gmsSuccess;
    }

    /** A running loop alone is not proof that a location provider accepted a write. */
    public boolean isFreshAt(long nowMs) {
        return recent(lastTickMs, nowMs)
                && (recent(lastSystemSuccessMs, nowMs) || recent(lastGmsSuccessMs, nowMs));
    }

    private static boolean recent(long timestampMs, long nowMs) {
        return timestampMs > 0 && nowMs >= timestampMs && nowMs - timestampMs < 5000;
    }
}
