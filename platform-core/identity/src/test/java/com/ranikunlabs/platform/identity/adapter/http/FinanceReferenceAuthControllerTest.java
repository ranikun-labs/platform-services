package com.ranikunlabs.platform.identity.adapter.http;

import com.ranikunlabs.platform.identity.application.FinanceReferenceAccessService;
import com.ranikunlabs.platform.identity.application.port.IssuedFinanceReferenceAccessToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FinanceReferenceAuthControllerTest {

    private FinanceReferenceAccessService service;
    private MockMvc mvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        service = mock(FinanceReferenceAccessService.class);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new FinanceReferenceAuthController(service))
                .addFilters(new FinanceReferenceRequestBodyLimitFilter(JsonMapper.builder().build()))
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void successIsNoStoreAccessOnlyEnvelope() throws Exception {
        when(service.authenticate("selected-user", "exact-password"))
                .thenReturn(new IssuedFinanceReferenceAccessToken(
                        "public-dummy-access-token", Instant.parse("2026-11-06T15:00:00Z")));

        mvc.perform(post(FinanceReferenceAuthController.PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"selected-user\",\"password\":\"exact-password\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.data.accessToken").value("public-dummy-access-token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-11-06T15:00:00Z"))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist());
        verify(service).authenticate("selected-user", "exact-password");
    }

    @Test
    void rejectsRawBodyAbove2048BytesBeforeAuthentication() throws Exception {
        String base = "{\"userId\":\"u\",\"password\":\"p\"}";
        String oversized = base + " ".repeat(2049 - base.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

        assertBadRequest(oversized);
        verifyNoInteractions(service);
    }

    @Test
    void rejectsUnknownFieldsDuplicateKeysMalformedJsonAndBlankFields() throws Exception {
        assertBadRequest("{\"userId\":\"u\",\"password\":\"p\",\"extra\":true}");
        assertBadRequest("{\"userId\":\"u\",\"userId\":\"other\",\"password\":\"p\"}");
        assertBadRequest("{\"userId\":\"u\",\"password\":\"p\"} {} ");
        assertBadRequest("{\"userId\":\" \",\"password\":\"p\"}");
        assertBadRequest("{\"userId\":\"u\",\"password\":\"  \"}");
        verifyNoInteractions(service);
    }

    @Test
    void rejectsPerFieldUtf8ByteLimitBeforeAuthentication() throws Exception {
        assertBadRequest("{\"userId\":\"" + "u".repeat(129) + "\",\"password\":\"p\"}");
        assertBadRequest("{\"userId\":\"u\",\"password\":\"" + "p".repeat(73) + "\"}");
        verifyNoInteractions(service);
    }

    private void assertBadRequest(String body) throws Exception {
        mvc.perform(post(FinanceReferenceAuthController.PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}
