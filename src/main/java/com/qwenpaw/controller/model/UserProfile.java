package com.qwenpaw.controller.model;

/**
 * 管理台展示所需的精简用户信息；Redis 中只保存这两个非敏感展示字段。
 */
public record UserProfile(String userName, String loginName) {
}
