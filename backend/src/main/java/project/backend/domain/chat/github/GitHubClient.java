package project.backend.domain.chat.github;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import project.backend.global.exception.errorcode.GitHubErrorCode;
import project.backend.global.exception.ex.GitHubException;

@Component
public class GitHubClient {

    private final RestClient restClient;

    public GitHubClient() {
        this(createRestClient());
    }

    GitHubClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public Long registerWebhook(String accessToken, String owner, String repo, String webhookUrl) {
        Map<String, Object> requestBody = Map.of(
            "name", "web",
            "active", true,
            "events", List.of("issues", "pull_request", "pull_request_review"),
            "config", Map.of(
                "url", webhookUrl,
                "content_type", "json",
                "insecure_ssl", "0"
            )
        );

        try {
            Map<String, Object> response = restClient.post()
                .uri("/repos/{owner}/{repo}/hooks", owner, repo)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github.v3+json")
                .body(requestBody)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                    throw toRegisterException(clientResponse);
                })
                .body(new ParameterizedTypeReference<>() {
                });

            if (response == null || !(response.get("id") instanceof Number idNumber)) {
                throw new GitHubException(GitHubErrorCode.WEBHOOK_REGISTER_FAILED);
            }
            return idNumber.longValue();
        } catch (GitHubException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new GitHubException(GitHubErrorCode.WEBHOOK_REGISTER_FAILED);
        }
    }

    public void deleteWebhook(String accessToken, String owner, String repo, Long webhookId) {
        try {
            restClient.delete()
                .uri("/repos/{owner}/{repo}/hooks/{webhookId}", owner, repo, webhookId)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github.v3+json")
                .retrieve()
                .toBodilessEntity();
        } catch (RuntimeException exception) {
            throw new GitHubException(GitHubErrorCode.WEBHOOK_DELETE_FAILED);
        }
    }

    private static RestClient createRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder()
            .baseUrl("https://api.github.com")
            .requestFactory(requestFactory)
            .build();
    }

    private GitHubException toRegisterException(ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.value() == 401) {
            return new GitHubException(GitHubErrorCode.INVALID_TOKEN);
        }
        if (status.value() == 404) {
            return new GitHubException(GitHubErrorCode.REPO_NOT_FOUND);
        }
        if (status.is5xxServerError()) {
            return new GitHubException(GitHubErrorCode.SERVER_ERROR);
        }
        if (status.value() == 429) {
            return new GitHubException(GitHubErrorCode.CLIENT_ERROR);
        }
        if (status.value() == 403) {
            if ("0".equals(response.getHeaders().getFirst("X-RateLimit-Remaining"))
                    || response.getHeaders().getFirst("Retry-After") != null
                    || containsSecondaryRateLimitMessage(response)) {
                return new GitHubException(GitHubErrorCode.CLIENT_ERROR);
            }
            return new GitHubException(GitHubErrorCode.UNAUTHORIZED_REPO);
        }
        return new GitHubException(GitHubErrorCode.CLIENT_ERROR);
    }

    private boolean containsSecondaryRateLimitMessage(ClientHttpResponse response) throws IOException {
        String responseBody = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
        return responseBody.toLowerCase(Locale.ROOT).contains("secondary rate limit");
    }
}
