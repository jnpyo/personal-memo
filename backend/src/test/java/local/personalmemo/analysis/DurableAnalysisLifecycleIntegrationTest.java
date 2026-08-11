package local.personalmemo.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import local.personalmemo.analysis.application.AnalysisService;
import local.personalmemo.analysis.domain.CloudAnalysisGateway;
import local.personalmemo.analysis.domain.CloudAnalysisRequest;
import local.personalmemo.analysis.domain.CloudAnalysisResult;
import local.personalmemo.analysis.domain.CloudGatewayBinding;
import local.personalmemo.analysis.domain.CloudGatewayDescriptor;
import local.personalmemo.analysis.domain.CloudTransferMode;
import local.personalmemo.analysis.domain.TagRetrievalContext;
import local.personalmemo.analysis.infrastructure.TagRetrievalContextCodec;
import local.personalmemo.common.security.Hashing;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.node.ObjectNode;

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
  @Autowired private AnalysisService analysisService;
  @Autowired private TagRetrievalContextCodec tagContextCodec;

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

  @Test
  void preparedRequestUsesOwnerScopedStoredContextAndFinalizationScrubsInternalEvidence()
      throws Exception {
    UUID collisionTagId = insertOwnerOsCollision();
    UUID otherOwnerId = UUID.randomUUID();
    String otherOwnerSecret = "other-owner-context-secret";
    insertOtherOwnerOsAlias(otherOwnerId, UUID.randomUUID(), otherOwnerSecret);
    CountDownLatch gatewayEntered = new CountDownLatch(1);
    CountDownLatch releaseGateway = new CountDownLatch(1);
    AtomicReference<CloudAnalysisRequest> capturedRequest = new AtomicReference<>();
    when(cloudGateway.bind())
        .thenReturn(
            binding(
                request -> {
                  capturedRequest.set(request);
                  gatewayEntered.countDown();
                  awaitRelease(releaseGateway);
                  ObjectNode enriched = request.validatedLocalProposal();
                  ((ObjectNode) enriched.path("providerMetadata"))
                      .put("tagRetrievalContext", "ＯＳ")
                      .put("retrievalContext", "ＯＳ")
                      .put("retrievalContextHash", "spoofed-context-hash")
                      .put("retrievalContextVersion", "spoofed-context-version")
                      .put("retrievalContextCandidateCount", 99)
                      .put("retrieval_context", "ＯＳ");
                  return CloudAnalysisResult.success(enriched);
                }));
    UUID memoId = createMemoWithContent("retrieval-owner-scope", AMBIGUOUS_MEMO + " OS");

    ExecutorService caller = Executors.newSingleThreadExecutor();
    Future<MvcResult> started =
        caller.submit(() -> startAnalysis(memoId, "retrieval-owner-scope-start", 1));
    RetrievalContextEvidence preparedEvidence;
    TagRetrievalContext requestContext;
    UUID runId;
    try {
      assertThat(gatewayEntered.await(5, TimeUnit.SECONDS)).isTrue();
      runId = runLifecycle(memoId).runId();
      preparedEvidence = retrievalContextEvidence(runId);
      requestContext = capturedRequest.get().tagRetrievalContext().orElseThrow();
      releaseGateway.countDown();

      MvcResult completed = started.get(5, TimeUnit.SECONDS);
      assertThat(completed.getResponse().getStatus()).isEqualTo(200);
      assertThat(response(completed).path("status").asText()).isEqualTo("REVIEW_REQUIRED");

      assertThat(preparedEvidence.rawContext())
          .isEqualTo(tagContextCodec.serialize(requestContext));
      assertThat(preparedEvidence.contextHash())
          .isEqualTo(Hashing.sha256(preparedEvidence.rawContext()));
      assertThat(preparedEvidence.version()).isEqualTo(TagRetrievalContext.CURRENT_VERSION);
      assertThat(preparedEvidence.candidateCount()).isEqualTo(2);
      assertThat(requestContext.candidates())
          .extracting(TagRetrievalContext.Candidate::existingTagId)
          .containsExactly(OPERATING_SYSTEMS_TAG_ID, collisionTagId);
      assertThat(preparedEvidence.rawContext())
          .contains("ＯＳ")
          .doesNotContain(otherOwnerSecret, otherOwnerId.toString());

      RetrievalContextEvidence finalizedEvidence = retrievalContextEvidence(runId);
      assertThat(dispatchLifecycle(runId).state()).isEqualTo("FINALIZED");
      assertThat(finalizedEvidence.rawContext()).isNull();
      assertThat(finalizedEvidence.contextHash()).isEqualTo(preparedEvidence.contextHash());
      assertThat(finalizedEvidence.version()).isEqualTo(preparedEvidence.version());
      assertThat(finalizedEvidence.candidateCount()).isEqualTo(preparedEvidence.candidateCount());

      UUID proposalId = UUID.fromString(response(completed).path("proposalId").asText());
      MvcResult proposalResult =
          mvc.perform(get("/api/v1/analysis-proposals/{id}", proposalId)).andReturn();
      String runPayload = response(completed).toString();
      String proposalPayload = response(proposalResult).toString();
      for (String internalField :
          List.of(
              "tagRetrievalContext",
              "retrievalContext",
              "retrievalContextHash",
              "retrievalContextVersion",
              "retrievalContextCandidateCount",
              "retrieval_context")) {
        assertThat(runPayload).doesNotContain(internalField);
        assertThat(proposalPayload).doesNotContain(internalField);
      }
      assertThat(runPayload)
          .doesNotContain(
              preparedEvidence.rawContext(),
              preparedEvidence.contextHash(),
              preparedEvidence.version(),
              "ＯＳ",
              otherOwnerSecret);
      assertThat(proposalPayload)
          .doesNotContain(
              preparedEvidence.rawContext(),
              preparedEvidence.contextHash(),
              preparedEvidence.version(),
              "ＯＳ",
              otherOwnerSecret);
    } finally {
      releaseGateway.countDown();
      caller.shutdownNow();
      assertThat(caller.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void taxonomyChangeAfterPrepareRecoveryReusesStoredContextAndProviderToken() throws Exception {
    UUID collisionTagId = insertOwnerOsCollision();
    AtomicInteger gatewayCalls = new AtomicInteger();
    List<CloudAnalysisRequest> capturedRequests = new CopyOnWriteArrayList<>();
    AbandonedDispatch abandoned =
        abandonRunningDispatch(
            "retrieval-recovery", AMBIGUOUS_MEMO + " OS", gatewayCalls, capturedRequests);
    RetrievalContextEvidence preparedEvidence = retrievalContextEvidence(abandoned.runId());
    String storedProviderToken = storedProviderRequestToken(abandoned.runId());
    retireTag(collisionTagId);
    expireLease(abandoned.runId());
    when(cloudGateway.bind())
        .thenReturn(
            binding(
                request -> {
                  gatewayCalls.incrementAndGet();
                  capturedRequests.add(request);
                  return CloudAnalysisResult.success(request.validatedLocalProposal());
                }));

    SecurityContextHolder.clearContext();
    int recovered = analysisService.recoverPendingDispatches(10);

    assertThat(recovered).isEqualTo(1);
    assertThat(gatewayCalls).hasValue(2);
    assertThat(capturedRequests).hasSize(2);
    CloudAnalysisRequest preparedRequest = capturedRequests.get(0);
    CloudAnalysisRequest recoveryRequest = capturedRequests.get(1);
    TagRetrievalContext preparedContext = preparedRequest.tagRetrievalContext().orElseThrow();
    TagRetrievalContext recoveryContext = recoveryRequest.tagRetrievalContext().orElseThrow();
    assertThat(recoveryContext).isEqualTo(preparedContext);
    assertThat(tagContextCodec.serialize(recoveryContext)).isEqualTo(preparedEvidence.rawContext());
    assertThat(recoveryContext.candidates())
        .extracting(TagRetrievalContext.Candidate::existingTagId)
        .containsExactly(OPERATING_SYSTEMS_TAG_ID, collisionTagId);
    assertThat(recoveryRequest.providerRequestToken())
        .isEqualTo(preparedRequest.providerRequestToken());
    assertThat(recoveryRequest.providerRequestToken().value()).isEqualTo(storedProviderToken);

    String storedProposal = storedProposalJson(abandoned.runId());
    assertThat(json.readTree(storedProposal).at("/tagCandidates/0/existingTagId").isNull())
        .isTrue();
    assertThat(json.readTree(storedProposal).at("/tagCandidates/0/isNewProposal").asBoolean())
        .isTrue();
    assertThat(json.readTree(storedProposal).at("/tagCandidates/0/matchedAlias").asText())
        .isEqualTo("OS");
    assertThat(dispatchLifecycle(abandoned.runId()).state()).isEqualTo("FINALIZED");
    assertThat(runLifecycle(abandoned.memoId()).status()).isEqualTo("REVIEW_REQUIRED");
    assertThat(proposalCount(abandoned.runId())).isEqualTo(1L);
  }

  @Test
  void legacyNoneDispatchRecoveryDoesNotInventRetrievalContext() throws Exception {
    AtomicInteger gatewayCalls = new AtomicInteger();
    List<CloudAnalysisRequest> capturedRequests = new CopyOnWriteArrayList<>();
    AbandonedDispatch abandoned =
        abandonRunningDispatch(
            "retrieval-legacy-none", AMBIGUOUS_MEMO + " OS", gatewayCalls, capturedRequests);
    db.sql(
            """
            update analysis_run_dispatches
               set retrieval_context = null,
                   retrieval_context_hash = null,
                   retrieval_context_version = 'none',
                   retrieval_context_candidate_count = 0
             where analysis_run_id = :runId
               and owner_id = :ownerId
            """)
        .param("runId", abandoned.runId())
        .param("ownerId", OWNER_ID)
        .update();
    expireLease(abandoned.runId());
    when(cloudGateway.bind())
        .thenReturn(
            binding(
                request -> {
                  gatewayCalls.incrementAndGet();
                  capturedRequests.add(request);
                  return CloudAnalysisResult.success(request.validatedLocalProposal());
                }));

    SecurityContextHolder.clearContext();
    int recovered = analysisService.recoverPendingDispatches(10);

    assertThat(recovered).isEqualTo(1);
    assertThat(gatewayCalls).hasValue(2);
    assertThat(capturedRequests).hasSize(2);
    assertThat(capturedRequests.get(1).tagRetrievalContext()).isEmpty();
    assertThat(capturedRequests.get(1).providerRequestToken())
        .isEqualTo(capturedRequests.get(0).providerRequestToken());
    assertThat(dispatchLifecycle(abandoned.runId()).state()).isEqualTo("FINALIZED");
    assertThat(proposalCount(abandoned.runId())).isEqualTo(1L);
  }

  @Test
  void tamperedRetrievalContextHashFailsClosedWithoutBlockingTheNextCandidate() throws Exception {
    AtomicInteger malformedGatewayCalls = new AtomicInteger();
    AbandonedDispatch malformed =
        abandonRunningDispatch("retrieval-tampered", malformedGatewayCalls);
    expireLease(malformed.runId());
    db.sql(
            """
            update analysis_run_dispatches
               set retrieval_context_hash = :wrongHash
             where analysis_run_id = :runId
               and owner_id = :ownerId
            """)
        .param("wrongHash", "0".repeat(64))
        .param("runId", malformed.runId())
        .param("ownerId", OWNER_ID)
        .update();

    AtomicInteger validGatewayCalls = new AtomicInteger();
    AbandonedDispatch valid = abandonRunningDispatch("retrieval-valid", validGatewayCalls);
    expireLease(valid.runId());
    AtomicInteger recoveryGatewayCalls = new AtomicInteger();
    when(cloudGateway.bind())
        .thenReturn(
            binding(
                request -> {
                  recoveryGatewayCalls.incrementAndGet();
                  return CloudAnalysisResult.success(request.validatedLocalProposal());
                }));

    SecurityContextHolder.clearContext();
    int recovered = analysisService.recoverPendingDispatches(10);

    assertThat(recovered).isEqualTo(1);
    assertThat(malformedGatewayCalls).hasValue(1);
    assertThat(dispatchLifecycle(malformed.runId()).state()).isEqualTo("RUNNING");
    assertThat(dispatchLifecycle(malformed.runId()).fenceToken()).isEqualTo(1L);
    assertThat(proposalCount(malformed.runId())).isZero();
    assertThat(validGatewayCalls).hasValue(1);
    assertThat(dispatchLifecycle(valid.runId()).state()).isEqualTo("FINALIZED");
    assertThat(runLifecycle(valid.memoId()).status()).isEqualTo("REVIEW_REQUIRED");
    assertThat(proposalCount(valid.runId())).isEqualTo(1L);
    assertThat(recoveryGatewayCalls).hasValue(1);
  }

  @Test
  void recoveryWithoutSecurityContextCompletesAnExpiredRunningDispatch() throws Exception {
    AtomicInteger gatewayCalls = new AtomicInteger();
    AbandonedDispatch abandoned = abandonRunningDispatch("recovery-expired", gatewayCalls);
    expireLease(abandoned.runId());
    when(cloudGateway.bind())
        .thenReturn(
            binding(
                request -> {
                  gatewayCalls.incrementAndGet();
                  return CloudAnalysisResult.success(request.validatedLocalProposal());
                }));

    SecurityContextHolder.clearContext();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    int recovered = analysisService.recoverPendingDispatches(10);

    assertThat(recovered).isEqualTo(1);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    RunLifecycle completed = runLifecycle(abandoned.memoId());
    DispatchLifecycle finalized = dispatchLifecycle(abandoned.runId());
    assertThat(completed.status()).isEqualTo("REVIEW_REQUIRED");
    assertThat(completed.cloudOutcome()).isEqualTo("SUCCESS");
    assertThat(completed.completedAt()).isNotNull();
    assertThat(finalized.state()).isEqualTo("FINALIZED");
    assertThat(finalized.fenceToken()).isEqualTo(2L);
    assertThat(finalized.hasPreparedProposal()).isFalse();
    assertThat(finalized.leaseExpiresAt()).isNull();
    assertThat(proposalCount(abandoned.runId())).isEqualTo(1L);
    assertThat(storedStartStatus(abandoned.key())).isEqualTo("REVIEW_REQUIRED");
    assertThat(gatewayCalls).hasValue(2);
    assertNoCanonicalAnalysisWrites();
  }

  @Test
  void recoveryWithoutSecurityContextClaimsAPreparedRestartDispatch() throws Exception {
    AtomicInteger gatewayCalls = new AtomicInteger();
    AbandonedDispatch abandoned = abandonRunningDispatch("recovery-prepared", gatewayCalls);
    resetToPrepared(abandoned.runId());
    when(cloudGateway.bind())
        .thenReturn(
            binding(
                request -> {
                  gatewayCalls.incrementAndGet();
                  return CloudAnalysisResult.success(request.validatedLocalProposal());
                }));

    SecurityContextHolder.clearContext();
    int recovered = analysisService.recoverPendingDispatches(10);

    assertThat(recovered).isEqualTo(1);
    RunLifecycle completed = runLifecycle(abandoned.memoId());
    DispatchLifecycle finalized = dispatchLifecycle(abandoned.runId());
    assertThat(completed.status()).isEqualTo("REVIEW_REQUIRED");
    assertThat(completed.cloudOutcome()).isEqualTo("SUCCESS");
    assertThat(finalized.state()).isEqualTo("FINALIZED");
    assertThat(finalized.fenceToken()).isEqualTo(1L);
    assertThat(finalized.hasPreparedProposal()).isFalse();
    assertThat(proposalCount(abandoned.runId())).isEqualTo(1L);
    assertThat(storedStartStatus(abandoned.key())).isEqualTo("REVIEW_REQUIRED");
    assertThat(gatewayCalls).hasValue(2);
    assertNoCanonicalAnalysisWrites();
  }

  @Test
  void recoverySkipsALiveLeaseWithoutCallingOrChangingTheDispatch() throws Exception {
    AtomicInteger gatewayCalls = new AtomicInteger();
    AbandonedDispatch abandoned = abandonRunningDispatch("recovery-live", gatewayCalls);
    extendLeaseToDeadline(abandoned.runId());
    when(cloudGateway.bind())
        .thenReturn(
            binding(
                request -> {
                  gatewayCalls.incrementAndGet();
                  return CloudAnalysisResult.success(request.validatedLocalProposal());
                }));
    DispatchLifecycle before = dispatchLifecycle(abandoned.runId());

    SecurityContextHolder.clearContext();
    int recovered = analysisService.recoverPendingDispatches(10);

    assertThat(recovered).isZero();
    assertThat(dispatchLifecycle(abandoned.runId())).isEqualTo(before);
    assertThat(runLifecycle(abandoned.memoId()).status()).isEqualTo("RUNNING");
    assertThat(proposalCount(abandoned.runId())).isZero();
    assertThat(storedStartStatus(abandoned.key())).isEqualTo("RUNNING");
    assertThat(gatewayCalls).hasValue(1);
  }

  @Test
  void recoveryDoesNotCrossAnOwnerBoundaryToFindAnIdempotencyKey() throws Exception {
    AtomicInteger gatewayCalls = new AtomicInteger();
    AbandonedDispatch abandoned = abandonRunningDispatch("recovery-owner", gatewayCalls);
    expireLease(abandoned.runId());
    UUID otherOwnerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    db.sql("insert into users(id,created_at,updated_at) values(:id,now(),now())")
        .param("id", otherOwnerId)
        .update();
    db.sql(
            """
            update idempotency_records
               set owner_id = :otherOwnerId
             where owner_id = :ownerId
               and operation = 'ANALYSIS_START'
               and idempotency_key = :key
            """)
        .param("otherOwnerId", otherOwnerId)
        .param("ownerId", OWNER_ID)
        .param("key", abandoned.key())
        .update();
    when(cloudGateway.bind())
        .thenReturn(
            binding(
                request -> {
                  gatewayCalls.incrementAndGet();
                  return CloudAnalysisResult.success(request.validatedLocalProposal());
                }));
    DispatchLifecycle before = dispatchLifecycle(abandoned.runId());

    SecurityContextHolder.clearContext();
    int recovered = analysisService.recoverPendingDispatches(10);

    assertThat(recovered).isZero();
    assertThat(dispatchLifecycle(abandoned.runId())).isEqualTo(before);
    assertThat(runLifecycle(abandoned.memoId()).status()).isEqualTo("RUNNING");
    assertThat(proposalCount(abandoned.runId())).isZero();
    assertThat(gatewayCalls).hasValue(1);
    assertThat(
            db.sql(
                    """
                    select count(*)
                      from idempotency_records
                     where owner_id = :otherOwnerId
                       and operation = 'ANALYSIS_START'
                       and idempotency_key = :key
                    """)
                .param("otherOwnerId", otherOwnerId)
                .param("key", abandoned.key())
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  void malformedRecoveryKeyFailsClosedWithoutBlockingTheNextCandidate() throws Exception {
    AtomicInteger firstAbandonedCalls = new AtomicInteger();
    AbandonedDispatch malformed = abandonRunningDispatch("recovery-malformed", firstAbandonedCalls);
    expireLease(malformed.runId());
    db.sql(
            """
            update analysis_run_dispatches
               set idempotency_key_hash = :wrongHash
             where analysis_run_id = :runId
               and owner_id = :ownerId
            """)
        .param("wrongHash", "0".repeat(64))
        .param("runId", malformed.runId())
        .param("ownerId", OWNER_ID)
        .update();

    AtomicInteger secondAbandonedCalls = new AtomicInteger();
    AbandonedDispatch valid = abandonRunningDispatch("recovery-valid", secondAbandonedCalls);
    expireLease(valid.runId());
    AtomicInteger recoveryCalls = new AtomicInteger();
    when(cloudGateway.bind())
        .thenReturn(
            binding(
                request -> {
                  recoveryCalls.incrementAndGet();
                  return CloudAnalysisResult.success(request.validatedLocalProposal());
                }));

    SecurityContextHolder.clearContext();
    int recovered = analysisService.recoverPendingDispatches(10);

    assertThat(recovered).isEqualTo(1);
    assertThat(dispatchLifecycle(malformed.runId()).state()).isEqualTo("RUNNING");
    assertThat(runLifecycle(malformed.memoId()).status()).isEqualTo("RUNNING");
    assertThat(proposalCount(malformed.runId())).isZero();
    assertThat(dispatchLifecycle(valid.runId()).state()).isEqualTo("FINALIZED");
    assertThat(runLifecycle(valid.memoId()).status()).isEqualTo("REVIEW_REQUIRED");
    assertThat(proposalCount(valid.runId())).isEqualTo(1L);
    assertThat(recoveryCalls).hasValue(1);
  }

  @Test
  void callerAndRecoveryScannerRaceUsesOneNewGatewayAttemptAndOneFinalResponse() throws Exception {
    AtomicInteger abandonedCalls = new AtomicInteger();
    AbandonedDispatch abandoned = abandonRunningDispatch("recovery-race", abandonedCalls);
    expireLease(abandoned.runId());
    CountDownLatch gatewayEntered = new CountDownLatch(1);
    CountDownLatch releaseGateway = new CountDownLatch(1);
    AtomicInteger recoveryCalls = new AtomicInteger();
    when(cloudGateway.bind())
        .thenReturn(
            binding(
                request -> {
                  recoveryCalls.incrementAndGet();
                  gatewayEntered.countDown();
                  awaitRelease(releaseGateway);
                  return CloudAnalysisResult.success(request.validatedLocalProposal());
                }));

    ExecutorService actors = Executors.newFixedThreadPool(2);
    Future<Integer> scanner = actors.submit(() -> analysisService.recoverPendingDispatches(10));
    Future<MvcResult> caller = null;
    try {
      assertThat(gatewayEntered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(dispatchLifecycle(abandoned.runId()).fenceToken()).isEqualTo(2L);
      caller = actors.submit(() -> startAnalysis(abandoned.memoId(), abandoned.key(), 1));
      Future<MvcResult> waitingCaller = caller;
      assertThatThrownBy(() -> waitingCaller.get(250, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      releaseGateway.countDown();
      assertThat(scanner.get(5, TimeUnit.SECONDS)).isEqualTo(1);
      MvcResult replay = caller.get(5, TimeUnit.SECONDS);

      assertThat(replay.getResponse().getStatus()).isEqualTo(200);
      assertThat(response(replay).path("id").asText()).isEqualTo(abandoned.runId().toString());
      assertThat(response(replay).path("status").asText()).isEqualTo("REVIEW_REQUIRED");
      assertThat(dispatchLifecycle(abandoned.runId()).state()).isEqualTo("FINALIZED");
      assertThat(proposalCount(abandoned.runId())).isEqualTo(1L);
      assertThat(storedStartStatus(abandoned.key())).isEqualTo("REVIEW_REQUIRED");
      assertThat(recoveryCalls).hasValue(1);
    } finally {
      releaseGateway.countDown();
      scanner.cancel(true);
      if (caller != null) {
        caller.cancel(true);
      }
      actors.shutdownNow();
      assertThat(actors.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private AbandonedDispatch abandonRunningDispatch(String keyPrefix, AtomicInteger gatewayCalls)
      throws Exception {
    return abandonRunningDispatch(keyPrefix, AMBIGUOUS_MEMO, gatewayCalls, null);
  }

  private AbandonedDispatch abandonRunningDispatch(
      String keyPrefix,
      String memoContent,
      AtomicInteger gatewayCalls,
      List<CloudAnalysisRequest> capturedRequests)
      throws Exception {
    CountDownLatch gatewayEntered = new CountDownLatch(1);
    CountDownLatch gatewayInterrupted = new CountDownLatch(1);
    when(cloudGateway.bind())
        .thenReturn(
            binding(
                request -> {
                  gatewayCalls.incrementAndGet();
                  if (capturedRequests != null) {
                    capturedRequests.add(request);
                  }
                  gatewayEntered.countDown();
                  try {
                    new CountDownLatch(1).await();
                  } catch (InterruptedException exception) {
                    gatewayInterrupted.countDown();
                    Thread.currentThread().interrupt();
                  }
                  return CloudAnalysisResult.success(request.validatedLocalProposal());
                }));
    UUID memoId = createMemoWithContent(keyPrefix, memoContent);
    String key = keyPrefix + "-start";
    ExecutorService caller = Executors.newSingleThreadExecutor();
    Future<MvcResult> start = caller.submit(() -> startAnalysis(memoId, key, 1));
    try {
      assertThat(gatewayEntered.await(5, TimeUnit.SECONDS)).isTrue();
      RunLifecycle running = runLifecycle(memoId);
      assertThat(dispatchLifecycle(running.runId()).state()).isEqualTo("RUNNING");
      assertThat(start.cancel(true)).isTrue();
      assertThat(gatewayInterrupted.await(5, TimeUnit.SECONDS)).isTrue();
      return new AbandonedDispatch(memoId, running.runId(), key);
    } finally {
      start.cancel(true);
      caller.shutdownNow();
      assertThat(caller.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private void expireLease(UUID runId) {
    db.sql(
            """
            update analysis_run_dispatches
               set last_attempt_started_at = prepared_at,
                   lease_expires_at = prepared_at + interval '1 millisecond',
                   updated_at = greatest(updated_at, prepared_at)
             where analysis_run_id = :runId
               and owner_id = :ownerId
            """)
        .param("runId", runId)
        .param("ownerId", OWNER_ID)
        .update();
  }

  private void resetToPrepared(UUID runId) {
    db.sql(
            """
            update analysis_run_dispatches
               set state = 'PREPARED',
                   fence_token = 0,
                   last_attempt_started_at = null,
                   lease_expires_at = null,
                   updated_at = greatest(updated_at, prepared_at)
             where analysis_run_id = :runId
               and owner_id = :ownerId
            """)
        .param("runId", runId)
        .param("ownerId", OWNER_ID)
        .update();
    db.sql(
            """
            update analysis_runs
               set status = 'QUEUED',
                   cloud_outcome = 'PENDING'
             where id = :runId
               and owner_id = :ownerId
            """)
        .param("runId", runId)
        .param("ownerId", OWNER_ID)
        .update();
  }

  private void extendLeaseToDeadline(UUID runId) {
    db.sql(
            """
            update analysis_run_dispatches
               set lease_expires_at = deadline_at
             where analysis_run_id = :runId
               and owner_id = :ownerId
            """)
        .param("runId", runId)
        .param("ownerId", OWNER_ID)
        .update();
  }

  private UUID createAmbiguousMemo(String keyPrefix) throws Exception {
    return createMemoWithContent(keyPrefix, AMBIGUOUS_MEMO);
  }

  private UUID createMemoWithContent(String keyPrefix, String content) throws Exception {
    UUID memoId = UUID.randomUUID();
    MvcResult created = createMemo(memoId, keyPrefix + "-create", content);
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    return memoId;
  }

  private UUID insertOwnerOsCollision() {
    db.sql(
            """
            update tag_aliases
               set alias = 'ＯＳ'
             where owner_id = :ownerId
               and tag_id = :tagId
               and normalized_alias = 'os'
            """)
        .param("ownerId", OWNER_ID)
        .param("tagId", OPERATING_SYSTEMS_TAG_ID)
        .update();
    UUID collisionTagId = UUID.randomUUID();
    db.sql(
            """
            insert into tags(
              id, owner_id, canonical_name, normalized_name, state, created_at, updated_at
            ) values (
              :tagId, :ownerId, 'OS', 'os', 'ACTIVE', now(), now()
            )
            """)
        .param("tagId", collisionTagId)
        .param("ownerId", OWNER_ID)
        .update();
    return collisionTagId;
  }

  private void insertOtherOwnerOsAlias(UUID ownerId, UUID tagId, String canonicalName) {
    db.sql("insert into users(id,created_at,updated_at) values(:id,now(),now())")
        .param("id", ownerId)
        .update();
    db.sql(
            """
            insert into tags(
              id, owner_id, canonical_name, normalized_name, state, created_at, updated_at
            ) values (
              :tagId, :ownerId, :canonicalName, :canonicalName, 'ACTIVE', now(), now()
            )
            """)
        .param("tagId", tagId)
        .param("ownerId", ownerId)
        .param("canonicalName", canonicalName)
        .update();
    db.sql(
            """
            insert into tag_aliases(
              id, owner_id, tag_id, alias, normalized_alias, source, created_at
            ) values (
              :aliasId, :ownerId, :tagId, 'OS', 'os', 'USER', now()
            )
            """)
        .param("aliasId", UUID.randomUUID())
        .param("ownerId", ownerId)
        .param("tagId", tagId)
        .update();
  }

  private void retireTag(UUID tagId) {
    db.sql(
            """
            update tags
               set state = 'RETIRED',
                   updated_at = now()
             where id = :tagId
               and owner_id = :ownerId
            """)
        .param("tagId", tagId)
        .param("ownerId", OWNER_ID)
        .update();
  }

  private RetrievalContextEvidence retrievalContextEvidence(UUID runId) {
    return db.sql(
            """
            select retrieval_context,
                   retrieval_context_hash,
                   retrieval_context_version,
                   retrieval_context_candidate_count
              from analysis_run_dispatches
             where analysis_run_id = :runId
               and owner_id = :ownerId
            """)
        .param("runId", runId)
        .param("ownerId", OWNER_ID)
        .query(
            (resultSet, rowNumber) ->
                new RetrievalContextEvidence(
                    resultSet.getString("retrieval_context"),
                    resultSet.getString("retrieval_context_hash"),
                    resultSet.getString("retrieval_context_version"),
                    resultSet.getInt("retrieval_context_candidate_count")))
        .single();
  }

  private String storedProviderRequestToken(UUID runId) {
    return db.sql(
            """
            select cloud_provider_request_token
              from analysis_runs
             where id = :runId
               and owner_id = :ownerId
            """)
        .param("runId", runId)
        .param("ownerId", OWNER_ID)
        .query(String.class)
        .single();
  }

  private String storedProposalJson(UUID runId) {
    return db.sql(
            """
            select proposal_json::text
              from analysis_proposals
             where analysis_run_id = :runId
               and owner_id = :ownerId
            """)
        .param("runId", runId)
        .param("ownerId", OWNER_ID)
        .query(String.class)
        .single();
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

  private record RetrievalContextEvidence(
      String rawContext, String contextHash, String version, int candidateCount) {}

  private record AbandonedDispatch(UUID memoId, UUID runId, String key) {}
}
