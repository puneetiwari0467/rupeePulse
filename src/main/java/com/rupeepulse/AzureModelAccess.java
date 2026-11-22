package com.rupeepulse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class AzureModelAccess {
    private final boolean managed;
    private final String localKey;
    private final String identityEndpoint;
    private final String identityHeader;
    private final DailyModelBudget budget;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private String token;
    private Instant expires = Instant.EPOCH;

    AzureModelAccess(ObjectMapper json,
                     @Value("${AZURE_OPENAI_MANAGED_IDENTITY:false}") boolean managed,
                     @Value("${AZURE_OPENAI_API_KEY:}") String localKey,
                     @Value("${IDENTITY_ENDPOINT:}") String identityEndpoint,
                     @Value("${IDENTITY_HEADER:}") String identityHeader,
                     @Value("${FOLDPULSE_MODEL_CALLS_PER_UTC_DAY:120}") int dailyLimit) {
        this.json = json;
        this.managed = managed;
        this.localKey = localKey.trim();
        this.identityEndpoint = identityEndpoint;
        this.identityHeader = identityHeader;
        this.budget = managed ? new DailyModelBudget(Path.of("/home/data/foldpulse"), dailyLimit, Clock.systemUTC()) : null;
    }

    boolean configured() { return managed || !localKey.isEmpty(); }
    boolean hosted() { return managed; }

    HttpRequest.Builder authorize(HttpRequest.Builder request) throws IOException, InterruptedException {
        if (!managed) return request.header("api-key", localKey);
        String bearer = accessToken();
        budget.reserve();
        return request.header("Authorization", "Bearer " + bearer);
    }

    // App Service supplies these two values to the worker, never to the browser.
    private synchronized String accessToken() throws IOException, InterruptedException {
        if (token != null && expires.isAfter(Instant.now().plusSeconds(120))) return token;
        if (identityEndpoint.isBlank() || identityHeader.isBlank()) throw new IOException("Managed identity is not available.");
        var uri = URI.create(identityEndpoint + "?api-version=2019-08-01&resource=https%3A%2F%2Fcognitiveservices.azure.com%2F");
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
            .header("X-IDENTITY-HEADER", identityHeader).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode()!=200) throw new IOException("Managed identity returned HTTP " + response.statusCode());
        var body = json.readTree(response.body());
        String next = body.path("access_token").asText("");
        long expiry = body.path("expires_on").asLong(0);
        if (next.isBlank() || expiry <= Instant.now().getEpochSecond()) throw new IOException("Managed identity returned no valid token.");
        token = next;
        expires = Instant.ofEpochSecond(expiry);
        return token;
    }
}
