package local.personalmemo.auth.infrastructure;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import local.personalmemo.auth.application.AuthService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.auth.google", name = "enabled", havingValue = "true")
public final class GoogleOAuthFailureHandler implements AuthenticationFailureHandler {
  private final AuthService auth;
  private final SecurityContextHolderStrategy contextHolderStrategy;
  private final SecurityContextRepository contextRepository;

  public GoogleOAuthFailureHandler(
      AuthService auth,
      SecurityContextHolderStrategy contextHolderStrategy,
      SecurityContextRepository contextRepository) {
    this.auth = auth;
    this.contextHolderStrategy = contextHolderStrategy;
    this.contextRepository = contextRepository;
  }

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException, ServletException {
    GoogleOAuthSuccessHandler.LinkIntent intent = consumeLinkIntent(request);
    String callbackState = request.getParameter("state");
    boolean markedLink = GoogleOAuthFlowState.isLink(callbackState);
    boolean linkAttempt = markedLink || intent != null;
    UUID indexedOwner = indexedOwner(request);
    if (linkAttempt) {
      String errorCode = linkErrorCode(intent, callbackState, markedLink, indexedOwner);
      if (indexedOwner != null && restoreOwner(indexedOwner, request, response)) {
        response.sendRedirect("/account?error=" + errorCode);
        return;
      }
      clearSession(request);
      response.sendRedirect("/account?error=" + errorCode);
      return;
    }
    if (indexedOwner != null && restoreOwner(indexedOwner, request, response)) {
      response.sendRedirect("/account?error=OAUTH_FAILED");
      return;
    }
    clearSession(request);
    response.sendRedirect("/login?error=OAUTH_FAILED");
  }

  private GoogleOAuthSuccessHandler.LinkIntent consumeLinkIntent(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) {
      return null;
    }
    synchronized (session) {
      Object value = session.getAttribute(GoogleOAuthSuccessHandler.LINK_INTENT_SESSION_ATTRIBUTE);
      session.removeAttribute(GoogleOAuthSuccessHandler.LINK_INTENT_SESSION_ATTRIBUTE);
      return value instanceof GoogleOAuthSuccessHandler.LinkIntent intent ? intent : null;
    }
  }

  private String linkErrorCode(
      GoogleOAuthSuccessHandler.LinkIntent intent,
      String callbackState,
      boolean markedLink,
      UUID indexedOwner) {
    if (!markedLink
        || intent == null
        || !GoogleOAuthFlowState.matchesLink(intent.oauthState(), callbackState)
        || indexedOwner == null
        || !indexedOwner.equals(intent.ownerId())) {
      return "LINK_INTENT_INVALID";
    }
    if (GoogleOAuthFlowState.isExpired(intent.createdAt(), Instant.now())) {
      return "LINK_INTENT_EXPIRED";
    }
    return "OAUTH_FAILED";
  }

  private void clearSession(HttpServletRequest request) {
    contextHolderStrategy.clearContext();
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
  }

  private UUID indexedOwner(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) {
      return null;
    }
    Object indexedOwnerValue =
        session.getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME);
    if (!(indexedOwnerValue instanceof String value)) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private boolean restoreOwner(
      UUID ownerId, HttpServletRequest request, HttpServletResponse response) {
    try {
      var principal = auth.requireUser(ownerId).toPrincipal();
      var authentication =
          org.springframework.security.authentication.UsernamePasswordAuthenticationToken
              .authenticated(
                  principal,
                  null,
                  java.util.List.of(
                      new org.springframework.security.core.authority.SimpleGrantedAuthority(
                          "ROLE_USER")));
      var context = contextHolderStrategy.createEmptyContext();
      context.setAuthentication(authentication);
      contextHolderStrategy.setContext(context);
      contextRepository.saveContext(context, request, response);
      request
          .getSession(true)
          .setAttribute(
              FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, principal.getName());
      return true;
    } catch (RuntimeException exception) {
      return false;
    }
  }
}
