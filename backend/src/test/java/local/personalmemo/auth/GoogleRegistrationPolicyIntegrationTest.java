package local.personalmemo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.util.Map;
import local.personalmemo.auth.application.AuthService;
import local.personalmemo.auth.domain.GoogleProfile;
import local.personalmemo.auth.domain.LoginMethod;
import local.personalmemo.auth.infrastructure.GoogleOAuthSuccessHandler;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@PostgresIntegration
@SpringBootTest(
    properties = {
      "app.auth.google.enabled=true",
      "app.auth.google.registration-enabled=false",
      "app.auth.google.client-id=test-client",
      "app.auth.google.client-secret=test-secret",
      "app.auth.google.redirect-uri=http://localhost:5173/login/oauth2/code/google"
    })
@AutoConfigureMockMvc
class GoogleRegistrationPolicyIntegrationTest extends PostgresIntegrationTestSupport {
  @Autowired AuthService auth;
  @Autowired GoogleOAuthSuccessHandler googleSuccessHandler;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  void capabilitiesDistinguishGoogleAuthenticationFromGoogleRegistration() throws Exception {
    MvcResult capabilities = mvc.perform(get("/api/v1/auth/capabilities")).andReturn();

    assertThat(capabilities.getResponse().getStatus()).isEqualTo(200);
    JsonNode body = json.readTree(capabilities.getResponse().getContentAsByteArray());
    assertThat(body.path("googleEnabled").asBoolean()).isTrue();
    assertThat(body.path("googleRegistrationEnabled").asBoolean()).isFalse();
  }

  @Test
  void disabledGoogleRegistrationRejectsNewUserButKeepsLinkAndExistingLogin() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpSession transientSession = (MockHttpSession) request.getSession(true);
    MockHttpServletResponse response = new MockHttpServletResponse();

    googleSuccessHandler.onAuthenticationSuccess(
        request,
        response,
        oauthToken("unregistered-subject", "unregistered@example.com", "Unregistered User"));

    assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=GOOGLE_REGISTRATION_DISABLED");
    assertThat(db.sql("select count(*) from external_identities").query(Long.class).single())
        .isZero();
    assertThat(db.sql("select count(*) from users").query(Long.class).single()).isEqualTo(1);
    assertThatThrownBy(() -> transientSession.getAttribute("anything"))
        .isInstanceOf(IllegalStateException.class);

    var local =
        auth.register("linked@example.com", "correct horse battery", "Linked User", "Asia/Seoul");
    GoogleProfile linkedGoogle =
        new GoogleProfile("linked-subject", "linked@example.com", true, "Linked User");

    auth.linkGoogle(local.userId(), linkedGoogle);

    assertThat(auth.googleLogin(linkedGoogle).userId()).isEqualTo(local.userId());
    assertThat(auth.loginMethods(local.userId()))
        .containsExactlyInAnyOrder(LoginMethod.LOCAL, LoginMethod.GOOGLE);
    assertThat(db.sql("select count(*) from users").query(Long.class).single()).isEqualTo(2);
  }

  private OAuth2AuthenticationToken oauthToken(String subject, String email, String fullName) {
    OidcUser oidc = mock(OidcUser.class);
    when(oidc.getSubject()).thenReturn(subject);
    when(oidc.getEmail()).thenReturn(email);
    when(oidc.getFullName()).thenReturn(fullName);
    when(oidc.getName()).thenReturn(subject);
    when(oidc.getClaims()).thenReturn(Map.<String, Object>of("email_verified", true));
    return new OAuth2AuthenticationToken(
        oidc, List.of(new SimpleGrantedAuthority("OIDC_USER")), "google");
  }
}
