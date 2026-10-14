package com.qwenpaw.controller.web;

import com.qwenpaw.controller.model.SkillPackageInfo;
import com.qwenpaw.controller.model.SkillPublishRequest;
import com.qwenpaw.controller.model.SkillPublishResult;
import com.qwenpaw.controller.service.SkillReleaseService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillReleaseControllerTest {

    private final SkillReleaseService service = mock(SkillReleaseService.class);
    private final SkillReleaseController controller = new SkillReleaseController(service);

    @Test
    void returnsCreatedForValidUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "demo.zip", "application/zip", new byte[]{1});
        SkillPackageInfo info = new SkillPackageInfo();
        info.setSkillName("demo");
        when(service.upload(file)).thenReturn(info);

        var response = controller.upload(file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(info);
    }

    @Test
    void mapsInvalidUploadToBadRequest() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "bad.txt", "text/plain", new byte[]{1});
        when(service.upload(file)).thenThrow(new IllegalArgumentException("skill package must be a .zip file"));

        assertThatThrownBy(() -> controller.upload(file))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void returnsPublishSummary() throws Exception {
        SkillPublishRequest request = new SkillPublishRequest();
        SkillPublishResult result = new SkillPublishResult();
        result.setSuccess(true);
        when(service.publish(request)).thenReturn(result);

        assertThat(controller.publish(request)).isSameAs(result);
    }
}
