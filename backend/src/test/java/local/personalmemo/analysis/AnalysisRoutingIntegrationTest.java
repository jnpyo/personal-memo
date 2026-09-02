package local.personalmemo.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import local.personalmemo.analysis.domain.AnalysisProvenance;
import local.personalmemo.analysis.domain.CloudAnalysisExecutor;
import local.personalmemo.analysis.domain.CloudAnalysisGateway;
import local.personalmemo.analysis.domain.CloudAnalysisRequest;
import local.personalmemo.analysis.domain.CloudAnalysisResult;
import local.personalmemo.analysis.domain.CloudGatewayBinding;
import local.personalmemo.analysis.domain.LocalAnalyzer;
import local.personalmemo.analysis.infrastructure.FakeAnalyzer;
import local.personalmemo.analysis.infrastructure.FakeCloudAnalysisGateway;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.node.ObjectNode;

@PostgresIntegration
class AnalysisRoutingIntegrationTest extends PostgresIntegrationTestSupport {
  private static final AnalysisProvenance FAKE_PROVENANCE =
      new AnalysisProvenance("fake-v10", "none", "none", "none");

  @MockitoBean private LocalAnalyzer localAnalyzer;
  @MockitoBean private CloudAnalysisGateway cloudGateway;

  private FakeAnalyzer deterministicAnalyzer;
  private FakeCloudAnalysisGateway fakeCloudGateway;
  private CloudAnalysisExecutor cloudExecutor;

  @BeforeEach
  void useDeterministicFakesByDefault() {
    deterministicAnalyzer = new FakeAnalyzer(json);
    fakeCloudGateway = new FakeCloudAnalysisGateway();
    cloudExecutor = mock(CloudAnalysisExecutor.class);
    CloudGatewayBinding fakeBinding = fakeCloudGateway.bind();
    when(localAnalyzer.proposalSchemaVersion()).thenReturn("2");
    when(localAnalyzer.provenance()).thenReturn(FAKE_PROVENANCE);
    when(cloudGateway.bind())
        .thenReturn(new CloudGatewayBinding(fakeBinding.descriptor(), cloudExecutor));
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
    when(cloudExecutor.execute(any(CloudAnalysisRequest.class)))
        .thenAnswer(invocation -> fakeBinding.execute(invocation.getArgument(0)));
  }

  @Test
  void clearProposalStaysLocalAndNeverCallsCloud() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "route-local-create", "2026.11.25 18:00 OS 과제 제출");

    var started = startAnalysis(memoId, "route-local-start", 1);
    UUID runId = UUID.fromString(response(started).path("id").asText());
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());
    var proposal = mvc.perform(get("/api/v1/analysis-proposals/{id}", proposalId)).andReturn();

    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    assertRun(runId, "LOCAL");
    assertThat(response(proposal).at("/tagCandidates/0/existingTagId").asText())
        .isEqualTo(OPERATING_SYSTEMS_TAG_ID.toString());
    assertThat(response(proposal).at("/tagCandidates/0/canonicalName").asText()).isEqualTo("운영체제");
    assertThat(response(proposal).at("/tagCandidates/0/matchedAlias").asText()).isEqualTo("OS");
    assertThat(response(proposal).at("/tagCandidates/0/isNewProposal").asBoolean()).isFalse();
    assertThat(response(proposal).at("/tagCandidates/1/existingTagId").asText())
        .isEqualTo(ASSIGNMENT_TAG_ID.toString());
    assertThat(response(proposal).path("ambiguityReasons").toString()).doesNotContain("NEW_TOPIC");
    assertThat(response(proposal).at("/providerMetadata/route").asText()).isEqualTo("LOCAL_REVIEW");
    verify(cloudExecutor, never()).execute(any(CloudAnalysisRequest.class));
    assertCanonicalDataWasNotChanged();
  }

  @Test
  void localProviderMetadataIsReducedToTheServerAllowlist() throws Exception {
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
              ((ObjectNode) proposal.path("providerMetadata"))
                  .put("cloudOutcome", "SUCCESS")
                  .put("cloudProviderId", "spoofed-provider")
                  .put("approvedCorrectionHints", "private-approved-text")
                  .put("invocationMode", "spoofed-mode")
                  .put("providerFailureText", "provider-secret-local-detail")
                  .put("rawMemoFragment", "must-not-be-stored");
              return proposal;
            });
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "route-local-metadata-create", "2026.11.25 18:00 OS 과제 제출");

    var started = startAnalysis(memoId, "route-local-metadata-start", 1);
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());
    var proposal = mvc.perform(get("/api/v1/analysis-proposals/{id}", proposalId)).andReturn();

    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(proposal).at("/providerMetadata/route").asText()).isEqualTo("LOCAL_REVIEW");
    assertThat(response(proposal).at("/providerMetadata/cloudOutcome").isMissingNode()).isTrue();
    assertThat(response(proposal).at("/providerMetadata/cloudProviderId").isMissingNode()).isTrue();
    assertThat(response(proposal).at("/providerMetadata/approvedCorrectionHints").isMissingNode())
        .isTrue();
    assertThat(response(proposal).at("/providerMetadata/invocationMode").isMissingNode()).isTrue();
    assertThat(response(proposal).at("/providerMetadata/providerFailureText").isMissingNode())
        .isTrue();
    assertThat(response(proposal).at("/providerMetadata/rawMemoFragment").isMissingNode()).isTrue();
    verify(cloudExecutor, never()).execute(any(CloudAnalysisRequest.class));
    assertCanonicalDataWasNotChanged();
  }

  @Test
  void ownerNeutralCandidateNeverResolvesToAnotherOwnersMatchingTag() throws Exception {
    UUID otherOwnerId = UUID.randomUUID();
    UUID otherTagId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.parse("2026-08-05T02:00:00Z"));
    db.sql("insert into users(id,created_at,updated_at) values(:id,:now,:now)")
        .param("id", otherOwnerId)
        .param("now", now)
        .update();
    db.sql(
            "insert into tags(id,owner_id,canonical_name,normalized_name,state,created_at,updated_at) "
                + "values(:id,:ownerId,'유리패드','유리패드','ACTIVE',:now,:now)")
        .param("id", otherTagId)
        .param("ownerId", otherOwnerId)
        .param("now", now)
        .update();
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "route-other-owner-name-create", "유리패드 마모 상태 다음 달에 다시 확인");

    var started = startAnalysis(memoId, "route-other-owner-name-start", 1);
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());
    var proposal = mvc.perform(get("/api/v1/analysis-proposals/{id}", proposalId)).andReturn();

    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(proposal).at("/tagCandidates/0/existingTagId").isNull()).isTrue();
    assertThat(response(proposal).at("/tagCandidates/0/isNewProposal").asBoolean()).isTrue();
    assertThat(response(proposal).toString()).doesNotContain(otherTagId.toString());
    assertThat(
            db.sql("select count(*) from tags where owner_id=:ownerId")
                .param("ownerId", OWNER_ID)
                .query(Long.class)
                .single())
        .isEqualTo(2L);
  }

  @Test
  void ambiguousProposalCallsFakeCloudOnceAndPersistsOnlyAReviewProposal() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "route-hybrid-create", "전에 교수님이 말한 거 다음 주쯤 올리기");

    var started = startAnalysis(memoId, "route-hybrid-start", 1);
    UUID runId = UUID.fromString(response(started).path("id").asText());
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());
    var proposal = mvc.perform(get("/api/v1/analysis-proposals/{id}", proposalId)).andReturn();

    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    assertRun(runId, "HYBRID");
    verify(cloudExecutor, times(1)).execute(any(CloudAnalysisRequest.class));
    assertThat(proposal.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(proposal).at("/providerMetadata/cloudGatewayVersion").asText())
        .isEqualTo("fake-cloud-v2");
    assertThat(response(proposal).at("/providerMetadata/cloudTransferMode").asText())
        .isEqualTo("NO_NETWORK");
    assertThat(response(proposal).at("/providerMetadata/cloudOutcome").asText())
        .isEqualTo("SUCCESS");
    assertThat(response(proposal).at("/providerMetadata/cloudToolCalls").asInt()).isZero();
    assertThat(response(proposal).at("/providerMetadata/cloudMutationCalls").asInt()).isZero();
    assertCanonicalDataWasNotChanged();
  }

  @Test
  void actionlessEventAlternativeKeepsItsItemTypeAcrossTheValidatedCloudRoute() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "route-event-alternative-create", "10월 3일 회의 또는 동창회");

    var started = startAnalysis(memoId, "route-event-alternative-start", 1);
    UUID runId = UUID.fromString(response(started).path("id").asText());
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());
    var proposal = mvc.perform(get("/api/v1/analysis-proposals/{id}", proposalId)).andReturn();

    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    assertRun(runId, "HYBRID");
    assertThat(response(proposal).at("/typeCandidates/0/value").asText()).isEqualTo("EVENT");
    assertThat(response(proposal).at("/itemCandidates/0/kind").asText()).isEqualTo("EVENT");
    assertThat(response(proposal).path("ambiguityReasons").toString()).contains("MULTI_INTENT");
    verify(cloudExecutor, times(1)).execute(any(CloudAnalysisRequest.class));
    assertCanonicalDataWasNotChanged();
  }

  @Test
  void hiddenNestedDateAmbiguityIsRejectedBeforeRouting() throws Exception {
    UUID memoId = UUID.randomUUID();
    String raw = "다음 주쯤 과제 제출";
    createMemo(memoId, "route-hidden-date-create", raw);
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
              proposal.putArray("ambiguityReasons");
              return proposal;
            });

    var failed = startAnalysis(memoId, "route-hidden-date-start", 1);

    assertThat(failed.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(failed).path("code").asText()).isEqualTo("INVALID_ANALYSIS_PROPOSAL");
    verify(cloudExecutor, never()).execute(any(CloudAnalysisRequest.class));
    assertFailedAnalysisLeftOnlyRawMemo(memoId, raw);
  }

  @Test
  void lowTypeScoreIsDerivedByTheServerAndCannotBypassCloudRouting() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "route-low-score-create", "과제 제출");
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
              ((ObjectNode) proposal.at("/typeCandidates/0")).put("score", 0.65);
              return proposal;
            });

    var started = startAnalysis(memoId, "route-low-score-start", 1);
    UUID runId = UUID.fromString(response(started).path("id").asText());
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());
    var proposal = mvc.perform(get("/api/v1/analysis-proposals/{id}", proposalId)).andReturn();

    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    assertRun(runId, "HYBRID");
    assertThat(runAmbiguityReasons(runId)).contains("LOW_TYPE_MARGIN");
    assertThat(response(proposal).at("/providerMetadata/receivedRoutingReasons").toString())
        .contains("LOW_TYPE_MARGIN");
    assertThat(response(proposal).at("/providerMetadata/receivedRoutingPolicyVersion").asText())
        .isEqualTo("field-policy-v2");
    verify(cloudExecutor, times(1)).execute(any(CloudAnalysisRequest.class));
  }

  @Test
  void datesBeyondTheProposalLimitEscalateInsteadOfDisappearing() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(
        memoId,
        "route-date-limit-create",
        "2026.08.06 09:00 2026.08.07 09:00 2026.08.08 09:00 "
            + "2026.08.09 09:00 2026.08.10 09:00 2026.08.11 09:00 과제 제출");

    var started = startAnalysis(memoId, "route-date-limit-start", 1);
    UUID runId = UUID.fromString(response(started).path("id").asText());

    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    assertRun(runId, "HYBRID");
    assertThat(runAmbiguityReasons(runId)).contains("CANDIDATE_LIMIT_EXCEEDED");
    verify(cloudExecutor, times(1)).execute(any(CloudAnalysisRequest.class));
  }

  @Test
  void hybridRunKeepsTheLocalRoutingReasonsWhenCloudResolvesThem() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "route-provenance-create", "다음 주쯤 과제 제출");
    when(cloudExecutor.execute(any(CloudAnalysisRequest.class)))
        .thenAnswer(
            invocation -> {
              CloudAnalysisRequest request = invocation.getArgument(0);
              ObjectNode enriched = request.validatedLocalProposal();
              ObjectNode date = (ObjectNode) enriched.at("/dateCandidates/0");
              date.put("value", "2026-08-12")
                  .put("precision", "DATE_ONLY")
                  .put("timeSpecified", false)
                  .putArray("ambiguityReasons");
              enriched.putArray("ambiguityReasons");
              return CloudAnalysisResult.success(enriched);
            });

    var started = startAnalysis(memoId, "route-provenance-start", 1);
    var replay = startAnalysis(memoId, "route-provenance-start", 1);
    UUID runId = UUID.fromString(response(started).path("id").asText());
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());
    var proposal = mvc.perform(get("/api/v1/analysis-proposals/{id}", proposalId)).andReturn();

    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(replay)).isEqualTo(response(started));
    assertRun(runId, "HYBRID");
    assertThat(runAmbiguityReasons(runId)).contains("IMPRECISE_DATE");
    assertThat(response(proposal).path("ambiguityReasons")).isEmpty();
    verify(cloudExecutor, times(1)).execute(any(CloudAnalysisRequest.class));
  }

  @Test
  void replayingAnAmbiguousStartDoesNotCallEitherAnalyzerTwice() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "route-idempotent-create", "전에 교수님이 말한 거 다음 주쯤 올리기");

    var first = startAnalysis(memoId, "route-idempotent-start", 1);
    var replay = startAnalysis(memoId, "route-idempotent-start", 1);

    assertThat(first.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(replay)).isEqualTo(response(first));
    verify(localAnalyzer, times(1))
        .analyze(any(UUID.class), anyInt(), anyString(), any(Instant.class), anyString());
    verify(cloudExecutor, times(1)).execute(any(CloudAnalysisRequest.class));
    assertThat(db.sql("select count(*) from analysis_runs").query(Long.class).single())
        .isEqualTo(1L);
    assertThat(db.sql("select count(*) from analysis_proposals").query(Long.class).single())
        .isEqualTo(1L);
  }

  @Test
  void unknownLocalAmbiguityReasonFailsBeforeRoutingAndPreservesRawMemo() throws Exception {
    UUID memoId = UUID.randomUUID();
    String raw = "2026.11.25 18:00 OS 과제 제출";
    createMemo(memoId, "route-unknown-create", raw);
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
              proposal.putArray("ambiguityReasons").add("UNKNOWN_FUTURE_SIGNAL");
              return proposal;
            });

    var failed = startAnalysis(memoId, "route-unknown-start", 1);

    assertThat(failed.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(failed).path("code").asText()).isEqualTo("INVALID_ANALYSIS_PROPOSAL");
    verify(cloudExecutor, never()).execute(any(CloudAnalysisRequest.class));
    assertFailedAnalysisLeftOnlyRawMemo(memoId, raw);
  }

  @Test
  void invalidCloudProposalFallsBackToTheValidatedLocalProposal() throws Exception {
    UUID memoId = UUID.randomUUID();
    String raw = "전에 교수님이 말한 거 다음 주쯤 올리기";
    createMemo(memoId, "route-cloud-invalid-create", raw);
    when(cloudExecutor.execute(any(CloudAnalysisRequest.class)))
        .thenAnswer(
            invocation -> {
              CloudAnalysisRequest request = invocation.getArgument(0);
              ObjectNode invalid = request.validatedLocalProposal();
              invalid.remove("suggestedTitle");
              return CloudAnalysisResult.success(invalid);
            });

    var started = startAnalysis(memoId, "route-cloud-invalid-start", 1);

    assertCloudFallback(started, memoId, raw, "INVALID_RESPONSE");
    verify(cloudExecutor, times(1)).execute(any(CloudAnalysisRequest.class));
  }

  @Test
  void cloudProposalWithAnUnownedReferenceFallsBackBeforePersistence() throws Exception {
    UUID memoId = UUID.randomUUID();
    String raw = "전에 교수님이 말한 거 다음 주쯤 올리기";
    createMemo(memoId, "route-cloud-reference-create", raw);
    when(cloudExecutor.execute(any(CloudAnalysisRequest.class)))
        .thenAnswer(
            invocation -> {
              CloudAnalysisRequest request = invocation.getArgument(0);
              ObjectNode invalid = request.validatedLocalProposal();
              invalid
                  .putArray("tagCandidates")
                  .add(
                      json.createObjectNode()
                          .put("existingTagId", UUID.randomUUID().toString())
                          .put("canonicalName", "unavailable tag")
                          .putNull("matchedAlias")
                          .put("score", 0.9)
                          .put("isNewProposal", false));
              return CloudAnalysisResult.success(invalid);
            });

    var started = startAnalysis(memoId, "route-cloud-reference-start", 1);

    assertCloudFallback(started, memoId, raw, "INVALID_RESPONSE");
    verify(cloudExecutor, times(1)).execute(any(CloudAnalysisRequest.class));
  }

  @Test
  void cloudGatewayExceptionFallsBackWithoutLeakingProviderTextAndReplays() throws Exception {
    UUID memoId = UUID.randomUUID();
    String raw = "전에 교수님이 말한 거 다음 주쯤 올리기";
    createMemo(memoId, "route-cloud-error-create", raw);
    when(cloudExecutor.execute(any(CloudAnalysisRequest.class)))
        .thenThrow(new IllegalStateException("simulated provider failure"));

    var started = startAnalysis(memoId, "route-cloud-error-start", 1);
    var replay = startAnalysis(memoId, "route-cloud-error-start", 1);

    assertThat(response(replay)).isEqualTo(response(started));
    assertThat(response(started).toString()).doesNotContain("simulated provider");
    assertCloudFallback(started, memoId, raw, "UNEXPECTED_FAILURE");
    verify(cloudExecutor, times(1)).execute(any(CloudAnalysisRequest.class));
  }

  @Test
  void localProposalCannotContradictServerOwnedProvenance() throws Exception {
    UUID memoId = UUID.randomUUID();
    String raw = "2026.11.25 18:00 OS 과제 제출";
    createMemo(memoId, "route-local-provenance-create", raw);
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
              ((ObjectNode) proposal.path("providerMetadata"))
                  .put("promptVersion", "provider-claimed-version");
              return proposal;
            });

    var failed = startAnalysis(memoId, "route-local-provenance-start", 1);

    assertThat(failed.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(failed).path("code").asText()).isEqualTo("INVALID_ANALYSIS_PROPOSAL");
    verify(cloudExecutor, never()).execute(any(CloudAnalysisRequest.class));
    assertFailedAnalysisLeftOnlyRawMemo(memoId, raw);
  }

  @Test
  void localProposalCannotDowngradeTheServerOwnedSchemaContract() throws Exception {
    UUID memoId = UUID.randomUUID();
    String raw = "2026.11.25 18:00 OS 과제 제출";
    createMemo(memoId, "route-local-schema-create", raw);
    when(localAnalyzer.analyze(
            any(UUID.class), anyInt(), anyString(), any(Instant.class), anyString()))
        .thenAnswer(
            invocation ->
                deterministicAnalyzer
                    .analyze(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        invocation.getArgument(4))
                    .put("schemaVersion", "1"));

    var failed = startAnalysis(memoId, "route-local-schema-start", 1);

    assertThat(failed.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(failed).path("code").asText()).isEqualTo("INVALID_ANALYSIS_PROPOSAL");
    verify(cloudExecutor, never()).execute(any(CloudAnalysisRequest.class));
    assertFailedAnalysisLeftOnlyRawMemo(memoId, raw);
  }

  @Test
  void cloudSchemaDowngradeFallsBackToTheValidatedLocalProposal() throws Exception {
    UUID memoId = UUID.randomUUID();
    String raw = "전에 교수님이 말한 거 다음 주쯤 올리기";
    createMemo(memoId, "route-cloud-schema-create", raw);
    when(cloudExecutor.execute(any(CloudAnalysisRequest.class)))
        .thenAnswer(
            invocation ->
                CloudAnalysisResult.success(
                    ((CloudAnalysisRequest) invocation.getArgument(0))
                        .validatedLocalProposal()
                        .put("schemaVersion", "1")));

    var started = startAnalysis(memoId, "route-cloud-schema-start", 1);

    assertCloudFallback(started, memoId, raw, "INVALID_RESPONSE");
    verify(cloudExecutor, times(1)).execute(any(CloudAnalysisRequest.class));
  }

  @Test
  void cloudProposalCannotRemoveServerOwnedProvenanceAndFallsBack() throws Exception {
    UUID memoId = UUID.randomUUID();
    String raw = "전에 교수님이 말한 거 다음 주쯤 올리기";
    createMemo(memoId, "route-cloud-provenance-create", raw);
    when(cloudExecutor.execute(any(CloudAnalysisRequest.class)))
        .thenAnswer(
            invocation -> {
              ObjectNode enriched =
                  ((CloudAnalysisRequest) invocation.getArgument(0)).validatedLocalProposal();
              ((ObjectNode) enriched.path("providerMetadata")).remove("embeddingModelVersion");
              return CloudAnalysisResult.success(enriched);
            });

    var started = startAnalysis(memoId, "route-cloud-provenance-start", 1);

    assertCloudFallback(started, memoId, raw, "INVALID_RESPONSE");
    verify(cloudExecutor, times(1)).execute(any(CloudAnalysisRequest.class));
  }

  @Test
  void cloudProposalCannotChangeServerOwnedRoutingPolicyVersionAndFallsBack() throws Exception {
    UUID memoId = UUID.randomUUID();
    String raw = "전에 교수님이 말한 거 다음 주쯤 올리기";
    createMemo(memoId, "route-cloud-policy-provenance-create", raw);
    when(cloudExecutor.execute(any(CloudAnalysisRequest.class)))
        .thenAnswer(
            invocation -> {
              ObjectNode enriched =
                  ((CloudAnalysisRequest) invocation.getArgument(0)).validatedLocalProposal();
              ((ObjectNode) enriched.path("providerMetadata"))
                  .put("routingPolicyVersion", "provider-overwrite-v1");
              return CloudAnalysisResult.success(enriched);
            });

    var started = startAnalysis(memoId, "route-cloud-policy-provenance-start", 1);

    assertCloudFallback(started, memoId, raw, "INVALID_RESPONSE");
    verify(cloudExecutor, times(1)).execute(any(CloudAnalysisRequest.class));
  }

  @Test
  void staleRevisionFailsBeforeLocalOrCloudAnalysis() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "route-stale-create", "전에 교수님이 말한 거 다음 주쯤 올리기");
    updateMemo(memoId, 1, "revision two");

    var failed = startAnalysis(memoId, "route-stale-start", 1);

    assertThat(failed.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(failed).path("code").asText()).isEqualTo("STALE_MEMO_REVISION");
    verifyNoInteractions(localAnalyzer, cloudGateway);
    assertThat(db.sql("select count(*) from analysis_runs").query(Long.class).single()).isZero();
  }

  private void assertRun(UUID runId, String expectedRoute) {
    RunState state =
        db.sql(
                "select route, status, schema_version, analyzer_version, prompt_version, "
                    + "local_model_version, "
                    + "embedding_model_version, routing_policy_version "
                    + "from analysis_runs "
                    + "where id=:runId and owner_id=:ownerId")
            .param("runId", runId)
            .param("ownerId", OWNER_ID)
            .query(
                (resultSet, rowNumber) ->
                    new RunState(
                        resultSet.getString("route"),
                        resultSet.getString("status"),
                        resultSet.getString("schema_version"),
                        resultSet.getString("analyzer_version"),
                        resultSet.getString("prompt_version"),
                        resultSet.getString("local_model_version"),
                        resultSet.getString("embedding_model_version"),
                        resultSet.getString("routing_policy_version")))
            .single();
    assertThat(state.route()).isEqualTo(expectedRoute);
    assertThat(state.status()).isEqualTo("REVIEW_REQUIRED");
    assertThat(state.schemaVersion()).isEqualTo("2");
    assertThat(state.analyzerVersion()).isEqualTo("fake-v10");
    assertThat(state.promptVersion()).isEqualTo("none");
    assertThat(state.localModelVersion()).isEqualTo("none");
    assertThat(state.embeddingModelVersion()).isEqualTo("none");
    assertThat(state.routingPolicyVersion()).isEqualTo("field-policy-v2");
  }

  private String runAmbiguityReasons(UUID runId) {
    return db.sql(
            "select ambiguity_reasons::text from analysis_runs "
                + "where id=:runId and owner_id=:ownerId")
        .param("runId", runId)
        .param("ownerId", OWNER_ID)
        .query(String.class)
        .single();
  }

  private CloudEvidence cloudEvidence(UUID runId) {
    return db.sql(
            """
            select cloud_transfer_mode,
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
                    resultSet.getString("cloud_transfer_mode"),
                    resultSet.getString("cloud_gateway_version"),
                    resultSet.getString("cloud_provider_id"),
                    resultSet.getString("cloud_model_version"),
                    resultSet.getString("cloud_consent_policy_version"),
                    resultSet.getString("cloud_outcome")))
        .single();
  }

  private void assertCloudFallback(
      org.springframework.test.web.servlet.MvcResult started,
      UUID memoId,
      String expectedContent,
      String expectedOutcome)
      throws Exception {
    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    UUID runId = UUID.fromString(response(started).path("id").asText());
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());
    assertRun(runId, "HYBRID");

    CloudEvidence evidence = cloudEvidence(runId);
    assertThat(evidence.transferMode()).isEqualTo("NO_NETWORK");
    assertThat(evidence.gatewayVersion()).isEqualTo("fake-cloud-v2");
    assertThat(evidence.providerId()).isEqualTo("fake");
    assertThat(evidence.modelVersion()).isEqualTo("none");
    assertThat(evidence.consentPolicyVersion()).isEqualTo("no-network-v1");
    assertThat(evidence.outcome()).isEqualTo(expectedOutcome);

    var storedProposal =
        mvc.perform(get("/api/v1/analysis-proposals/{id}", proposalId)).andReturn();
    assertThat(storedProposal.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(storedProposal).path("suggestedTitle").isObject()).isTrue();
    assertThat(response(storedProposal).at("/providerMetadata/cloudOutcome").asText())
        .isEqualTo(expectedOutcome);
    assertThat(response(storedProposal).at("/providerMetadata/cloudGatewayVersion").asText())
        .isEqualTo("fake-cloud-v2");
    assertThat(response(storedProposal).toString()).doesNotContain("provider failure");
    assertThat(
            db.sql("select content from memo_revisions where memo_id=:memoId and owner_id=:ownerId")
                .param("memoId", memoId)
                .param("ownerId", OWNER_ID)
                .query(String.class)
                .single())
        .isEqualTo(expectedContent);
    assertThat(db.sql("select count(*) from analysis_runs").query(Long.class).single())
        .isEqualTo(1L);
    assertThat(db.sql("select count(*) from analysis_proposals").query(Long.class).single())
        .isEqualTo(1L);
    assertThat(
            db.sql("select count(*) from idempotency_records where operation='ANALYSIS_START'")
                .query(Long.class)
                .single())
        .isEqualTo(1L);
    assertCanonicalDataWasNotChanged();
  }

  private void assertCanonicalDataWasNotChanged() {
    assertThat(db.sql("select count(*) from tags").query(Long.class).single()).isEqualTo(2L);
    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from task_details").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from analysis_applications").query(Long.class).single())
        .isZero();
  }

  private void assertFailedAnalysisLeftOnlyRawMemo(UUID memoId, String expectedContent) {
    assertThat(
            db.sql("select content from memo_revisions where memo_id=:memoId and owner_id=:ownerId")
                .param("memoId", memoId)
                .param("ownerId", OWNER_ID)
                .query(String.class)
                .single())
        .isEqualTo(expectedContent);
    assertThat(db.sql("select count(*) from analysis_runs").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from analysis_proposals").query(Long.class).single())
        .isZero();
    assertThat(
            db.sql("select count(*) from idempotency_records where operation='ANALYSIS_START'")
                .query(Long.class)
                .single())
        .isZero();
    assertCanonicalDataWasNotChanged();
  }

  private record RunState(
      String route,
      String status,
      String schemaVersion,
      String analyzerVersion,
      String promptVersion,
      String localModelVersion,
      String embeddingModelVersion,
      String routingPolicyVersion) {}

  private record CloudEvidence(
      String transferMode,
      String gatewayVersion,
      String providerId,
      String modelVersion,
      String consentPolicyVersion,
      String outcome) {}
}
