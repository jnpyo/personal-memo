package local.personalmemo.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import local.personalmemo.analysis.domain.AnalysisProvenance;
import local.personalmemo.analysis.domain.CloudAnalysisFailureReason;
import local.personalmemo.analysis.domain.CloudAnalysisGateway;
import local.personalmemo.analysis.domain.CloudAnalysisRequest;
import local.personalmemo.analysis.domain.CloudAnalysisResult;
import local.personalmemo.analysis.domain.CloudGatewayDescriptor;
import local.personalmemo.analysis.domain.CloudTransferMode;
import local.personalmemo.analysis.domain.LocalAnalyzer;
import local.personalmemo.analysis.infrastructure.FakeAnalyzer;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.node.ObjectNode;

@PostgresIntegration
class CloudConsentBoundaryIntegrationTest extends PostgresIntegrationTestSupport {
  private static final AnalysisProvenance FAKE_PROVENANCE =
      new AnalysisProvenance("fake-v6", "none", "none", "none");
  private static final CloudGatewayDescriptor NO_NETWORK =
      new CloudGatewayDescriptor(
          "test-local-v1", "test-fake", "none", "no-network-v1", CloudTransferMode.NO_NETWORK);
  private static final CloudGatewayDescriptor EXTERNAL =
      new CloudGatewayDescriptor(
          "test-external-v1",
          "test-provider",
          "test-model-v1",
          "memo-transfer-v1",
          CloudTransferMode.EXTERNAL_MEMO_CONTENT);

  @MockitoBean private LocalAnalyzer localAnalyzer;
  @MockitoBean private CloudAnalysisGateway cloudGateway;

  private FakeAnalyzer deterministicAnalyzer;

  @BeforeEach
  void useDeterministicLocalAnalysisAndNoNetworkCloudSuccess() {
    deterministicAnalyzer = new FakeAnalyzer(json);
    when(localAnalyzer.proposalSchemaVersion()).thenReturn("2");
    when(localAnalyzer.provenance()).thenReturn(FAKE_PROVENANCE);
    when(localAnalyzer.analyze(
            any(UUID.class), anyInt(), anyString(), any(Instant.class), anyString()))
        .thenAnswer(
            invocation ->
                deterministicAnalyzer.analyze(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    invocation.getArgument(4)));
    when(cloudGateway.descriptor()).thenReturn(NO_NETWORK);
    when(cloudGateway.enrich(any(CloudAnalysisRequest.class)))
        .thenAnswer(
            invocation ->
                CloudAnalysisResult.success(
                    ((CloudAnalysisRequest) invocation.getArgument(0)).validatedLocalProposal()));
  }

  @Test
  void noNetworkGatewayRunsWhenConsentIsFalse() throws Exception {
    UUID memoId = createAmbiguousMemo("consent-no-network");

    var started = startAnalysis(memoId, "consent-no-network-start", 1);

    UUID runId = assertReviewRequired(started, memoId, "SUCCESS");
    assertEvidence(runId, NO_NETWORK, "SUCCESS");
    verify(cloudGateway, times(1)).enrich(any(CloudAnalysisRequest.class));
  }

  @Test
  void externalGatewayIsSkippedWithoutConsentAndReplayDoesNotReenterIt() throws Exception {
    when(cloudGateway.descriptor()).thenReturn(EXTERNAL);
    UUID memoId = createAmbiguousMemo("consent-denied");

    var started = startAnalysis(memoId, "consent-denied-start", 1);
    var replay = startAnalysis(memoId, "consent-denied-start", 1);

    assertThat(response(replay)).isEqualTo(response(started));
    UUID runId = assertReviewRequired(started, memoId, "CONSENT_REQUIRED");
    assertEvidence(runId, EXTERNAL, "CONSENT_REQUIRED");
    verify(cloudGateway, times(1)).descriptor();
    verify(cloudGateway, never()).enrich(any(CloudAnalysisRequest.class));
  }

  @Test
  void exactPinnedConsentAllowsTheExternalGateway() throws Exception {
    when(cloudGateway.descriptor()).thenReturn(EXTERNAL);
    grantCurrentOwner("memo-transfer-v1");
    UUID memoId = createAmbiguousMemo("consent-exact");

    var started = startAnalysis(memoId, "consent-exact-start", 1);

    UUID runId = assertReviewRequired(started, memoId, "SUCCESS");
    assertEvidence(runId, EXTERNAL, "SUCCESS");
    verify(cloudGateway, times(1)).enrich(any(CloudAnalysisRequest.class));
  }

  @Test
  void anotherOwnersExactGrantDoesNotAuthorizeTheCurrentOwner() throws Exception {
    when(cloudGateway.descriptor()).thenReturn(EXTERNAL);
    grantOtherOwner("memo-transfer-v1");
    UUID memoId = createAmbiguousMemo("consent-other-owner");

    var started = startAnalysis(memoId, "consent-other-owner-start", 1);

    UUID runId = assertReviewRequired(started, memoId, "CONSENT_REQUIRED");
    assertEvidence(runId, EXTERNAL, "CONSENT_REQUIRED");
    verify(cloudGateway, never()).enrich(any(CloudAnalysisRequest.class));
  }

  @Test
  void aMismatchedOrRevokedGrantDoesNotAuthorizeTheGateway() throws Exception {
    when(cloudGateway.descriptor()).thenReturn(EXTERNAL);
    grantCurrentOwner("older-policy-v1");
    UUID mismatchedMemo = createAmbiguousMemo("consent-mismatch");

    var mismatched = startAnalysis(mismatchedMemo, "consent-mismatch-start", 1);

    UUID mismatchedRun = assertReviewRequired(mismatched, mismatchedMemo, "CONSENT_REQUIRED");
    assertEvidence(mismatchedRun, EXTERNAL, "CONSENT_REQUIRED");

    revokeCurrentOwner();
    UUID revokedMemo = createAmbiguousMemo("consent-revoked");
    var revoked = startAnalysis(revokedMemo, "consent-revoked-start", 1);

    UUID revokedRun = assertReviewRequired(revoked, revokedMemo, "CONSENT_REQUIRED");
    assertEvidence(revokedRun, EXTERNAL, "CONSENT_REQUIRED");
    verify(cloudGateway, never()).enrich(any(CloudAnalysisRequest.class));
  }

  @Test
  void aFutureDatedGrantDoesNotAuthorizeTheGateway() throws Exception {
    when(cloudGateway.descriptor()).thenReturn(EXTERNAL);
    grantCurrentOwner("memo-transfer-v1", Instant.parse("2099-01-01T00:00:00Z"));
    UUID memoId = createAmbiguousMemo("consent-future-grant");

    var started = startAnalysis(memoId, "consent-future-grant-start", 1);

    UUID runId = assertReviewRequired(started, memoId, "CONSENT_REQUIRED");
    assertEvidence(runId, EXTERNAL, "CONSENT_REQUIRED");
    verify(cloudGateway, never()).enrich(any(CloudAnalysisRequest.class));
  }

  @ParameterizedTest
  @EnumSource(CloudAnalysisFailureReason.class)
  void externalTypedFailurePersistsTheLocalProposalWithoutCanonicalChanges(
      CloudAnalysisFailureReason reason) throws Exception {
    when(cloudGateway.descriptor()).thenReturn(EXTERNAL);
    grantCurrentOwner("memo-transfer-v1");
    when(cloudGateway.enrich(any(CloudAnalysisRequest.class)))
        .thenReturn(CloudAnalysisResult.failure(reason));
    UUID memoId = createAmbiguousMemo("consent-" + reason.name().toLowerCase());

    var started = startAnalysis(memoId, "consent-" + reason.name().toLowerCase() + "-start", 1);

    UUID runId = assertReviewRequired(started, memoId, reason.name());
    assertEvidence(runId, EXTERNAL, reason.name());
    verify(cloudGateway, times(1)).enrich(any(CloudAnalysisRequest.class));
  }

  @Test
  void descriptorExceptionFallsBackWithoutCallingOrLeakingTheGateway() throws Exception {
    when(cloudGateway.descriptor())
        .thenThrow(new IllegalStateException("provider descriptor secret failure"));
    UUID memoId = createAmbiguousMemo("consent-descriptor-error");

    var started = startAnalysis(memoId, "consent-descriptor-error-start", 1);

    UUID runId = assertReviewRequired(started, memoId, "UNEXPECTED_FAILURE");
    CloudEvidence evidence = readEvidence(runId);
    assertThat(evidence.transferMode()).isEqualTo("DESCRIPTOR_UNAVAILABLE");
    assertThat(evidence.gatewayVersion()).isEqualTo("unavailable");
    assertThat(evidence.providerId()).isEqualTo("unavailable");
    assertThat(evidence.modelVersion()).isEqualTo("unavailable");
    assertThat(evidence.consentPolicyVersion()).isEqualTo("unavailable");
    assertThat(evidence.outcome()).isEqualTo("UNEXPECTED_FAILURE");
    assertThat(response(started).toString()).doesNotContain("descriptor secret");
    verify(cloudGateway, never()).enrich(any(CloudAnalysisRequest.class));
  }

  @Test
  void nearLimitLocalMetadataIsMinimizedSoAFailureStillPersistsAReviewProposal() throws Exception {
    when(localAnalyzer.analyze(
            any(UUID.class), anyInt(), anyString(), any(Instant.class), anyString()))
        .thenAnswer(
            invocation -> {
              ObjectNode proposal =
                  deterministicAnalyzer.analyze(
                      invocation.getArgument(0),
                      invocation.getArgument(1),
                      invocation.getArgument(2),
                      invocation.getArgument(3),
                      invocation.getArgument(4));
              ((ObjectNode) proposal.path("providerMetadata")).put("padding", "x".repeat(7600));
              return proposal;
            });
    when(cloudGateway.enrich(any(CloudAnalysisRequest.class)))
        .thenReturn(CloudAnalysisResult.failure(CloudAnalysisFailureReason.TIMEOUT));
    UUID memoId = createAmbiguousMemo("consent-near-limit");

    var started = startAnalysis(memoId, "consent-near-limit-start", 1);
    UUID runId = assertReviewRequired(started, memoId, "TIMEOUT");
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());
    var proposal = mvc.perform(get("/api/v1/analysis-proposals/{id}", proposalId)).andReturn();

    assertEvidence(runId, NO_NETWORK, "TIMEOUT");
    assertThat(response(proposal).at("/providerMetadata/padding").isMissingNode()).isTrue();
    assertThat(response(proposal).at("/providerMetadata/analyzerVersion").asText())
        .isEqualTo("fake-v6");
    assertThat(response(proposal).at("/providerMetadata/deterministicRulesVersion").asText())
        .isEqualTo("korean-rules-v4");
  }

  @Test
  void reservedCloudMetadataIsAlwaysOverwrittenByTheServer() throws Exception {
    when(cloudGateway.enrich(any(CloudAnalysisRequest.class)))
        .thenAnswer(
            invocation -> {
              ObjectNode proposal =
                  ((CloudAnalysisRequest) invocation.getArgument(0)).validatedLocalProposal();
              ObjectNode metadata = (ObjectNode) proposal.path("providerMetadata");
              metadata
                  .put("cloudGatewayVersion", "spoofed-gateway")
                  .put("cloudProviderId", "spoofed-provider")
                  .put("cloudModelVersion", "spoofed-model")
                  .put("cloudConsentPolicyVersion", "spoofed-policy")
                  .put("cloudOutcome", "spoofed-outcome")
                  .put("cloudMutationCalls", 99)
                  .put("toolCalls", 99)
                  .put("route", "spoofed-route")
                  .put("providerFailureText", "provider-secret-success-detail")
                  .put("rawMemoFragment", "must-not-be-stored");
              metadata.putArray("receivedRoutingReasons").add("spoofed-reason");
              return CloudAnalysisResult.success(proposal);
            });
    UUID memoId = createAmbiguousMemo("consent-metadata");

    var started = startAnalysis(memoId, "consent-metadata-start", 1);
    UUID runId = assertReviewRequired(started, memoId, "SUCCESS");
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());
    var proposal = mvc.perform(get("/api/v1/analysis-proposals/{id}", proposalId)).andReturn();

    assertEvidence(runId, NO_NETWORK, "SUCCESS");
    assertThat(response(proposal).at("/providerMetadata/cloudGatewayVersion").asText())
        .isEqualTo("test-local-v1");
    assertThat(response(proposal).at("/providerMetadata/cloudProviderId").asText())
        .isEqualTo("test-fake");
    assertThat(response(proposal).at("/providerMetadata/cloudOutcome").asText())
        .isEqualTo("SUCCESS");
    assertThat(response(proposal).at("/providerMetadata/cloudMutationCalls").asInt()).isZero();
    assertThat(response(proposal).at("/providerMetadata/toolCalls").asInt()).isZero();
    assertThat(response(proposal).at("/providerMetadata/route").asText()).isEqualTo("CLOUD_ENRICH");
    assertThat(response(proposal).at("/providerMetadata/providerFailureText").isMissingNode())
        .isTrue();
    assertThat(response(proposal).at("/providerMetadata/rawMemoFragment").isMissingNode()).isTrue();
    assertThat(response(proposal).at("/providerMetadata/receivedRoutingReasons").toString())
        .doesNotContain("spoofed");
  }

  private UUID createAmbiguousMemo(String keyPrefix) throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, keyPrefix + "-create", "전에 교수님이 말한 거 다음 주쯤 올리기");
    return memoId;
  }

  private UUID assertReviewRequired(
      org.springframework.test.web.servlet.MvcResult started,
      UUID memoId,
      String expectedCloudOutcome)
      throws Exception {
    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    UUID runId = UUID.fromString(response(started).path("id").asText());
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());
    assertThat(response(started).path("status").asText()).isEqualTo("REVIEW_REQUIRED");

    var proposal = mvc.perform(get("/api/v1/analysis-proposals/{id}", proposalId)).andReturn();
    assertThat(proposal.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(proposal).path("memoId").asText()).isEqualTo(memoId.toString());
    assertThat(response(proposal).at("/providerMetadata/cloudOutcome").asText())
        .isEqualTo(expectedCloudOutcome);
    assertThat(response(proposal).toString()).doesNotContain("provider failure");
    assertThat(
            db.sql("select content from memo_revisions where memo_id=:memoId and owner_id=:ownerId")
                .param("memoId", memoId)
                .param("ownerId", OWNER_ID)
                .query(String.class)
                .single())
        .isEqualTo("전에 교수님이 말한 거 다음 주쯤 올리기");
    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from task_details").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from analysis_applications").query(Long.class).single())
        .isZero();
    return runId;
  }

  private void assertEvidence(
      UUID runId, CloudGatewayDescriptor descriptor, String expectedOutcome) {
    CloudEvidence evidence = readEvidence(runId);
    assertThat(evidence.route()).isEqualTo("HYBRID");
    assertThat(evidence.status()).isEqualTo("REVIEW_REQUIRED");
    assertThat(evidence.transferMode()).isEqualTo(descriptor.transferMode().name());
    assertThat(evidence.gatewayVersion()).isEqualTo(descriptor.gatewayVersion());
    assertThat(evidence.providerId()).isEqualTo(descriptor.providerId());
    assertThat(evidence.modelVersion()).isEqualTo(descriptor.modelVersion());
    assertThat(evidence.consentPolicyVersion()).isEqualTo(descriptor.consentPolicyVersion());
    assertThat(evidence.outcome()).isEqualTo(expectedOutcome);
  }

  private CloudEvidence readEvidence(UUID runId) {
    return db.sql(
            """
            select route,
                   status,
                   cloud_transfer_mode,
                   cloud_gateway_version,
                   cloud_provider_id,
                   cloud_model_version,
                   cloud_consent_policy_version,
                   cloud_outcome
              from analysis_runs
             where id = :runId
               and owner_id = :ownerId
            """)
        .param("runId", runId)
        .param("ownerId", OWNER_ID)
        .query(
            (resultSet, rowNumber) ->
                new CloudEvidence(
                    resultSet.getString("route"),
                    resultSet.getString("status"),
                    resultSet.getString("cloud_transfer_mode"),
                    resultSet.getString("cloud_gateway_version"),
                    resultSet.getString("cloud_provider_id"),
                    resultSet.getString("cloud_model_version"),
                    resultSet.getString("cloud_consent_policy_version"),
                    resultSet.getString("cloud_outcome")))
        .single();
  }

  private void grantCurrentOwner(String policyVersion) {
    grantCurrentOwner(policyVersion, Instant.parse("2026-08-10T00:00:00Z"));
  }

  private void grantCurrentOwner(String policyVersion, Instant grantedAt) {
    db.sql(
            """
            update user_settings
               set cloud_analysis_consent = true,
                   cloud_analysis_consent_policy_version = :policyVersion,
                   cloud_analysis_consent_granted_at = :grantedAt,
                   settings_version = settings_version + 1
             where user_id = :ownerId
            """)
        .param("policyVersion", policyVersion)
        .param("grantedAt", Timestamp.from(grantedAt))
        .param("ownerId", OWNER_ID)
        .update();
  }

  private void revokeCurrentOwner() {
    db.sql(
            """
            update user_settings
               set cloud_analysis_consent = false,
                   cloud_analysis_consent_policy_version = null,
                   cloud_analysis_consent_granted_at = null,
                   settings_version = settings_version + 1
             where user_id = :ownerId
            """)
        .param("ownerId", OWNER_ID)
        .update();
  }

  private void grantOtherOwner(String policyVersion) {
    UUID ownerId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.parse("2026-08-10T00:00:00Z"));
    db.sql("insert into users(id,created_at,updated_at) values(:id,:now,:now)")
        .param("id", ownerId)
        .param("now", now)
        .update();
    db.sql(
            """
            insert into user_settings(
              user_id,
              time_zone,
              cloud_analysis_consent,
              cloud_analysis_consent_policy_version,
              cloud_analysis_consent_granted_at
            ) values (
              :ownerId,
              'Asia/Seoul',
              true,
              :policyVersion,
              :grantedAt
            )
            """)
        .param("ownerId", ownerId)
        .param("policyVersion", policyVersion)
        .param("grantedAt", now)
        .update();
  }

  private record CloudEvidence(
      String route,
      String status,
      String transferMode,
      String gatewayVersion,
      String providerId,
      String modelVersion,
      String consentPolicyVersion,
      String outcome) {}
}
