package com.qwenpaw.controller.service;

import com.qwenpaw.controller.config.QwenPawProperties;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class KubernetesServiceConcurrencyTest {

    @Test
    void skipsApiKeyCreationWhenDeploymentAppearsWhileWaitingForLease() {
        KubernetesClient client = mock(KubernetesClient.class);
        QwenPawProperties properties = new QwenPawProperties();
        PersonalApiKeyService personalApiKeyService = mock(PersonalApiKeyService.class);
        EllmEndpointResolver endpointResolver = mock(EllmEndpointResolver.class);
        UserProfileCacheService userProfileCacheService = mock(UserProfileCacheService.class);
        KubernetesLeaseLock provisioningLock = mock(KubernetesLeaseLock.class);
        KubernetesService service = spy(new KubernetesService(
                client,
                properties,
                personalApiKeyService,
                endpointResolver,
                userProfileCacheService,
                provisioningLock));
        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                .withName("qwenpaw-alice")
                .endMetadata()
                .build();

        doReturn(null, deployment).when(service).getDeployment("qwenpaw-alice");
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get())
                .when(provisioningLock)
                .withLock(eq("qwenpaw-alice"), any());

        assertThat(service.createDeployment("alice")).isEqualTo("qwenpaw-alice");

        verify(personalApiKeyService, never()).createPersonalApiKey(any(), any());
        verify(endpointResolver, never()).resolve();
        verify(userProfileCacheService, never()).cacheIfAbsent(any());
    }
}
