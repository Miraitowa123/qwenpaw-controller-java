package com.qwenpaw.controller.model;

import java.time.OffsetDateTime;

/**
 * 已上传并通过校验的技能发布包。
 */
public class SkillPackageInfo {

    private String skillName;
    private String packageDigest;
    private long sizeBytes;
    private int entryCount;
    private OffsetDateTime uploadedAt;
    private boolean current;

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

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public int getEntryCount() {
        return entryCount;
    }

    public void setEntryCount(int entryCount) {
        this.entryCount = entryCount;
    }

    public OffsetDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(OffsetDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public boolean isCurrent() {
        return current;
    }

    public void setCurrent(boolean current) {
        this.current = current;
    }
}
