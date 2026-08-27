package com.qwenpaw.controller.web;

import com.qwenpaw.controller.config.QwenPawProperties;
import com.qwenpaw.controller.model.PodStatus;
import com.qwenpaw.controller.model.ListUserPodsResponse;
import com.qwenpaw.controller.model.UserPodMapping;
import com.qwenpaw.controller.model.UserPodResponse;
import com.qwenpaw.controller.model.UserProfile;
import com.qwenpaw.controller.service.KubernetesService;
import com.qwenpaw.controller.service.AgentHeartbeatService;
import com.qwenpaw.controller.service.PersonalApiKeyFileService;
import com.qwenpaw.controller.service.PersonalDataSyncService;
import com.qwenpaw.controller.service.PodManager;
import com.qwenpaw.controller.service.SkillDownloadService;
import com.qwenpaw.controller.service.UserProfileCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;

class UserPodControllerTest {

    private final PodManager podManager = mock(PodManager.class);
    private final AgentHeartbeatService agentHeartbeatService = mock(AgentHeartbeatService.class);
    private final UserProfileCacheService userProfileCacheService = mock(UserProfileCacheService.class);
    private final UserPodController controller = new UserPodController(
            podManager,
            mock(KubernetesService.class),
            mock(SkillDownloadService.class),
            mock(PersonalApiKeyFileService.class),
            mock(PersonalDataSyncService.class),
            mock(QwenPawProperties.class),
            agentHeartbeatService,
            userProfileCacheService);

    @BeforeEach
    void defaultEmptyUserProfileCache() {
        when(userProfileCacheService.getCachedProfiles(anyList())).thenReturn(Map.of());
    }

    @Test
    void returnsAcceptedWhilePodIsBeingCreated() {
        when(podManager.ensureUserPod("alice")).thenReturn(Optional.of(mapping(PodStatus.CREATING)));

        ResponseEntity<UserPodResponse> response = controller.createOrGetUserPod("Alice");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(PodStatus.CREATING);
        assertThat(response.getBody().getMessage()).isEqualTo("Pod is starting");
    }

    @Test
    void returnsOkWhenPodIsRunning() {
        when(podManager.ensureUserPod("alice")).thenReturn(Optional.of(mapping(PodStatus.RUNNING)));

        ResponseEntity<UserPodResponse> response = controller.createOrGetUserPod("alice");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(PodStatus.RUNNING);
        assertThat(response.getBody().getMessage()).isEqualTo("Pod is ready");
    }

    @Test
    void returnsAcceptedWhilePodIsTerminating() {
        when(podManager.ensureUserPod("alice")).thenReturn(Optional.of(mapping(PodStatus.TERMINATING)));

        ResponseEntity<UserPodResponse> response = controller.createOrGetUserPod("alice");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(PodStatus.TERMINATING);
        assertThat(response.getBody().getMessage()).isEqualTo("Pod is starting");
    }

    @Test
    void returnsServiceUnavailableWhenPodHasFailed() {
        when(podManager.ensureUserPod("alice")).thenReturn(Optional.of(mapping(PodStatus.FAILED)));

        ResponseEntity<UserPodResponse> response = controller.createOrGetUserPod("alice");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(PodStatus.FAILED);
    }

    @Test
    void includesRedisHeartbeatInPodList() {
        OffsetDateTime lastAccess = OffsetDateTime.parse("2026-08-18T06:00:00Z");
        when(podManager.listUserPods()).thenReturn(List.of(mapping(PodStatus.RUNNING)));
        when(agentHeartbeatService.getStatus("alice"))
                .thenReturn(new AgentHeartbeatService.HeartbeatStatus(lastAccess, 259_199L));

        UserPodResponse response = controller.listUserPods().getUsers().get(0);

        assertThat(response.getLastAccess()).isEqualTo(lastAccess);
        assertThat(response.getHeartbeatTtlSeconds()).isEqualTo(259_199L);
    }

    @Test
    void includesCachedUserProfileInPodList() {
        when(podManager.listUserPods()).thenReturn(List.of(mapping(PodStatus.RUNNING)));
        when(userProfileCacheService.getCachedProfiles(List.of("alice")))
                .thenReturn(Map.of("alice", new UserProfile("李志城", "lizc")));
        when(agentHeartbeatService.getStatus("alice"))
                .thenReturn(AgentHeartbeatService.HeartbeatStatus.empty());

        UserPodResponse response = controller.listUserPods().getUsers().get(0);

        assertThat(response.getUserName()).isEqualTo("李志城");
        assertThat(response.getLoginName()).isEqualTo("lizc");
    }

    @Test
    void returnsPaginationMetadataAndOnlyLoadsHeartbeatForCurrentPage() {
        UserPodMapping alice = mapping(PodStatus.RUNNING);
        when(podManager.listUserPodsPage(2, 10))
                .thenReturn(new PodManager.UserPodPage(List.of(alice), 25, 2, 10));
        when(agentHeartbeatService.getStatus("alice"))
                .thenReturn(AgentHeartbeatService.HeartbeatStatus.empty());

        ListUserPodsResponse response = controller.listUserPods(2, 10);

        assertThat(response.getUsers()).hasSize(1);
        assertThat(response.getTotal()).isEqualTo(25);
        assertThat(response.getPage()).isEqualTo(2);
        assertThat(response.getPageSize()).isEqualTo(10);
        assertThat(response.getTotalPages()).isEqualTo(3);
        verify(agentHeartbeatService).getStatus("alice");
        verify(podManager, never()).listUserPods();
    }

    @Test
    void searchesPaginatedPodsByNormalizedUserId() {
        UserPodMapping alice = mapping(PodStatus.RUNNING);
        when(podManager.listUserPodsPage("alice", 1, 20))
                .thenReturn(new PodManager.UserPodPage(List.of(alice), 1, 1, 20));
        when(agentHeartbeatService.getStatus("alice"))
                .thenReturn(AgentHeartbeatService.HeartbeatStatus.empty());

        ListUserPodsResponse response = controller.listUserPods(" Alice ", 1, 20);

        assertThat(response.getUsers()).hasSize(1);
        assertThat(response.getTotal()).isEqualTo(1);
        verify(podManager).listUserPodsPage("alice", 1, 20);
        verify(podManager, never()).listUserPodsPage(1, 20);
    }

    @Test
    void rejectsInvalidPaginationParameters() {
        assertThatThrownBy(() -> controller.listUserPods(0, 20))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("page must be greater than or equal to 1");
        assertThatThrownBy(() -> controller.listUserPods(1, 101))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("page_size must be between 1 and 100");
    }

    private UserPodMapping mapping(PodStatus status) {
        UserPodMapping mapping = new UserPodMapping();
        mapping.setUserId("alice");
        mapping.setPodName(status == PodStatus.RUNNING ? "qwenpaw-alice-abc" : "");
        mapping.setDeploymentName("qwenpaw-alice");
        mapping.setServiceName("qwenpaw-alice");
        mapping.setHttprouteName("qwenpaw-alice");
        mapping.setPathPrefix("/bocompaw/alice");
        mapping.setStatus(status);
        return mapping;
    }
}
