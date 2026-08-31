package local.personalmemo.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import local.personalmemo.auth.api.AuthDtos.AuthSession;
import local.personalmemo.auth.api.AuthDtos.Capabilities;
import local.personalmemo.auth.api.AuthDtos.Csrf;
import local.personalmemo.auth.api.AuthDtos.GoogleLinkIntent;
import local.personalmemo.auth.api.AuthDtos.Login;
import local.personalmemo.auth.api.AuthDtos.Register;
import local.personalmemo.auth.application.AuthService;
import local.personalmemo.auth.config.AuthProperties;
import local.personalmemo.auth.domain.AppPrincipal;
import local.personalmemo.auth.domain.LoginMethod;
import local.personalmemo.auth.infrastructure.GoogleOAuthSuccessHandler;
import local.personalmemo.common.auth.CurrentIdentity;
import local.personalmemo.common.error.DomainException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public final class AuthController {
  private final AuthService auth;
  private final CurrentIdentity identity;
  private final AuthProperties properties;
  private final SecurityContextHolderStrategy contextHolderStrategy;
  private final SecurityContextRepository contextRepository;
  private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

  public AuthController(
      AuthService auth,
      CurrentIdentity identity,
      AuthProperties properties,
      SecurityContextHolderStrategy contextHolderStrategy,
      SecurityContextRepository contextRepository,
      @Qualifier("localAuthenticationSessionStrategy") SessionAuthenticationStrategy sessionAuthenticationStrategy) {
    this.auth = auth;
    this.identity = identity;
    this.properties = properties;
    this.contextHolderStrategy = contextHolderStrategy;
    this.contextRepository = contextRepository;
    this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
  }

  @GetMapping("/capabilities")
  public Capabilities capabilities() {
    return new Capabilities(
        properties.registrationEnabled(),
        properties.google().enabled(),
        properties.google().enabled() && properties.google().registrationEnabled());
  }

  @GetMapping("/csrf")
  public Csrf csrf(CsrfToken token) {
    return new Csrf(token.getHeaderName(), token.getParameterName(), token.getToken());
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public AuthSession register(
      @Valid @RequestBody Register request,
      HttpServletRequest servletRequest,
      HttpServletResponse servletResponse) {
    AppPrincipal principal =
        auth.register(
            request.email(), request.password(), request.displayName(), request.timeZone());
    establishSession(principal, servletRequest, servletResponse);
    return view(principal);
  }

  @PostMapping("/login")
  public AuthSession login(
      @Valid @RequestBody Login request,
      HttpServletRequest servletRequest,
      HttpServletResponse servletResponse) {
    AppPrincipal principal = auth.login(request.email(), request.password());
    establishSession(principal, servletRequest, servletResponse);
    return view(principal);
  }

  @GetMapping("/me")
  public AuthSession me() {
    return view(auth.requireUser(identity.ownerId()).toPrincipal());
  }

  @PostMapping("/google/link-intent")
  public GoogleLinkIntent googleLinkIntent(HttpServletRequest request) {
    if (!properties.google().enabled()) {
      throw DomainException.notFound("Google authentication");
    }
    HttpSession session = request.getSession(true);
    session.setAttribute(
        GoogleOAuthSuccessHandler.LINK_INTENT_SESSION_ATTRIBUTE,
        new GoogleOAuthSuccessHandler.LinkIntent(identity.ownerId(), Instant.now(), null));
    return new GoogleLinkIntent("/oauth2/authorization/google");
  }

  @DeleteMapping("/identities/google")
  public AuthSession unlinkGoogle() {
    var userId = identity.ownerId();
    auth.unlinkGoogle(userId);
    return view(auth.requireUser(userId).toPrincipal());
  }

  private void establishSession(
      AppPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
    // A successful local authentication starts a clean account boundary. Rotating only the
    // identifier would preserve OAuth authorization requests and link intents from the previous
    // account, allowing a stale callback to act on that account after a re-login.
    HttpSession previousSession = request.getSession(false);
    contextHolderStrategy.clearContext();
    if (previousSession != null) {
      previousSession.invalidate();
    }
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    request.getSession(true);
    sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
    var context = contextHolderStrategy.createEmptyContext();
    context.setAuthentication(authentication);
    contextHolderStrategy.setContext(context);
    contextRepository.saveContext(context, request, response);
    request
        .getSession(true)
        .setAttribute(
            FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, principal.getName());
  }

  private AuthSession view(AppPrincipal principal) {
    List<String> methods =
        auth.loginMethods(principal.userId()).stream()
            .map(LoginMethod::name)
            .sorted(Comparator.naturalOrder())
            .toList();
    return new AuthSession(principal.userId(), principal.email(), principal.displayName(), methods);
  }
}
