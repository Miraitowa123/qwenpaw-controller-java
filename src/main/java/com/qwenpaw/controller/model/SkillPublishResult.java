package com.qwenpaw.controller.model;

import java.util.List;

/**
 * 一次批量技能发布的汇总结果。
 */
public class SkillPublishResult {

    private boolean success;
    private String skillName;
    private String packageDigest;
    private int requestedCount;
    private int installedCount;
    private int updatedCount;
    private int failedCount;
    private List<SkillPublishUserResult> results;

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

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

    public int getRequestedCount() {
        return requestedCount;
    }

    public void setRequestedCount(int requestedCount) {
        this.requestedCount = requestedCount;
    }

    public int getInstalledCount() {
        return installedCount;
    }

    public void setInstalledCount(int installedCount) {
        this.installedCount = installedCount;
    }

    public int getUpdatedCount() {
        return updatedCount;
    }

    public void setUpdatedCount(int updatedCount) {
        this.updatedCount = updatedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(int failedCount) {
        this.failedCount = failedCount;
    }

    public List<SkillPublishUserResult> getResults() {
        return results;
    }

    public void setResults(List<SkillPublishUserResult> results) {
        this.results = results;
    }
}
