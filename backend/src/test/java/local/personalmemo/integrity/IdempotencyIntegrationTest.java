package local.personalmemo.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import local.personalmemo.analysis.domain.CloudAnalysisExecutor;
import local.personalmemo.analysis.domain.CloudAnalysisGateway;
import local.personalmemo.analysis.domain.CloudAnalysisRequest;
import local.personalmemo.analysis.domain.CloudAnalysisResult;
import local.personalmemo.analysis.domain.CloudGatewayBinding;
import local.personalmemo.analysis.domain.CloudGatewayDescriptor;
import local.personalmemo.analysis.domain.CloudTransferMode;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@PostgresIntegration
class IdempotencyIntegrationTest extends PostgresIntegrationTestSupport {
  private static final CloudGatewayDescriptor NO_NETWORK =
      new CloudGatewayDescriptor(
          "idempotency-test-gateway-v1",
          "test-fake",
          "none",
          "no-network-v1",
          CloudTransferMode.NO_NETWORK);

  @MockitoBean private CloudAnalysisGateway cloudGateway;

  @Test
  void concurrentAnalysisStartWithOneKeyProducesOneRunProposalAndGatewayInvocation()
      throws Exception {
    CloudAnalysisExecutor cloudExecutor = mock(CloudAnalysisExecutor.class);
    when(cloudGateway.bind()).thenReturn(new CloudGatewayBinding(NO_NETWORK, cloudExecutor));
    when(cloudExecutor.execute(any(CloudAnalysisRequest.class)))
        .thenAnswer(
            invocation ->
                CloudAnalysisResult.success(
                    ((CloudAnalysisRequest) invocation.getArgument(0)).validatedLocalProposal()));
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "create-before-concurrent-start", "전에 교수님이 말한 거 다음 주쯤 올리기");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                ready.countDown();
                go.await();
                return startAnalysis(memoId, "concurrent-start-key", 1);
              });
      var second =
          executor.submit(
              () -> {
                ready.countDown();
                go.await();
                return startAnalysis(memoId, "concurrent-start-key", 1);
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();

      var firstResult = first.get(15, TimeUnit.SECONDS);
      var secondResult = second.get(15, TimeUnit.SECONDS);
      assertThat(firstResult.getResponse().getStatus()).isEqualTo(200);
      assertThat(secondResult.getResponse().getStatus()).isEqualTo(200);
      assertThat(response(firstResult).path("id").asText())
          .isEqualTo(response(secondResult).path("id").asText());
      assertThat(response(firstResult).path("proposalId").asText())
          .isEqualTo(response(secondResult).path("proposalId").asText());
    }

    assertThat(db.sql("select count(*) from analysis_runs").query(Long.class).single())
        .isEqualTo(1);
    assertThat(db.sql("select count(*) from analysis_proposals").query(Long.class).single())
        .isEqualTo(1);
    String storedToken =
        db.sql("select cloud_provider_request_token from analysis_runs")
            .query(String.class)
            .single();
    ArgumentCaptor<CloudAnalysisRequest> requestCaptor =
        ArgumentCaptor.forClass(CloudAnalysisRequest.class);
    verify(cloudExecutor, times(1)).execute(requestCaptor.capture());
    assertThat(requestCaptor.getValue().providerRequestToken().value()).isEqualTo(storedToken);
    assertThat(
            db.sql(
                    "select count(*) from idempotency_records "
                        + "where operation='ANALYSIS_START' and idempotency_key='concurrent-start-key'")
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void duplicateMemoCreateReturnsOriginalAndRejectsPayloadMismatch() throws Exception {
    UUID memoId = UUID.randomUUID();
    var first = createMemo(memoId, "same-create-key", "원본 메모");
    var duplicate = createMemo(memoId, "same-create-key", "원본 메모");

    assertThat(first.getResponse().getStatus()).isEqualTo(201);
    assertThat(duplicate.getResponse().getStatus()).isEqualTo(201);
    assertThat(response(duplicate).path("id").asText()).isEqualTo(memoId.toString());
    assertThat(db.sql("select count(*) from memos").query(Long.class).single()).isEqualTo(1);
    assertThat(db.sql("select count(*) from memo_revisions").query(Long.class).single())
        .isEqualTo(1);

    var mismatch = createMemo(memoId, "same-create-key", "다른 요청 본문");
    assertIdempotencyConflict(mismatch);
    assertThat(
            db.sql("select content from memo_revisions where memo_id=:id")
                .param("id", memoId)
                .query(String.class)
                .single())
        .isEqualTo("원본 메모");
  }

  @Test
  void duplicateStartAndApplyCreateExactlyOneResourceSet() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "create-for-idempotency", "11.25 OS과제 제출");

    var firstStart = startAnalysis(memoId, "same-start-key", 1);
    var duplicateStart = startAnalysis(memoId, "same-start-key", 1);
    UUID proposalId = UUID.fromString(response(firstStart).path("proposalId").asText());

    assertThat(response(duplicateStart).path("id").asText())
        .isEqualTo(response(firstStart).path("id").asText());
    assertThat(response(duplicateStart).path("proposalId").asText())
        .isEqualTo(proposalId.toString());
    assertThat(db.sql("select count(*) from analysis_runs").query(Long.class).single())
        .isEqualTo(1);
    assertThat(db.sql("select count(*) from analysis_proposals").query(Long.class).single())
        .isEqualTo(1);

    UUID anotherMemoId = UUID.randomUUID();
    createMemo(anotherMemoId, "create-another-for-start-conflict", "다른 메모");
    var changedStartBody = Map.of("memoRevision", 1, "policy", "AUTO");
    var changedStart =
        mvc.perform(
                post("/api/v1/memos/{id}/analysis-runs", anotherMemoId)
                    .header("Idempotency-Key", "same-start-key")
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(changedStartBody)))
            .andReturn();
    assertIdempotencyConflict(changedStart);

    Map<String, Object> due =
        Map.of(
            "surfaceText", "11.25",
            "value", "2026-11-25",
            "precision", "DATE_ONLY",
            "timeZone", "Asia/Seoul",
            "timeSpecified", false);
    var firstApply = applyProposal(proposalId, "same-apply-key", 1, "OS과제 제출", due);
    var duplicateApply = applyProposal(proposalId, "same-apply-key", 1, "OS과제 제출", due);
    UUID applicationId = UUID.fromString(response(firstApply).path("applicationId").asText());

    assertThat(response(duplicateApply).path("applicationId").asText())
        .isEqualTo(applicationId.toString());
    assertThat(db.sql("select count(*) from analysis_applications").query(Long.class).single())
        .isEqualTo(1);
    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single()).isEqualTo(1);
    assertThat(db.sql("select count(*) from task_details").query(Long.class).single()).isEqualTo(1);
    assertThat(db.sql("select count(*) from item_tags").query(Long.class).single()).isEqualTo(1);

    var changedApply = applyProposal(proposalId, "same-apply-key", 1, "동일 키의 다른 제목", due);
    assertIdempotencyConflict(changedApply);
    assertThat(
            db.sql("select title from memo_items where application_id=:id")
                .param("id", applicationId)
                .query(String.class)
                .single())
        .isEqualTo("OS과제 제출");
  }

  @Test
  void concurrentApplyWithTheSameKeyAndBodyReturnsOneApplication() throws Exception {
    UUID proposalId = createProposal("concurrent-apply-same-key");

    List<org.springframework.test.web.servlet.MvcResult> results =
        runConcurrently(
            () -> applyProposal(proposalId, "concurrent-apply-same-key", 1, "동시 적용 한 번", null),
            () -> applyProposal(proposalId, "concurrent-apply-same-key", 1, "동시 적용 한 번", null));

    assertThat(results)
        .allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
    assertThat(response(results.get(1)).path("applicationId").asText())
        .isEqualTo(response(results.get(0)).path("applicationId").asText());
    assertSingleAppliedResourceSet("concurrent-apply-same-key");
  }

  @Test
  void concurrentApplyWithTheSameKeyAndDifferentBodiesRejectsTheLoser() throws Exception {
    UUID proposalId = createProposal("concurrent-apply-key-reuse");

    List<org.springframework.test.web.servlet.MvcResult> results =
        runConcurrently(
            () -> applyProposal(proposalId, "concurrent-apply-key-reuse", 1, "동시 적용 첫 본문", null),
            () -> applyProposal(proposalId, "concurrent-apply-key-reuse", 1, "동시 적용 다른 본문", null));

    assertOneSuccessAndOneConflict(results, "IDEMPOTENCY_KEY_REUSED");
    assertSingleAppliedResourceSet("concurrent-apply-key-reuse");
  }

  @Test
  void concurrentApplyWithDifferentKeysCannotApplyOneProposalTwice() throws Exception {
    UUID proposalId = createProposal("concurrent-apply-different-keys");

    List<org.springframework.test.web.servlet.MvcResult> results =
        runConcurrently(
            () -> applyProposal(proposalId, "concurrent-apply-first-key", 1, "동시 적용 제안", null),
            () -> applyProposal(proposalId, "concurrent-apply-second-key", 1, "동시 적용 제안", null));

    assertOneSuccessAndOneConflict(results, "PROPOSAL_NOT_APPLICABLE");
    assertThat(
            db.sql(
                    "select count(*) from idempotency_records "
                        + "where operation='ANALYSIS_APPLY' and idempotency_key in (:first,:second)")
                .param("first", "concurrent-apply-first-key")
                .param("second", "concurrent-apply-second-key")
                .query(Long.class)
                .single())
        .isEqualTo(1);
    assertThat(db.sql("select count(*) from analysis_applications").query(Long.class).single())
        .isEqualTo(1);
    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single()).isEqualTo(1);
    assertThat(db.sql("select count(*) from task_details").query(Long.class).single()).isEqualTo(1);
    assertThat(db.sql("select count(*) from item_tags").query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void duplicateUndoReturnsOriginalResultWithoutTouchingRawMemo() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "create-for-undo-idempotency", "11.25 OS과제 제출");
    UUID proposalId =
        UUID.fromString(
            response(startAnalysis(memoId, "start-for-undo-idempotency", 1))
                .path("proposalId")
                .asText());
    UUID applicationId =
        UUID.fromString(
            response(applyProposal(proposalId, "apply-for-undo-idempotency", 1, "OS과제 제출", null))
                .path("applicationId")
                .asText());

    var firstUndo = undoApplication(applicationId, "same-undo-key");
    var duplicateUndo = undoApplication(applicationId, "same-undo-key");

    assertThat(firstUndo.getResponse().getStatus()).isEqualTo(200);
    assertThat(duplicateUndo.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(duplicateUndo).path("applicationId").asText())
        .isEqualTo(applicationId.toString());
    assertThat(response(duplicateUndo).path("status").asText()).isEqualTo("UNDONE");
    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single()).isZero();
    assertThat(
            db.sql("select count(*) from memo_revisions where memo_id=:id")
                .param("id", memoId)
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  private UUID createProposal(String keyPrefix) throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, keyPrefix + "-create", "11.25 OS과제 제출");
    return UUID.fromString(
        response(startAnalysis(memoId, keyPrefix + "-start", 1)).path("proposalId").asText());
  }

  private List<org.springframework.test.web.servlet.MvcResult> runConcurrently(
      Callable<org.springframework.test.web.servlet.MvcResult> firstCall,
      Callable<org.springframework.test.web.servlet.MvcResult> secondCall)
      throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                ready.countDown();
                go.await();
                return firstCall.call();
              });
      var second =
          executor.submit(
              () -> {
                ready.countDown();
                go.await();
                return secondCall.call();
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
    }
  }

  private void assertOneSuccessAndOneConflict(
      List<org.springframework.test.web.servlet.MvcResult> results, String expectedConflictCode)
      throws Exception {
    assertThat(results.stream().map(result -> result.getResponse().getStatus()).sorted().toList())
        .containsExactly(200, 409);
    var conflict =
        results.stream()
            .filter(result -> result.getResponse().getStatus() == 409)
            .findFirst()
            .orElseThrow();
    assertThat(response(conflict).path("code").asText()).isEqualTo(expectedConflictCode);
  }

  private void assertSingleAppliedResourceSet(String key) {
    assertThat(
            db.sql(
                    "select count(*) from idempotency_records "
                        + "where operation='ANALYSIS_APPLY' and idempotency_key=:key")
                .param("key", key)
                .query(Long.class)
                .single())
        .isEqualTo(1);
    assertThat(db.sql("select count(*) from analysis_applications").query(Long.class).single())
        .isEqualTo(1);
    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single()).isEqualTo(1);
    assertThat(db.sql("select count(*) from task_details").query(Long.class).single()).isEqualTo(1);
    assertThat(db.sql("select count(*) from item_tags").query(Long.class).single()).isEqualTo(1);
  }

  private void assertIdempotencyConflict(org.springframework.test.web.servlet.MvcResult result)
      throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(result).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
  }
}
