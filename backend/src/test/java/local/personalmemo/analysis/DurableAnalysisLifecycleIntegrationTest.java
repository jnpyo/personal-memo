package local.personalmemo.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import local.personalmemo.analysis.domain.CloudAnalysisGateway;
import local.personalmemo.analysis.domain.CloudAnalysisResult;
import local.personalmemo.analysis.domain.CloudGatewayBinding;
import local.personalmemo.analysis.domain.CloudGatewayDescriptor;
import local.personalmemo.analysis.domain.CloudTransferMode;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@PostgresIntegration
@TestPropertySource(properties = "app.analysis.cloud-execution.timeout=2s")
class DurableAnalysisLifecycleIntegrationTest extends PostgresIntegrationTestSupport {
  private static final String AMBIGUOUS_MEMO = "전에 교수님이 말한 거 다음 주쯤 올리기";
  private static final CloudGatewayDescriptor NO_NETWORK_DESCRIPTOR =
      new CloudGatewayDescriptor(
          "durable-lifecycle-test-v1",
          "test-fake",
          "none",
          "no-network-v1",
          CloudTransferMode.NO_NETWORK);

  @MockitoBean private CloudAnalysisGateway cloudGateway;

  @BeforeEach
  void useSuccessfulBoundGatewayByDefault() {
    when(cloudGateway.bind())
        .thenReturn(
            binding(request -> CloudAnalysisResult.success(request.validatedLocalProposal())));
  }

  @Test
  void blockedGatewayLeavesCommittedDispatchAllowsMemoEditAndFinalizesLateResultAsStale()
      throws Exception {
    CountDownLatch gatewayEntered = new CountDownLatch(1);
    CountDownLatch releaseGateway = new CountDownLatch(1);
    AtomicBoolean gatewayTransactionActive = new AtomicBoolean(true);
    AtomicInteger gatewayCalls = new AtomicInteger();
    when(cloudGateway.bind())
        .thenReturn(
            binding(
                request -> {
                  gatewayCalls.incrementAndGet();
                  gatewayTransactionActive.set(
                      TransactionSynchronizationManager.isActualTransactionActive());
                  gatewayEntered.countDown();
                  awaitRelease(releaseGateway);
                  return CloudAnalysisResult.success(request.validatedLocalProposal());
                }));
    UUID memoId = createAmbiguousMemo("durable-stale");

    ExecutorService callers = Executors.newFixedThreadPool(2);
    Future<MvcResult> start = callers.submit(() -> startAnalysis(memoId, "durable-stale-start", 1));
    try {
      assertThat(gatewayEntered.await(5, TimeUnit.SECONDS)).isTrue();

      RunLifecycle running = runLifecycle(memoId);
      DispatchLifecycle activeDispatch = dispatchLifecycle(running.runId());
      assertThat(running.status()).isEqualTo("RUNNING");
      assertThat(running.cloudOutcome()).isEqualTo("PENDING");
      assertThat(running.executionContractVersion()).isEqualTo("durable-v1");
      assertThat(running.completedAt()).isNull();
      assertThat(activeDispatch.state()).isEqualTo("RUNNING");
      assertThat(activeDispatch.fenceToken()).isEqualTo(1L);
      assertThat(activeDispatch.hasPreparedProposal()).isTrue();
      assertThat(activeDispatch.leaseExpiresAt()).isNotNull();
      assertThat(activeDispatch.finalizedAt()).isNull();
      assertThat(gatewayTransactionActive).isFalse();

      Future<MvcResult> edit =
          callers.submit(
              () -> updateMemo(memoId, "durable-stale-edit", 1, "gateway 실행 중 저장한 revision two"));
      MvcResult edited = edit.get(1, TimeUnit.SECONDS);
      assertThat(edited.getResponse().getStatus()).isEqualTo(200);
      assertThat(response(edited).path("currentRevision").asInt()).isEqualTo(2);
      assertThat(runLifecycle(memoId).status()).isEqualTo("STALE");

      releaseGateway.countDown();
      MvcResult completed = start.get(5, TimeUnit.SECONDS);

      assertThat(completed.getResponse().getStatus()).isEqualTo(409);
      assertThat(response(completed).path("code").asText()).isEqualTo("STALE_MEMO_REVISION");
      RunLifecycle stale = runLifecycle(memoId);
      DispatchLifecycle finalized = dispatchLifecycle(stale.runId());
      assertThat(stale.status()).isEqualTo("STALE");
      assertThat(stale.cloudOutcome()).isEqualTo("SUCCESS");
      assertThat(stale.completedAt()).isNotNull();
      assertThat(finalized.state()).isEqualTo("FINALIZED");
      assertThat(finalized.hasPreparedProposal()).isFalse();
      assertThat(finalized.leaseExpiresAt()).isNull();
      assertThat(finalized.finalizedAt()).isNotNull();
      assertThat(gatewayCalls).hasValue(1);
      assertThat(proposalCount(stale.runId())).isEqualTo(1L);
      assertThat(storedStartStatus("durable-stale-start")).isEqualTo("STALE");
      assertNoCanonicalAnalysisWrites();
    } finally {
      releaseGateway.countDown();
      callers.shutdownNow();
      assertThat(callers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void blockedGatewayAllowsTrashAndNeverRevivesTheStaleRun() throws Exception {
    CountDownLatch gatewayEntered = new CountDownLatch(1);
    CountDownLatch releaseGateway = new CountDownLatch(1);
    AtomicInteger gatewayCalls = new AtomicInteger();
    when(cloudGateway.bind())
        .thenReturn(
            binding(
                request -> {
                  gatewayCalls.incrementAndGet();
                  gatewayEntered.countDown();
                  awaitRelease(releaseGateway);
                  return CloudAnalysisResult.success(request.validatedLocalProposal());
                }));
    UUID memoId = createAmbiguousMemo("durable-trash");

    ExecutorService caller = Executors.newSingleThreadExecutor();
    Future<MvcResult> start = caller.submit(() -> startAnalysis(memoId, "durable-trash-start", 1));
    try {
      assertThat(gatewayEntered.await(5, TimeUnit.SECONDS)).isTrue();

      MvcResult trashed = trashMemo(memoId, "durable-trash-memo");
      assertThat(trashed.getResponse().getStatus()).isEqualTo(200);
      assertThat(response(trashed).path("status").asText()).isEqualTo("TRASHED");
      assertThat(runLifecycle(memoId).status()).isEqualTo("STALE");

      releaseGateway.countDown();
      MvcResult completed = start.get(5, TimeUnit.SECONDS);
      assertThat(completed.getResponse().getStatus()).isEqualTo(409);
      assertThat(response(completed).path("code").asText()).isEqualTo("STALE_MEMO_REVISION");
      RunLifecycle stale = runLifecycle(memoId);
      assertThat(stale.status()).isEqualTo("STALE");
      assertThat(stale.completedAt()).isNotNull();
      assertThat(dispatchLifecycle(stale.runId()).state()).isEqualTo("FINALIZED");
      assertThat(gatewayCalls).hasValue(1);
      assertNoCanonicalAnalysisWrites();
    } finally {
      releaseGateway.countDown();
      caller.shutdownNow();
      assertThat(caller.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void undoPreservesATagReferencedByAPendingDispatchUntilAnalysisFinalizesAndReplays()
      throws Exception {
    db.sql("delete from tag_aliases where tag_id = :tagId")
        .param("tagId", OPERATING_SYSTEMS_TAG_ID)
        .update();
    db.sql("delete from tags where id = :tagId").param("tagId", OPERATING_SYSTEMS_TAG_ID).update();

    UUID creatorMemoId = createAmbiguousMemo("durable-tag-creator");
    MvcResult creatorStart = startAnalysis(creatorMemoId, "durable-tag-creator-start", 1);
    assertThat(creatorStart.getResponse().getStatus()).isEqualTo(200);
    UUID creatorProposalId = UUID.fromString(response(creatorStart).path("proposalId").asText());

    Map<String, Object> selectedTag = new LinkedHashMap<>();
    selectedTag.put("existingTagId", null);
    selectedTag.put("newCanonicalName", "\uC6B4\uC601\uCCB4\uC81C");
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("kind", "TASK");
    item.put("title", "tag creator");
    item.put("due", null);
    Map<String, Object> applyBody = new LinkedHashMap<>();
    applyBody.put("expectedMemoRevision", 1);
    applyBody.put("selectedType", "TASK");
    applyBody.put("title", "tag creator");
    applyBody.put("selectedTags", List.of(selectedTag));
    applyBody.put("items", List.of(item));
    MvcResult applied = applyProposal(creatorProposalId, "durable-tag-creator-apply", applyBody);
    assertThat(applied.getResponse().getStatus()).isEqualTo(200);
    UUID creatorApplicationId = UUID.fromString(response(applied).path("applicationId").asText());
    UUID createdTagId =
        db.sql(
                """
                select id
                  from tags
                 where owner_id = :ownerId
                   and normalized_name = :normalizedName
                   and created_by_application_id = :applicationId
                """)
            .param("ownerId", OWNER_ID)
            .param("normalizedName", "\uC6B4\uC601\uCCB4\uC81C")
            .param("applicationId", creatorApplicationId)
            .query(UUID.class)
            .single();

    CountDownLatch bindingEntered = new CountDownLatch(1);
    CountDownLatch releaseBinding = new CountDownLatch(1);
    CountDownLatch gatewayEntered = new CountDownLatch(1);
    CountDownLatch releaseGateway = new CountDownLatch(1);
    AtomicInteger gatewayCalls = new AtomicInteger();
    when(cloudGateway.bind())
        .thenAnswer(
            ignored -> {
              bindingEntered.countDown();
              awaitRelease(releaseBinding);
              return binding(
                  request -> {
                    gatewayCalls.incrementAndGet();
                    gatewayEntered.countDown();
                    awaitRelease(releaseGateway);
                    return CloudAnalysisResult.success(request.validatedLocalProposal());
                  });
            });
    UUID pendingMemoId = UUID.randomUUID();
    MvcResult created =
        createMemo(
            pendingMemoId,
            "durable-tag-pending-create",
            AMBIGUOUS_MEMO + " \uC6B4\uC601\uCCB4\uC81C");
    assertThat(created.getResponse().getStatus()).isEqualTo(201);

    CountDownLatch undoStarted = new CountDownLatch(1);
    ExecutorService caller = Executors.newFixedThreadPool(2);
    Future<MvcResult> start =
        caller.submit(() -> startAnalysis(pendingMemoId, "durable-tag-pending-start", 1));
    Future<MvcResult> undo = null;
    try {
      assertThat(bindingEntered.await(5, TimeUnit.SECONDS)).isTrue();
      undo =
          caller.submit(
              () -> {
                undoStarted.countDown();
                return undoApplication(creatorApplicationId, "durable-tag-creator-undo");
              });
      assertThat(undoStarted.await(5, TimeUnit.SECONDS)).isTrue();
      Future<MvcResult> blockedUndo = undo;
      assertThatThrownBy(() -> blockedUndo.get(250, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      releaseBinding.countDown();
      assertThat(gatewayEntered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(pendingDispatchReferencesTag(createdTagId)).isTrue();

      MvcResult undone = undo.get(5, TimeUnit.SECONDS);
      assertThat(undone.getResponse().getStatus()).isEqualTo(200);
      assertThat(response(undone).path("status").asText()).isEqualTo("UNDONE");
      assertThat(activeTagExists(createdTagId)).isTrue();

      releaseGateway.countDown();
      MvcResult completed = start.get(5, TimeUnit.SECONDS);
      assertThat(completed.getResponse().getStatus()).isEqualTo(200);
      assertThat(response(completed).path("status").asText()).isEqualTo("REVIEW_REQUIRED");
      assertThat(gatewayCalls).hasValue(1);
      assertThat(
              dispatchLifecycle(UUID.fromString(response(completed).path("id").asText())).state())
          .isEqualTo("FINALIZED");
      assertThat(activeTagExists(createdTagId)).isTrue();

      MvcResult replay = startAnalysis(pendingMemoId, "durable-tag-pending-start", 1);
      assertThat(replay.getResponse().getStatus()).isEqualTo(200);
      assertThat(response(replay)).isEqualTo(response(completed));
      assertThat(gatewayCalls).hasValue(1);
    } finally {
      releaseBinding.countDown();
      releaseGateway.countDown();
      caller.shutdownNow();
      assertThat(caller.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void boundedTimeoutFinalizesAReviewableLocalFallback() throws Exception {
    CountDownLatch gatewayEntered = new CountDownLatch(1);
    CountDownLatch gatewayInterrupted = new CountDownLatch(1);
    AtomicInteger gatewayCalls = new AtomicInteger();
    when(cloudGateway.bind())
        .thenReturn(
            binding(
                request -> {
                  gatewayCalls.incrementAndGet();
                  gatewayEntered.countDown();
                  try {
                    new CountDownLatch(1).await();
                  } catch (InterruptedException exception) {
                    gatewayInterrupted.countDown();
                    Thread.currentThread().interrupt();
                  }
                  return CloudAnalysisResult.success(request.validatedLocalProposal());
                }));
    UUID memoId = createAmbiguousMemo("durable-timeout");
    Instant startedAt = Instant.now();

    MvcResult completed = startAnalysis(memoId, "durable-timeout-start", 1);

    assertThat(completed.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(completed).path("status").asText()).isEqualTo("REVIEW_REQUIRED");
    assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofSeconds(5));
    assertThat(gatewayEntered.getCount()).isZero();
    assertThat(gatewayInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(gatewayCalls).hasValue(1);

    UUID runId = UUID.fromString(response(completed).path("id").asText());
    RunLifecycle run = runLifecycle(memoId);
    DispatchLifecycle dispatch = dispatchLifecycle(runId);
    assertThat(run.status()).isEqualTo("REVIEW_REQUIRED");
    assertThat(run.cloudOutcome()).isEqualTo("TIMEOUT");
    assertThat(run.executionContractVersion()).isEqualTo("durable-v1");
    assertThat(run.completedAt()).isNotNull();
    assertThat(dispatch.state()).isEqualTo("FINALIZED");
    assertThat(dispatch.callTimeoutMs()).isEqualTo(2_000);
    assertThat(dispatch.hasPreparedProposal()).isFalse();
    assertThat(proposalCloudOutcome(runId)).isEqualTo("TIMEOUT");
    assertThat(proposalCount(runId)).isEqualTo(1L);
    assertNoCanonicalAnalysisWrites();
  }

  @Test
  void concurrentSameKeyUsesOneRunDispatchAndGatewayCallAndReplaysOneResponse() throws Exception {
    CountDownLatch callersReady = new CountDownLatch(2);
    CountDownLatch startCallers = new CountDownLatch(1);
    CountDownLatch gatewayEntered = new CountDownLatch(1);
    CountDownLatch releaseGateway = new CountDownLatch(1);
    CountDownLatch secondBindingObserved = new CountDownLatch(1);
    AtomicInteger bindCount = new AtomicInteger();
    AtomicInteger gatewayCalls = new AtomicInteger();
    CloudGatewayBinding binding =
        binding(
            request -> {
              gatewayCalls.incrementAndGet();
              gatewayEntered.countDown();
              awaitRelease(releaseGateway);
              return CloudAnalysisResult.success(request.validatedLocalProposal());
            });
    when(cloudGateway.bind())
        .thenAnswer(
            ignored -> {
              if (bindCount.incrementAndGet() >= 2) {
                secondBindingObserved.countDown();
              }
              return binding;
            });
    UUID memoId = createAmbiguousMemo("durable-concurrent");

    ExecutorService callers = Executors.newFixedThreadPool(2);
    Future<MvcResult> first =
        callers.submit(
            () -> {
              callersReady.countDown();
              startCallers.await();
              return startAnalysis(memoId, "durable-concurrent-start", 1);
            });
    Future<MvcResult> second =
        callers.submit(
            () -> {
              callersReady.countDown();
              startCallers.await();
              return startAnalysis(memoId, "durable-concurrent-start", 1);
            });
    try {
      assertThat(callersReady.await(5, TimeUnit.SECONDS)).isTrue();
      startCallers.countDown();
      assertThat(gatewayEntered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(secondBindingObserved.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(runCount()).isEqualTo(1L);
      assertThat(dispatchCount()).isEqualTo(1L);

      releaseGateway.countDown();
      MvcResult firstResult = first.get(5, TimeUnit.SECONDS);
      MvcResult secondResult = second.get(5, TimeUnit.SECONDS);

      assertThat(firstResult.getResponse().getStatus()).isEqualTo(200);
      assertThat(secondResult.getResponse().getStatus()).isEqualTo(200);
      assertThat(response(secondResult)).isEqualTo(response(firstResult));
      assertThat(gatewayCalls).hasValue(1);
      assertThat(runCount()).isEqualTo(1L);
      assertThat(dispatchCount()).isEqualTo(1L);
      assertThat(db.sql("select count(*) from analysis_proposals").query(Long.class).single())
          .isEqualTo(1L);
      assertThat(storedStartStatus("durable-concurrent-start")).isEqualTo("REVIEW_REQUIRED");
    } finally {
      startCallers.countDown();
      releaseGateway.countDown();
      callers.shutdownNow();
      assertThat(callers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void sameKeyWithDifferentBodyConflictsWithoutAnotherDispatchOrGatewayCall() throws Exception {
    AtomicInteger gatewayCalls = new AtomicInteger();
    when(cloudGateway.bind())
        .thenReturn(
            binding(
                request -> {
                  gatewayCalls.incrementAndGet();
                  return CloudAnalysisResult.success(request.validatedLocalProposal());
                }));
    UUID memoId = createAmbiguousMemo("durable-key-conflict");

    MvcResult first = startAnalysis(memoId, "durable-key-conflict-start", 1);
    MvcResult mismatch = startAnalysis(memoId, "durable-key-conflict-start", 2);

    assertThat(first.getResponse().getStatus()).isEqualTo(200);
    assertThat(mismatch.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(mismatch).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(gatewayCalls).hasValue(1);
    assertThat(runCount()).isEqualTo(1L);
    assertThat(dispatchCount()).isEqualTo(1L);
    assertThat(db.sql("select count(*) from analysis_proposals").query(Long.class).single())
        .isEqualTo(1L);
  }

  private UUID createAmbiguousMemo(String keyPrefix) throws Exception {
    UUID memoId = UUID.randomUUID();
    MvcResult created = createMemo(memoId, keyPrefix + "-create", AMBIGUOUS_MEMO);
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    return memoId;
  }

  private CloudGatewayBinding binding(
      local.personalmemo.analysis.domain.CloudAnalysisExecutor executor) {
    return new CloudGatewayBinding(NO_NETWORK_DESCRIPTOR, executor);
  }

  private void awaitRelease(CountDownLatch release) {
    try {
      release.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private RunLifecycle runLifecycle(UUID memoId) {
    return db.sql(
            """
            select id, status, cloud_outcome, cloud_execution_contract_version, completed_at
              from analysis_runs
             where memo_id = :memoId
               and owner_id = :ownerId
            """)
        .param("memoId", memoId)
        .param("ownerId", OWNER_ID)
        .query(
            (resultSet, rowNumber) ->
                new RunLifecycle(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("status"),
                    resultSet.getString("cloud_outcome"),
                    resultSet.getString("cloud_execution_contract_version"),
                    resultSet.getTimestamp("completed_at") == null
                        ? null
                        : resultSet.getTimestamp("completed_at").toInstant()))
        .single();
  }

  private DispatchLifecycle dispatchLifecycle(UUID runId) {
    return db.sql(
            """
            select state,
                   fence_token,
                   validated_local_proposal is not null as has_prepared_proposal,
                   call_timeout_ms,
                   lease_expires_at,
                   finalized_at
              from analysis_run_dispatches
             where analysis_run_id = :runId
               and owner_id = :ownerId
            """)
        .param("runId", runId)
        .param("ownerId", OWNER_ID)
        .query(
            (resultSet, rowNumber) ->
                new DispatchLifecycle(
                    resultSet.getString("state"),
                    resultSet.getLong("fence_token"),
                    resultSet.getBoolean("has_prepared_proposal"),
                    resultSet.getInt("call_timeout_ms"),
                    resultSet.getTimestamp("lease_expires_at") == null
                        ? null
                        : resultSet.getTimestamp("lease_expires_at").toInstant(),
                    resultSet.getTimestamp("finalized_at") == null
                        ? null
                        : resultSet.getTimestamp("finalized_at").toInstant()))
        .single();
  }

  private long runCount() {
    return db.sql("select count(*) from analysis_runs").query(Long.class).single();
  }

  private long dispatchCount() {
    return db.sql("select count(*) from analysis_run_dispatches").query(Long.class).single();
  }

  private long proposalCount(UUID runId) {
    return db.sql("select count(*) from analysis_proposals where analysis_run_id = :runId")
        .param("runId", runId)
        .query(Long.class)
        .single();
  }

  private boolean pendingDispatchReferencesTag(UUID tagId) {
    return db.sql(
            """
            select exists (
              select 1
                from analysis_run_dispatches d
                cross join lateral jsonb_array_elements(
                  d.validated_local_proposal::jsonb -> 'tagCandidates'
                ) candidate
               where d.owner_id = :ownerId
                 and d.state in ('PREPARED', 'RUNNING')
                 and candidate ->> 'existingTagId' = :tagId
            )
            """)
        .param("ownerId", OWNER_ID)
        .param("tagId", tagId.toString())
        .query(Boolean.class)
        .single();
  }

  private boolean activeTagExists(UUID tagId) {
    return db.sql(
            """
            select exists (
              select 1
                from tags
               where id = :tagId
                 and owner_id = :ownerId
                 and state = 'ACTIVE'
            )
            """)
        .param("tagId", tagId)
        .param("ownerId", OWNER_ID)
        .query(Boolean.class)
        .single();
  }

  private String proposalCloudOutcome(UUID runId) {
    return db.sql(
            """
            select proposal_json -> 'providerMetadata' ->> 'cloudOutcome'
              from analysis_proposals
             where analysis_run_id = :runId
            """)
        .param("runId", runId)
        .query(String.class)
        .single();
  }

  private String storedStartStatus(String key) {
    return db.sql(
            """
            select response_json ->> 'status'
              from idempotency_records
             where owner_id = :ownerId
               and operation = 'ANALYSIS_START'
               and idempotency_key = :key
            """)
        .param("ownerId", OWNER_ID)
        .param("key", key)
        .query(String.class)
        .single();
  }

  private void assertNoCanonicalAnalysisWrites() {
    assertThat(db.sql("select count(*) from analysis_applications").query(Long.class).single())
        .isZero();
    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from task_details").query(Long.class).single()).isZero();
  }

  private record RunLifecycle(
      UUID runId,
      String status,
      String cloudOutcome,
      String executionContractVersion,
      Instant completedAt) {}

  private record DispatchLifecycle(
      String state,
      long fenceToken,
      boolean hasPreparedProposal,
      int callTimeoutMs,
      Instant leaseExpiresAt,
      Instant finalizedAt) {}
}
