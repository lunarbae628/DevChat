package project.backend.global.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import project.backend.global.util.CookieUtils;
import project.backend.auth.jwt.JwtProvider;
import project.backend.auth.jwt.TokenStatus;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtProvider jwtProvider;

	private static final List<String> WHITE_LIST = List.of(
			"/signup",
			"/login",
			"/token/refresh",
			"/actuator/health",
			"/actuator/prometheus",
			"/ws",
			"/swagger-ui",
			"/"
	);
	private static final String REFRESH_REQUIRED_CODE = "AUTH_REFRESH_REQUIRED";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
									FilterChain filterChain) throws ServletException, IOException {

		String requestURI = request.getRequestURI();
		log.info("[JWT Filter] 요청 URI: {}", requestURI);

		if (isWhitelisted(requestURI)) {
			filterChain.doFilter(request, response);
			return;
		}

		Cookie cookie = CookieUtils.getCookie(request, "accessToken").orElse(null);

		if (cookie == null) {
			sendUnauthorized(response, "토큰이 없습니다.");
			return;
		}

		String accessToken = cookie.getValue();
		TokenStatus tokenStatus = jwtProvider.validateAccessToken(accessToken);

		if (tokenStatus == TokenStatus.VALID) {
			log.info("[JWT] 유효한 토큰");
			Authentication authentication = jwtProvider.getAuthentication(accessToken);
			SecurityContextHolder.getContext().setAuthentication(authentication);
		} else {
			log.warn("[JWT] 유효하지 않은 토큰 - status: {}", tokenStatus);
			SecurityContextHolder.clearContext();
			sendUnauthorized(response, "토큰이 만료되었거나 유효하지 않습니다.");
			return;
		}

		filterChain.doFilter(request, response);
	}

	private boolean isWhitelisted(String requestURI) {
		return requestURI.startsWith("/github/")
				|| requestURI.startsWith("/swagger-ui/")
				|| requestURI.equals("/v3/api-docs")
				|| requestURI.startsWith("/v3/api-docs/")
				|| WHITE_LIST.contains(requestURI);
	}

	private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType("application/json; charset=utf-8");
		response.getWriter().write("{\"code\":\"" + REFRESH_REQUIRED_CODE + "\",\"message\":\"" + message + "\"}");
	}
}
