package com.qwenpaw.controller.model;

import java.util.List;

public class ListUserPodsResponse {

    private List<UserPodResponse> users;
    private int total;

    public ListUserPodsResponse(List<UserPodResponse> users) {
        this.users = users;
        this.total = users.size();
    }

    public List<UserPodResponse> getUsers() {
        return users;
    }

    public void setUsers(List<UserPodResponse> users) {
        this.users = users;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }
}
