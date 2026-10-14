package com.qwenpaw.controller.service;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        MockMultipartFile upload = zipUpload("demo.zip", Map.of(
                "demo/SKILL.md", "# Demo\n",
                "demo/scripts/run.py", "print('ok')\n"));

        SkillPackageInfo info = service.upload(upload);

        assertThat(info.getSkillName()).isEqualTo("demo");
        assertThat(info.isCurrent()).isTrue();
        assertThat(info.getEntryCount()).isEqualTo(2);
        assertThat(templateSkillsRoot.resolve("demo/SKILL.md")).hasContent("# Demo\n");
        assertThat(templateSkillsRoot.resolve("demo/scripts/run.py")).exists();
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
                "demo/SKILL.md", "# Demo\n",
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
    void keepsImmutablePackagesAndMarksLatestTemplateVersionCurrent() throws IOException {
        SkillPackageInfo first = service.upload(zipUpload("demo-v1.zip", Map.of(
                "demo/SKILL.md", "# Version 1",
                "demo/removed.txt", "old")));
        SkillPackageInfo second = service.upload(zipUpload("demo-v2.zip", Map.of(
                "demo/SKILL.md", "# Version 2",
                "demo/current.txt", "new")));

        assertThat(first.getPackageDigest()).isNotEqualTo(second.getPackageDigest());
        assertThat(templateSkillsRoot.resolve("demo/SKILL.md")).hasContent("# Version 2");
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
    void rejectsPackageWithoutTopLevelSkillMd() {
        MockMultipartFile upload = zipUpload("demo.zip", Map.of(
                "demo/README.md", "missing skill metadata"));

        assertThatThrownBy(() -> service.upload(upload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKILL.md");
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
        Files.createDirectories(personalDataRoot.resolve("bob"));
        SkillPackageInfo info = service.upload(zipUpload("demo.zip", Map.of(
                "demo/SKILL.md", "# Version 2\n",
                "demo/new.txt", "new")));

        SkillPublishRequest request = publishRequest("demo", info.getPackageDigest(), List.of("alice", "bob"));
        SkillPublishResult result = service.publish(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getInstalledCount()).isEqualTo(1);
        assertThat(result.getUpdatedCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isZero();
        assertThat(userSkills("alice").resolve("demo/old.txt")).doesNotExist();
        assertThat(userSkills("alice").resolve("demo/SKILL.md")).hasContent("# Version 2\n");
        assertThat(userSkills("bob").resolve("demo/new.txt")).hasContent("new");
    }

    @Test
    void reportsMissingUserWithoutRollingBackSuccessfulUsers() throws IOException {
        Files.createDirectories(personalDataRoot.resolve("alice"));
        SkillPackageInfo info = service.upload(zipUpload("demo.zip", Map.of(
                "demo/SKILL.md", "# Demo")));

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

    private Path userSkills(String userId) {
        return personalDataRoot.resolve(userId + "/working/workspaces/default/skills");
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
