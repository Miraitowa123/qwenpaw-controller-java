package com.qwenpaw.controller.service;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 根据 qwenpaw-runtime-config 的 RUN_ENV 选择用户中心地址。
 */
@Component
public class UserCenterEndpointResolver {

    private static final String QUERY_USER_INFO_PATH =
            "/GUWP.GUWP-USERCENTER.V-1.0/api/1.0/userCenter/user/queryUserInfo";

    /**
     * SIT/UAT/UAT2/UATC 共用 UAT 用户中心，PRD 使用生产用户中心。
     */
    private static final Map<String, String> USER_CENTER_BASE_URLS = Map.of(
            "SIT", "http://12.244.102.22",
            "UAT", "http://12.244.102.22",
            "UAT2", "http://12.244.102.22",
            "UATC", "http://12.244.102.22",
            "PRD", "http://goas.bocomm.com");

    private final EllmEndpointResolver ellmEndpointResolver;

    public UserCenterEndpointResolver(EllmEndpointResolver ellmEndpointResolver) {
        this.ellmEndpointResolver = ellmEndpointResolver;
    }

    /**
     * 复用已有 RUN_ENV 解析逻辑，保证用户中心和个人 API Key 使用同一运行环境。
     */
    public String resolveQueryUserInfoUrl() {
        String runEnv = ellmEndpointResolver.resolve().runEnv();
        String baseUrl = USER_CENTER_BASE_URLS.get(runEnv);
        if (baseUrl == null) {
            throw new IllegalStateException(
                    "Unsupported RUN_ENV " + runEnv + " for user center, supported values are "
                            + USER_CENTER_BASE_URLS.keySet());
        }
        return baseUrl + QUERY_USER_INFO_PATH;
    }
}
