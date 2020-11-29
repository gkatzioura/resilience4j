/*
 *
 *  Copyright 2020 Emmanouil Gkatziouras
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */
package io.github.resilience4j.ratelimiter.internal;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RefillRateLimiterConfig;
import io.github.resilience4j.ratelimiter.event.RateLimiterOnFailureEvent;
import io.github.resilience4j.ratelimiter.event.RateLimiterOnSuccessEvent;
import io.vavr.collection.HashMap;
import io.vavr.collection.Map;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static java.lang.Long.min;
import static java.lang.System.nanoTime;
import static java.lang.Thread.currentThread;
import static java.util.concurrent.locks.LockSupport.parkNanos;

/**
 * {@link RefillRateLimiter} has a max capacity of permits refilled periodically.
 * <p>Each period has duration of {@link RefillRateLimiterConfig#limitRefreshPeriod} in nanoseconds.
 * <p>By contract the initial {@link RefillRateLimiter.State#activePermissions} are set
 * to be the same with{@link RefillRateLimiterConfig#permitCapacity}
 * <p>A ratio of permit per nanoseconds is calculated using
 * {@link RefillRateLimiterConfig#limitRefreshPeriod} and {@link RefillRateLimiterConfig#limitForPeriod}.
 * On a permit request the permits that should have been replenished are calculated based on the nanos passed and the ratio of nanos per permit.
 * For the {@link RefillRateLimiter} callers it is looks like a token bucket supporting bursts and a gradual refill,
 * under the hood there is some optimisations that will skip this refresh if {@link RefillRateLimiter} is not used actively.
 * <p>All {@link RefillRateLimiter} updates are atomic and state is encapsulated in {@link
 * AtomicReference} to {@link RefillRateLimiter.State}
 */
public class RefillRateLimiter extends BaseAtomicLimiter<RefillRateLimiterConfig, RefillRateLimiter.State> implements RateLimiter {

    private static final long nanoTimeStart = nanoTime();

    public RefillRateLimiter(String name, RefillRateLimiterConfig rateLimiterConfig) {
        this(name, rateLimiterConfig, HashMap.empty());
    }

    public RefillRateLimiter(String name, RefillRateLimiterConfig rateLimiterConfig,
                             Map<String, String> tags) {
        super(name, new AtomicReference<>(new State(
            rateLimiterConfig, rateLimiterConfig.getInitialPermits(), 0, nanoTime() - nanoTimeStart
        )),tags);
    }

    /**
     * Calculate the nanos needed for one permission
     * @param rateLimiterConfig
     */
    private long calculateNanosPerPermission(RefillRateLimiterConfig rateLimiterConfig) {
        long permissionsPeriodInNanos = rateLimiterConfig.getLimitRefreshPeriod().toNanos();
        int permissionsInPeriod = rateLimiterConfig.getLimitForPeriod();

        return permissionsPeriodInNanos/permissionsInPeriod;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean acquirePermission(int permits) {
        long timeoutInNanos = state().get().getConfig().getTimeoutDuration().toNanos();
        State modifiedState = updateStateWithBackOff(permits, timeoutInNanos);
        boolean result = waitForPermissionIfNecessary(timeoutInNanos, modifiedState.getNanosToWait());
        publishRateLimiterEvent(result, permits);
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long reservePermission(final int permits) {
        long timeoutInNanos = state().get().getConfig().getTimeoutDuration().toNanos();
        State modifiedState = updateStateWithBackOff(permits, timeoutInNanos);

        boolean canAcquireImmediately = modifiedState.getNanosToWait()<= 0;
        if (canAcquireImmediately) {
            publishRateLimiterEvent(true, permits);
            return 0;
        }

        boolean canAcquireInTime = timeoutInNanos >= modifiedState.getNanosToWait();
        if (canAcquireInTime) {
            publishRateLimiterEvent(true, permits);
            return modifiedState.getNanosToWait();
        }

        publishRateLimiterEvent(false, permits);
        return -1;
    }

    /**
     * Atomically updates the current {@link RefillRateLimiter.State} with the results of applying the {@link
     * RefillRateLimiter#calculateNextState}, returning the updated {@link RefillRateLimiter.State}. It differs from
     * {@link AtomicReference#updateAndGet(UnaryOperator)} by constant back off. It means that after
     * one try to {@link AtomicReference#compareAndSet(Object, Object)} this method will wait for a
     * while before try one more time. This technique was originally described in this
     * <a href="https://arxiv.org/abs/1305.5800"> paper</a>
     * and showed great results with {@link RefillRateLimiter} in benchmark tests.
     *
     * @param timeoutInNanos a side-effect-free function
     * @return the updated value
     */
    protected State updateStateWithBackOff(final int permits, final long timeoutInNanos) {
        State prev;
        State next;
        do {
            prev = state().get();
            next = calculateNextState(permits, timeoutInNanos, prev);
        } while (!compareAndSet(prev, next));
        return next;
    }

    /**
     * Atomically sets the value to the given updated value if the current value {@code ==} the
     * expected value. It differs from {@link AtomicReference#updateAndGet(UnaryOperator)} by
     * constant back off. It means that after one try to {@link AtomicReference#compareAndSet(Object,
     * Object)} this method will wait for a while before try one more time. This technique was
     * originally described in this
     * <a href="https://arxiv.org/abs/1305.5800"> paper</a>
     * and showed great results with {@link AtomicRateLimiter} in benchmark tests.
     *
     * @param current the expected value
     * @param next    the new value
     * @return {@code true} if successful. False return indicates that the actual value was not
     * equal to the expected value.
     */
    protected boolean compareAndSet(final State current, final State next) {
        if (state().compareAndSet(current, next)) {
            return true;
        }
        parkNanos(1); // back-off
        return false;
    }


    /**
     * A side-effect-free function that can calculate next {@link State} from current. It determines
     * time duration that you should wait for the given number of permits and reserves it for you,
     * if you'll be able to wait long enough.
     *
     * @param permits        number of permits
     * @param timeoutInNanos max time that caller can wait for permission in nanoseconds
     * @param activeState    current state of {@link RefillRateLimiter}
     * @return next {@link State}
     */
    protected State calculateNextState(final int permits, final long timeoutInNanos,
                                     final State activeState) {
        long nanosSinceLastUpdate = nanosSinceLastUpdate(activeState);
        int nexPermissions = calculateNextPermissions(activeState, nanosSinceLastUpdate);

        long nextNanosToWait = nanosToWaitForPermission(
            permits, activeState.getConfig().getNanosPerPermit(), nexPermissions);

        State nextState = reservePermissions(activeState, permits, timeoutInNanos,
            nexPermissions, nextNanosToWait);
        return nextState;
    }

    private int calculateNextPermissions(State activeState, long nanosSinceLastUpdate) {
        long accumulatedPermissions = accumulatedPermissions(activeState, nanosSinceLastUpdate);
        return (int) min(activeState.getConfig().getPermitCapacity(), accumulatedPermissions);
    }

    private long accumulatedPermissions(State activeState, long nanosSinceLastUpdate) {
        int currentPermissions = activeState.activePermissions;
        long permissionsBatches = calculateBatches(activeState.getConfig().getNanosPerPermit(), nanosSinceLastUpdate);
        return permissionsBatches + currentPermissions;
    }

    private long nanosSinceLastUpdate(State activeState) {
        return nanoTime() - activeState.updatedAt;
    }

    /**
     * Calculate the batches, should be inlined
     * @param nanosPerPermission
     * @param nanosSinceLastUpdate
     * @return
     */
    private long calculateBatches(long nanosPerPermission, long nanosSinceLastUpdate) {
        if(nanosSinceLastUpdate<=0l) {
            return 0l;
        }
        return nanosSinceLastUpdate / nanosPerPermission;
    }

    /**
     * Calculates time to wait for the required permits of permissions to get accumulated
     *
     * @param permits              permits of required permissions
     * @param nanosPerPermission   nanos needed for one permission to be released
     * @param availablePermissions currently available permissions, can be negative if some
     *                             permissions have been reserved
     */
    private long nanosToWaitForPermission(final int permits, final long nanosPerPermission,
                                          final long availablePermissions) {
        if (availablePermissions >= permits) {
            return 0L;
        }

        long permissionsWanted = permits - availablePermissions;
        long nanosToWait = permissionsWanted*nanosPerPermission;
        return nanosToWait;
    }

    /**
     * Determines whether caller can acquire permission before timeout or not and then creates
     * corresponding {@link State}. Reserves permissions only if caller can successfully wait for
     * permission.
     *
     * @param state
     * @param permits        permits of permissions
     * @param timeoutInNanos max time that caller can wait for permission in nanoseconds
     * @param permissions    permissions for new {@link State}
     * @param nanosToWait    nanoseconds to wait for the next permission
     * @return new {@link State} with possibly reserved permissions and time to wait
     */
    private State reservePermissions(final State state, final int permits,
                                     final long timeoutInNanos,
                                     final int permissions, final long nanosToWait) {
        boolean canAcquireInTime = timeoutInNanos >= nanosToWait;
        int permissionsWithReservation = permissions;
        if (canAcquireInTime) {
            permissionsWithReservation -= permits;
        }

        return new State(state.getConfig(), permissionsWithReservation, nanosToWait, nanoTime());
    }


    /**
     * If nanosToWait is bigger than 0 it tries to park {@link Thread} for nanosToWait but not
     * longer then timeoutInNanos.
     *
     * @param timeoutInNanos max time that caller can wait
     * @param nanosToWait    nanoseconds caller need to wait
     * @return true if caller was able to wait for nanosToWait without {@link Thread#interrupt} and
     * not exceed timeout
     */
    protected boolean waitForPermissionIfNecessary(final long timeoutInNanos,
                                                 final long nanosToWait) {
        boolean canAcquireImmediately = nanosToWait <= 0;
        boolean canAcquireInTime = timeoutInNanos >= nanosToWait;

        if (canAcquireImmediately) {
            return true;
        }
        if (canAcquireInTime) {
            return waitForPermission(nanosToWait);
        }
        waitForPermission(timeoutInNanos);
        return false;
    }

    /**
     * Parks {@link Thread} for nanosToWait.
     * <p>If the current thread is {@linkplain Thread#interrupted}
     * while waiting for a permit then it won't throw {@linkplain InterruptedException}, but its
     * interrupt status will be set.
     *
     * @param nanosToWait nanoseconds caller need to wait
     * @return true if caller was not {@link Thread#interrupted} while waiting
     */
    private boolean waitForPermission(final long nanosToWait) {
        waitingThreads().incrementAndGet();
        long deadline = currentNanoTime() + nanosToWait;
        boolean wasInterrupted = false;
        while (currentNanoTime() < deadline && !wasInterrupted) {
            long sleepBlockDuration = deadline - currentNanoTime();
            parkNanos(sleepBlockDuration);
            wasInterrupted = Thread.interrupted();
        }
        waitingThreads().decrementAndGet();
        if (wasInterrupted) {
            currentThread().interrupt();
        }
        return !wasInterrupted;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RefillRateLimiterConfig getRateLimiterConfig() {
        return state().get().getConfig();
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public Metrics getMetrics() {
        return new RefillRateLimiterMetrics();
    }



    /**
     * Get the enhanced Metrics with some implementation specific details.
     *
     * @return the detailed metrics
     */
    public RefillRateLimiterMetrics getDetailedMetrics() {
        return new RefillRateLimiterMetrics();
    }


    /**
     * <p>{@link RefillRateLimiter.State} represents immutable state of {@link RefillRateLimiter}
     * where:
     * <ul>
     * <li>activePermissions - count of available permissions after
     * the last {@link RefillRateLimiter#acquirePermission()} call.
     * Can be negative if some permissions where reserved.</li>
     * <p>
     * <li>nanosToWait - count of nanoseconds to wait for permission for
     * the last {@link RefillRateLimiter#acquirePermission()} call.</li>
     * <p>
     * <li>updatedAt - the last time the state was updated.</li>
     * </ul>
     */
    static class State extends BaseState<RefillRateLimiterConfig> {

        private final int activePermissions;
        private final long updatedAt;

        public State(RefillRateLimiterConfig config, int activePermissions, long nanosToWait, long updatedAt) {
            super(config, activePermissions, nanosToWait);
            this.activePermissions = activePermissions;
            this.updatedAt = updatedAt;
        }

        @Override
        State withConfig(RefillRateLimiterConfig config) {
            return new State(config, activePermissions, getNanosToWait(), updatedAt);
        }

    }


    /**
     * Enhanced {@link Metrics} with some implementation specific details
     */
    public class RefillRateLimiterMetrics implements Metrics {

        private RefillRateLimiterMetrics() {
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int getNumberOfWaitingThreads() {
            return waitingThreads().get();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int getAvailablePermissions() {
            State currentState = state().get();
            State estimatedState = calculateNextState(1, -1, currentState);
            return estimatedState.activePermissions;
        }

        /**
         * @return estimated time duration in nanos to wait for the next permission
         */
        public long getNanosToWait() {
            State currentState = state().get();
            State estimatedState = calculateNextState(1, -1, currentState);
            return estimatedState.getNanosToWait();
        }

    }
}
