package com.qwenpaw.controller.web;

import com.qwenpaw.controller.model.SkillPackageInfo;
import com.qwenpaw.controller.model.SkillPublishRequest;
import com.qwenpaw.controller.model.SkillPublishResult;
import com.qwenpaw.controller.model.SkillReleaseOverviewResponse;
import com.qwenpaw.controller.service.SkillReleaseService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.NoSuchFileException;

/**
 * 管理技能发布包上传、查询和批量下发。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class SkillReleaseController {

    private final SkillReleaseService skillReleaseService;

    public SkillReleaseController(SkillReleaseService skillReleaseService) {
        this.skillReleaseService = skillReleaseService;
    }

    @GetMapping("/skill-packages")
    public SkillReleaseOverviewResponse getOverview() {
        try {
            return skillReleaseService.getOverview();
        } catch (NoSuchFileException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, fileErrorMessage(e), e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to read skill packages", e);
        }
    }

    @PostMapping(value = "/skill-packages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SkillPackageInfo> upload(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(skillReleaseService.upload(file));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (NoSuchFileException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, fileErrorMessage(e), e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to store skill package: " + e.getMessage(), e);
        }
    }

    @PostMapping("/skill-publications")
    public SkillPublishResult publish(@RequestBody SkillPublishRequest request) {
        try {
            return skillReleaseService.publish(request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (NoSuchFileException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill package not found", e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to publish skill: " + e.getMessage(), e);
        }
    }

    private String fileErrorMessage(NoSuchFileException e) {
        String reason = e.getReason();
        return reason == null || reason.isBlank() ? e.getFile() : reason + ": " + e.getFile();
    }
}
