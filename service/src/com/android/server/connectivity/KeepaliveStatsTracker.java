/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.connectivity;

import android.annotation.NonNull;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.metrics.DailykeepaliveInfoReported;
import com.android.metrics.DurationForNumOfKeepalive;
import com.android.metrics.DurationPerNumOfKeepalive;
import com.android.metrics.KeepaliveLifetimeForCarrier;
import com.android.metrics.KeepaliveLifetimePerCarrier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// TODO(b/273451360): Also track DailykeepaliveInfoReported
/**
 * Tracks carrier and duration metrics of automatic on/off keepalives.
 *
 * <p>This class follows AutomaticOnOffKeepaliveTracker closely and its on*Keepalive methods needs
 * to be called in a timely manner to keep the metrics accurate. It is also not thread-safe and all
 * public methods must be called by the same thread, namely the ConnectivityService handler thread.
 */
public class KeepaliveStatsTracker {
    private static final String TAG = KeepaliveStatsTracker.class.getSimpleName();

    @NonNull private final Handler mConnectivityServiceHandler;
    @NonNull private final Dependencies mDependencies;

    // Class to store network information, lifetime durations and active state of a keepalive.
    private static final class KeepaliveStats {
        // The carrier ID for a keepalive, or TelephonyManager.UNKNOWN_CARRIER_ID(-1) if not set.
        public final int carrierId;
        // The transport types of the underlying network for each keepalive. A network may include
        // multiple transport types. Each transport type is represented by a different bit, defined
        // in NetworkCapabilities
        public final int transportTypes;
        // The keepalive interval in millis.
        public final int intervalMs;

        // Snapshot of the lifetime stats
        public static class LifetimeStats {
            public final int lifetimeMs;
            public final int activeLifetimeMs;

            LifetimeStats(int lifetimeMs, int activeLifetimeMs) {
                this.lifetimeMs = lifetimeMs;
                this.activeLifetimeMs = activeLifetimeMs;
            }
        }

        // The total time since the keepalive is started until it is stopped.
        private int mLifetimeMs = 0;
        // The total time the keepalive is active (not suspended).
        private int mActiveLifetimeMs = 0;

        // A timestamp of the most recent time the lifetime metrics was updated.
        private long mLastUpdateLifetimeTimestamp;

        // A flag to indicate if the keepalive is active.
        private boolean mKeepaliveActive = true;

        /**
         * Gets the lifetime stats for the keepalive, updated to timeNow, and then resets it.
         *
         * @param timeNow a timestamp obtained using Dependencies.getUptimeMillis
         */
        public LifetimeStats getAndResetLifetimeStats(long timeNow) {
            updateLifetimeStatsAndSetActive(timeNow, mKeepaliveActive);
            // Get a snapshot of the stats
            final LifetimeStats lifetimeStats = new LifetimeStats(mLifetimeMs, mActiveLifetimeMs);
            // Reset the stats
            resetLifetimeStats(timeNow);

            return lifetimeStats;
        }

        public boolean isKeepaliveActive() {
            return mKeepaliveActive;
        }

        KeepaliveStats(int carrierId, int transportTypes, int intervalSeconds, long timeNow) {
            this.carrierId = carrierId;
            this.transportTypes = transportTypes;
            this.intervalMs = intervalSeconds * 1000;
            mLastUpdateLifetimeTimestamp = timeNow;
        }

        /**
         * Updates the lifetime metrics to the given time and sets the active state. This should be
         * called whenever the active state of the keepalive changes.
         *
         * @param timeNow a timestamp obtained using Dependencies.getUptimeMillis
         */
        public void updateLifetimeStatsAndSetActive(long timeNow, boolean keepaliveActive) {
            final int durationIncrease = (int) (timeNow - mLastUpdateLifetimeTimestamp);
            mLifetimeMs += durationIncrease;
            if (mKeepaliveActive) mActiveLifetimeMs += durationIncrease;

            mLastUpdateLifetimeTimestamp = timeNow;
            mKeepaliveActive = keepaliveActive;
        }

        /**
         * Resets the lifetime metrics but does not reset the active/stopped state of the keepalive.
         * This also updates the time to timeNow, ensuring stats will start from this time.
         *
         * @param timeNow a timestamp obtained using Dependencies.getUptimeMillis
         */
        public void resetLifetimeStats(long timeNow) {
            mLifetimeMs = 0;
            mActiveLifetimeMs = 0;
            mLastUpdateLifetimeTimestamp = timeNow;
        }
    }

    // List of duration stats metric where the index is the number of concurrent keepalives.
    // Each DurationForNumOfKeepalive message stores a registered duration and an active duration.
    // Registered duration is the total time spent with mNumRegisteredKeepalive == index.
    // Active duration is the total time spent with mNumActiveKeepalive == index.
    private final List<DurationForNumOfKeepalive.Builder> mDurationPerNumOfKeepalive =
            new ArrayList<>();

    // Map of keepalives identified by the id from getKeepaliveId to their stats information.
    private final SparseArray<KeepaliveStats> mKeepaliveStatsPerId = new SparseArray<>();

    // Generate a unique integer using a given network's netId and the slot number.
    // This is possible because netId is a 16 bit integer, so an integer with the first 16 bits as
    // the netId and the last 16 bits as the slot number can be created. This allows slot numbers to
    // be up to 2^16.
    private int getKeepaliveId(@NonNull Network network, int slot) {
        final int netId = network.getNetId();
        if (netId < 0 || netId >= (1 << 16)) {
            throw new IllegalArgumentException("Unexpected netId value: " + netId);
        }
        if (slot < 0 || slot >= (1 << 16)) {
            throw new IllegalArgumentException("Unexpected slot value: " + slot);
        }

        return (netId << 16) + slot;
    }

    // Class to act as the key to aggregate the KeepaliveLifetimeForCarrier stats.
    private static final class LifetimeKey {
        public final int carrierId;
        public final int transportTypes;
        public final int intervalMs;

        LifetimeKey(int carrierId, int transportTypes, int intervalMs) {
            this.carrierId = carrierId;
            this.transportTypes = transportTypes;
            this.intervalMs = intervalMs;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            final LifetimeKey that = (LifetimeKey) o;

            return carrierId == that.carrierId && transportTypes == that.transportTypes
                    && intervalMs == that.intervalMs;
        }

        @Override
        public int hashCode() {
            return carrierId + 3 * transportTypes + 5 * intervalMs;
        }
    }

    // Map to aggregate the KeepaliveLifetimeForCarrier stats using LifetimeKey as the key.
    final Map<LifetimeKey, KeepaliveLifetimeForCarrier.Builder> mAggregateKeepaliveLifetime =
            new HashMap<>();

    private int mNumRegisteredKeepalive = 0;
    private int mNumActiveKeepalive = 0;

    // A timestamp of the most recent time the duration metrics was updated.
    private long mLastUpdateDurationsTimestamp;

    /** Dependency class */
    @VisibleForTesting
    public static class Dependencies {
        // Returns a timestamp with the time base of SystemClock.uptimeMillis to keep durations
        // relative to start time and avoid timezone change.
        public long getUptimeMillis() {
            return SystemClock.uptimeMillis();
        }
    }

    public KeepaliveStatsTracker(@NonNull Handler handler) {
        this(handler, new Dependencies());
    }

    @VisibleForTesting
    public KeepaliveStatsTracker(@NonNull Handler handler, @NonNull Dependencies dependencies) {
        mDependencies = Objects.requireNonNull(dependencies);
        mConnectivityServiceHandler = Objects.requireNonNull(handler);

        mLastUpdateDurationsTimestamp = mDependencies.getUptimeMillis();
    }

    /** Ensures the list of duration metrics is large enough for number of registered keepalives. */
    private void ensureDurationPerNumOfKeepaliveSize() {
        if (mNumActiveKeepalive < 0 || mNumRegisteredKeepalive < 0) {
            throw new IllegalStateException(
                    "Number of active or registered keepalives is negative");
        }
        if (mNumActiveKeepalive > mNumRegisteredKeepalive) {
            throw new IllegalStateException(
                    "Number of active keepalives greater than registered keepalives");
        }

        while (mDurationPerNumOfKeepalive.size() <= mNumRegisteredKeepalive) {
            final DurationForNumOfKeepalive.Builder durationForNumOfKeepalive =
                    DurationForNumOfKeepalive.newBuilder();
            durationForNumOfKeepalive.setNumOfKeepalive(mDurationPerNumOfKeepalive.size());
            durationForNumOfKeepalive.setKeepaliveRegisteredDurationsMsec(0);
            durationForNumOfKeepalive.setKeepaliveActiveDurationsMsec(0);

            mDurationPerNumOfKeepalive.add(durationForNumOfKeepalive);
        }
    }

    /**
     * Updates the durations metrics to the given time. This should always be called before making a
     * change to mNumRegisteredKeepalive or mNumActiveKeepalive to keep the duration metrics
     * correct.
     *
     * @param timeNow a timestamp obtained using Dependencies.getUptimeMillis
     */
    private void updateDurationsPerNumOfKeepalive(long timeNow) {
        if (mDurationPerNumOfKeepalive.size() < mNumRegisteredKeepalive) {
            Log.e(TAG, "Unexpected jump in number of registered keepalive");
        }
        ensureDurationPerNumOfKeepaliveSize();

        final int durationIncrease = (int) (timeNow - mLastUpdateDurationsTimestamp);
        final DurationForNumOfKeepalive.Builder durationForNumOfRegisteredKeepalive =
                mDurationPerNumOfKeepalive.get(mNumRegisteredKeepalive);

        durationForNumOfRegisteredKeepalive.setKeepaliveRegisteredDurationsMsec(
                durationForNumOfRegisteredKeepalive.getKeepaliveRegisteredDurationsMsec()
                        + durationIncrease);

        final DurationForNumOfKeepalive.Builder durationForNumOfActiveKeepalive =
                mDurationPerNumOfKeepalive.get(mNumActiveKeepalive);

        durationForNumOfActiveKeepalive.setKeepaliveActiveDurationsMsec(
                durationForNumOfActiveKeepalive.getKeepaliveActiveDurationsMsec()
                        + durationIncrease);

        mLastUpdateDurationsTimestamp = timeNow;
    }

    // TODO(b/273451360): Make use of SubscriptionManager.OnSubscriptionsChangedListener since
    // TelephonyManager.getSimCarrierId will be a cross-process call.
    private int getCarrierId() {
        // No implementation yet.
        return TelephonyManager.UNKNOWN_CARRIER_ID;
    }

    private int getTransportTypes(@NonNull NetworkCapabilities networkCapabilities) {
        // Transport types are internally packed as bits starting from bit 0. Casting to int works
        // fine since for now and the foreseeable future, there will be less than 32 transports.
        return (int) networkCapabilities.getTransportTypesInternal();
    }

    /** Inform the KeepaliveStatsTracker a keepalive has just started and is active. */
    public void onStartKeepalive(
            @NonNull Network network,
            int slot,
            @NonNull NetworkCapabilities nc,
            int intervalSeconds) {
        ensureRunningOnHandlerThread();

        final int keepaliveId = getKeepaliveId(network, slot);
        if (mKeepaliveStatsPerId.contains(keepaliveId)) {
            throw new IllegalArgumentException(
                    "Attempt to start keepalive stats on a known network, slot pair");
        }
        final long timeNow = mDependencies.getUptimeMillis();
        updateDurationsPerNumOfKeepalive(timeNow);

        mNumRegisteredKeepalive++;
        mNumActiveKeepalive++;

        final KeepaliveStats newKeepaliveStats =
                new KeepaliveStats(
                        getCarrierId(), getTransportTypes(nc), intervalSeconds, timeNow);

        mKeepaliveStatsPerId.put(keepaliveId, newKeepaliveStats);
    }

    /**
     * Inform the KeepaliveStatsTracker that the keepalive with the given network, slot pair has
     * updated its active state to keepaliveActive.
     *
     * @return the KeepaliveStats associated with the network, slot pair or null if it is unknown.
     */
    private @NonNull KeepaliveStats onKeepaliveActive(
            @NonNull Network network, int slot, boolean keepaliveActive) {
        final long timeNow = mDependencies.getUptimeMillis();
        return onKeepaliveActive(network, slot, keepaliveActive, timeNow);
    }

    /**
     * Inform the KeepaliveStatsTracker that the keepalive with the given network, slot pair has
     * updated its active state to keepaliveActive.
     *
     * @param network the network of the keepalive
     * @param slot the slot number of the keepalive
     * @param keepaliveActive the new active state of the keepalive
     * @param timeNow a timestamp obtained using Dependencies.getUptimeMillis
     * @return the KeepaliveStats associated with the network, slot pair or null if it is unknown.
     */
    private @NonNull KeepaliveStats onKeepaliveActive(
            @NonNull Network network, int slot, boolean keepaliveActive, long timeNow) {
        ensureRunningOnHandlerThread();

        final int keepaliveId = getKeepaliveId(network, slot);
        if (!mKeepaliveStatsPerId.contains(keepaliveId)) {
            throw new IllegalArgumentException(
                    "Attempt to set active keepalive on an unknown network, slot pair");
        }
        updateDurationsPerNumOfKeepalive(timeNow);

        final KeepaliveStats keepaliveStats = mKeepaliveStatsPerId.get(keepaliveId);
        if (keepaliveActive != keepaliveStats.isKeepaliveActive()) {
            mNumActiveKeepalive += keepaliveActive ? 1 : -1;
        }

        keepaliveStats.updateLifetimeStatsAndSetActive(timeNow, keepaliveActive);
        return keepaliveStats;
    }

    /** Inform the KeepaliveStatsTracker a keepalive has just been paused. */
    public void onPauseKeepalive(@NonNull Network network, int slot) {
        onKeepaliveActive(network, slot, /* keepaliveActive= */ false);
    }

    /** Inform the KeepaliveStatsTracker a keepalive has just been resumed. */
    public void onResumeKeepalive(@NonNull Network network, int slot) {
        onKeepaliveActive(network, slot, /* keepaliveActive= */ true);
    }

    /** Inform the KeepaliveStatsTracker a keepalive has just been stopped. */
    public void onStopKeepalive(@NonNull Network network, int slot) {
        final int keepaliveId = getKeepaliveId(network, slot);
        final long timeNow = mDependencies.getUptimeMillis();

        final KeepaliveStats keepaliveStats =
                onKeepaliveActive(network, slot, /* keepaliveActive= */ false, timeNow);

        mNumRegisteredKeepalive--;

        // add to the aggregate since it will be removed.
        addToAggregateKeepaliveLifetime(keepaliveStats, timeNow);
        // free up the slot.
        mKeepaliveStatsPerId.remove(keepaliveId);
    }

    /**
     * Updates and adds the lifetime metric of keepaliveStats to the aggregate.
     *
     * @param keepaliveStats the stats to add to the aggregate
     * @param timeNow a timestamp obtained using Dependencies.getUptimeMillis
     */
    private void addToAggregateKeepaliveLifetime(
            @NonNull KeepaliveStats keepaliveStats, long timeNow) {

        final KeepaliveStats.LifetimeStats lifetimeStats =
                keepaliveStats.getAndResetLifetimeStats(timeNow);

        final LifetimeKey key =
                new LifetimeKey(
                        keepaliveStats.carrierId,
                        keepaliveStats.transportTypes,
                        keepaliveStats.intervalMs);

        KeepaliveLifetimeForCarrier.Builder keepaliveLifetimeForCarrier =
                mAggregateKeepaliveLifetime.get(key);

        if (keepaliveLifetimeForCarrier == null) {
            keepaliveLifetimeForCarrier =
                    KeepaliveLifetimeForCarrier.newBuilder()
                            .setCarrierId(keepaliveStats.carrierId)
                            .setTransportTypes(keepaliveStats.transportTypes)
                            .setIntervalsMsec(keepaliveStats.intervalMs);
            mAggregateKeepaliveLifetime.put(key, keepaliveLifetimeForCarrier);
        }

        keepaliveLifetimeForCarrier.setLifetimeMsec(
                keepaliveLifetimeForCarrier.getLifetimeMsec() + lifetimeStats.lifetimeMs);
        keepaliveLifetimeForCarrier.setActiveLifetimeMsec(
                keepaliveLifetimeForCarrier.getActiveLifetimeMsec()
                        + lifetimeStats.activeLifetimeMs);
    }

    /**
     * Builds and returns DailykeepaliveInfoReported proto.
     *
     * @return the DailykeepaliveInfoReported proto that was built.
     */
    @VisibleForTesting
    public @NonNull DailykeepaliveInfoReported buildKeepaliveMetrics() {
        ensureRunningOnHandlerThread();
        final long timeNow = mDependencies.getUptimeMillis();
        return buildKeepaliveMetrics(timeNow);
    }

    /**
     * Updates the metrics to timeNow and builds and returns DailykeepaliveInfoReported proto.
     *
     * @param timeNow a timestamp obtained using Dependencies.getUptimeMillis
     */
    private @NonNull DailykeepaliveInfoReported buildKeepaliveMetrics(long timeNow) {
        updateDurationsPerNumOfKeepalive(timeNow);

        final DurationPerNumOfKeepalive.Builder durationPerNumOfKeepalive =
                DurationPerNumOfKeepalive.newBuilder();

        mDurationPerNumOfKeepalive.forEach(
                durationForNumOfKeepalive ->
                        durationPerNumOfKeepalive.addDurationForNumOfKeepalive(
                                durationForNumOfKeepalive));

        final KeepaliveLifetimePerCarrier.Builder keepaliveLifetimePerCarrier =
                KeepaliveLifetimePerCarrier.newBuilder();

        for (int i = 0; i < mKeepaliveStatsPerId.size(); i++) {
            final KeepaliveStats keepaliveStats = mKeepaliveStatsPerId.valueAt(i);
            addToAggregateKeepaliveLifetime(keepaliveStats, timeNow);
        }

        // Fill keepalive carrier stats to the proto
        mAggregateKeepaliveLifetime
                .values()
                .forEach(
                        keepaliveLifetimeForCarrier ->
                                keepaliveLifetimePerCarrier.addKeepaliveLifetimeForCarrier(
                                        keepaliveLifetimeForCarrier));

        final DailykeepaliveInfoReported.Builder dailyKeepaliveInfoReported =
                DailykeepaliveInfoReported.newBuilder();

        // TODO(b/273451360): fill all the other values and write to ConnectivityStatsLog.
        dailyKeepaliveInfoReported.setDurationPerNumOfKeepalive(durationPerNumOfKeepalive);
        dailyKeepaliveInfoReported.setKeepaliveLifetimePerCarrier(keepaliveLifetimePerCarrier);

        return dailyKeepaliveInfoReported.build();
    }

    /**
     * Builds and resets the stored metrics. Similar to buildKeepaliveMetrics but also resets the
     * metrics while maintaining the state of the keepalives.
     *
     * @return the DailykeepaliveInfoReported proto that was built.
     */
    public @NonNull DailykeepaliveInfoReported buildAndResetMetrics() {
        ensureRunningOnHandlerThread();
        final long timeNow = mDependencies.getUptimeMillis();

        final DailykeepaliveInfoReported metrics = buildKeepaliveMetrics(timeNow);

        mDurationPerNumOfKeepalive.clear();
        ensureDurationPerNumOfKeepaliveSize();

        mAggregateKeepaliveLifetime.clear();
        // Reset the stats for existing keepalives
        for (int i = 0; i < mKeepaliveStatsPerId.size(); i++) {
            mKeepaliveStatsPerId.valueAt(i).resetLifetimeStats(timeNow);
        }

        return metrics;
    }

    private void ensureRunningOnHandlerThread() {
        if (mConnectivityServiceHandler.getLooper().getThread() != Thread.currentThread()) {
            throw new IllegalStateException(
                    "Not running on handler thread: " + Thread.currentThread().getName());
        }
    }
}
