package com.qwenpaw.controller.service;

import com.qwenpaw.controller.config.QwenPawProperties;
import io.fabric8.kubernetes.api.model.coordination.v1.Lease;
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseBuilder;
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 使用 Kubernetes Lease 对同一用户的资源初始化做跨 Controller Pod 串行化。
 */
@Service
public class KubernetesLeaseLock {

    private static final Logger log = LoggerFactory.getLogger(KubernetesLeaseLock.class);
    private static final int MIN_LEASE_DURATION_SECONDS = 30;
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(200);

    private final KubernetesClient client;
    private final String namespace;
    private final Duration leaseDuration;
    private final Duration waitTimeout;
    private final Duration pollInterval;
    private final ScheduledExecutorService renewalExecutor;

    @Autowired
    public KubernetesLeaseLock(KubernetesClient client, QwenPawProperties properties) {
        this(
                client,
                properties.getK8sNamespace(),
                Duration.ofSeconds(Math.max(
                        MIN_LEASE_DURATION_SECONDS,
                        properties.getPersonalApiKeyTimeoutSeconds() + 15L)),
                Duration.ofSeconds(Math.max(
                        MIN_LEASE_DURATION_SECONDS + 5L,
                        properties.getPersonalApiKeyTimeoutSeconds() + 20L)),
                DEFAULT_POLL_INTERVAL);
    }

    KubernetesLeaseLock(KubernetesClient client,
                        String namespace,
                        Duration leaseDuration,
                        Duration waitTimeout,
                        Duration pollInterval) {
        this.client = client;
        this.namespace = namespace;
        this.leaseDuration = leaseDuration;
        this.waitTimeout = waitTimeout;
        this.pollInterval = pollInterval;
        this.renewalExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "qwenpaw-provisioning-lease-renewer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 获取指定名称的 Lease，执行操作后仅释放自己持有的 Lease。
     */
    public <T> T withLock(String leaseName, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        String holderIdentity = UUID.randomUUID().toString();
        Lease acquired = acquire(leaseName, holderIdentity);
        long renewalPeriodMillis = Math.max(1_000L, leaseDuration.toMillis() / 3L);
        ScheduledFuture<?> renewal = renewalExecutor.scheduleAtFixedRate(
                () -> renewSafely(leaseName, holderIdentity),
                renewalPeriodMillis,
                renewalPeriodMillis,
                TimeUnit.MILLISECONDS);
        try {
            return action.get();
        } finally {
            renewal.cancel(false);
            releaseSafely(acquired, holderIdentity);
        }
    }

    private Lease acquire(String leaseName, String holderIdentity) {
        long deadlineNanos = System.nanoTime() + waitTimeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            Lease desired = newLease(leaseName, holderIdentity, now);
            Lease created = tryCreate(desired);
            if (created != null) {
                log.debug("Acquired provisioning lease {} as {}", leaseName, holderIdentity);
                return created;
            }

            Lease current = get(leaseName);
            if (current != null && isExpired(current, now)) {
                Lease replacement = expiredLeaseReplacement(current, holderIdentity, now);
                Lease replaced = tryReplace(current, replacement);
                if (replaced != null) {
                    log.warn("Took over expired provisioning lease {} as {}", leaseName, holderIdentity);
                    return replaced;
                }
            }
            pause();
        }
        throw new IllegalStateException("Timed out waiting for provisioning lease " + leaseName);
    }

    private Lease newLease(String leaseName, String holderIdentity, ZonedDateTime now) {
        return new LeaseBuilder()
                .withNewMetadata()
                .withName(leaseName)
                .withNamespace(namespace)
                .addToLabels("app", "qwenpaw-controller")
                .addToLabels("qwenpaw.io/purpose", "user-provisioning")
                .endMetadata()
                .withNewSpec()
                .withHolderIdentity(holderIdentity)
                .withAcquireTime(now)
                .withRenewTime(now)
                .withLeaseDurationSeconds(Math.toIntExact(leaseDuration.toSeconds()))
                .withLeaseTransitions(0)
                .endSpec()
                .build();
    }

    private Lease expiredLeaseReplacement(Lease current, String holderIdentity, ZonedDateTime now) {
        Integer transitions = current.getSpec() == null ? null : current.getSpec().getLeaseTransitions();
        return new LeaseBuilder(current)
                .editOrNewSpec()
                .withHolderIdentity(holderIdentity)
                .withAcquireTime(now)
                .withRenewTime(now)
                .withLeaseDurationSeconds(Math.toIntExact(leaseDuration.toSeconds()))
                .withLeaseTransitions(transitions == null ? 1 : transitions + 1)
                .endSpec()
                .build();
    }

    private boolean isExpired(Lease lease, ZonedDateTime now) {
        LeaseSpec spec = lease.getSpec();
        if (spec == null || spec.getLeaseDurationSeconds() == null) {
            return true;
        }
        ZonedDateTime lastHeartbeat = spec.getRenewTime() != null ? spec.getRenewTime() : spec.getAcquireTime();
        return lastHeartbeat == null
                || !lastHeartbeat.plusSeconds(spec.getLeaseDurationSeconds()).isAfter(now);
    }

    private void release(Lease acquired, String holderIdentity) {
        String leaseName = acquired.getMetadata().getName();
        Lease current = get(leaseName);
        if (current == null
                || current.getSpec() == null
                || !holderIdentity.equals(current.getSpec().getHolderIdentity())) {
            return;
        }
        tryDelete(current);
        log.debug("Released provisioning lease {} held by {}", leaseName, holderIdentity);
    }

    private void releaseSafely(Lease acquired, String holderIdentity) {
        try {
            release(acquired, holderIdentity);
        } catch (RuntimeException e) {
            // 不能让清理 Lease 的临时故障覆盖已经完成的业务结果；Lease 到期后仍可自动接管。
            log.warn("Failed to release provisioning lease {}; it will expire automatically",
                    acquired.getMetadata().getName(), e);
        }
    }

    /**
     * 操作执行期间定期续租；Controller Pod 异常退出后续租停止，其他副本可在租期结束后接管。
     */
    private void renewSafely(String leaseName, String holderIdentity) {
        try {
            Lease current = get(leaseName);
            if (current == null
                    || current.getSpec() == null
                    || !holderIdentity.equals(current.getSpec().getHolderIdentity())) {
                log.warn("Cannot renew provisioning lease {} because ownership changed", leaseName);
                return;
            }
            Lease replacement = new LeaseBuilder(current)
                    .editOrNewSpec()
                    .withRenewTime(ZonedDateTime.now(ZoneOffset.UTC))
                    .endSpec()
                    .build();
            if (tryReplace(current, replacement) == null) {
                log.warn("Provisioning lease {} renewal conflicted; will retry", leaseName);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to renew provisioning lease {}; will retry", leaseName, e);
        }
    }

    /**
     * 关闭 Lease 续租线程，由 Spring 容器销毁 Bean 时调用。
     */
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        renewalExecutor.shutdownNow();
    }

    Lease tryCreate(Lease lease) {
        try {
            return client.leases()
                    .inNamespace(namespace)
                    .resource(lease)
                    .create();
        } catch (KubernetesClientException e) {
            if (e.getCode() == 409) {
                return null;
            }
            throw e;
        }
    }

    Lease get(String leaseName) {
        return client.leases()
                .inNamespace(namespace)
                .withName(leaseName)
                .get();
    }

    Lease tryReplace(Lease current, Lease replacement) {
        try {
            return client.leases()
                    .inNamespace(namespace)
                    .resource(replacement)
                    .lockResourceVersion(current.getMetadata().getResourceVersion())
                    .update();
        } catch (KubernetesClientException e) {
            if (e.getCode() == 404 || e.getCode() == 409) {
                return null;
            }
            throw e;
        }
    }

    void tryDelete(Lease lease) {
        try {
            client.leases()
                    .inNamespace(namespace)
                    .resource(lease)
                    .lockResourceVersion(lease.getMetadata().getResourceVersion())
                    .delete();
        } catch (KubernetesClientException e) {
            if (e.getCode() != 404 && e.getCode() != 409) {
                throw e;
            }
        }
    }

    private void pause() {
        try {
            Thread.sleep(pollInterval.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for provisioning lease", e);
        }
    }
}
