package project.backend.global.security.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import project.backend.auth.jwt.JwtProvider;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtProvider jwtProvider;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtProvider);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/swagger-ui/index.html",
            "/swagger-ui/swagger-ui.css",
            "/v3/api-docs",
            "/v3/api-docs/swagger-config"
    })
    void documentationPathsPassWithoutAccessToken(String requestUri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(filterChain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("access token이 없으면 refresh 대상으로 구분 가능한 401 응답을 반환한다")
    void missingAccessToken_returnsRefreshRequiredCode() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat-rooms");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
            .isEqualTo("{\"code\":\"AUTH_REFRESH_REQUIRED\",\"message\":\"토큰이 없습니다.\"}");
        assertThat(filterChain.getRequest()).isNull();
    }
}
