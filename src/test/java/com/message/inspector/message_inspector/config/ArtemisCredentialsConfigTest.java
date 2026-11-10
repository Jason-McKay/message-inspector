package com.message.inspector.message_inspector.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;

/**
 * Pins the broker password as required (since 2026-11-10). It used to default to admin in
 * three places, so a missing ARTEMIS_PASSWORD silently tried admin against whichever broker
 * was on 61616. Now the shared broker's password lives in the DPAPI store, and a missing
 * value must fail startup on the placeholder instead.
 * <p>
 * Reads the real main files rather than a Spring context: Surefire sets a test-only
 * ARTEMIS_PASSWORD for the other contexts, which would hide exactly the default this
 * checks for. compose.yaml is not checked: its bundled broker is a throwaway with its own
 * admin/admin, passed to the app explicitly.
 */
class ArtemisCredentialsConfigTest {

  private static final Path PROPERTIES = Path.of("src/main/resources/application.properties");
  private static final Path ARTEMIS_CONFIG =
      Path.of("src/main/java/com/message/inspector/message_inspector/config/ArtemisConfig.java");

  @Test
  void brokerPasswordHasNoDefault() throws IOException {
    Properties props = load();
    assertThat(props.getProperty("spring.artemis.password")).isEqualTo("${ARTEMIS_PASSWORD}");
    assertThat(props.getProperty("jolokia.password"))
      .isEqualTo("${JOLOKIA_PASSWORD:${spring.artemis.password}}");
  }

  @Test
  void artemisConfigValueHasNoDefault() throws IOException {
    String source = Files.readString(ARTEMIS_CONFIG, StandardCharsets.UTF_8);
    assertThat(source).contains("@Value(\"${spring.artemis.password}\")");
    assertThat(source).doesNotContain("spring.artemis.password:");
  }

  @Test
  void missingPasswordFailsOnThePlaceholder() throws IOException {
    PropertySourcesPropertyResolver resolver = resolver(Map.of());
    assertThatThrownBy(() -> resolver.getProperty("spring.artemis.password"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("ARTEMIS_PASSWORD");
    assertThatThrownBy(() -> resolver.getProperty("jolokia.password"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("ARTEMIS_PASSWORD");
  }

  @Test
  void jolokiaFollowsTheBrokerPasswordUnlessOverridden() throws IOException {
    PropertySourcesPropertyResolver shared = resolver(Map.of("ARTEMIS_PASSWORD", "from-env"));
    assertThat(shared.getProperty("spring.artemis.password")).isEqualTo("from-env");
    assertThat(shared.getProperty("jolokia.password")).isEqualTo("from-env");

    PropertySourcesPropertyResolver split =
      resolver(Map.of("ARTEMIS_PASSWORD", "from-env", "JOLOKIA_PASSWORD", "jolokia-only"));
    assertThat(split.getProperty("jolokia.password")).isEqualTo("jolokia-only");
  }

  /**
   * Broker and Jolokia keys only. The app's own login users (message-inspector.users.*)
   * are a separate concern and keep their local defaults.
   */
  @Test
  void noMainFileDefaultsABrokerPasswordToAdmin() throws IOException {
    Pattern adminDefault =
      Pattern.compile("(?i)(artemis|jolokia)[^\\s=]*password[^\\s=]*:admin\\b");
    List<Path> offenders;
    try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
      offenders = files
        .filter(Files::isRegularFile)
        .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".properties")
          || p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
        .filter(p -> adminDefault.matcher(read(p)).find())
        .toList();
    }
    assertThat(offenders).isEmpty();
  }

  private static Properties load() throws IOException {
    Properties props = new Properties();
    try (Reader in = Files.newBufferedReader(PROPERTIES, StandardCharsets.UTF_8)) {
      props.load(in);
    }
    return props;
  }

  /** Only application.properties plus the given env: no system properties, no real env. */
  private static PropertySourcesPropertyResolver resolver(Map<String, Object> env)
      throws IOException {
    MutablePropertySources sources = new MutablePropertySources();
    sources.addLast(new MapPropertySource("env", env));
    sources.addLast(new PropertiesPropertySource("application", load()));
    return new PropertySourcesPropertyResolver(sources);
  }

  private static String read(Path p) {
    try {
      return Files.readString(p, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(p.toString(), e);
    }
  }
}
