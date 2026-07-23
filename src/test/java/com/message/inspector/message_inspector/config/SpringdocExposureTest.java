package com.message.inspector.message_inspector.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the springdoc prod-exposure toggle: the base config disables the API docs
 * ({@code springdoc.*.enabled=false} in application.properties) and only the {@code dev}
 * profile -- the default profile -- switches them back on. A deployment that sets
 * {@code SPRING_PROFILES_ACTIVE} to anything else therefore serves no Swagger assets,
 * which is what makes the springdoc RetireJS findings from the Phase 2 scan suppressible.
 * <p>
 * The routes stay permitted in {@code SecurityConfig} either way; disabled docs answer
 * 404, and permitting a nonexistent route is harmless.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SpringdocExposureTest {

  /**
   * No active profile set, so {@code spring.profiles.default=dev} applies and
   * application-dev.properties re-enables the docs -- the fresh-clone experience.
   */
  @Nested
  class DevProfileDefault {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocsAreServedOnTheDevProfile() throws Exception {
      mockMvc.perform(get("/api/api-docs"))
        .andExpect(status().isOk());
    }
  }

  /**
   * The base-config values, as any non-dev deployment gets them. Set directly rather
   * than via a profile so the test pins the flags themselves, not profile plumbing.
   */
  @Nested
  @TestPropertySource(properties = {
    "springdoc.api-docs.enabled=false",
    "springdoc.swagger-ui.enabled=false"
  })
  class ProdLikeConfig {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocsAre404OutsideDev() throws Exception {
      mockMvc.perform(get("/api/api-docs"))
        .andExpect(status().isNotFound());
    }
  }
}
