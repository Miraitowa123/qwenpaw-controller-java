package com.qwenpaw.controller.model;

import java.util.List;

/**
 * 用户 personal-api-key.json 列表接口响应。
 */
public class ListUserPersonalApiKeysResponse {

    /**
     * 用户 personal-api-key.json 明细。
     */
    private List<UserPersonalApiKeyResponse> users;

    /**
     * 总数。
     */
    private int total;

    /**
     * 创建列表响应。
     */
    public ListUserPersonalApiKeysResponse(List<UserPersonalApiKeyResponse> users) {
        this.users = users;
        this.total = users.size();
    }

    /**
     * 获取明细列表。
     */
    public List<UserPersonalApiKeyResponse> getUsers() {
        return users;
    }

    /**
     * 设置明细列表。
     */
    public void setUsers(List<UserPersonalApiKeyResponse> users) {
        this.users = users;
    }

    /**
     * 获取总数。
     */
    public int getTotal() {
        return total;
    }

    /**
     * 设置总数。
     */
    public void setTotal(int total) {
        this.total = total;
    }
}
