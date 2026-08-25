package com.qwenpaw.controller.service;

import com.qwenpaw.controller.config.QwenPawProperties;
import com.qwenpaw.controller.model.PodStatus;
import com.qwenpaw.controller.model.UserPodMapping;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PodManagerTest {

    private final KubernetesService kubernetesService = mock(KubernetesService.class);
    private final QwenPawProperties properties = mock(QwenPawProperties.class);
    private final PodManager podManager = new PodManager(kubernetesService, properties);

    @BeforeEach
    void setUp() {
        when(properties.getQwenpawAppLabel()).thenReturn("qwenpaw");
        when(properties.getBasePathPrefix()).thenReturn("/bocompaw");
    }

    @Test
    void createsResourcesWithoutWaitingForPodReady() {
        Deployment deployment = deployment("qwenpaw-alice");
        when(kubernetesService.getDeployment("qwenpaw-alice")).thenReturn(deployment);
        when(kubernetesService.getDeploymentStatus(deployment)).thenReturn(PodStatus.CREATING);
        when(kubernetesService.listPodsByLabel(Map.of("app", "qwenpaw", "user", "alice")))
                .thenReturn(List.of());

        UserPodMapping mapping = podManager.ensureUserPod("alice").orElseThrow();

        assertThat(mapping.getStatus()).isEqualTo(PodStatus.CREATING);
        verify(kubernetesService).createDeployment("alice");
        verify(kubernetesService).createService("alice");
        verify(kubernetesService).createHttpRoute("alice");
        verify(kubernetesService, never()).waitForPodReady("qwenpaw-alice");
    }

    @Test
    void reportsCreatingFromDeploymentWhenPodDoesNotExist() {
        Deployment deployment = deployment("qwenpaw-alice");
        when(kubernetesService.getDeployment("qwenpaw-alice")).thenReturn(deployment);
        when(kubernetesService.getDeploymentStatus(deployment)).thenReturn(PodStatus.CREATING);
        when(kubernetesService.listPodsByLabel(Map.of("app", "qwenpaw", "user", "alice")))
                .thenReturn(List.of());

        UserPodMapping mapping = podManager.getUserPod("alice").orElseThrow();

        assertThat(mapping.getStatus()).isEqualTo(PodStatus.CREATING);
        assertThat(mapping.getDeploymentName()).isEqualTo("qwenpaw-alice");
    }

    @Test
    void paginatesPodsInStableCreationTimeOrderAndOnlyMapsRequestedPage() {
        Pod alice = pod("alice", "qwenpaw-alice-abc", "2026-08-18T06:00:00Z");
        Pod bob = pod("bob", "qwenpaw-bob-abc", "2026-08-20T06:00:00Z");
        Pod charlie = pod("charlie", "qwenpaw-charlie-abc", "2026-08-19T06:00:00Z");
        when(kubernetesService.listPodsByLabel(Map.of("app", "qwenpaw")))
                .thenReturn(List.of(alice, bob, charlie));
        when(kubernetesService.getPodStatus(charlie)).thenReturn(PodStatus.RUNNING);

        PodManager.UserPodPage page = podManager.listUserPodsPage(2, 1);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.pageSize()).isEqualTo(1);
        assertThat(page.users()).extracting(UserPodMapping::getUserId).containsExactly("charlie");
        verify(kubernetesService).getPodStatus(charlie);
        verify(kubernetesService, never()).getPodStatus(alice);
        verify(kubernetesService, never()).getPodStatus(bob);
    }

    @Test
    void returnsEmptyPageWhenRequestedPageExceedsTotal() {
        Pod alice = pod("alice", "qwenpaw-alice-abc", "2026-08-18T06:00:00Z");
        when(kubernetesService.listPodsByLabel(Map.of("app", "qwenpaw")))
                .thenReturn(List.of(alice));

        PodManager.UserPodPage page = podManager.listUserPodsPage(3, 20);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.users()).isEmpty();
        verify(kubernetesService, never()).getPodStatus(alice);
    }

    private Deployment deployment(String name) {
        return new DeploymentBuilder()
                .withNewMetadata()
                .withName(name)
                .endMetadata()
                .build();
    }

    private Pod pod(String userId, String name, String creationTimestamp) {
        return new PodBuilder()
                .withNewMetadata()
                .withName(name)
                .withCreationTimestamp(creationTimestamp)
                .addToLabels("app", "qwenpaw")
                .addToLabels("user", userId)
                .endMetadata()
                .build();
    }
}
