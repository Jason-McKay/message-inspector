package com.message.inspector.message_inspector.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Configuration
public class RestTemplateConfig {

  @Value("${jolokia.username}")
  private String username;

  @Value("${jolokia.password}")
  private String password;

  @Bean
  public RestTemplate restTemplate() {
    return new RestTemplate();
  }

  @Bean
  public RestTemplate jolokiaRestTemplate() {
    RestTemplate restTemplate = new RestTemplate();

    // Add Basic Authentication
    restTemplate.getInterceptors().add((request, body, execution) -> {
      String auth = username + ":" + password;
      byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
      String authHeader = "Basic " + new String(encodedAuth);
      request.getHeaders().add("Authorization", authHeader);
      return execution.execute(request, body);
    });

    // Add a message converter for 'text/plain' that treats it as JSON
    MappingJackson2HttpMessageConverter jacksonConverter = new MappingJackson2HttpMessageConverter();
    List<MediaType> mediaTypes = new ArrayList<>(jacksonConverter.getSupportedMediaTypes());
    mediaTypes.add(MediaType.TEXT_PLAIN); // handle Jolokia's text/plain JSON
    jacksonConverter.setSupportedMediaTypes(mediaTypes);

    restTemplate.getMessageConverters().addFirst(jacksonConverter); // add with the highest priority

    return restTemplate;
  }
}
