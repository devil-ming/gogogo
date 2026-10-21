package com.zcshou.service;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MockPositionTest {
    @Test public void targetWithoutAnyWritesIsPending() {
        assertFalse(position(0, 0, 0).isFreshAt(10000));
        assertFalse(position(10000, 0, 0).isFreshAt(10000));
    }

    @Test public void recentSystemOrGmsWriteCanConfirmActivity() {
        assertTrue(position(10000, 9990, 0).isFreshAt(10010));
        assertTrue(position(10000, 0, 9990).isFreshAt(10010));
    }

    @Test public void healthyLoopWithStaleProvidersIsPending() {
        assertFalse(position(10000, 5000, 4000).isFreshAt(10000));
    }

    @Test public void stalledLoopIsPendingEvenIfGmsCompletesLate() {
        assertFalse(position(5000, 5000, 9990).isFreshAt(10000));
    }

    @Test public void exactStaleBoundaryAndFutureTimestampsAreNotFresh() {
        assertTrue(position(10000, 10000, 0).isFreshAt(14999));
        assertFalse(position(10000, 10000, 0).isFreshAt(15000));
        assertFalse(position(11000, 11000, 0).isFreshAt(10000));
    }

    private MockPosition position(long tick, long system, long gms) {
        return new MockPosition(113.85, 34.03, 55, 0, 0, tick, system, gms);
    }
}
