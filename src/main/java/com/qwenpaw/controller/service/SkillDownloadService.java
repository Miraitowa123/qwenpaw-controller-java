package com.qwenpaw.controller.service;

import com.qwenpaw.controller.config.QwenPawProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 负责把用户工作区里的技能目录打包为 zip。
 */
@Service
public class SkillDownloadService {

    /**
     * 技能目录相对于用户目录的固定路径。
     */
    private static final List<String> SKILLS_PATH = List.of("working", "workspaces", "default", "skills");

    /**
     * controller 配置项，用于读取 NAS 挂载路径。
     */
    private final QwenPawProperties properties;

    /**
     * 注入 controller 配置。
     */
    public SkillDownloadService(QwenPawProperties properties) {
        this.properties = properties;
    }

    /**
     * 根据用户和技能名称定位技能目录。
     */
    public SkillArchive locateSkill(String userId, String skillName) throws IOException {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("skillName is required");
        }
        if (skillName.contains("/") || skillName.contains("\\") || ".".equals(skillName) || "..".equals(skillName)) {
            throw new IllegalArgumentException("skillName contains illegal path characters");
        }

        Path personalDataRoot = Path.of(properties.getPersonalDataMountPath()).toAbsolutePath().normalize();
        Path skillsRoot = personalDataRoot.resolve(userId).normalize();
        for (String segment : SKILLS_PATH) {
            skillsRoot = skillsRoot.resolve(segment).normalize();
        }

        Path skillDir = skillsRoot.resolve(skillName).normalize();
        if (!skillDir.startsWith(skillsRoot)) {
            throw new IllegalArgumentException("skillName escapes skills directory");
        }
        if (!Files.isDirectory(skillDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(skillDir.toString());
        }
        return new SkillArchive(skillName, skillDir);
    }

    /**
     * 把技能目录写成 zip 数据流。
     */
    public void writeZip(SkillArchive archive, OutputStream output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8);
             Stream<Path> paths = Files.walk(archive.directory())) {
            List<Path> sortedPaths = paths
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            for (Path path : sortedPaths) {
                if (Files.isSymbolicLink(path)) {
                    continue;
                }
                addZipEntry(zip, archive, path);
            }
            zip.finish();
        }
    }

    /**
     * 向 zip 中加入目录或普通文件。
     */
    private void addZipEntry(ZipOutputStream zip, SkillArchive archive, Path path) throws IOException {
        boolean isDirectory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        boolean isRegularFile = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        if (!isDirectory && !isRegularFile) {
            return;
        }

        String entryName = zipEntryName(archive, path, isDirectory);
        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis());
        try {
            zip.putNextEntry(entry);
        } catch (FileAlreadyExistsException ignored) {
            return;
        }
        try {
            if (isRegularFile) {
                Files.copy(path, zip);
            }
        } finally {
            zip.closeEntry();
        }
    }

    /**
     * 生成 zip 内部路径，保留技能目录本身作为第一层。
     */
    private String zipEntryName(SkillArchive archive, Path path, boolean isDirectory) {
        Path relative = archive.directory().relativize(path);
        String rootName = archive.skillName();
        if (relative.toString().isBlank()) {
            return rootName + "/";
        }
        String entryName = rootName + "/" + relative.toString().replace('\\', '/');
        return isDirectory && !entryName.endsWith("/") ? entryName + "/" : entryName;
    }

    /**
     * 已定位的技能压缩目标。
     */
    public record SkillArchive(String skillName, Path directory) {
    }
}
