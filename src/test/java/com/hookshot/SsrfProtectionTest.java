package com.hookshot;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** With the production setting (allow-private-targets=false) internal addresses must be refused. */
@SpringBootTest(properties = "hookshot.allow-private-targets=false")
@AutoConfigureMockMvc
class SsrfProtectionTest {

    @Autowired MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {
        "http://127.0.0.1:8080/hook",
        "http://10.0.0.5/hook",
        "http://192.168.1.10/hook",
        "http://169.254.169.254/latest/meta-data/",
        "http://[::1]/hook",
        "file:///etc/passwd"
    })
    void refusesToRegisterInternalTargets(String url) throws Exception {
        mvc.perform(post("/api/endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid webhook target"));
    }
}
