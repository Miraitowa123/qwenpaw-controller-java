package com.qwenpaw.controller.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.jna.Library;
import com.sun.jna.Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/** Reads SKILL.md metadata and updates QwenPaw's workspace skill.json format. */
final class WorkspaceSkillManifest {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSkillManifest.class);
    private static final String SCHEMA = "workspace-skill-manifest.v1";
    private static final int MAX_FRONTMATTER_BYTES = 256 * 1024;
    private static final Charset LEGACY_CHARSET = Charset.forName("GB18030");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LibC LIBC = Native.load("c", LibC.class);

    private WorkspaceSkillManifest() {
    }

    static SkillMetadata readMetadata(Path skillDirectory, String skillName) throws IOException {
        Path skillMd = skillDirectory.resolve("SKILL.md");
        byte[] prefix;
        try (InputStream input = Files.newInputStream(skillMd)) {
            prefix = input.readNBytes(MAX_FRONTMATTER_BYTES + 1);
        }
        int firstEnd = lineEnd(prefix, 0);
        if (firstEnd < 0 || !isDelimiter(prefix, 0, firstEnd, true)) {
            throw new IllegalArgumentException("SKILL.md must start with YAML frontmatter");
        }
        int headerEnd = -1;
        int lineStart = firstEnd + 1;
        while (lineStart < prefix.length && lineStart < MAX_FRONTMATTER_BYTES) {
            int end = lineEnd(prefix, lineStart);
            if (end < 0) {
                end = prefix.length;
            }
            if (end > MAX_FRONTMATTER_BYTES) {
                break;
            }
            if (isDelimiter(prefix, lineStart, end, false)) {
                headerEnd = lineStart;
                break;
            }
            if (end == prefix.length) {
                break;
            }
            lineStart = end + 1;
        }
        if (headerEnd < 0) {
            throw new IllegalArgumentException("SKILL.md YAML frontmatter is missing its closing delimiter");
        }

        String yamlText = decodeFrontmatter(prefix, firstEnd + 1, headerEnd - firstEnd - 1);
        Object parsed;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(MAX_FRONTMATTER_BYTES);
            parsed = new Yaml(new SafeConstructor(options)).load(yamlText);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("SKILL.md YAML frontmatter is invalid", e);
        }
        if (!(parsed instanceof Map<?, ?> frontmatter)) {
            throw new IllegalArgumentException("SKILL.md YAML frontmatter must be an object");
        }
        String name = string(frontmatter.get("name")).trim();
        String description = string(frontmatter.get("description")).trim();
        if (!skillName.equals(name)) {
            throw new IllegalArgumentException("SKILL.md name must match the top-level skill directory");
        }
        if (description.isEmpty()) {
            throw new IllegalArgumentException("SKILL.md description is required");
        }

        Map<?, ?> metadata = object(frontmatter.get("metadata"));
        String version = firstNonblank(frontmatter.get("version"), metadata.get("version"),
                metadata.get("builtin_skill_version"));
        Map<?, ?> qwenpaw = object(metadata.get("qwenpaw"));
        String emoji = string(qwenpaw.get("emoji"));
        Object requires = null;
        for (String namespace : List.of("openclaw", "qwenpaw", "clawdbot")) {
            Map<?, ?> provider = object(metadata.get(namespace));
            if (provider.containsKey("requires")) {
                requires = provider.get("requires");
                break;
            }
        }
        if (requires == null) {
            requires = metadata.containsKey("requires") ? metadata.get("requires") : frontmatter.get("requires");
        }
        List<String> bins = List.of();
        List<String> envs = List.of();
        if (requires instanceof List<?> list) {
            bins = strings(list);
        } else if (requires instanceof Map<?, ?> required) {
            bins = strings(required.get("bins"));
            envs = strings(required.get("env"));
        }
        long modified = Math.max(Files.getLastModifiedTime(skillDirectory).toMillis(),
                Files.getLastModifiedTime(skillMd).toMillis());
        return new SkillMetadata(description, version, emoji, bins, envs, Instant.ofEpochMilli(modified).toString());
    }

    static Path prepareUpdate(Path manifest, String skillName, SkillMetadata metadata) throws IOException {
        if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(manifest))) {
            throw new IOException("skill.json is not a regular file: " + manifest);
        }
        ObjectNode root;
        if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
            JsonNode parsed = JSON.readTree(manifest.toFile());
            if (!(parsed instanceof ObjectNode existing)) {
                throw new IOException("skill.json must contain a JSON object: " + manifest);
            }
            root = existing;
            if (!SCHEMA.equals(root.path("schema_version").asText())) {
                throw new IOException("unsupported skill.json schema: " + manifest);
            }
        } else {
            root = JSON.createObjectNode();
            root.put("schema_version", SCHEMA);
        }
        JsonNode skillsNode = root.get("skills");
        if (skillsNode != null && !(skillsNode instanceof ObjectNode)) {
            throw new IOException("skill.json skills must be an object: " + manifest);
        }
        ObjectNode skills = skillsNode == null ? root.putObject("skills") : (ObjectNode) skillsNode;
        JsonNode oldNode = skills.get(skillName);
        if (oldNode != null && !(oldNode instanceof ObjectNode)) {
            throw new IOException("skill.json entry must be an object: " + skillName);
        }
        ObjectNode entry = oldNode == null ? JSON.createObjectNode() : ((ObjectNode) oldNode).deepCopy();
        if (!entry.path("enabled").isBoolean()) {
            entry.put("enabled", true);
        }
        if (!entry.path("channels").isArray() || entry.path("channels").isEmpty()) {
            ArrayNode channels = entry.putArray("channels");
            channels.add("all");
        }
        entry.put("source", "customized");
        entry.put("installed_from", "zip");
        ObjectNode info = JSON.createObjectNode();
        info.put("name", skillName);
        info.put("description", metadata.description());
        info.put("version_text", metadata.version());
        info.put("emoji", metadata.emoji());
        info.put("commit_text", "");
        info.put("source", "customized");
        info.put("protected", false);
        info.set("requirements", requirements(metadata));
        info.put("updated_at", metadata.updatedAt());
        entry.set("metadata", info);
        entry.set("requirements", requirements(metadata));
        entry.put("updated_at", metadata.updatedAt());
        skills.set(skillName, entry);

        JsonNode versionNode = root.get("version");
        if (versionNode != null && !versionNode.canConvertToLong()) {
            throw new IOException("skill.json version must be an integer: " + manifest);
        }
        long previousVersion = versionNode == null ? 0 : versionNode.longValue();
        root.put("version", Math.max(previousVersion + 1, System.currentTimeMillis()));

        Path temporary = Files.createTempFile(manifest.getParent(), ".skill-manifest-", ".json");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
            preserveFileIdentity(temporary, manifest);
            return temporary;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
    }

    private static void preserveFileIdentity(Path temporary, Path manifest) throws IOException {
        boolean existing = Files.exists(manifest, LinkOption.NOFOLLOW_LINKS);
        Path attributesFrom = existing ? manifest : manifest.getParent();
        PosixFileAttributes attributes = Files.readAttributes(attributesFrom, PosixFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        PosixFileAttributeView view = Files.getFileAttributeView(temporary, PosixFileAttributeView.class);
        view.setOwner(attributes.owner());
        view.setGroup(attributes.group());
        if (existing) {
            view.setPermissions(attributes.permissions());
        } else {
            view.setPermissions(newFilePermissions());
        }
    }

    private static EnumSet<PosixFilePermission> newFilePermissions() {
        return EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_READ);
    }

    static void commit(Path temporary, Path manifest) throws IOException {
        try {
            Files.move(temporary, manifest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, manifest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static ManifestLock lock(Path manifest) throws IOException {
        Path lockPath = manifest.resolveSibling(".skill.json.lock");
        boolean created = false;
        try {
            Files.createFile(lockPath);
            created = true;
        } catch (FileAlreadyExistsException ignored) {
            // QwenPaw keeps this lock file between writes.
        }
        if (created) {
            PosixFileAttributes attributes = Files.readAttributes(manifest.getParent(), PosixFileAttributes.class);
            PosixFileAttributeView view = Files.getFileAttributeView(lockPath, PosixFileAttributeView.class);
            view.setOwner(attributes.owner());
            view.setGroup(attributes.group());
            view.setPermissions(newFilePermissions());
        }
        if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(lockPath)) {
            throw new IOException("skill.json lock is not a regular file: " + lockPath);
        }
        int descriptor = LIBC.open(lockPath.toString(), 0);
        if (descriptor < 0) {
            throw new IOException("cannot open skill.json lock, errno=" + Native.getLastError());
        }
        if (LIBC.flock(descriptor, 2) != 0) {
            int error = Native.getLastError();
            LIBC.close(descriptor);
            throw new IOException("cannot lock skill.json, errno=" + error);
        }
        return new ManifestLock(descriptor);
    }

    private static ObjectNode requirements(SkillMetadata metadata) {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode bins = result.putArray("require_bins");
        metadata.bins().forEach(bins::add);
        ArrayNode envs = result.putArray("require_envs");
        metadata.envs().forEach(envs::add);
        return result;
    }

    private static int lineEnd(byte[] bytes, int from) {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static boolean isDelimiter(byte[] bytes, int start, int end, boolean allowBom) {
        if (allowBom && end - start >= 3 && (bytes[start] & 0xff) == 0xef
                && (bytes[start + 1] & 0xff) == 0xbb && (bytes[start + 2] & 0xff) == 0xbf) {
            start += 3;
        }
        while (start < end && (bytes[start] == ' ' || bytes[start] == '\r' || bytes[start] == '\t')) {
            start++;
        }
        while (end > start && (bytes[end - 1] == ' ' || bytes[end - 1] == '\r' || bytes[end - 1] == '\t')) {
            end--;
        }
        return end - start == 3 && bytes[start] == '-' && bytes[start + 1] == '-' && bytes[start + 2] == '-';
    }

    private static String decodeFrontmatter(byte[] bytes, int offset, int length) {
        try {
            return decode(bytes, offset, length, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            try {
                return decode(bytes, offset, length, LEGACY_CHARSET);
            } catch (CharacterCodingException fallback) {
                throw new IllegalArgumentException("SKILL.md frontmatter must use UTF-8 or GB18030", fallback);
            }
        }
    }

    private static String decode(byte[] bytes, int offset, int length, Charset charset)
            throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length)).toString();
    }

    private static Map<?, ?> object(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String firstNonblank(Object... values) {
        for (Object value : values) {
            if (!string(value).isBlank()) {
                return string(value);
            }
        }
        return "";
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!string(item).isBlank()) {
                result.add(string(item));
            }
        }
        return List.copyOf(result);
    }

    record SkillMetadata(String description, String version, String emoji, List<String> bins,
                         List<String> envs, String updatedAt) {
    }

    static final class ManifestLock implements AutoCloseable {
        private final int descriptor;

        private ManifestLock(int descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public void close() {
            int unlock = LIBC.flock(descriptor, 8);
            int close = LIBC.close(descriptor);
            if (unlock != 0 || close != 0) {
                log.warn("Cannot release skill.json lock cleanly, errno={}", Native.getLastError());
            }
        }
    }

    private interface LibC extends Library {
        int open(String path, int flags);

        int flock(int descriptor, int operation);

        int close(int descriptor);
    }
}
