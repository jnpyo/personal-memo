package local.personalmemo.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import local.personalmemo.analysis.domain.AnalysisProvenance;
import local.personalmemo.analysis.domain.CloudAnalysisGateway;
import local.personalmemo.analysis.domain.CloudAnalysisRequest;
import local.personalmemo.analysis.domain.CloudAnalysisResult;
import local.personalmemo.analysis.domain.CloudGatewayBinding;
import local.personalmemo.analysis.domain.CloudGatewayDescriptor;
import local.personalmemo.analysis.domain.CloudTransferMode;
import local.personalmemo.analysis.domain.LocalAnalyzer;
import local.personalmemo.analysis.infrastructure.FakeAnalyzer;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.node.ObjectNode;

@PostgresIntegration
@TestPropertySource(
    properties = {
      "app.analysis.invocation.mode=AI_PREFERRED",
      "app.analysis.invocation.approved-corrections-enabled=true",
      "app.analysis.invocation.approved-correction-context-k=3",
      "app.analysis.cloud-execution.timeout=2s"
    })
class AiPreferredAnalysisIntegrationTest extends PostgresIntegrationTestSupport {
  private static final AnalysisProvenance FAKE_PROVENANCE =
      new AnalysisProvenance("fake-v10", "none", "none", "none");
  private static final CloudGatewayDescriptor LOCAL_MODEL =
      new CloudGatewayDescriptor(
          "ai-preferred-integration-v1",
          "localhost-ollama-test",
          "public-synthetic-model-v1",
          "local-machine-v1",
          CloudTransferMode.LOCAL_MACHINE_MEMO_CONTENT);

  @MockitoBean private LocalAnalyzer localAnalyzer;
  @MockitoBean private CloudAnalysisGateway cloudGateway;

  private FakeAnalyzer deterministicAnalyzer;
  private List<CloudAnalysisRequest> observedRequests;

  @BeforeEach
  void useSuccessfulMachineLocalGateway() {
    deterministicAnalyzer = new FakeAnalyzer(json);
    observedRequests = new CopyOnWriteArrayList<>();
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
    when(cloudGateway.bind())
        .thenReturn(
            new CloudGatewayBinding(
                LOCAL_MODEL,
                request -> {
                  observedRequests.add(request);
                  return CloudAnalysisResult.success(request.validatedLocalProposal());
                }));
  }

  @Test
  void clearProposalInvokesLocalModelWithoutInventingAmbiguityAndScrubsContext() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "ai-preferred-clear-create", "2026.11.25 18:00 OS 과제 제출");

    var started = startAnalysis(memoId, "ai-preferred-clear-start", 1);

    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    UUID runId = UUID.fromString(response(started).path("id").asText());
    CloudAnalysisRequest request = observedRequests.getFirst();
    assertThat(request).isNotNull();
    assertThat(request.routingReasons()).isEmpty();
    assertThat(request.localModelInput()).isPresent();
    assertThat(request.localModelInput().orElseThrow().approvedCorrectionHints()).isEmpty();
    verify(cloudGateway, times(1)).bind();

    DurableEvidence evidence =
        db.sql(
                """
                select r.route,
                       r.ambiguity_reasons::text as ambiguity_reasons,
                       d.invocation_policy_version,
                       d.invocation_mode,
                       d.invocation_reason_code,
                       d.fallback_reason_codes::text as fallback_reason_codes,
                       d.model_contribution_status,
                       d.approved_correction_context,
                       d.approved_correction_context_hash,
                       d.approved_correction_context_version,
                       d.approved_correction_context_count,
                       d.state
                  from analysis_runs r
                  join analysis_run_dispatches d
                    on d.analysis_run_id = r.id
                   and d.owner_id = r.owner_id
                 where r.id = :runId
                   and r.owner_id = :ownerId
                """)
            .param("runId", runId)
            .param("ownerId", OWNER_ID)
            .query(
                (resultSet, rowNumber) ->
                    new DurableEvidence(
                        resultSet.getString("route"),
                        resultSet.getString("ambiguity_reasons"),
                        resultSet.getString("invocation_policy_version"),
                        resultSet.getString("invocation_mode"),
                        resultSet.getString("invocation_reason_code"),
                        resultSet.getString("fallback_reason_codes"),
                        resultSet.getString("model_contribution_status"),
                        resultSet.getString("approved_correction_context"),
                        resultSet.getString("approved_correction_context_hash"),
                        resultSet.getString("approved_correction_context_version"),
                        resultSet.getInt("approved_correction_context_count"),
                        resultSet.getString("state")))
            .single();
    assertThat(evidence.route()).isEqualTo("HYBRID");
    assertThat(evidence.ambiguityReasons()).isEqualTo("[]");
    assertThat(evidence.invocationPolicyVersion()).isEqualTo("model-invocation-v1");
    assertThat(evidence.invocationMode()).isEqualTo("AI_PREFERRED");
    assertThat(evidence.invocationReasonCode()).isEqualTo("AI_PREFERRED_POLICY");
    assertThat(evidence.fallbackReasonCodes()).isEqualTo("[]");
    assertThat(evidence.modelContributionStatus()).isEqualTo("ACCEPTED_UNCHANGED");
    assertThat(evidence.approvedCorrectionContext()).isNull();
    assertThat(evidence.approvedCorrectionContextHash()).matches("[0-9a-f]{64}");
    assertThat(evidence.approvedCorrectionContextVersion()).isEqualTo("approved-type-anchor-k3-v1");
    assertThat(evidence.approvedCorrectionContextCount()).isZero();
    assertThat(evidence.state()).isEqualTo("FINALIZED");
    assertThat(
            db.sql("select count(*) from memo_items where owner_id=:ownerId")
                .param("ownerId", OWNER_ID)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void laterLocalRequestReceivesOnlyTheMatchingApprovedTypeAnchor() throws Exception {
    when(localAnalyzer.analyze(
            any(UUID.class), anyInt(), anyString(), any(Instant.class), anyString()))
        .thenAnswer(
            invocation ->
                forcedRecordProposal(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    invocation.getArgument(4)));
    UUID approvedMemoId = UUID.randomUUID();
    createMemo(approvedMemoId, "approved-anchor-source-create", "디스코드 접속하기");
    var sourceRun = startAnalysis(approvedMemoId, "approved-anchor-source-start", 1);
    UUID sourceProposalId = UUID.fromString(response(sourceRun).path("proposalId").asText());
    Map<String, Object> approvedItem = new java.util.LinkedHashMap<>();
    approvedItem.put("kind", "TASK");
    approvedItem.put("title", "디스코드 접속하기");
    approvedItem.put("due", null);

    var applied =
        applyProposal(
            sourceProposalId,
            "approved-anchor-source-apply",
            Map.of(
                "expectedMemoRevision",
                1,
                "selectedType",
                "TASK",
                "title",
                "디스코드 접속하기",
                "selectedTags",
                List.of(),
                "items",
                List.of(approvedItem)));
    assertThat(applied.getResponse().getStatus()).isEqualTo(200);

    UUID targetMemoId = UUID.randomUUID();
    createMemo(targetMemoId, "approved-anchor-target-create", "게임 서버 접속하기");
    var targetRun = startAnalysis(targetMemoId, "approved-anchor-target-start", 1);

    assertThat(targetRun.getResponse().getStatus()).isEqualTo(200);
    assertThat(observedRequests).hasSize(2);
    CloudAnalysisRequest targetRequest = observedRequests.get(1);
    assertThat(targetRequest.localModelInput()).isPresent();
    assertThat(targetRequest.localModelInput().orElseThrow().approvedCorrectionHints())
        .containsExactly(
            new local.personalmemo.analysis.domain.ApprovedCorrectionContext.Hint("접속하기", "TASK"));
    assertThat(targetRequest.toString())
        .doesNotContain("디스코드", approvedMemoId.toString(), sourceProposalId.toString());
  }

  private ObjectNode forcedRecordProposal(
      UUID memoId, int revision, String content, Instant recordedAt, String timeZone) {
    ObjectNode proposal =
        deterministicAnalyzer.analyze(memoId, revision, content, recordedAt, timeZone);
    proposal
        .putArray("typeCandidates")
        .add(json.createObjectNode().put("value", "RECORD").put("score", 0.99));
    proposal.putArray("dateCandidates");
    proposal.putArray("tagCandidates");
    ObjectNode item = (ObjectNode) proposal.path("itemCandidates").path(0);
    item.put("kind", "RECORD").putNull("action").putNull("object").putNull("dueDateCandidateId");
    proposal.putArray("relationCandidates");
    proposal.putArray("ambiguityReasons");
    ((ObjectNode) proposal.path("providerMetadata"))
        .put("route", "LOCAL_REVIEW")
        .put("classificationBasis", "EXPLICIT_RULE")
        .put("unparsedTemporalCueCount", 0)
        .put("unrecognizedActionCueCount", 0);
    return proposal;
  }

  private record DurableEvidence(
      String route,
      String ambiguityReasons,
      String invocationPolicyVersion,
      String invocationMode,
      String invocationReasonCode,
      String fallbackReasonCodes,
      String modelContributionStatus,
      String approvedCorrectionContext,
      String approvedCorrectionContextHash,
      String approvedCorrectionContextVersion,
      int approvedCorrectionContextCount,
      String state) {}
}
