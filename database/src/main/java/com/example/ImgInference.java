package com.example;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

@Service
public class ImgInference {
    private final RestClient restClient;

    public ImgInference(RestClient.Builder builder) {
        // Force HTTP/1.1 to avoid h2c upgrade issues with Uvicorn
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);

        this.restClient = builder
                .baseUrl("http://localhost:8000")
                .requestFactory(requestFactory)
                .build();
    }

    // Send a single image as raw bytes and get back a single count
    public Integer inferCount(byte[] imageBytes) {
        try {
            String response = restClient.post()
                    .uri("/infer")
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(imageBytes)
                    .retrieve()
                    .body(String.class);

            if (response == null || response.isBlank()) {
                return null;
            }
            return Integer.parseInt(response.trim());
        } catch (Exception e) {
            System.err.println("Inference failed: " + e.getMessage());
            return null;
        }
    }

    // Process a list of images by calling inferCount for each one
    public List<Integer> inferCounts(List<ImagePayload> images) {
        List<Integer> counts = new ArrayList<>();
        for (ImagePayload img : images) {
            Integer count = inferCount(img.bytes());
            counts.add(count);
        }
        return counts;
    }

    public static record ImagePayload(String filename, byte[] bytes) {
    }
}
