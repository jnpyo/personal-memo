package local.personalmemo.auth.infrastructure;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import local.personalmemo.auth.application.AuthService;
import local.personalmemo.auth.domain.AppPrincipal;
import local.personalmemo.auth.domain.GoogleProfile;
import local.personalmemo.common.error.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.auth.google", name = "enabled", havingValue = "true")
public final class GoogleOAuthSuccessHandler implements AuthenticationSuccessHandler {
  public static final String LINK_INTENT_SESSION_ATTRIBUTE =
      GoogleOAuthSuccessHandler.class.getName() + ".LINK_INTENT";
  private final AuthService auth;
  private final SecurityContextHolderStrategy contextHolderStrategy;
  private final SecurityContextRepository contextRepository;
  private final OAuth2AuthorizedClientService authorizedClients;

  public GoogleOAuthSuccessHandler(
      AuthService auth,
      SecurityContextHolderStrategy contextHolderStrategy,
      SecurityContextRepository contextRepository,
      OAuth2AuthorizedClientService authorizedClients) {
    this.auth = auth;
    this.contextHolderStrategy = contextHolderStrategy;
    this.contextRepository = contextRepository;
    this.authorizedClients = authorizedClients;
  }

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException, ServletException {
    String registrationId = "google";
    String oauthPrincipalName = authentication.getName();
    if (authentication instanceof OAuth2AuthenticationToken oauth) {
      registrationId = oauth.getAuthorizedClientRegistrationId();
    }

    LinkIntent intent = consumeLinkIntent(request);
    String callbackState = request.getParameter("state");
    boolean markedLink = GoogleOAuthFlowState.isLink(callbackState);
    boolean linkAttempt = markedLink || intent != null;
    try {
      UUID verifiedLinkOwner =
          linkAttempt ? validateLinkIntent(intent, callbackState, markedLink, request) : null;
      if (!(authentication.getPrincipal() instanceof OidcUser oidcUser)) {
        throw DomainException.unauthorized(
            "OAUTH_FAILED", "Google did not return an OpenID Connect identity.");
      }
      GoogleProfile profile = profile(oidcUser);
      AppPrincipal principal;
      if (linkAttempt) {
        principal = auth.linkGoogle(verifiedLinkOwner, profile);
      } else {
        principal = auth.googleLogin(profile);
      }
      installPrincipal(principal, request, response);
      response.sendRedirect(linkAttempt ? "/account?linked=google" : "/");
    } catch (DomainException exception) {
      recoverFromError(request, response);
      response.sendRedirect(
          (linkAttempt ? "/account?error=" : "/login?error=") + safeErrorCode(exception.code()));
    } catch (RuntimeException exception) {
      recoverFromError(request, response);
      response.sendRedirect(
          linkAttempt ? "/account?error=OAUTH_FAILED" : "/login?error=OAUTH_FAILED");
    } finally {
      removeAuthorizedClient(registrationId, oauthPrincipalName);
    }
  }

  private GoogleProfile profile(OidcUser oidcUser) {
    Object verifiedClaim = oidcUser.getClaims().get("email_verified");
    boolean verified =
        Boolean.TRUE.equals(verifiedClaim)
            || "true".equalsIgnoreCase(String.valueOf(verifiedClaim));
    String name = oidcUser.getFullName();
    if (name == null || name.isBlank()) {
      name = oidcUser.getEmail();
    }
    return new GoogleProfile(oidcUser.getSubject(), oidcUser.getEmail(), verified, name);
  }

  private void installPrincipal(
      AppPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    var context = contextHolderStrategy.createEmptyContext();
    context.setAuthentication(authentication);
    contextHolderStrategy.setContext(context);
    contextRepository.saveContext(context, request, response);
    request
        .getSession(true)
        .setAttribute(
            FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, principal.getName());
  }

  private LinkIntent consumeLinkIntent(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) {
      return null;
    }
    synchronized (session) {
      Object value = session.getAttribute(LINK_INTENT_SESSION_ATTRIBUTE);
      session.removeAttribute(LINK_INTENT_SESSION_ATTRIBUTE);
      return value instanceof LinkIntent linkIntent ? linkIntent : null;
    }
  }

  private void removeAuthorizedClient(String registrationId, String principalName) {
    try {
      authorizedClients.removeAuthorizedClient(registrationId, principalName);
    } catch (RuntimeException ignored) {
      // The provider token is deliberately best-effort deleted and never copied into our domain.
    }
  }

  private UUID validateLinkIntent(
      LinkIntent intent, String callbackState, boolean markedLink, HttpServletRequest request) {
    if (!markedLink
        || intent == null
        || !GoogleOAuthFlowState.matchesLink(intent.oauthState(), callbackState)) {
      throw DomainException.conflict(
          "LINK_INTENT_INVALID", "The Google link request could not be verified.");
    }
    if (GoogleOAuthFlowState.isExpired(intent.createdAt(), Instant.now())) {
      throw DomainException.conflict("LINK_INTENT_EXPIRED", "The Google link request expired.");
    }
    UUID indexedOwner = indexedOwner(request);
    if (indexedOwner == null || !indexedOwner.equals(intent.ownerId())) {
      throw DomainException.conflict(
          "LINK_INTENT_INVALID", "The Google link request could not be verified.");
    }
    return indexedOwner;
  }

  private void recoverFromError(HttpServletRequest request, HttpServletResponse response) {
    UUID indexedOwner = indexedOwner(request);
    if (indexedOwner != null && installOwner(indexedOwner, request, response)) {
      return;
    }
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
    Object value = session.getAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME);
    if (!(value instanceof String ownerId)) {
      return null;
    }
    try {
      return UUID.fromString(ownerId);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private boolean installOwner(
      UUID ownerId, HttpServletRequest request, HttpServletResponse response) {
    try {
      installPrincipal(auth.requireUser(ownerId).toPrincipal(), request, response);
      return true;
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private String safeErrorCode(String code) {
    return switch (code) {
      case "ACCOUNT_LINK_REQUIRED",
          "GOOGLE_REGISTRATION_DISABLED",
          "GOOGLE_IDENTITY_CONFLICT",
          "GOOGLE_EMAIL_NOT_VERIFIED",
          "LINK_INTENT_EXPIRED",
          "LINK_INTENT_INVALID" ->
          code;
      default -> "OAUTH_FAILED";
    };
  }

  public record LinkIntent(UUID ownerId, Instant createdAt, String oauthState)
      implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
  }
}
