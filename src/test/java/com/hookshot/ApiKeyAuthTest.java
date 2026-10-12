package com.hookshot;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "hookshot.api-key=s3cret-key")
@AutoConfigureMockMvc
class ApiKeyAuthTest {

    @Autowired MockMvc mvc;

    @Test
    void rejectsRequestsWithoutKey() throws Exception {
        mvc.perform(get("/api/stats")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsRequestsWithWrongKey() throws Exception {
        mvc.perform(get("/api/stats").header("X-API-Key", "nope")).andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsRequestsWithCorrectKey() throws Exception {
        mvc.perform(get("/api/stats").header("X-API-Key", "s3cret-key")).andExpect(status().isOk());
    }

    @Test
    void healthCheckStaysOpenForOrchestrators() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
