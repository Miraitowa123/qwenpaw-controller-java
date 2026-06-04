package com.qwenpaw.controller.model;

import java.util.List;

/**
 * 用户 Pod 列表接口的响应。
 */
public class ListUserPodsResponse {

    /**
     * 当前返回的用户 Pod 明细。
     */
    private List<UserPodResponse> users;

    /**
     * 用户 Pod 总数。
     */
    private int total;

    /**
     * 创建列表响应，并根据列表长度填充总数。
     */
    public ListUserPodsResponse(List<UserPodResponse> users) {
        this.users = users;
        this.total = users.size();
    }

    /**
     * 获取用户 Pod 明细列表。
     */
    public List<UserPodResponse> getUsers() {
        return users;
    }

    /**
     * 设置用户 Pod 明细列表。
     */
    public void setUsers(List<UserPodResponse> users) {
        this.users = users;
    }

    /**
     * 获取用户 Pod 总数。
     */
    public int getTotal() {
        return total;
    }

    /**
     * 设置用户 Pod 总数。
     */
    public void setTotal(int total) {
        this.total = total;
    }
}
