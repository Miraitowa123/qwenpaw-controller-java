package com.qwenpaw.controller.service;

import com.qwenpaw.controller.config.QwenPawProperties;
import com.qwenpaw.controller.model.TemplateSyncFailure;
import com.qwenpaw.controller.model.TemplateSyncNode;
import com.qwenpaw.controller.model.TemplateSyncOverviewResponse;
import com.qwenpaw.controller.model.TemplateSyncRequest;
import com.qwenpaw.controller.model.TemplateSyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 负责读取模板目录树、用户目录列表，并把公共模板下发到选中的用户目录。
 */
@Service
public class PersonalDataSyncService {

    /**
     * 同步日志。
     */
    private static final Logger log = LoggerFactory.getLogger(PersonalDataSyncService.class);

    /**
     * 配置项。
     */
    private final QwenPawProperties properties;

    /**
     * 注入配置。
     */
    public PersonalDataSyncService(QwenPawProperties properties) {
        this.properties = properties;
    }

    /**
     * 读取模板树和用户列表。
     */
    public TemplateSyncOverviewResponse getOverview() throws IOException {
        Path personalDataRoot = personalDataRoot();
        Path templateRoot = templateRoot();
        requireReadableDirectory(personalDataRoot, "personalData root");
        requireReadableDirectory(templateRoot, "template root");

        TemplateSyncOverviewResponse response = new TemplateSyncOverviewResponse();
        response.setTemplateRoot(properties.getQwenpawPublicTemplateSubPath());
        response.setTemplateTree(readChildren(templateRoot, templateRoot));
        response.setUsers(listUsers(personalDataRoot));
        return response;
    }

    /**
     * 把选中的模板路径同步到选中的用户目录。
     */
    public TemplateSyncResult sync(TemplateSyncRequest request) throws IOException {
        List<String> templatePaths = normalizeSelections(request.getTemplatePaths());
        List<String> userIds = normalizeSelections(request.getUserIds());
        if (templatePaths.isEmpty()) {
            throw new IllegalArgumentException("templatePaths is required");
        }
        if (userIds.isEmpty()) {
            throw new IllegalArgumentException("userIds is required");
        }

        Path personalDataRoot = personalDataRoot();
        Path templateRoot = templateRoot();
        requireReadableDirectory(personalDataRoot, "personalData root");
        requireReadableDirectory(templateRoot, "template root");

        TemplateSyncResult result = new TemplateSyncResult();
        result.setTemplatePaths(templatePaths);
        result.setUserIds(userIds);

        List<TemplateSyncFailure> failures = new ArrayList<>();
        int copiedCount = 0;
        int skippedCount = 0;

        for (String userId : userIds) {
            Path userRoot = resolveUserRoot(personalDataRoot, userId);
            if (!Files.isDirectory(userRoot, LinkOption.NOFOLLOW_LINKS)) {
                failures.add(failure(userId, null, userRoot.toString(), "user directory not found"));
                continue;
            }

            for (String templatePath : templatePaths) {
                try {
                    Path source = resolveTemplatePath(templateRoot, templatePath);
                    if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                        failures.add(failure(userId, templatePath, source.toString(), "template path not found"));
                        continue;
                    }

                    SyncCounter counter = copySelectedPath(source, templateRoot, userRoot, request.isOverwrite(), userId, templatePath, failures);
                    copiedCount += counter.copied();
                    skippedCount += counter.skipped();
                } catch (RuntimeException | IOException e) {
                    failures.add(failure(userId, templatePath, templateRoot.resolve(templatePath).normalize().toString(), e.getMessage()));
                }
            }
        }

        result.setCopiedCount(copiedCount);
        result.setSkippedCount(skippedCount);
        result.setFailures(failures);
        result.setSuccess(failures.isEmpty());
        result.setMessage(failures.isEmpty()
                ? "Template sync completed successfully"
                : "Template sync completed with failures");
        log.info("Synced {} template paths to {} users with {} copied, {} skipped, {} failures",
                templatePaths.size(), userIds.size(), copiedCount, skippedCount, failures.size());
        return result;
    }

    /**
     * 读取模板目录下的子节点。
     */
    private List<TemplateSyncNode> readChildren(Path current, Path templateRoot) throws IOException {
        try (Stream<Path> children = Files.list(current)) {
            return children
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !".DS_Store".equals(path.getFileName().toString()))
                    .sorted(Comparator.comparingInt(Path::getNameCount).thenComparing(Path::toString))
                    .map(path -> toNode(path, templateRoot))
                    .toList();
        }
    }

    /**
     * 把路径转换为树节点。
     */
    private TemplateSyncNode toNode(Path path, Path templateRoot) {
        TemplateSyncNode node = new TemplateSyncNode();
        node.setName(path.getFileName().toString());
        node.setRelativePath(templateRoot.relativize(path).toString().replace('\\', '/'));
        boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        node.setDirectory(directory);
        if (directory) {
            try {
                node.setChildren(readChildren(path, templateRoot));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read template directory " + path, e);
            }
        } else {
            node.setChildren(List.of());
        }
        return node;
    }

    /**
     * 列出模板目录下的用户 ID。
     */
    private List<String> listUsers(Path personalDataRoot) throws IOException {
        try (Stream<Path> users = Files.list(personalDataRoot)) {
            return users
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !properties.getQwenpawPublicTemplateSubPath().equals(path.getFileName().toString()))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    /**
     * 复制选中的单个模板路径或目录。
     */
    private SyncCounter copySelectedPath(Path source,
                                         Path templateRoot,
                                         Path userRoot,
                                         boolean overwrite,
                                         String userId,
                                         String templatePath,
                                         List<TemplateSyncFailure> failures) throws IOException {
        if (Files.isSymbolicLink(source)) {
            failures.add(failure(userId, templatePath, source.toString(), "symbolic links are not supported"));
            return SyncCounter.zero();
        }

        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> walk = Files.walk(source)) {
                List<Path> paths = walk
                        .sorted(Comparator.comparingInt(Path::getNameCount).thenComparing(Path::toString))
                        .toList();
                return copyPaths(paths, templateRoot, userRoot, overwrite, userId, templatePath, failures);
            } catch (IOException e) {
                failures.add(failure(userId, templatePath, source.toString(), "failed to walk template directory: " + e.getMessage()));
                return SyncCounter.zero();
            }
        }

        return copyPaths(List.of(source), templateRoot, userRoot, overwrite, userId, templatePath, failures);
    }

    /**
     * 执行一组路径复制。
     */
    private SyncCounter copyPaths(List<Path> paths,
                                  Path templateRoot,
                                  Path userRoot,
                                  boolean overwrite,
                                  String userId,
                                  String templatePath,
                                  List<TemplateSyncFailure> failures) throws IOException {
        int copied = 0;
        int skipped = 0;
        for (Path path : paths) {
            try {
                if (Files.isSymbolicLink(path)) {
                    skipped++;
                    continue;
                }

                Path relative = templateRoot.relativize(path);
                Path target = userRoot.resolve(relative).normalize();
                if (!target.startsWith(userRoot)) {
                    failures.add(failure(userId, templatePath, target.toString(), "target path escapes user directory"));
                    continue;
                }

                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                    continue;
                }

                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }

                Files.createDirectories(target.getParent());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !overwrite) {
                    skipped++;
                    continue;
                }

                CopyOption[] options = overwrite
                        ? new CopyOption[]{StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES}
                        : new CopyOption[]{StandardCopyOption.COPY_ATTRIBUTES};
                Files.copy(path, target, options);
                copied++;
            } catch (IOException e) {
                failures.add(failure(userId, templatePath, path.toString(), e.getMessage()));
            }
        }
        return new SyncCounter(copied, skipped);
    }

    /**
     * 定位模板目录根路径。
     */
    private Path templateRoot() {
        return personalDataRoot().resolve(properties.getQwenpawPublicTemplateSubPath()).normalize();
    }

    /**
     * 定位 personalData 根路径。
     */
    private Path personalDataRoot() {
        return Path.of(properties.getPersonalDataMountPath()).toAbsolutePath().normalize();
    }

    /**
     * 校验同步所需目录可访问，并给前端返回更明确的错误。
     */
    private void requireReadableDirectory(Path path, String label) throws IOException {
        if (!Files.exists(path)) {
            throw new NoSuchFileException(path.toString(), null, label + " does not exist");
        }
        if (!Files.isDirectory(path)) {
            throw new IOException(label + " is not a directory: " + path);
        }
        if (!Files.isReadable(path)) {
            throw new IOException(label + " is not readable: " + path);
        }
    }

    /**
     * 定位指定用户目录并校验不越界。
     */
    private Path resolveUserRoot(Path personalDataRoot, String userId) {
        String normalizedUserId = normalizeSelection(userId);
        Path userSegment = Path.of(normalizedUserId);
        if (userSegment.getNameCount() != 1 || ".".equals(userSegment.toString()) || "..".equals(userSegment.toString())) {
            throw new IllegalArgumentException("invalid userId");
        }

        Path userRoot = personalDataRoot.resolve(userSegment).normalize();
        if (!userRoot.startsWith(personalDataRoot)) {
            throw new IllegalArgumentException("userId escapes personalData directory");
        }
        return userRoot;
    }

    /**
     * 定位模板目录下的相对路径。
     */
    private Path resolveTemplatePath(Path templateRoot, String relativePath) {
        String normalized = normalizeSelection(relativePath);
        Path path = templateRoot.resolve(Path.of(normalized)).normalize();
        if (!path.startsWith(templateRoot)) {
            throw new IllegalArgumentException("template path escapes template directory");
        }
        return path;
    }

    /**
     * 规范化选中路径，统一成相对路径形式。
     */
    private String normalizeSelection(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        Path path = Path.of(value.trim()).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            throw new IllegalArgumentException("invalid path: " + value);
        }
        return path.toString().replace('\\', '/');
    }

    /**
     * 规范化并去重。
     */
    private List<String> normalizeSelections(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            normalized.add(normalizeSelection(value));
        }
        return List.copyOf(normalized);
    }

    /**
     * 构造失败明细。
     */
    private TemplateSyncFailure failure(String userId, String templatePath, String targetPath, String message) {
        TemplateSyncFailure failure = new TemplateSyncFailure();
        failure.setUserId(userId);
        failure.setTemplatePath(templatePath);
        failure.setTargetPath(targetPath);
        failure.setMessage(message);
        return failure;
    }

    /**
     * 简单的复制统计。
     */
    private record SyncCounter(int copied, int skipped) {
        private static SyncCounter zero() {
            return new SyncCounter(0, 0);
        }
    }
}
