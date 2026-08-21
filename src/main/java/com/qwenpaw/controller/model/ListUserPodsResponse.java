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
     * 当前页码，从 1 开始。
     */
    private int page;

    /**
     * 每页最多返回的 Pod 数量。
     */
    private int pageSize;

    /**
     * 总页数；没有 Pod 时为 0。
     */
    private int totalPages;

    /**
     * 创建列表响应，并根据列表长度填充总数。
     */
    public ListUserPodsResponse(List<UserPodResponse> users) {
        this(users, users.size(), 1, users.size());
    }

    /**
     * 创建分页列表响应。
     */
    public ListUserPodsResponse(List<UserPodResponse> users, int total, int page, int pageSize) {
        this.users = users;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
        this.totalPages = total == 0 || pageSize == 0 ? 0 : ((total - 1) / pageSize) + 1;
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

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(int totalPages) {
        this.totalPages = totalPages;
    }
}
