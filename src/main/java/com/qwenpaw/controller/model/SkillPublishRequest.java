package com.qwenpaw.controller.model;

import java.util.List;

/**
 * 把指定技能发布包下发给选中用户的请求。
 */
public class SkillPublishRequest {

    private String skillName;
    private String packageDigest;
    private List<String> userIds;

    public String getSkillName() {
        return skillName;
    }

    public void setSkillName(String skillName) {
        this.skillName = skillName;
    }

    public String getPackageDigest() {
        return packageDigest;
    }

    public void setPackageDigest(String packageDigest) {
        this.packageDigest = packageDigest;
    }

    public List<String> getUserIds() {
        return userIds;
    }

    public void setUserIds(List<String> userIds) {
        this.userIds = userIds;
    }
}
