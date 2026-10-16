package com.qwenpaw.controller.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwenpaw.controller.config.QwenPawProperties;
import com.qwenpaw.controller.model.SkillPackageInfo;
import com.qwenpaw.controller.model.SkillPublishRequest;
import com.qwenpaw.controller.model.SkillPublishResult;
import com.qwenpaw.controller.model.SkillReleaseOverviewResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillReleaseServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDirectory;

    private Path personalDataRoot;
    private Path templateSkillsRoot;
    private SkillReleaseService service;

    @BeforeEach
    void setUp() throws IOException {
        personalDataRoot = tempDirectory.resolve("personalData");
        templateSkillsRoot = personalDataRoot.resolve("public-secret/working/workspaces/default/skills");
        Files.createDirectories(templateSkillsRoot);

        QwenPawProperties properties = new QwenPawProperties();
        properties.setPersonalDataMountPath(personalDataRoot.toString());
        properties.setQwenpawPublicTemplateSubPath("public-secret");
        properties.setSkillPackageMaxSizeBytes(1024 * 1024);
        properties.setSkillPackageMaxExpandedSizeBytes(4 * 1024 * 1024);
        properties.setSkillPackageMaxEntries(100);
        properties.setSkillPublishConcurrency(2);
        properties.setSkillPublishMaxUsers(10);

        KubernetesLeaseLock leaseLock = mock(KubernetesLeaseLock.class);
        when(leaseLock.withLock(anyString(), any())).thenAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(1);
            return action.get();
        });
        service = new SkillReleaseService(properties, leaseLock, Executors.newFixedThreadPool(2));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void uploadsValidatedPackageAndUpdatesTemplateSkill() throws IOException {
        Files.createDirectories(personalDataRoot.resolve("alice"));
        Path templateManifestPath = templateSkillsRoot.getParent().resolve("skill.json");
        Files.writeString(templateManifestPath, """
                {"schema_version":"workspace-skill-manifest.v1","version":42,
                 "skills":{"other":{"enabled":false,"metadata":{"description":"keep"}}},
                 "custom_key":"preserve"}
                """);
        PosixFileAttributes originalAttributes = Files.readAttributes(templateManifestPath, PosixFileAttributes.class);
        MockMultipartFile upload = zipUpload("demo.zip", Map.of(
                "demo/SKILL.md", skillMd("demo", "A demo skill"),
                "demo/scripts/run.py", "print('ok')\n"));

        SkillPackageInfo info = service.upload(upload);

        assertThat(info.getSkillName()).isEqualTo("demo");
        assertThat(info.isCurrent()).isTrue();
        assertThat(info.getEntryCount()).isEqualTo(2);
        assertThat(templateSkillsRoot.resolve("demo/SKILL.md")).hasContent(skillMd("demo", "A demo skill"));
        assertThat(templateSkillsRoot.resolve("demo/scripts/run.py")).exists();
        JsonNode templateManifest = manifest(templateSkillsRoot);
        assertThat(templateManifest.path("schema_version").asText()).isEqualTo("workspace-skill-manifest.v1");
        assertThat(templateManifest.at("/skills/demo/metadata/name").asText()).isEqualTo("demo");
        assertThat(templateManifest.at("/skills/demo/metadata/description").asText()).isEqualTo("A demo skill");
        assertThat(templateManifest.at("/skills/demo/enabled").asBoolean()).isTrue();
        assertThat(templateManifest.at("/skills/demo/installed_from").asText()).isEqualTo("zip");
        assertThat(templateManifest.at("/skills/other/metadata/description").asText()).isEqualTo("keep");
        assertThat(templateManifest.path("custom_key").asText()).isEqualTo("preserve");
        assertThat(templateManifest.path("version").asLong()).isGreaterThan(42);
        PosixFileAttributes updatedAttributes = Files.readAttributes(templateManifestPath, PosixFileAttributes.class);
        assertThat(updatedAttributes.owner()).isEqualTo(originalAttributes.owner());
        assertThat(updatedAttributes.group()).isEqualTo(originalAttributes.group());
        assertThat(updatedAttributes.permissions()).isEqualTo(originalAttributes.permissions());
        assertThat(Files.getPosixFilePermissions(templateManifestPath.resolveSibling(".skill.json.lock")))
                .contains(PosixFilePermission.GROUP_WRITE);
        assertThat(personalDataRoot.resolve("public-secret/.skill-packages/demo/"
                + info.getPackageDigest() + ".zip")).isRegularFile();

        SkillReleaseOverviewResponse overview = service.getOverview();
        assertThat(overview.getUsers()).containsExactly("alice");
        assertThat(overview.getPackages()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getSkillName()).isEqualTo("demo");
                    assertThat(item.isCurrent()).isTrue();
                });
    }

    @Test
    void acceptsGb18030EncodedZipEntryNames() throws IOException {
        MockMultipartFile upload = zipUpload("demo.zip", Map.of(
                "demo/SKILL.md", skillMd("demo", "A demo skill"),
                "demo/说明.txt", "中文说明\n"), Charset.forName("GB18030"));

        SkillPackageInfo info = service.upload(upload);

        assertThat(info.getSkillName()).isEqualTo("demo");
        assertThat(templateSkillsRoot.resolve("demo/说明.txt")).hasContent("中文说明\n");
        assertThat(service.getOverview().getPackages()).singleElement()
                .satisfies(item -> {
                    assertThat(item.getSkillName()).isEqualTo("demo");
                    assertThat(item.getPackageDigest()).isEqualTo(info.getPackageDigest());
                    assertThat(item.getEntryCount()).isEqualTo(2);
                    assertThat(item.isCurrent()).isTrue();
                });
    }

    @Test
    void copiesFrontmatterVersionAndRequirementsIntoManifest() throws IOException {
        String content = """
                ---
                name: 机票预订
                description: 查询并推荐航班，确认后生成机票确认页。
                version: "1.2"
                metadata:
                  qwenpaw:
                    requires:
                      bins: [python3]
                      env: [FLIGHT_API_KEY]
                ---
                # 机票预订
                """;

        service.upload(zipUpload("flights.zip", Map.of("机票预订/SKILL.md", content)));

        JsonNode entry = manifest(templateSkillsRoot).path("skills").path("机票预订");
        assertThat(entry.path("metadata").path("name").asText()).isEqualTo("机票预订");
        assertThat(entry.path("metadata").path("description").asText())
                .isEqualTo("查询并推荐航班，确认后生成机票确认页。");
        assertThat(entry.path("metadata").path("version_text").asText()).isEqualTo("1.2");
        assertThat(entry.path("requirements").path("require_bins").get(0).asText()).isEqualTo("python3");
        assertThat(entry.path("metadata").path("requirements").path("require_envs").get(0).asText())
                .isEqualTo("FLIGHT_API_KEY");
    }

    @Test
    void keepsImmutablePackagesAndMarksLatestTemplateVersionCurrent() throws IOException {
        SkillPackageInfo first = service.upload(zipUpload("demo-v1.zip", Map.of(
                "demo/SKILL.md", skillMd("demo", "First version"),
                "demo/removed.txt", "old")));
        SkillPackageInfo second = service.upload(zipUpload("demo-v2.zip", Map.of(
                "demo/SKILL.md", skillMd("demo", "Second version"),
                "demo/current.txt", "new")));

        assertThat(first.getPackageDigest()).isNotEqualTo(second.getPackageDigest());
        assertThat(templateSkillsRoot.resolve("demo/SKILL.md")).hasContent(skillMd("demo", "Second version"));
        assertThat(manifest(templateSkillsRoot).at("/skills/demo/metadata/description").asText())
                .isEqualTo("Second version");
        assertThat(templateSkillsRoot.resolve("demo/removed.txt")).doesNotExist();
        assertThat(templateSkillsRoot.resolve("demo/current.txt")).exists();
        assertThat(service.getOverview().getPackages())
                .hasSize(2)
                .filteredOn(SkillPackageInfo::isCurrent)
                .singleElement()
                .extracting(SkillPackageInfo::getPackageDigest)
                .isEqualTo(second.getPackageDigest());
    }

    @Test
    void publishesSelectedOlderPackageMetadataInsteadOfCurrentTemplateMetadata() throws IOException {
        Files.createDirectories(personalDataRoot.resolve("alice"));
        SkillPackageInfo first = service.upload(zipUpload("demo-v1.zip", Map.of(
                "demo/SKILL.md", skillMd("demo", "First version"))));
        service.upload(zipUpload("demo-v2.zip", Map.of(
                "demo/SKILL.md", skillMd("demo", "Second version"))));

        SkillPublishResult result = service.publish(
                publishRequest("demo", first.getPackageDigest(), List.of("alice")));

        assertThat(result.isSuccess()).isTrue();
        assertThat(manifest(userSkills("alice")).at("/skills/demo/metadata/description").asText())
                .isEqualTo("First version");
        assertThat(manifest(templateSkillsRoot).at("/skills/demo/metadata/description").asText())
                .isEqualTo("Second version");
    }

    @Test
    void rejectsMismatchedFrontmatterNameBeforeChangingTemplate() throws IOException {
        SkillPackageInfo first = service.upload(zipUpload("demo-v1.zip", Map.of(
                "demo/SKILL.md", skillMd("demo", "First version"))));

        assertThatThrownBy(() -> service.upload(zipUpload("demo-v2.zip", Map.of(
                "demo/SKILL.md", skillMd("other", "Wrong name")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name must match");

        assertThat(templateSkillsRoot.resolve("demo/SKILL.md")).hasContent(skillMd("demo", "First version"));
        assertThat(manifest(templateSkillsRoot).at("/skills/demo/metadata/description").asText())
                .isEqualTo("First version");
        assertThat(service.getOverview().getPackages()).singleElement()
                .extracting(SkillPackageInfo::getPackageDigest).isEqualTo(first.getPackageDigest());
    }

    @Test
    void rollsBackTemplateSkillWhenManifestCannotBeUpdated() throws IOException {
        SkillPackageInfo first = service.upload(zipUpload("demo-v1.zip", Map.of(
                "demo/SKILL.md", skillMd("demo", "First version"))));
        Path manifestPath = templateSkillsRoot.getParent().resolve("skill.json");
        Files.writeString(manifestPath, "invalid json");

        assertThatThrownBy(() -> service.upload(zipUpload("demo-v2.zip", Map.of(
                "demo/SKILL.md", skillMd("demo", "Second version")))))
                .isInstanceOf(IOException.class);

        assertThat(templateSkillsRoot.resolve("demo/SKILL.md")).hasContent(skillMd("demo", "First version"));
        assertThat(Files.readString(manifestPath)).isEqualTo("invalid json");
        assertThat(service.getOverview().getPackages())
                .filteredOn(SkillPackageInfo::isCurrent)
                .singleElement().extracting(SkillPackageInfo::getPackageDigest)
                .isEqualTo(first.getPackageDigest());
    }

    @Test
    void rejectsPackageWithoutTopLevelSkillMd() {
        MockMultipartFile upload = zipUpload("demo.zip", Map.of(
                "demo/README.md", "missing skill metadata"));

        assertThatThrownBy(() -> service.upload(upload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKILL.md");
    }

    @Test
    void rejectsSkillWithoutNameAndDescriptionFrontmatter() {
        assertThatThrownBy(() -> service.upload(zipUpload("demo.zip", Map.of(
                "demo/SKILL.md", "# Demo without frontmatter"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frontmatter");
        assertThat(templateSkillsRoot.resolve("demo")).doesNotExist();
    }

    @Test
    void rejectsMultipleTopLevelDirectories() {
        MockMultipartFile upload = zipUpload("demo.zip", Map.of(
                "demo/SKILL.md", "# Demo",
                "other/file.txt", "unexpected"));

        assertThatThrownBy(() -> service.upload(upload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one top-level");
    }

    @Test
    void rejectsParentTraversalEntry() {
        MockMultipartFile upload = zipUpload("demo.zip", Map.of(
                "demo/SKILL.md", "# Demo",
                "demo/../outside.txt", "unexpected"));

        assertThatThrownBy(() -> service.upload(upload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent traversal");
        assertThat(tempDirectory.resolve("outside.txt")).doesNotExist();
    }

    @Test
    void publishesConcurrentlyByInstallingAndReplacingUserSkills() throws IOException {
        Path aliceSkill = userSkills("alice").resolve("demo");
        Files.createDirectories(aliceSkill);
        Files.writeString(aliceSkill.resolve("old.txt"), "old");
        Path aliceManifest = userSkills("alice").getParent().resolve("skill.json");
        Files.writeString(aliceManifest, """
                {"schema_version":"workspace-skill-manifest.v1","version":7,"skills":{
                  "demo":{"enabled":false,"channels":["dingtalk"],"config":{"key":"keep"}},
                  "other":{"enabled":true}
                }}
                """);
        Files.createDirectories(personalDataRoot.resolve("bob"));
        SkillPackageInfo info = service.upload(zipUpload("demo.zip", Map.of(
                "demo/SKILL.md", skillMd("demo", "Second version"),
                "demo/new.txt", "new")));

        SkillPublishRequest request = publishRequest("demo", info.getPackageDigest(), List.of("alice", "bob"));
        SkillPublishResult result = service.publish(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getInstalledCount()).isEqualTo(1);
        assertThat(result.getUpdatedCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isZero();
        assertThat(userSkills("alice").resolve("demo/old.txt")).doesNotExist();
        assertThat(userSkills("alice").resolve("demo/SKILL.md")).hasContent(skillMd("demo", "Second version"));
        assertThat(userSkills("bob").resolve("demo/new.txt")).hasContent("new");
        JsonNode alice = manifest(userSkills("alice"));
        assertThat(alice.at("/skills/demo/enabled").asBoolean()).isFalse();
        assertThat(alice.at("/skills/demo/channels/0").asText()).isEqualTo("dingtalk");
        assertThat(alice.at("/skills/demo/config/key").asText()).isEqualTo("keep");
        assertThat(alice.at("/skills/demo/metadata/description").asText()).isEqualTo("Second version");
        assertThat(alice.at("/skills/other/enabled").asBoolean()).isTrue();
        JsonNode bob = manifest(userSkills("bob"));
        assertThat(bob.at("/skills/demo/enabled").asBoolean()).isTrue();
        assertThat(bob.at("/skills/demo/metadata/name").asText()).isEqualTo("demo");
        assertThat(bob.at("/skills/demo/installed_from").asText()).isEqualTo("zip");
    }

    @Test
    void reportsMissingUserWithoutRollingBackSuccessfulUsers() throws IOException {
        Files.createDirectories(personalDataRoot.resolve("alice"));
        SkillPackageInfo info = service.upload(zipUpload("demo.zip", Map.of(
                "demo/SKILL.md", skillMd("demo", "A demo skill"))));

        SkillPublishResult result = service.publish(
                publishRequest("demo", info.getPackageDigest(), List.of("alice", "missing")));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getInstalledCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(userSkills("alice").resolve("demo/SKILL.md")).exists();
        assertThat(result.getResults())
                .anySatisfy(item -> {
                    assertThat(item.getUserId()).isEqualTo("missing");
                    assertThat(item.getStatus()).isEqualTo("FAILED");
                });
    }

    @Test
    void concurrentDifferentSkillsForOneUserKeepBothManifestEntries() throws IOException {
        Files.createDirectories(personalDataRoot.resolve("alice"));
        SkillPackageInfo first = service.upload(zipUpload("alpha.zip", Map.of(
                "alpha/SKILL.md", skillMd("alpha", "First skill"))));
        SkillPackageInfo second = service.upload(zipUpload("beta.zip", Map.of(
                "beta/SKILL.md", skillMd("beta", "Second skill"))));

        CompletableFuture<SkillPublishResult> alpha = CompletableFuture.supplyAsync(() -> {
            try {
                return service.publish(publishRequest("alpha", first.getPackageDigest(), List.of("alice")));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        CompletableFuture<SkillPublishResult> beta = CompletableFuture.supplyAsync(() -> {
            try {
                return service.publish(publishRequest("beta", second.getPackageDigest(), List.of("alice")));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(alpha.join().isSuccess()).isTrue();
        assertThat(beta.join().isSuccess()).isTrue();
        JsonNode manifest = manifest(userSkills("alice"));
        assertThat(manifest.at("/skills/alpha/metadata/description").asText()).isEqualTo("First skill");
        assertThat(manifest.at("/skills/beta/metadata/description").asText()).isEqualTo("Second skill");
    }

    @Test
    void invalidUserManifestDoesNotReplaceExistingSkillDirectory() throws IOException {
        Path target = userSkills("alice").resolve("demo");
        Files.createDirectories(target);
        Files.writeString(target.resolve("original.txt"), "keep");
        Files.writeString(userSkills("alice").getParent().resolve("skill.json"), "invalid json");
        SkillPackageInfo info = service.upload(zipUpload("demo.zip", Map.of(
                "demo/SKILL.md", skillMd("demo", "New version"))));

        SkillPublishResult result = service.publish(
                publishRequest("demo", info.getPackageDigest(), List.of("alice")));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(target.resolve("original.txt")).hasContent("keep");
        assertThat(target.resolve("SKILL.md")).doesNotExist();
    }

    private Path userSkills(String userId) {
        return personalDataRoot.resolve(userId + "/working/workspaces/default/skills");
    }

    private JsonNode manifest(Path skillsRoot) throws IOException {
        return JSON.readTree(skillsRoot.getParent().resolve("skill.json").toFile());
    }

    private String skillMd(String name, String description) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n\n# " + name + "\n";
    }

    private SkillPublishRequest publishRequest(String skillName, String digest, List<String> users) {
        SkillPublishRequest request = new SkillPublishRequest();
        request.setSkillName(skillName);
        request.setPackageDigest(digest);
        request.setUserIds(users);
        return request;
    }

    private MockMultipartFile zipUpload(String fileName, Map<String, String> entries) {
        return zipUpload(fileName, entries, StandardCharsets.UTF_8);
    }

    private MockMultipartFile zipUpload(String fileName,
                                        Map<String, String> entries,
                                        Charset entryNameCharset) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes, entryNameCharset)) {
                for (Map.Entry<String, String> entry : new LinkedHashMap<>(entries).entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return new MockMultipartFile("file", fileName, "application/zip", bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
