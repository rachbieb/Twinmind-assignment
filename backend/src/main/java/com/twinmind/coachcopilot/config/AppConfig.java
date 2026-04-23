package com.twinmind.coachcopilot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {

    @Bean
    WebClient groqWebClient(AppProperties properties) {
        return WebClient.builder()
                .baseUrl(properties.groqBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().compress(true)))
                .build();
    }
}
