package com.qwenpaw.controller.service;

import com.qwenpaw.controller.config.QwenPawProperties;
import com.qwenpaw.controller.model.SkillPackageInfo;
import com.qwenpaw.controller.model.SkillPublishRequest;
import com.qwenpaw.controller.model.SkillPublishResult;
import com.qwenpaw.controller.model.SkillPublishUserResult;
import com.qwenpaw.controller.model.SkillReleaseOverviewResponse;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 管理技能 ZIP 发布包，并把指定版本安全发布到模板和用户技能目录。
 */
@Service
public class SkillReleaseService {

    private static final Logger log = LoggerFactory.getLogger(SkillReleaseService.class);
    private static final List<String> SKILLS_PATH = List.of("working", "workspaces", "default", "skills");
    private static final String PACKAGES_DIRECTORY = ".skill-packages";
    private static final String CURRENT_FILE = ".current";
    private static final Pattern DIGEST_PATTERN = Pattern.compile("[a-f0-9]{64}");
    private static final int COPY_BUFFER_SIZE = 64 * 1024;
    private static final int MAX_ENTRY_PATH_LENGTH = 1024;
    private static final int MAX_ENTRY_PATH_DEPTH = 32;
    private static final Charset LEGACY_ZIP_CHARSET = Charset.forName("GB18030");

    private final QwenPawProperties properties;
    private final KubernetesLeaseLock leaseLock;
    private final ExecutorService publishExecutor;

    @Autowired
    public SkillReleaseService(QwenPawProperties properties, KubernetesLeaseLock leaseLock) {
        this(properties, leaseLock, newPublishExecutor(properties.getSkillPublishConcurrency()));
    }

    SkillReleaseService(QwenPawProperties properties,
                        KubernetesLeaseLock leaseLock,
                        ExecutorService publishExecutor) {
        this.properties = properties;
        this.leaseLock = leaseLock;
        this.publishExecutor = publishExecutor;
    }

    /**
     * 返回已上传的技能发布包和可选用户列表。
     */
    public SkillReleaseOverviewResponse getOverview() throws IOException {
        Path personalDataRoot = personalDataRoot();
        requireDirectory(personalDataRoot, "personalData root");
        requireDirectory(templateRoot(), "template root");

        SkillReleaseOverviewResponse response = new SkillReleaseOverviewResponse();
        response.setPackages(listPackages());
        response.setUsers(listUsers(personalDataRoot));
        return response;
    }

    /**
     * 校验并保存 ZIP，同时用其中的技能目录原子更新公共模板。
     */
    public SkillPackageInfo upload(MultipartFile file) throws IOException {
        validateUpload(file);
        Path templateRoot = templateRoot();
        requireDirectory(templateRoot, "template root");

        Path packageRoot = packageRoot();
        Path uploadTempRoot = packageRoot.resolve(".tmp");
        createSafeDirectories(templateRoot, packageRoot);
        createSafeDirectories(packageRoot, uploadTempRoot);
        Path uploadedArchive = Files.createTempFile(uploadTempRoot, "skill-upload-", ".zip");
        Path extractionRoot = null;
        try {
            ArchiveCopy archiveCopy = copyUpload(file, uploadedArchive);
            Path templateSkillsRoot = templateSkillsRoot();
            createSafeDirectories(templateRoot, templateSkillsRoot);
            extractionRoot = Files.createTempDirectory(templateSkillsRoot, ".skill-upload-");
            ExtractedSkill extracted = extractArchive(uploadedArchive, extractionRoot);
            WorkspaceSkillManifest.SkillMetadata metadata = WorkspaceSkillManifest.readMetadata(
                    extractionRoot.resolve(extracted.skillName()), extracted.skillName());

            Path finalExtractionRoot = extractionRoot;
            return withIoLock(lockName("skill-workspace", templateRoot.toString()), () ->
                    installTemplatePackage(uploadedArchive, archiveCopy, finalExtractionRoot, extracted, metadata));
        } finally {
            Files.deleteIfExists(uploadedArchive);
            deleteRecursivelyQuietly(extractionRoot);
        }
    }

    /**
     * 使用有界线程池并发发布到多个用户；同一用户的清单通过 Kubernetes Lease 串行化。
     */
    public SkillPublishResult publish(SkillPublishRequest request) throws IOException {
        String skillName = normalizeSkillName(request == null ? null : request.getSkillName());
        String digest = normalizeDigest(request.getPackageDigest());
        List<String> userIds = normalizeUserIds(request.getUserIds());
        if (userIds.isEmpty()) {
            throw new IllegalArgumentException("userIds is required");
        }
        if (userIds.size() > properties.getSkillPublishMaxUsers()) {
            throw new IllegalArgumentException("userIds exceeds maximum of " + properties.getSkillPublishMaxUsers());
        }

        Path archive = resolvePackage(skillName, digest);
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(archive.toString());
        }

        List<CompletableFuture<SkillPublishUserResult>> futures = userIds.stream()
                .map(userId -> CompletableFuture.supplyAsync(
                        () -> publishOneSafely(archive, skillName, userId), publishExecutor))
                .toList();
        List<SkillPublishUserResult> userResults = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        int installed = countStatus(userResults, "INSTALLED");
        int updated = countStatus(userResults, "UPDATED");
        int failed = countStatus(userResults, "FAILED");
        SkillPublishResult result = new SkillPublishResult();
        result.setSuccess(failed == 0);
        result.setSkillName(skillName);
        result.setPackageDigest(digest);
        result.setRequestedCount(userIds.size());
        result.setInstalledCount(installed);
        result.setUpdatedCount(updated);
        result.setFailedCount(failed);
        result.setResults(userResults);
        log.info("Published skill {} package {} to {} users: {} installed, {} updated, {} failed",
                skillName, digest, userIds.size(), installed, updated, failed);
        return result;
    }

    private SkillPackageInfo installTemplatePackage(Path uploadedArchive,
                                                    ArchiveCopy archiveCopy,
                                                    Path extractionRoot,
                                                    ExtractedSkill extracted,
                                                    WorkspaceSkillManifest.SkillMetadata metadata) throws IOException {
        String skillName = extracted.skillName();
        Path skillPackageRoot = packageRoot().resolve(skillName).normalize();
        ensureUnder(packageRoot(), skillPackageRoot, "skill package path escapes package root");
        createSafeDirectories(packageRoot(), skillPackageRoot);
        Path packagePath = skillPackageRoot.resolve(archiveCopy.digest() + ".zip");
        boolean newPackage = !Files.exists(packagePath, LinkOption.NOFOLLOW_LINKS);
        if (!newPackage) {
            if (Files.isSymbolicLink(packagePath)
                    || !Files.isRegularFile(packagePath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("existing immutable package path is not a regular file");
            }
        } else {
            move(uploadedArchive, packagePath);
        }

        try {
            SkillPackageInfo info = packageInfo(skillName, archiveCopy.digest(), packagePath,
                    extracted.entryCount(), true);
            installWorkspaceSkill(extractionRoot.resolve(skillName), templateSkillsRoot(), skillName,
                    metadata, skillPackageRoot, archiveCopy.digest());
            return info;
        } catch (IOException | RuntimeException e) {
            if (newPackage) {
                try {
                    Files.deleteIfExists(packagePath);
                } catch (IOException cleanupError) {
                    e.addSuppressed(cleanupError);
                }
            }
            throw e;
        }
    }

    private SkillPublishUserResult publishOneSafely(Path archive, String skillName, String userId) {
        try {
            return withIoLock(lockName("skill-workspace", resolveUserRoot(userId).toString()),
                    () -> publishOne(archive, skillName, userId));
        } catch (Exception e) {
            log.warn("Failed to publish skill {} to user {}", skillName, userId, e);
            return userResult(userId, "FAILED", errorMessage(e));
        }
    }

    private SkillPublishUserResult publishOne(Path archive, String skillName, String userId) throws IOException {
        Path userRoot = resolveUserRoot(userId);
        requireDirectory(userRoot, "user directory");
        if (Files.isSymbolicLink(userRoot)) {
            throw new IOException("user directory must not be a symbolic link");
        }

        Path skillsRoot = resolveSkillsRoot(userRoot);
        createSafeDirectories(userRoot, skillsRoot);
        Path extractionRoot = Files.createTempDirectory(skillsRoot, ".skill-stage-");
        try {
            ExtractedSkill extracted = extractArchive(archive, extractionRoot);
            if (!skillName.equals(extracted.skillName())) {
                throw new IOException("package skill name does not match requested skill");
            }
            WorkspaceSkillManifest.SkillMetadata metadata = WorkspaceSkillManifest.readMetadata(
                    extractionRoot.resolve(skillName), skillName);
            boolean existed = installWorkspaceSkill(extractionRoot.resolve(skillName), skillsRoot,
                    skillName, metadata, null, null);
            return userResult(userId, existed ? "UPDATED" : "INSTALLED",
                    existed ? "Skill updated" : "Skill installed");
        } finally {
            deleteRecursivelyQuietly(extractionRoot);
        }
    }

    private ArchiveCopy copyUpload(MultipartFile file, Path target) throws IOException {
        MessageDigest digest = sha256Digest();
        long copied = 0;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (InputStream input = new BufferedInputStream(file.getInputStream());
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(target,
                     StandardOpenOption.TRUNCATE_EXISTING))) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                copied += read;
                if (copied > properties.getSkillPackageMaxSizeBytes()) {
                    throw new IllegalArgumentException("skill package exceeds maximum compressed size");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        if (copied == 0) {
            throw new IllegalArgumentException("skill package is empty");
        }
        return new ArchiveCopy(toHex(digest.digest()), copied);
    }

    private ExtractedSkill extractArchive(Path archive, Path destinationRoot) throws IOException {
        try {
            return extractArchive(archive, destinationRoot, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            if (!isMalformedZipEntryName(e)) {
                throw e;
            }
            // Windows 中文环境生成的 ZIP 可能没有 UTF-8 标志，文件名实际使用 GBK/GB18030。
            resetExtractionDirectory(destinationRoot);
            try {
                return extractArchive(archive, destinationRoot, LEGACY_ZIP_CHARSET);
            } catch (IllegalArgumentException fallbackError) {
                if (isMalformedZipEntryName(fallbackError)) {
                    throw new IllegalArgumentException(
                            "ZIP entry names must use UTF-8 or GB18030 encoding", fallbackError);
                }
                throw fallbackError;
            }
        }
    }

    private ExtractedSkill extractArchive(Path archive,
                                           Path destinationRoot,
                                           Charset entryNameCharset) throws IOException {
        Set<String> entryNames = new HashSet<>();
        String skillName = null;
        boolean skillMdFound = false;
        int entryCount = 0;
        long expandedBytes = 0;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];

        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)),
                entryNameCharset)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > properties.getSkillPackageMaxEntries()) {
                    throw new IllegalArgumentException("skill package contains too many entries");
                }

                Path relative = normalizeZipEntry(entry.getName());
                String normalizedEntry = relative.toString().replace('\\', '/');
                if (!entryNames.add(normalizedEntry)) {
                    throw new IllegalArgumentException("duplicate ZIP entry: " + entry.getName());
                }

                String entrySkillName = normalizeSkillName(relative.getName(0).toString());
                if (skillName == null) {
                    skillName = entrySkillName;
                } else if (!skillName.equals(entrySkillName)) {
                    throw new IllegalArgumentException("ZIP must contain exactly one top-level skill directory");
                }
                if (relative.getNameCount() == 1 && !entry.isDirectory()) {
                    throw new IllegalArgumentException("ZIP top-level skill entry must be a directory");
                }

                Path outputPath = destinationRoot.resolve(relative).normalize();
                ensureUnder(destinationRoot, outputPath, "ZIP entry escapes extraction directory");
                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                } else {
                    Files.createDirectories(outputPath.getParent());
                    try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(outputPath,
                            StandardOpenOption.CREATE_NEW))) {
                        int read;
                        while ((read = zip.read(buffer)) >= 0) {
                            if (read == 0) {
                                continue;
                            }
                            expandedBytes += read;
                            if (expandedBytes > properties.getSkillPackageMaxExpandedSizeBytes()) {
                                throw new IllegalArgumentException("skill package exceeds maximum expanded size");
                            }
                            output.write(buffer, 0, read);
                        }
                    }
                    if (relative.getNameCount() == 2 && "SKILL.md".equals(relative.getName(1).toString())) {
                        skillMdFound = true;
                    }
                }
                zip.closeEntry();
            }
        }

        if (skillName == null) {
            throw new IllegalArgumentException("skill package contains no entries");
        }
        Path extractedSkill = destinationRoot.resolve(skillName).normalize();
        if (!Files.isDirectory(extractedSkill, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("ZIP must contain one top-level skill directory");
        }
        if (!skillMdFound || !Files.isRegularFile(extractedSkill.resolve("SKILL.md"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("top-level skill directory must contain SKILL.md");
        }
        return new ExtractedSkill(skillName, entryCount, expandedBytes);
    }

    private boolean isMalformedZipEntryName(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof MalformedInputException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void resetExtractionDirectory(Path destinationRoot) throws IOException {
        deleteRecursively(destinationRoot);
        Files.createDirectory(destinationRoot);
    }

    private Path normalizeZipEntry(String entryName) {
        if (entryName == null || entryName.isBlank() || entryName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("ZIP contains an invalid empty entry name");
        }
        String portableName = entryName.replace('\\', '/');
        if (portableName.length() > MAX_ENTRY_PATH_LENGTH) {
            throw new IllegalArgumentException("ZIP entry path is too long");
        }
        if (portableName.startsWith("/") || portableName.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("ZIP contains an absolute entry path");
        }
        for (String segment : portableName.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("ZIP entry contains parent traversal: " + entryName);
            }
        }
        try {
            Path relative = Path.of(portableName).normalize();
            if (relative.isAbsolute() || relative.getNameCount() == 0 || relative.startsWith("..")) {
                throw new IllegalArgumentException("ZIP entry escapes skill directory: " + entryName);
            }
            if (relative.getNameCount() > MAX_ENTRY_PATH_DEPTH) {
                throw new IllegalArgumentException("ZIP entry path is too deep: " + entryName);
            }
            return relative;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("ZIP contains an invalid entry path", e);
        }
    }

    private List<SkillPackageInfo> listPackages() throws IOException {
        Path packageRoot = packageRoot();
        if (!Files.isDirectory(packageRoot, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }

        List<SkillPackageInfo> packages = new ArrayList<>();
        try (Stream<Path> skillDirectories = Files.list(packageRoot)) {
            for (Path skillDirectory : skillDirectories
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .toList()) {
                String skillName;
                try {
                    skillName = normalizeSkillName(skillDirectory.getFileName().toString());
                } catch (IllegalArgumentException e) {
                    log.warn("Ignoring invalid skill package directory {}", skillDirectory, e);
                    continue;
                }
                String currentDigest = readCurrentDigest(skillDirectory);
                try (Stream<Path> archives = Files.list(skillDirectory)) {
                    for (Path archive : archives
                            .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            .filter(path -> path.getFileName().toString().endsWith(".zip"))
                            .toList()) {
                        String fileName = archive.getFileName().toString();
                        String digest = fileName.substring(0, fileName.length() - 4);
                        if (!DIGEST_PATTERN.matcher(digest).matches()) {
                            continue;
                        }
                        try {
                            packages.add(packageInfo(skillName, digest, archive,
                                    countEntries(archive), digest.equals(currentDigest)));
                        } catch (IOException | IllegalArgumentException e) {
                            log.warn("Ignoring unreadable skill package {}", archive, e);
                        }
                    }
                }
            }
        }
        packages.sort(Comparator.comparing(SkillPackageInfo::getUploadedAt).reversed()
                .thenComparing(SkillPackageInfo::getSkillName));
        return List.copyOf(packages);
    }

    private int countEntries(Path archive) throws IOException {
        try {
            return countEntries(archive, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            if (!isMalformedZipEntryName(e)) {
                throw e;
            }
            try {
                return countEntries(archive, LEGACY_ZIP_CHARSET);
            } catch (IllegalArgumentException fallbackError) {
                if (isMalformedZipEntryName(fallbackError)) {
                    throw new IllegalArgumentException(
                            "ZIP entry names must use UTF-8 or GB18030 encoding", fallbackError);
                }
                throw fallbackError;
            }
        }
    }

    private int countEntries(Path archive, Charset entryNameCharset) throws IOException {
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)),
                entryNameCharset)) {
            while (zip.getNextEntry() != null) {
                count++;
                if (count > properties.getSkillPackageMaxEntries()) {
                    throw new IllegalArgumentException("skill package contains too many entries");
                }
                zip.closeEntry();
            }
        }
        return count;
    }

    private SkillPackageInfo packageInfo(String skillName,
                                         String digest,
                                         Path archive,
                                         int entryCount,
                                         boolean current) throws IOException {
        SkillPackageInfo info = new SkillPackageInfo();
        info.setSkillName(skillName);
        info.setPackageDigest(digest);
        info.setSizeBytes(Files.size(archive));
        info.setEntryCount(entryCount);
        info.setUploadedAt(OffsetDateTime.ofInstant(
                Files.getLastModifiedTime(archive, LinkOption.NOFOLLOW_LINKS).toInstant(), ZoneOffset.UTC));
        info.setCurrent(current);
        return info;
    }

    private List<String> listUsers(Path personalDataRoot) throws IOException {
        try (Stream<Path> users = Files.list(personalDataRoot)) {
            return users
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !properties.getQwenpawPublicTemplateSubPath().equals(path.getFileName().toString()))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    private boolean installWorkspaceSkill(Path stagedDirectory,
                                          Path skillsRoot,
                                          String skillName,
                                          WorkspaceSkillManifest.SkillMetadata metadata,
                                          Path currentPackageRoot,
                                          String digest) throws IOException {
        Path targetDirectory = skillsRoot.resolve(skillName).normalize();
        ensureUnder(skillsRoot, targetDirectory, "skill path escapes skills root");
        if (!Files.isDirectory(stagedDirectory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(stagedDirectory)) {
            throw new IOException("staged skill directory is missing or invalid");
        }
        Path manifest = skillsRoot.getParent().resolve("skill.json");
        Path backup = targetDirectory.resolveSibling(".skill-backup-" + UUID.randomUUID());
        Path current = currentPackageRoot == null ? null : currentPackageRoot.resolve(CURRENT_FILE);
        try (WorkspaceSkillManifest.ManifestLock ignored = WorkspaceSkillManifest.lock(manifest)) {
            boolean hadTarget = Files.exists(targetDirectory, LinkOption.NOFOLLOW_LINKS);
            if (hadTarget && (Files.isSymbolicLink(targetDirectory)
                    || !Files.isDirectory(targetDirectory, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("target skill path is not a regular directory");
            }
            byte[] oldCurrent = null;
            if (current != null && Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
                    throw new IOException("current package marker is not a regular file: " + current);
                }
                oldCurrent = Files.readAllBytes(current);
            }
            Path stagedManifest = WorkspaceSkillManifest.prepareUpdate(manifest, skillName, metadata);
            boolean movedOld = false;
            boolean markerTouched = false;
            try {
                if (hadTarget) {
                    move(targetDirectory, backup);
                    movedOld = true;
                }
                move(stagedDirectory, targetDirectory);
                if (current != null) {
                    markerTouched = true;
                    writeCurrentDigest(currentPackageRoot, digest);
                }
                WorkspaceSkillManifest.commit(stagedManifest, manifest);
            } catch (IOException | RuntimeException e) {
                if (markerTouched) {
                    try {
                        restoreCurrentDigest(current, oldCurrent);
                    } catch (IOException rollbackError) {
                        e.addSuppressed(rollbackError);
                    }
                }
                try {
                    if ((movedOld || !hadTarget) && Files.exists(targetDirectory, LinkOption.NOFOLLOW_LINKS)) {
                        deleteRecursively(targetDirectory);
                    }
                    if (movedOld) {
                        move(backup, targetDirectory);
                    }
                } catch (IOException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            } finally {
                try {
                    Files.deleteIfExists(stagedManifest);
                } catch (IOException cleanupError) {
                    log.warn("Failed to clean staged skill manifest {}", stagedManifest, cleanupError);
                }
            }
            if (movedOld) {
                deleteRecursivelyQuietly(backup);
            }
            return hadTarget;
        }
    }

    private void restoreCurrentDigest(Path current, byte[] oldContent) throws IOException {
        if (oldContent == null) {
            Files.deleteIfExists(current);
            return;
        }
        Path temporary = Files.createTempFile(current.getParent(), ".current-restore-", ".tmp");
        try {
            Files.write(temporary, oldContent, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, current, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private void writeCurrentDigest(Path skillPackageRoot, String digest) throws IOException {
        Path current = skillPackageRoot.resolve(CURRENT_FILE);
        Path temporary = Files.createTempFile(skillPackageRoot, ".current-", ".tmp");
        try {
            Files.writeString(temporary, digest + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, current, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String readCurrentDigest(Path skillPackageRoot) {
        Path current = skillPackageRoot.resolve(CURRENT_FILE);
        try {
            return normalizeDigest(Files.readString(current, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private Path resolvePackage(String skillName, String digest) {
        Path packagePath = packageRoot().resolve(skillName).resolve(digest + ".zip").normalize();
        ensureUnder(packageRoot(), packagePath, "package path escapes package root");
        return packagePath;
    }

    private Path resolveUserRoot(String userId) {
        Path root = personalDataRoot();
        Path userRoot = root.resolve(userId).normalize();
        ensureUnder(root, userRoot, "userId escapes personalData directory");
        return userRoot;
    }

    private Path resolveSkillsRoot(Path userRoot) {
        Path skillsRoot = userRoot;
        for (String segment : SKILLS_PATH) {
            skillsRoot = skillsRoot.resolve(segment);
        }
        return skillsRoot.normalize();
    }

    private Path personalDataRoot() {
        return Path.of(properties.getPersonalDataMountPath()).toAbsolutePath().normalize();
    }

    private Path templateRoot() {
        Path root = personalDataRoot();
        Path templateRoot = root.resolve(properties.getQwenpawPublicTemplateSubPath()).normalize();
        ensureUnder(root, templateRoot, "template root escapes personalData directory");
        return templateRoot;
    }

    private Path templateSkillsRoot() {
        return resolveSkillsRoot(templateRoot());
    }

    private Path packageRoot() {
        return templateRoot().resolve(PACKAGES_DIRECTORY).normalize();
    }

    private void createSafeDirectories(Path root, Path target) throws IOException {
        ensureUnder(root, target, "directory escapes root");
        Path current = root;
        Path relative = root.relativize(target);
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)
                        || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("path component is not a regular directory: " + current);
                }
            } else {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException e) {
                    if (Files.isSymbolicLink(current)
                            || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                        throw e;
                    }
                }
            }
        }
    }

    private void requireDirectory(Path path, String label) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException(path.toString(), null, label + " does not exist");
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException(label + " is not a regular directory: " + path);
        }
        if (!Files.isReadable(path)) {
            throw new IOException(label + " is not readable: " + path);
        }
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("skill ZIP file is required");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("skill package must be a .zip file");
        }
        if (file.getSize() > properties.getSkillPackageMaxSizeBytes()) {
            throw new IllegalArgumentException("skill package exceeds maximum compressed size");
        }
    }

    private String normalizeSkillName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("skillName is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > 128 || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid skillName");
        }
        try {
            Path segment = Path.of(trimmed).normalize();
            if (segment.isAbsolute() || segment.getNameCount() != 1
                    || ".".equals(segment.toString()) || "..".equals(segment.toString())
                    || !segment.toString().equals(trimmed)) {
                throw new IllegalArgumentException("invalid skillName");
            }
            return trimmed;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("invalid skillName", e);
        }
    }

    private List<String> normalizeUserIds(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            normalized.add(normalizeUserId(value));
        }
        return List.copyOf(normalized);
    }

    private String normalizeUserId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("invalid userId");
        }
        String trimmed = value.trim();
        try {
            Path segment = Path.of(trimmed).normalize();
            if (segment.isAbsolute() || segment.getNameCount() != 1
                    || ".".equals(segment.toString()) || "..".equals(segment.toString())
                    || !segment.toString().equals(trimmed)) {
                throw new IllegalArgumentException("invalid userId: " + value);
            }
            return trimmed;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("invalid userId: " + value, e);
        }
    }

    private String normalizeDigest(String value) {
        if (value == null) {
            throw new IllegalArgumentException("packageDigest is required");
        }
        String digest = value.trim().toLowerCase(Locale.ROOT);
        if (!DIGEST_PATTERN.matcher(digest).matches()) {
            throw new IllegalArgumentException("invalid packageDigest");
        }
        return digest;
    }

    private void ensureUnder(Path root, Path candidate, String message) {
        if (!candidate.normalize().startsWith(root.normalize())) {
            throw new IllegalArgumentException(message);
        }
    }

    private int countStatus(List<SkillPublishUserResult> results, String status) {
        return (int) results.stream().filter(result -> status.equals(result.getStatus())).count();
    }

    private SkillPublishUserResult userResult(String userId, String status, String message) {
        SkillPublishUserResult result = new SkillPublishUserResult();
        result.setUserId(userId);
        result.setStatus(status);
        result.setMessage(message);
        return result;
    }

    private String errorMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getMessage() == null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String lockName(String purpose, String key) {
        MessageDigest digest = sha256Digest();
        byte[] bytes = digest.digest(key.getBytes(StandardCharsets.UTF_8));
        return purpose + "-" + toHex(bytes).substring(0, 32);
    }

    private <T> T withIoLock(String lockName, IoSupplier<T> supplier) throws IOException {
        try {
            return leaseLock.withLock(lockName, () -> {
                try {
                    return supplier.get();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(Character.forDigit((item >>> 4) & 0x0f, 16));
            value.append(Character.forDigit(item & 0x0f, 16));
        }
        return value.toString();
    }

    private void deleteRecursivelyQuietly(Path path) {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            deleteRecursively(path);
        } catch (IOException e) {
            log.warn("Failed to clean temporary skill path {}", path, e);
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path);
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static ExecutorService newPublishExecutor(int configuredConcurrency) {
        int concurrency = Math.max(1, Math.min(configuredConcurrency, 32));
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "skill-publisher-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(concurrency, threadFactory);
    }

    @PreDestroy
    public void shutdown() {
        publishExecutor.shutdownNow();
    }

    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private record ArchiveCopy(String digest, long sizeBytes) {
    }

    private record ExtractedSkill(String skillName, int entryCount, long expandedBytes) {
    }
}
