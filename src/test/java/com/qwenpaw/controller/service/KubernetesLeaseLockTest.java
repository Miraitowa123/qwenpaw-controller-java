package com.qwenpaw.controller.service;

import io.fabric8.kubernetes.api.model.coordination.v1.Lease;
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class KubernetesLeaseLockTest {

    private final List<KubernetesLeaseLock> locks = new ArrayList<>();

    @AfterEach
    void shutDownLocks() {
        locks.forEach(KubernetesLeaseLock::shutdown);
    }

    @Test
    void serializesSameUserAcrossControllerInstances() throws Exception {
        LeaseStore store = new LeaseStore();
        KubernetesLeaseLock firstLock = newLock(store);
        KubernetesLeaseLock secondLock = newLock(store);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        try {
            Future<String> first = executor.submit(() -> firstLock.withLock("qwenpaw-alice", () -> {
                enter(active, maxActive);
                firstEntered.countDown();
                await(releaseFirst);
                active.decrementAndGet();
                return "first";
            }));
            assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();

            Future<String> second = executor.submit(() -> {
                secondAttempted.countDown();
                return secondLock.withLock("qwenpaw-alice", () -> {
                    enter(active, maxActive);
                    secondEntered.countDown();
                    active.decrementAndGet();
                    return "second";
                });
            });

            assertThat(secondAttempted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(secondEntered.await(100, TimeUnit.MILLISECONDS)).isFalse();
            releaseFirst.countDown();

            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(second.get(1, TimeUnit.SECONDS)).isEqualTo("second");
            assertThat(maxActive).hasValue(1);
            assertThat(store.current.get()).isNull();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private KubernetesLeaseLock newLock(LeaseStore store) {
        KubernetesLeaseLock lock = new InMemoryKubernetesLeaseLock(store);
        locks.add(lock);
        return lock;
    }

    private static void enter(AtomicInteger active, AtomicInteger maxActive) {
        int current = active.incrementAndGet();
        maxActive.accumulateAndGet(current, Math::max);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static final class InMemoryKubernetesLeaseLock extends KubernetesLeaseLock {

        private final LeaseStore store;

        private InMemoryKubernetesLeaseLock(LeaseStore store) {
            super(null, "ai", Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofMillis(5));
            this.store = store;
        }

        @Override
        Lease tryCreate(Lease lease) {
            return store.tryCreate(lease);
        }

        @Override
        Lease get(String leaseName) {
            return store.get(leaseName);
        }

        @Override
        Lease tryReplace(Lease current, Lease replacement) {
            return store.tryReplace(current, replacement);
        }

        @Override
        void tryDelete(Lease lease) {
            store.tryDelete(lease);
        }
    }

    private static final class LeaseStore {

        private final AtomicReference<Lease> current = new AtomicReference<>();
        private final AtomicInteger resourceVersion = new AtomicInteger();

        synchronized Lease tryCreate(Lease lease) {
            if (current.get() != null) {
                return null;
            }
            Lease stored = withNextResourceVersion(lease);
            current.set(stored);
            return stored;
        }

        synchronized Lease get(String leaseName) {
            Lease lease = current.get();
            if (lease == null || !leaseName.equals(lease.getMetadata().getName())) {
                return null;
            }
            return new LeaseBuilder(lease).build();
        }

        synchronized Lease tryReplace(Lease expected, Lease replacement) {
            Lease lease = current.get();
            if (lease == null || !sameResourceVersion(lease, expected)) {
                return null;
            }
            Lease stored = withNextResourceVersion(replacement);
            current.set(stored);
            return stored;
        }

        synchronized void tryDelete(Lease expected) {
            Lease lease = current.get();
            if (lease != null && sameResourceVersion(lease, expected)) {
                current.set(null);
            }
        }

        private Lease withNextResourceVersion(Lease lease) {
            return new LeaseBuilder(lease)
                    .editMetadata()
                    .withResourceVersion(Integer.toString(resourceVersion.incrementAndGet()))
                    .endMetadata()
                    .build();
        }

        private boolean sameResourceVersion(Lease left, Lease right) {
            return left.getMetadata().getResourceVersion()
                    .equals(right.getMetadata().getResourceVersion());
        }
    }
}
