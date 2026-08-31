package local.personalmemo.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import local.personalmemo.common.security.Hashing;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@PostgresIntegration
class AnalysisModelEvidenceIntegrationTest extends PostgresIntegrationTestSupport {
  private static final String PRIVATE_SENTINEL = "foreign-owner-private-model-evidence";
  private static final String OWNER_TITLE_SENTINEL = "owner-private-analysis-path-title";
  private static final String OWNER_SOURCE_SENTINEL = "owner-private-analysis-path-source";
  private static final String LOCAL_PROPOSAL = "{\"synthetic\":true}";
  private static final String CURRENT_EVIDENCE =
      """
      {
        "version":"local-decision-v1",
        "typeSummary":{
          "candidateCount":2,
          "leader":"TASK",
          "leaderScore":0.9,
          "runnerUpScore":0.7,
          "margin":0.2
        },
        "temporalSummary":{
          "candidateCount":0,
          "preciseCount":0,
          "impreciseCount":0,
          "explicitTimeCount":0
        },
        "taxonomySummary":{
          "candidateCount":0,
          "newProposalCount":0,
          "strongestScore":null
        },
        "itemSummary":{
          "candidateCount":1,
          "taskCount":1,
          "verbPresentCount":1,
          "referentPresentCount":1,
          "dueBindingCount":0
        },
        "relationCandidateCount":0
      }
      """;

  @Test
  void summarizesOwnerModelEvidenceWithoutReturningRawContentOrIdentifiers() throws Exception {
    UUID memoId = UUID.randomUUID();
    assertThat(
            createMemo(memoId, OWNER_TITLE_SENTINEL, OWNER_SOURCE_SENTINEL)
                .getResponse()
                .getStatus())
        .isEqualTo(201);
    Instant preparedAt = Instant.now().minusSeconds(60);

    UUID noDispatch = insertHybridRun(OWNER_ID, memoId, preparedAt, false, "NO_NETWORK");

    UUID fullyLegacy =
        insertHybridRun(OWNER_ID, memoId, preparedAt.plusSeconds(1), false, "LEGACY_UNKNOWN");
    insertDispatch(
        OWNER_ID,
        fullyLegacy,
        preparedAt.plusSeconds(1),
        "PREPARED",
        "none",
        "legacy-v0",
        "[]",
        "NOT_RECORDED",
        "[]",
        "legacy-v0",
        "LEGACY_UNKNOWN",
        "LEGACY_UNKNOWN",
        "none",
        0);

    UUID rollingV19 =
        insertHybridRun(OWNER_ID, memoId, preparedAt.plusSeconds(2), false, "NO_NETWORK");
    insertDispatch(
        OWNER_ID,
        rollingV19,
        preparedAt.plusSeconds(2),
        "PREPARED",
        "local-decision-v1",
        "model-fallback-v1",
        "[\"TAG_UNCERTAINTY\"]",
        "PENDING",
        "[]",
        "legacy-v0",
        "LEGACY_UNKNOWN",
        "LEGACY_UNKNOWN",
        "none",
        0);

    UUID customNoNetworkRollingFinalized =
        insertHybridRun(OWNER_ID, memoId, preparedAt.plusSeconds(8), true, "NO_NETWORK");
    db.sql(
            "update analysis_runs set cloud_gateway_version='custom-no-network-v1' "
                + "where id=:id and owner_id=:owner")
        .param("id", customNoNetworkRollingFinalized)
        .param("owner", OWNER_ID)
        .update();
    insertDispatch(
        OWNER_ID,
        customNoNetworkRollingFinalized,
        preparedAt.plusSeconds(8),
        "FINALIZED",
        "local-decision-v1",
        "model-fallback-v1",
        "[\"DATE_UNCERTAINTY\"]",
        "LOCAL_FALLBACK",
        "[]",
        "legacy-v0",
        "LEGACY_UNKNOWN",
        "LEGACY_UNKNOWN",
        "none",
        0);

    UUID aiPreferredPending =
        insertHybridRun(
            OWNER_ID, memoId, preparedAt.plusSeconds(3), false, "LOCAL_MACHINE_MEMO_CONTENT");
    insertDispatch(
        OWNER_ID,
        aiPreferredPending,
        preparedAt.plusSeconds(3),
        "PREPARED",
        "local-decision-v1",
        "model-fallback-v1",
        "[]",
        "PENDING",
        "[]",
        "model-invocation-v1",
        "AI_PREFERRED",
        "AI_PREFERRED_POLICY",
        "approved-type-anchor-k3-v1",
        1);

    UUID uncertaintyRunning =
        insertHybridRun(
            OWNER_ID, memoId, preparedAt.plusSeconds(4), false, "EXTERNAL_MEMO_CONTENT");
    db.sql("update analysis_runs set status='RUNNING' where id=:id and owner_id=:owner")
        .param("id", uncertaintyRunning)
        .param("owner", OWNER_ID)
        .update();
    insertDispatch(
        OWNER_ID,
        uncertaintyRunning,
        preparedAt.plusSeconds(4),
        "RUNNING",
        "local-decision-v1",
        "model-fallback-v1",
        "[\"LOW_TYPE_MARGIN\",\"MULTI_INTENT\"]",
        "PENDING",
        "[]",
        "model-invocation-v1",
        "UNCERTAINTY_ONLY",
        "SEMANTIC_UNCERTAINTY",
        "none",
        0);

    UUID changed =
        insertHybridRun(
            OWNER_ID, memoId, preparedAt.plusSeconds(5), true, "LOCAL_MACHINE_MEMO_CONTENT");
    insertDispatch(
        OWNER_ID,
        changed,
        preparedAt.plusSeconds(5),
        "FINALIZED",
        "local-decision-v1",
        "model-fallback-v1",
        "[\"LOW_TYPE_MARGIN\"]",
        "ACCEPTED_CHANGED",
        "[\"SUGGESTED_TITLE\",\"TYPE_CANDIDATES\"]",
        "model-invocation-v1",
        "AI_PREFERRED",
        "AI_PREFERRED_POLICY",
        "approved-type-anchor-k3-v1",
        2);

    UUID fallback =
        insertHybridRun(
            OWNER_ID, memoId, preparedAt.plusSeconds(6), true, "LOCAL_MACHINE_MEMO_CONTENT");
    insertDispatch(
        OWNER_ID,
        fallback,
        preparedAt.plusSeconds(6),
        "FINALIZED",
        "local-decision-v1",
        "model-fallback-v1",
        "[\"UNPARSED_TEMPORAL_CUE\"]",
        "LOCAL_FALLBACK",
        "[]",
        "model-invocation-v1",
        "UNCERTAINTY_ONLY",
        "SEMANTIC_UNCERTAINTY",
        "none",
        0);

    UUID unchanged =
        insertHybridRun(
            OWNER_ID, memoId, preparedAt.plusSeconds(7), true, "LOCAL_MACHINE_MEMO_CONTENT");
    insertDispatch(
        OWNER_ID,
        unchanged,
        preparedAt.plusSeconds(7),
        "FINALIZED",
        "local-decision-v1",
        "model-fallback-v1",
        "[\"DEFAULT_RECORD_FALLBACK\"]",
        "ACCEPTED_UNCHANGED",
        "[]",
        "model-invocation-v1",
        "AI_PREFERRED",
        "SEMANTIC_UNCERTAINTY",
        "approved-type-anchor-k3-v1",
        0);

    UUID foreignRun = seedForeignRun(preparedAt.plusSeconds(9));

    var result = mvc.perform(get("/api/v1/analysis-path-evidence/summary")).andReturn();
    JsonNode body = response(result);

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
    assertThat(body.propertyNames())
        .containsExactlyInAnyOrder(
            "schemaVersion",
            "aggregationPolicyVersion",
            "cohort",
            "runs",
            "localDecisionEvidence",
            "lifecycle",
            "dispatchRoutes",
            "invocationModes",
            "invocationReasons",
            "localModelContributions",
            "approvedCorrectionSnapshots",
            "fallbackReasons",
            "changedFields");
    assertThat(body.path("cohort").propertyNames())
        .containsExactlyInAnyOrder("basis", "days", "fromInclusive", "toExclusive", "maxRuns");
    assertThat(body.path("runs").propertyNames())
        .containsExactlyInAnyOrder("total", "withDispatch", "withoutDispatch");
    assertThat(body.path("localDecisionEvidence").propertyNames())
        .containsExactlyInAnyOrder("current", "legacy");
    assertThat(body.path("lifecycle").propertyNames())
        .containsExactlyInAnyOrder("prepared", "running", "finalized");
    assertThat(body.path("dispatchRoutes").propertyNames())
        .containsExactlyInAnyOrder(
            "localModel", "externalMemoTransfer", "builtInFake", "legacyOrOther");
    assertThat(body.path("invocationModes").propertyNames())
        .containsExactlyInAnyOrder("legacyUnknown", "uncertaintyOnly", "aiPreferred");
    assertThat(body.path("invocationReasons").propertyNames())
        .containsExactlyInAnyOrder("legacyUnknown", "semanticUncertainty", "aiPreferredPolicy");
    assertThat(body.path("localModelContributions").propertyNames())
        .containsExactlyInAnyOrder(
            "notRecorded", "pending", "acceptedChanged", "acceptedUnchanged", "localFallback");
    assertThat(body.path("approvedCorrectionSnapshots").propertyNames())
        .containsExactlyInAnyOrder("withSignals", "totalSignals");
    assertThat(body.path("fallbackReasons").propertyNames())
        .containsExactlyInAnyOrder(
            "defaultRecordFallback",
            "unparsedTemporalCue",
            "unrecognizedActionCue",
            "lowTypeMargin",
            "tagUncertainty",
            "dateUncertainty",
            "unresolvedReference",
            "incompleteTask",
            "multiIntent",
            "candidateLimit",
            "localConflict");
    assertThat(body.path("changedFields").propertyNames())
        .containsExactlyInAnyOrder(
            "suggestedTitle",
            "typeCandidates",
            "dateCandidates",
            "tagCandidates",
            "itemCandidates",
            "relationCandidates",
            "ambiguityReasons");
    assertThat(body.path("schemaVersion").asText()).isEqualTo("1");
    assertThat(body.path("aggregationPolicyVersion").asText())
        .isEqualTo("analysis-path-evidence-summary-v1");
    assertThat(body.at("/cohort/basis").asText()).isEqualTo("ANALYSIS_RUN_CREATED_AT");
    assertThat(body.at("/cohort/days").asInt()).isEqualTo(14);
    assertThat(body.at("/cohort/maxRuns").asInt()).isEqualTo(1000);
    assertThat(body.at("/runs/total").asInt()).isEqualTo(9);
    assertThat(body.at("/runs/withDispatch").asInt()).isEqualTo(8);
    assertThat(body.at("/runs/withoutDispatch").asInt()).isEqualTo(1);
    assertThat(body.at("/localDecisionEvidence/current").asInt()).isEqualTo(7);
    assertThat(body.at("/localDecisionEvidence/legacy").asInt()).isEqualTo(1);
    assertThat(body.at("/lifecycle/prepared").asInt()).isEqualTo(3);
    assertThat(body.at("/lifecycle/running").asInt()).isEqualTo(1);
    assertThat(body.at("/lifecycle/finalized").asInt()).isEqualTo(4);
    assertThat(body.at("/dispatchRoutes/localModel").asInt()).isEqualTo(4);
    assertThat(body.at("/dispatchRoutes/externalMemoTransfer").asInt()).isEqualTo(1);
    assertThat(body.at("/dispatchRoutes/builtInFake").asInt()).isEqualTo(1);
    assertThat(body.at("/dispatchRoutes/legacyOrOther").asInt()).isEqualTo(2);
    assertThat(body.at("/invocationModes/legacyUnknown").asInt()).isEqualTo(3);
    assertThat(body.at("/invocationModes/uncertaintyOnly").asInt()).isEqualTo(2);
    assertThat(body.at("/invocationModes/aiPreferred").asInt()).isEqualTo(3);
    assertThat(body.at("/invocationReasons/legacyUnknown").asInt()).isEqualTo(3);
    assertThat(body.at("/invocationReasons/semanticUncertainty").asInt()).isEqualTo(3);
    assertThat(body.at("/invocationReasons/aiPreferredPolicy").asInt()).isEqualTo(2);
    assertThat(body.at("/localModelContributions/notRecorded").asInt()).isEqualTo(0);
    assertThat(body.at("/localModelContributions/pending").asInt()).isEqualTo(1);
    assertThat(body.at("/localModelContributions/acceptedChanged").asInt()).isEqualTo(1);
    assertThat(body.at("/localModelContributions/acceptedUnchanged").asInt()).isEqualTo(1);
    assertThat(body.at("/localModelContributions/localFallback").asInt()).isEqualTo(1);
    assertThat(body.at("/approvedCorrectionSnapshots/withSignals").asInt()).isEqualTo(2);
    assertThat(body.at("/approvedCorrectionSnapshots/totalSignals").asInt()).isEqualTo(3);
    assertThat(body.at("/fallbackReasons/defaultRecordFallback").asInt()).isEqualTo(1);
    assertThat(body.at("/fallbackReasons/unparsedTemporalCue").asInt()).isEqualTo(1);
    assertThat(body.at("/fallbackReasons/lowTypeMargin").asInt()).isEqualTo(2);
    assertThat(body.at("/fallbackReasons/tagUncertainty").asInt()).isEqualTo(1);
    assertThat(body.at("/fallbackReasons/dateUncertainty").asInt()).isEqualTo(1);
    assertThat(body.at("/fallbackReasons/multiIntent").asInt()).isEqualTo(1);
    assertThat(body.at("/changedFields/suggestedTitle").asInt()).isEqualTo(1);
    assertThat(body.at("/changedFields/typeCandidates").asInt()).isEqualTo(1);
    int recordedLocalModelContributions =
        body.at("/localModelContributions/pending").asInt()
            + body.at("/localModelContributions/acceptedChanged").asInt()
            + body.at("/localModelContributions/acceptedUnchanged").asInt()
            + body.at("/localModelContributions/localFallback").asInt();
    assertThat(recordedLocalModelContributions)
        .isLessThanOrEqualTo(body.at("/localDecisionEvidence/current").asInt());
    assertThat(sumNumericProperties(body.path("fallbackReasons")))
        .isGreaterThanOrEqualTo(
            body.at("/localDecisionEvidence/current").asInt()
                - body.at("/invocationReasons/aiPreferredPolicy").asInt());
    assertThat(body.toString())
        .doesNotContain(
            PRIVATE_SENTINEL,
            OWNER_TITLE_SENTINEL,
            OWNER_SOURCE_SENTINEL,
            OWNER_ID.toString(),
            memoId.toString(),
            noDispatch.toString(),
            fullyLegacy.toString(),
            rollingV19.toString(),
            customNoNetworkRollingFinalized.toString(),
            aiPreferredPending.toString(),
            uncertaintyRunning.toString(),
            changed.toString(),
            fallback.toString(),
            unchanged.toString(),
            foreignRun.toString(),
            Hashing.sha256(LOCAL_PROPOSAL),
            "memoContent",
            "proposalId",
            "analysisRunId",
            "approvedCorrectionContext",
            "providerRequestToken",
            "cloudTransferMode",
            "cloudGatewayVersion",
            "cloudProviderId",
            "cloudModelVersion",
            "synthetic-model-evidence-gateway",
            "synthetic-model-provider",
            "synthetic-model",
            "fake-cloud-v2",
            "custom-no-network-v1",
            "no-network-v1",
            "local-machine-v1",
            "external-consent-v1",
            "LOCAL_MACHINE_MEMO_CONTENT",
            "EXTERNAL_MEMO_CONTENT",
            "NO_NETWORK",
            "LEGACY_UNKNOWN",
            "DESCRIPTOR_UNAVAILABLE");
  }

  @Test
  void rejectsInvalidDaysAndWindowsAboveTheExplicitRunCap() throws Exception {
    var invalidDays =
        mvc.perform(get("/api/v1/analysis-path-evidence/summary").param("days", "0")).andReturn();
    assertThat(invalidDays.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(invalidDays).path("code").asText()).isEqualTo("VALIDATION_FAILED");

    var aboveMaximumDays =
        mvc.perform(get("/api/v1/analysis-path-evidence/summary").param("days", "91")).andReturn();
    assertThat(aboveMaximumDays.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(aboveMaximumDays).path("code").asText()).isEqualTo("VALIDATION_FAILED");

    var malformedDays =
        mvc.perform(get("/api/v1/analysis-path-evidence/summary").param("days", "invalid"))
            .andReturn();
    assertThat(malformedDays.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(malformedDays).path("code").asText()).isEqualTo("VALIDATION_FAILED");

    UUID memoId = UUID.randomUUID();
    assertThat(
            createMemo(memoId, "model-evidence-cap", "synthetic cap source")
                .getResponse()
                .getStatus())
        .isEqualTo(201);
    seedRunCap(memoId);

    var aboveCap =
        mvc.perform(get("/api/v1/analysis-path-evidence/summary").param("days", "1")).andReturn();
    assertThat(aboveCap.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(aboveCap).path("code").asText())
        .isEqualTo("ANALYSIS_PATH_EVIDENCE_WINDOW_TOO_LARGE");
  }

  private UUID insertLocalRun(UUID ownerId, UUID memoId, Instant createdAt) {
    UUID runId = UUID.randomUUID();
    db.sql(
            """
            insert into analysis_runs(
              id,owner_id,memo_id,memo_revision,route,status,schema_version,analyzer_version,
              ambiguity_reasons,created_at,completed_at,routing_policy_version,prompt_version,
              local_model_version,embedding_model_version,cloud_execution_contract_version
            ) values(
              :id,:owner,:memo,1,'LOCAL','REVIEW_REQUIRED','2','synthetic-model-evidence',
              '[]'::jsonb,:createdAt,:createdAt,'synthetic-policy','none','none','none','legacy-v0'
            )
            """)
        .param("id", runId)
        .param("owner", ownerId)
        .param("memo", memoId)
        .param("createdAt", Timestamp.from(createdAt))
        .update();
    return runId;
  }

  private UUID insertHybridRun(
      UUID ownerId, UUID memoId, Instant createdAt, boolean finalized, String transferMode) {
    UUID runId = UUID.randomUUID();
    boolean legacy = "LEGACY_UNKNOWN".equals(transferMode);
    boolean builtInFake = "NO_NETWORK".equals(transferMode);
    boolean external = "EXTERNAL_MEMO_CONTENT".equals(transferMode);
    String gatewayVersion =
        legacy
            ? "legacy-unknown"
            : builtInFake ? "fake-cloud-v2" : "synthetic-model-evidence-gateway";
    String providerId =
        legacy ? "legacy-unknown" : builtInFake ? "fake" : "synthetic-model-provider";
    String modelVersion = legacy ? "legacy-unknown" : builtInFake ? "none" : "synthetic-model";
    String consentPolicyVersion =
        legacy
            ? "legacy-unknown"
            : builtInFake ? "no-network-v1" : external ? "external-consent-v1" : "local-machine-v1";
    String outcome = legacy ? "LEGACY_UNKNOWN" : finalized ? "SUCCESS" : "PENDING";
    Timestamp authorization = external ? Timestamp.from(createdAt) : null;
    db.sql(
            """
            insert into analysis_runs(
              id,owner_id,memo_id,memo_revision,route,status,schema_version,analyzer_version,
              prompt_version,local_model_version,embedding_model_version,routing_policy_version,
              cloud_transfer_mode,cloud_gateway_version,cloud_provider_id,cloud_model_version,
              cloud_consent_policy_version,cloud_outcome,cloud_execution_contract_version,
              cloud_provider_request_token,cloud_authorization_checked_at,
              cloud_accepted_consent_granted_at,ambiguity_reasons,created_at,completed_at
            ) values(
              :id,:owner,:memo,1,'HYBRID',:status,'2','synthetic-model-evidence','none','none',
              'none','synthetic-policy',:transferMode,:gatewayVersion,:providerId,:modelVersion,
              :consentPolicyVersion,:outcome,:executionContractVersion,:token,:authorization,
              :authorization,'[]'::jsonb,:createdAt,:completedAt
            )
            """)
        .param("id", runId)
        .param("owner", ownerId)
        .param("memo", memoId)
        .param("status", finalized ? "REVIEW_REQUIRED" : "QUEUED")
        .param("transferMode", transferMode)
        .param("gatewayVersion", gatewayVersion)
        .param("providerId", providerId)
        .param("modelVersion", modelVersion)
        .param("consentPolicyVersion", consentPolicyVersion)
        .param("outcome", outcome)
        .param("executionContractVersion", legacy ? "legacy-v0" : "durable-v1")
        .param("token", legacy ? null : "pmr1_" + Hashing.sha256("token-" + runId))
        .param("authorization", authorization)
        .param("createdAt", Timestamp.from(createdAt))
        .param("completedAt", finalized ? Timestamp.from(createdAt.plusSeconds(3)) : null)
        .update();
    return runId;
  }

  private void insertDispatch(
      UUID ownerId,
      UUID runId,
      Instant preparedAt,
      String state,
      String localEvidenceVersion,
      String fallbackPolicyVersion,
      String fallbackReasons,
      String modelContribution,
      String changedFields,
      String invocationPolicyVersion,
      String invocationMode,
      String invocationReason,
      String approvedContextVersion,
      int approvedContextCount) {
    boolean finalized = "FINALIZED".equals(state);
    boolean running = "RUNNING".equals(state);
    String approvedContext =
        "approved-type-anchor-k3-v1".equals(approvedContextVersion)
            ? approvedContext(approvedContextCount)
            : null;
    db.sql(
            """
            insert into analysis_run_dispatches(
              analysis_run_id,owner_id,reserved_proposal_id,idempotency_key_hash,request_hash,
              validated_local_proposal,validated_local_proposal_hash,executor_binding_id,
              call_timeout_ms,max_attempts,deadline_at,state,fence_token,last_attempt_started_at,
              lease_expires_at,prepared_at,finalized_at,updated_at,retrieval_context,
              retrieval_context_hash,retrieval_context_version,retrieval_context_candidate_count,
              attempt_history_version,local_decision_evidence_version,local_decision_evidence,
              fallback_policy_version,fallback_reason_codes,model_contribution_status,
              model_changed_fields,invocation_policy_version,invocation_mode,
              invocation_reason_code,approved_correction_context,
              approved_correction_context_hash,approved_correction_context_version,
              approved_correction_context_count
            ) values(
              :runId,:owner,:proposalId,:keyHash,:requestHash,:proposal,:proposalHash,:bindingId,
              1000,3,:deadline,:state,:fence,:lastAttempt,:lease,:preparedAt,:finalizedAt,:updatedAt,
              null,null,'none',0,'none',:localEvidenceVersion,cast(:localEvidence as jsonb),
              :fallbackPolicyVersion,cast(:fallbackReasons as jsonb),:modelContribution,
              cast(:changedFields as jsonb),:invocationPolicyVersion,:invocationMode,
              :invocationReason,:approvedContext,:approvedContextHash,:approvedContextVersion,
              :approvedContextCount
            )
            """)
        .param("runId", runId)
        .param("owner", ownerId)
        .param("proposalId", UUID.randomUUID())
        .param("keyHash", Hashing.sha256("key-" + runId))
        .param("requestHash", Hashing.sha256("request-" + runId))
        .param("proposal", finalized ? null : LOCAL_PROPOSAL)
        .param("proposalHash", Hashing.sha256(LOCAL_PROPOSAL))
        .param("bindingId", "cgb1_" + Hashing.sha256("binding-" + runId))
        .param("deadline", Timestamp.from(preparedAt.plusSeconds(10)))
        .param("state", state)
        .param("fence", running ? 1 : 0)
        .param("lastAttempt", running ? Timestamp.from(preparedAt.plusSeconds(1)) : null)
        .param("lease", running ? Timestamp.from(preparedAt.plusSeconds(5)) : null)
        .param("preparedAt", Timestamp.from(preparedAt))
        .param("finalizedAt", finalized ? Timestamp.from(preparedAt.plusSeconds(3)) : null)
        .param("updatedAt", Timestamp.from(preparedAt.plusSeconds(finalized ? 3 : running ? 1 : 0)))
        .param("localEvidenceVersion", localEvidenceVersion)
        .param(
            "localEvidence",
            "local-decision-v1".equals(localEvidenceVersion) ? CURRENT_EVIDENCE : null)
        .param("fallbackPolicyVersion", fallbackPolicyVersion)
        .param("fallbackReasons", fallbackReasons)
        .param("modelContribution", modelContribution)
        .param("changedFields", changedFields)
        .param("invocationPolicyVersion", invocationPolicyVersion)
        .param("invocationMode", invocationMode)
        .param("invocationReason", invocationReason)
        .param("approvedContext", finalized ? null : approvedContext)
        .param(
            "approvedContextHash", approvedContext == null ? null : Hashing.sha256(approvedContext))
        .param("approvedContextVersion", approvedContextVersion)
        .param("approvedContextCount", approvedContextCount)
        .update();
  }

  private String approvedContext(int signalCount) {
    StringBuilder signals = new StringBuilder();
    for (int index = 0; index < signalCount; index++) {
      if (index > 0) {
        signals.append(',');
      }
      signals
          .append("{\"startUtf16\":")
          .append(index * 4)
          .append(",\"endUtf16\":")
          .append(index * 4 + 3)
          .append(",\"approvedKind\":\"TASK\"}");
    }
    return "{\"version\":\"approved-type-anchor-k3-v1\",\"signals\":[" + signals + "]}";
  }

  private UUID seedForeignRun(Instant createdAt) {
    UUID owner = UUID.randomUUID();
    UUID memo = UUID.randomUUID();
    Timestamp now = Timestamp.from(createdAt);
    db.sql("insert into users(id,created_at,updated_at) values(:id,:now,:now)")
        .param("id", owner)
        .param("now", now)
        .update();
    db.sql(
            "insert into user_settings(user_id,time_zone,cloud_analysis_consent) "
                + "values(:id,'Asia/Seoul',false)")
        .param("id", owner)
        .update();
    db.sql(
            "insert into memos(id,owner_id,current_revision,status,pinned,created_at,updated_at) "
                + "values(:memo,:owner,1,'ACTIVE',false,:now,:now)")
        .param("memo", memo)
        .param("owner", owner)
        .param("now", now)
        .update();
    db.sql(
            "insert into memo_revisions(memo_id,owner_id,revision,content,content_hash,created_at,"
                + "created_by,client_recorded_at,source_time_zone) "
                + "values(:memo,:owner,1,:content,:hash,:now,:owner,:now,'Asia/Seoul')")
        .param("memo", memo)
        .param("owner", owner)
        .param("content", PRIVATE_SENTINEL)
        .param("hash", Hashing.sha256(PRIVATE_SENTINEL))
        .param("now", now)
        .update();
    return insertLocalRun(owner, memo, createdAt);
  }

  private void seedRunCap(UUID memoId) {
    db.sql(
            """
            insert into analysis_runs(
              id,owner_id,memo_id,memo_revision,route,status,schema_version,analyzer_version,
              ambiguity_reasons,created_at,completed_at,routing_policy_version,prompt_version,
              local_model_version,embedding_model_version,cloud_execution_contract_version
            )
            select md5('model-evidence-cap-run-' || value)::uuid,:owner,:memo,1,
                   'LOCAL','REVIEW_REQUIRED','2','synthetic-cap','[]'::jsonb,
                   current_timestamp - interval '1 minute',
                   current_timestamp - interval '1 minute','synthetic-policy','none','none','none',
                   'legacy-v0'
              from generate_series(1,1001) value
            """)
        .param("owner", OWNER_ID)
        .param("memo", memoId)
        .update();
  }

  private int sumNumericProperties(JsonNode object) {
    int total = 0;
    for (JsonNode value : object) {
      total += value.asInt();
    }
    return total;
  }
}
