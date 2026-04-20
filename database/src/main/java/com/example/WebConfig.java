package com.example;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class WebConfig implements WebMvcConfigurer {

    /**
     * Inputs: registry (CorsRegistry) — Spring MVC CORS registry to configure
     * Outputs: void — registers CORS rules for all /api/** endpoints
     * Functionality: Allows cross-origin requests to /api/** from the local dev
     * servers and the
     * two Firebase-hosted production origins, permitting standard HTTP methods and
     * all headers.
     * Dependencies: org.springframework.web.servlet.config.annotation.CorsRegistry,
     * org.springframework.web.servlet.config.annotation.WebMvcConfigurer
     * Called by: Spring MVC framework during application context initialization
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "http://localhost:3000",
                        "http://localhost:3001",
                        "https://perc-elk-detection-48336.web.app",
                        "https://perc-elk-detection-48336.firebaseapp.com")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}