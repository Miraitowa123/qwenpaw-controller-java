package com.qwenpaw.controller.model;

import java.util.List;

/**
 * 技能发布页面需要的发布包和用户列表。
 */
public class SkillReleaseOverviewResponse {

    private List<SkillPackageInfo> packages;
    private List<String> users;

    public List<SkillPackageInfo> getPackages() {
        return packages;
    }

    public void setPackages(List<SkillPackageInfo> packages) {
        this.packages = packages;
    }

    public List<String> getUsers() {
        return users;
    }

    public void setUsers(List<String> users) {
        this.users = users;
    }
}
