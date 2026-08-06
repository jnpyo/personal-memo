package local.personalmemo.auth.config;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import local.personalmemo.auth.infrastructure.AuthRepository;
import local.personalmemo.auth.infrastructure.GoogleOAuthFailureHandler;
import local.personalmemo.auth.infrastructure.GoogleOAuthSuccessHandler;
import local.personalmemo.auth.infrastructure.LinkAwareAuthorizationRequestResolver;
import local.personalmemo.auth.infrastructure.LocalAccountUserDetailsService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.servlet.CookieSameSiteSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.InvalidCsrfTokenException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfiguration {
  @Bean
  DefaultCookieSerializer sessionCookieSerializer(
      @Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
    DefaultCookieSerializer serializer = new DefaultCookieSerializer();
    serializer.setCookieName("SESSION");
    serializer.setCookiePath("/");
    serializer.setUseHttpOnlyCookie(true);
    serializer.setSameSite("Lax");
    serializer.setUseSecureCookie(secure);
    return serializer;
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  AuthenticationManager authenticationManager(
      LocalAccountUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder);
    return new ProviderManager(provider);
  }

  @Bean
  SecurityContextHolderStrategy securityContextHolderStrategy() {
    return SecurityContextHolder.getContextHolderStrategy();
  }

  @Bean
  SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  CookieCsrfTokenRepository csrfTokenRepository(
      @Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
    CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    repository.setCookiePath("/");
    repository.setCookieCustomizer(cookie -> cookie.secure(secure));
    return repository;
  }

  @Bean
  CookieSameSiteSupplier csrfCookieSameSiteSupplier() {
    return CookieSameSiteSupplier.ofLax().whenHasName("XSRF-TOKEN");
  }

  @Bean("localAuthenticationSessionStrategy")
  SessionAuthenticationStrategy localAuthenticationSessionStrategy(
      CookieCsrfTokenRepository csrfTokenRepository) {
    return new CompositeSessionAuthenticationStrategy(
        List.of(
            new ChangeSessionIdAuthenticationStrategy(),
            new CsrfAuthenticationStrategy(csrfTokenRepository)));
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      AuthProperties properties,
      SecurityErrorWriter errors,
      AuthRepository users,
      SecurityContextHolderStrategy contextHolderStrategy,
      SecurityContextRepository contextRepository,
      CookieCsrfTokenRepository csrfTokenRepository,
      ObjectProvider<GoogleOAuthSuccessHandler> googleSuccess,
      ObjectProvider<GoogleOAuthFailureHandler> googleFailure,
      ObjectProvider<LinkAwareAuthorizationRequestResolver> googleAuthorizationResolver)
      throws Exception {
    ActiveAccountFilter activeAccountFilter =
        new ActiveAccountFilter(users, contextHolderStrategy, errors);
    http.securityContext(context -> context.securityContextRepository(contextRepository))
        .addFilterAfter(activeAccountFilter, SecurityContextHolderFilter.class)
        .sessionManagement(
            session ->
                session
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .sessionFixation(fixation -> fixation.changeSessionId()))
        .requestCache(cache -> cache.disable())
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(csrfTokenRepository)
                    .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(
                        "/api/v1/health",
                        "/actuator/health",
                        "/",
                        "/index.html",
                        "/manifest.webmanifest",
                        "/sw.js",
                        "/workbox-*.js",
                        "/assets/**",
                        "/icons/**",
                        "/favicon.ico")
                    .permitAll()
                    .requestMatchers(
                        "/api/v1/auth/capabilities",
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/oauth2/**",
                        "/login/oauth2/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            handling ->
                handling
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            errors.write(
                                response,
                                HttpServletResponse.SC_UNAUTHORIZED,
                                "AUTHENTICATION_REQUIRED",
                                "Authentication is required."))
                    .accessDeniedHandler(
                        (request, response, exception) -> {
                          boolean csrf =
                              exception instanceof InvalidCsrfTokenException
                                  || exception instanceof MissingCsrfTokenException;
                          errors.write(
                              response,
                              HttpServletResponse.SC_FORBIDDEN,
                              csrf ? "CSRF_TOKEN_INVALID" : "ACCESS_DENIED",
                              csrf ? "A valid CSRF token is required." : "Access is denied.");
                        }))
        .logout(
            logout ->
                logout
                    .logoutUrl("/api/v1/auth/logout")
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .deleteCookies("SESSION", "JSESSIONID", "XSRF-TOKEN")
                    .logoutSuccessHandler(
                        (request, response, authentication) ->
                            response.setStatus(HttpServletResponse.SC_NO_CONTENT))
                    // A valid CSRF token is still required. Making the endpoint public only makes
                    // sign-out idempotent after a session has already expired or been invalidated.
                    // ActiveAccountFilter continues to enforce the expected-owner guard whenever
                    // an authenticated principal is present.
                    .permitAll());

    if (properties.google().enabled()) {
      http.oauth2Login(
          oauth ->
              oauth
                  .authorizationEndpoint(
                      endpoint ->
                          endpoint.authorizationRequestResolver(
                              googleAuthorizationResolver.getObject()))
                  .successHandler(googleSuccess.getObject())
                  .failureHandler(googleFailure.getObject()));
    }
    return http.build();
  }
}
