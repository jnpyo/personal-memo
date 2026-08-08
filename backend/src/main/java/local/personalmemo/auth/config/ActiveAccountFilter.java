package local.personalmemo.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.UUID;
import local.personalmemo.auth.domain.AppPrincipal;
import local.personalmemo.auth.infrastructure.AuthRepository;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

final class ActiveAccountFilter extends OncePerRequestFilter {
  static final String EXPECTED_OWNER_HEADER = "X-Expected-Owner-Id";

  private final AuthRepository users;
  private final SecurityContextHolderStrategy contextHolderStrategy;
  private final SecurityErrorWriter errors;
  private final CookieClearingLogoutHandler clearCookies =
      new CookieClearingLogoutHandler("SESSION", "JSESSIONID", "XSRF-TOKEN");

  ActiveAccountFilter(
      AuthRepository users,
      SecurityContextHolderStrategy contextHolderStrategy,
      SecurityErrorWriter errors) {
    this.users = users;
    this.contextHolderStrategy = contextHolderStrategy;
    this.errors = errors;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication authentication = contextHolderStrategy.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && authentication.getPrincipal() instanceof AppPrincipal principal) {
      String path = applicationPath(request);
      boolean logoutRequest = path.equals("/api/v1/auth/logout");
      if (!logoutRequest && !users.isActive(principal.userId())) {
        contextHolderStrategy.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
          session.invalidate();
        }
        clearCookies.logout(request, response, authentication);
        errors.write(
            response,
            HttpServletResponse.SC_UNAUTHORIZED,
            "ACCOUNT_DISABLED",
            "This account is disabled.");
        return;
      }

      if (isOwnerScopedPath(path) && expectedOwnerDoesNotMatch(request, principal)) {
        errors.write(
            response,
            HttpServletResponse.SC_CONFLICT,
            "SESSION_OWNER_CHANGED",
            "The authenticated account changed before this request was sent.");
        return;
      }
    }
    chain.doFilter(request, response);
  }

  private boolean expectedOwnerDoesNotMatch(HttpServletRequest request, AppPrincipal principal) {
    String expectedOwner = request.getHeader(EXPECTED_OWNER_HEADER);
    if (expectedOwner == null) {
      return false;
    }
    try {
      return !principal.userId().equals(UUID.fromString(expectedOwner));
    } catch (IllegalArgumentException ignored) {
      return true;
    }
  }

  private boolean isOwnerScopedPath(String path) {
    return path.startsWith("/api/v1/memos")
        || path.startsWith("/api/v1/analysis-proposals")
        || path.startsWith("/api/v1/analysis-applications")
        || path.startsWith("/api/v1/analysis-review-outcomes")
        || path.startsWith("/api/v1/tasks")
        || path.startsWith("/api/v1/graph")
        || path.equals("/api/v1/auth/logout")
        || path.equals("/api/v1/auth/google/link-intent")
        || path.equals("/api/v1/auth/identities/google");
  }

  private String applicationPath(HttpServletRequest request) {
    String path = request.getServletPath();
    if (path != null && !path.isEmpty()) {
      return path;
    }
    path = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
      return path.substring(contextPath.length());
    }
    return path;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (HttpMethod.OPTIONS.matches(request.getMethod())) {
      return true;
    }
    String path = applicationPath(request);
    return path.equals("/")
        || path.equals("/index.html")
        || path.equals("/manifest.webmanifest")
        || path.equals("/sw.js")
        || path.equals("/favicon.ico")
        || path.startsWith("/workbox-")
        || path.startsWith("/assets/")
        || path.startsWith("/icons/")
        || path.equals("/api/v1/health")
        || path.startsWith("/actuator/health")
        || path.equals("/api/v1/auth/capabilities")
        || path.equals("/api/v1/auth/csrf")
        || path.equals("/api/v1/auth/register")
        || path.equals("/api/v1/auth/login")
        || path.startsWith("/oauth2/")
        || path.startsWith("/login/oauth2/");
  }
}
