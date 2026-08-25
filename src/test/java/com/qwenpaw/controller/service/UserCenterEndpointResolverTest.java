package com.qwenpaw.controller.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserCenterEndpointResolverTest {

    private final EllmEndpointResolver ellmEndpointResolver = mock(EllmEndpointResolver.class);
    private final UserCenterEndpointResolver resolver =
            new UserCenterEndpointResolver(ellmEndpointResolver);

    @Test
    void resolvesUatUserCenterUrl() {
        when(ellmEndpointResolver.resolve())
                .thenReturn(new EllmEndpointResolver.EllmEndpoint("UAT", "unused"));

        assertThat(resolver.resolveQueryUserInfoUrl()).isEqualTo(
                "http://12.244.102.22/GUWP.GUWP-USERCENTER.V-1.0/api/1.0/userCenter/user/queryUserInfo");
    }

    @Test
    void resolvesPrdUserCenterUrl() {
        when(ellmEndpointResolver.resolve())
                .thenReturn(new EllmEndpointResolver.EllmEndpoint("PRD", "unused"));

        assertThat(resolver.resolveQueryUserInfoUrl()).isEqualTo(
                "http://goas.bocomm.com/GUWP.GUWP-USERCENTER.V-1.0/api/1.0/userCenter/user/queryUserInfo");
    }
}
