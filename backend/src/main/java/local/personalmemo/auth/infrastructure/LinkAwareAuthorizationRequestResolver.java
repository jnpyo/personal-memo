package local.personalmemo.auth.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

/** Binds an explicit account-link intent to the first OAuth state generated for that session. */
@Component
@ConditionalOnProperty(prefix = "app.auth.google", name = "enabled", havingValue = "true")
public final class LinkAwareAuthorizationRequestResolver
    implements OAuth2AuthorizationRequestResolver {
  private final OAuth2AuthorizationRequestResolver delegate;

  public LinkAwareAuthorizationRequestResolver(ClientRegistrationRepository registrations) {
    this.delegate =
        new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
    OAuth2AuthorizationRequest authorization = delegate.resolve(request);
    return markAndBindGeneratedState(request, authorization);
  }

  @Override
  public OAuth2AuthorizationRequest resolve(
      HttpServletRequest request, String clientRegistrationId) {
    OAuth2AuthorizationRequest authorization = delegate.resolve(request, clientRegistrationId);
    return markAndBindGeneratedState(request, authorization);
  }

  private OAuth2AuthorizationRequest markAndBindGeneratedState(
      HttpServletRequest request, OAuth2AuthorizationRequest authorization) {
    if (authorization == null || authorization.getState() == null) {
      return authorization;
    }
    HttpSession session = request.getSession(false);
    if (session == null) {
      return authorization;
    }
    synchronized (session) {
      Object value = session.getAttribute(GoogleOAuthSuccessHandler.LINK_INTENT_SESSION_ATTRIBUTE);
      if (!(value instanceof GoogleOAuthSuccessHandler.LinkIntent intent)) {
        return authorization;
      }

      String markedState = GoogleOAuthFlowState.markLink(authorization.getState());
      OAuth2AuthorizationRequest markedAuthorization =
          OAuth2AuthorizationRequest.from(authorization).state(markedState).build();
      if (intent.oauthState() == null) {
        session.setAttribute(
            GoogleOAuthSuccessHandler.LINK_INTENT_SESSION_ATTRIBUTE,
            new GoogleOAuthSuccessHandler.LinkIntent(
                intent.ownerId(), intent.createdAt(), markedState));
      }
      return markedAuthorization;
    }
  }
}
