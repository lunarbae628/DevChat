package project.backend.domain.chat.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withForbiddenRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import project.backend.global.exception.errorcode.GitHubErrorCode;
import project.backend.global.exception.ex.GitHubException;

class GitHubClientTest {

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private HttpServer timeoutServer;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl("https://api.github.com");
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @AfterEach
    void tearDown() {
        if (timeoutServer != null) {
            timeoutServer.stop(0);
        }
    }

    @Test
    @DisplayName("웹훅 등록은 권한 조회 없이 POST 한 번으로 webhookId를 반환한다")
    void registerWebhook_postsOnceAndReturnsWebhookId() {
        GitHubClient client = new GitHubClient(builder.build());

        server.expect(requestTo("https://api.github.com/repos/team/repo/hooks"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer token"))
            .andExpect(content().json("""
                {"name":"web","active":true,"events":["issues","pull_request","pull_request_review"],
                "config":{"url":"https://devchat.test/github/7","content_type":"json","insecure_ssl":"0"}}
                """))
            .andRespond(withSuccess("{\"id\":42}", MediaType.APPLICATION_JSON));

        assertThat(client.registerWebhook("token", "team", "repo", "https://devchat.test/github/7"))
            .isEqualTo(42L);

        server.verify();
    }

    @Test
    @DisplayName("웹훅 등록에서 401을 받으면 유효하지 않은 토큰 오류로 변환한다")
    void registerWebhook_unauthorizedMapsToInvalidToken() {
        assertError(withUnauthorizedRequest(), GitHubErrorCode.INVALID_TOKEN);
    }

    @Test
    @DisplayName("웹훅 등록에서 404를 받으면 레포지토리 없음 오류로 변환한다")
    void registerWebhook_notFoundMapsToRepositoryNotFound() {
        assertError(withResourceNotFound(), GitHubErrorCode.REPO_NOT_FOUND);
    }

    @Test
    @DisplayName("rate limit 헤더가 있는 403은 클라이언트 오류로 변환한다")
    void registerWebhook_rateLimitedForbiddenMapsToClientError() {
        assertError(withForbiddenRequest().header("X-RateLimit-Remaining", "0"),
            GitHubErrorCode.CLIENT_ERROR);
    }

    @Test
    @DisplayName("Retry-After 헤더가 있는 403은 secondary rate limit 오류로 변환한다")
    void registerWebhook_secondaryRateLimitedForbiddenMapsToClientError() {
        assertError(withForbiddenRequest().header("Retry-After", "60"),
            GitHubErrorCode.CLIENT_ERROR);
    }

    @Test
    @DisplayName("secondary rate limit 본문이 있는 403은 클라이언트 오류로 변환한다")
    void registerWebhook_secondaryRateLimitMessageMapsToClientError() {
        assertError(withForbiddenRequest().body("""
            {"message":"You have exceeded a secondary rate limit. Please wait a few minutes before you try again."}
            """), GitHubErrorCode.CLIENT_ERROR);
    }

    @Test
    @DisplayName("rate limit이 아닌 403은 관리자 권한 오류로 변환한다")
    void registerWebhook_forbiddenMapsToUnauthorizedRepository() {
        assertError(withForbiddenRequest(), GitHubErrorCode.UNAUTHORIZED_REPO);
    }

    @Test
    @DisplayName("429는 클라이언트 오류로 변환한다")
    void registerWebhook_tooManyRequestsMapsToClientError() {
        assertError(withTooManyRequests(), GitHubErrorCode.CLIENT_ERROR);
    }

    @Test
    @DisplayName("5xx는 GitHub 서버 오류로 변환한다")
    void registerWebhook_serverErrorMapsToServerError() {
        assertError(withServerError(), GitHubErrorCode.SERVER_ERROR);
    }

    @Test
    @DisplayName("Retry-After 헤더가 있어도 5xx는 GitHub 서버 오류로 변환한다")
    void registerWebhook_serverErrorWithRetryAfterMapsToServerError() {
        assertError(withServerError().header("Retry-After", "60"), GitHubErrorCode.SERVER_ERROR);
    }

    @Test
    @DisplayName("응답 시간 제한을 넘기면 웹훅 등록 실패로 변환한다")
    void registerWebhook_readTimeoutMapsToWebhookRegisterFailure() throws Exception {
        timeoutServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        timeoutServer.createContext("/repos/team/repo/hooks", exchange -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(201, 9);
            exchange.getResponseBody().write("{\"id\":42}".getBytes());
            exchange.close();
        });
        timeoutServer.start();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(50));
        requestFactory.setReadTimeout(Duration.ofMillis(50));
        RestClient timeoutClient = RestClient.builder()
            .baseUrl("http://127.0.0.1:" + timeoutServer.getAddress().getPort())
            .requestFactory(requestFactory)
            .build();
        GitHubClient client = new GitHubClient(timeoutClient);

        assertThatThrownBy(() -> client.registerWebhook("token", "team", "repo", "https://devchat.test/github/7"))
            .isInstanceOfSatisfying(GitHubException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(GitHubErrorCode.WEBHOOK_REGISTER_FAILED));
    }

    private void assertError(org.springframework.test.web.client.response.DefaultResponseCreator response,
        GitHubErrorCode expected) {
        GitHubClient client = new GitHubClient(builder.build());
        server.expect(requestTo("https://api.github.com/repos/team/repo/hooks"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(response);

        assertThatThrownBy(() -> client.registerWebhook("token", "team", "repo", "https://devchat.test/github/7"))
            .isInstanceOfSatisfying(GitHubException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));

        server.verify();
    }
}
