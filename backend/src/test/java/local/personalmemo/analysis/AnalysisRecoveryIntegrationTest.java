package local.personalmemo.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;

@PostgresIntegration
class AnalysisRecoveryIntegrationTest extends PostgresIntegrationTestSupport {

  @Test
  void latestApplicationAlwaysReturnsARecoveryStateAndNeverLeaksAnotherOwner() throws Exception {
    var none = mvc.perform(get("/api/v1/analysis-applications/latest")).andReturn();
    assertThat(none.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(none).path("applicationId").isNull()).isTrue();
    assertThat(response(none).path("status").asText()).isEqualTo("NONE");

    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "recovery-latest-create", "복구할 작업");
    UUID proposalId =
        UUID.fromString(
            response(startAnalysis(memoId, "recovery-latest-start", 1))
                .path("proposalId")
                .asText());
    UUID applicationId =
        UUID.fromString(
            response(
                    applyProposal(
                        proposalId, "recovery-latest-apply", 1, "복구할 작업", null))
                .path("applicationId")
                .asText());

    seedForeignApplication(Instant.now().plusSeconds(3600), "APPLIED");

    var applied = mvc.perform(get("/api/v1/analysis-applications/latest")).andReturn();
    assertThat(response(applied).path("applicationId").asText())
        .isEqualTo(applicationId.toString());
    assertThat(response(applied).path("status").asText()).isEqualTo("APPLIED");

    undoApplication(applicationId, "recovery-latest-undo");
    var undone = mvc.perform(get("/api/v1/analysis-applications/latest")).andReturn();
    assertThat(response(undone).path("applicationId").asText())
        .isEqualTo(applicationId.toString());
    assertThat(response(undone).path("status").asText()).isEqualTo("UNDONE");
  }

  @Test
  void recoveryReturnsOnlyOwnedActiveCurrentRevisionProposalsForEachReviewStatus()
      throws Exception {
    UUID currentMemo = UUID.randomUUID();
    createMemo(currentMemo, "recovery-current-create", "나중에 검토할 현재 메모");
    UUID currentProposal =
        UUID.fromString(
            response(startAnalysis(currentMemo, "recovery-current-start", 1))
                .path("proposalId")
                .asText());
    postpone(currentProposal, "recovery-current-postpone");
    setProposalCreatedAt(currentProposal, Instant.parse("2026-08-05T03:00:00Z"));

    UUID reviewMemo = UUID.randomUUID();
    createMemo(reviewMemo, "recovery-review-create", "current review memo");
    UUID reviewProposal =
        UUID.fromString(
            response(startAnalysis(reviewMemo, "recovery-review-start", 1))
                .path("proposalId")
                .asText());
    setProposalCreatedAt(reviewProposal, Instant.parse("2026-08-05T04:00:00Z"));

    UUID oldRevisionMemo = UUID.randomUUID();
    createMemo(oldRevisionMemo, "recovery-old-create", "이전 revision");
    UUID oldRevisionProposal =
        UUID.fromString(
            response(startAnalysis(oldRevisionMemo, "recovery-old-start", 1))
                .path("proposalId")
                .asText());
    postpone(oldRevisionProposal, "recovery-old-postpone");
    updateMemo(oldRevisionMemo, 1, "현재 revision");
    forceRunStatus(oldRevisionProposal, "POSTPONED");

    UUID trashedMemo = UUID.randomUUID();
    createMemo(trashedMemo, "recovery-trashed-create", "휴지통 메모");
    UUID trashedProposal =
        UUID.fromString(
            response(startAnalysis(trashedMemo, "recovery-trashed-start", 1))
                .path("proposalId")
                .asText());
    postpone(trashedProposal, "recovery-trashed-postpone");
    trashMemo(trashedMemo, "recovery-trash");
    forceRunStatus(trashedProposal, "POSTPONED");

    seedForeignProposal(Instant.now().plusSeconds(7200), "POSTPONED");
    seedForeignProposal(Instant.now().plusSeconds(10800), "REVIEW_REQUIRED");

    var recovered =
        mvc.perform(
                get("/api/v1/analysis-proposals")
                    .param("status", "POSTPONED")
                    .param("limit", "1000"))
            .andReturn();
    assertThat(recovered.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(recovered).size()).isEqualTo(1);
    assertThat(response(recovered).at("/0/proposalId").asText())
        .isEqualTo(currentProposal.toString());
    assertThat(response(recovered).at("/0/status").asText()).isEqualTo("POSTPONED");
    assertThat(response(recovered).at("/0/createdAt").asText())
        .isEqualTo("2026-08-05T03:00:00Z");
    assertThat(response(recovered).at("/0/proposal/memoId").asText())
        .isEqualTo(currentMemo.toString());

    var awaitingReview =
        mvc.perform(
                get("/api/v1/analysis-proposals")
                    .param("status", "REVIEW_REQUIRED")
                    .param("limit", "1"))
            .andReturn();
    assertThat(awaitingReview.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(awaitingReview).size()).isEqualTo(1);
    assertThat(response(awaitingReview).at("/0/proposalId").asText())
        .isEqualTo(reviewProposal.toString());
    assertThat(response(awaitingReview).at("/0/status").asText())
        .isEqualTo("REVIEW_REQUIRED");
    assertThat(response(awaitingReview).at("/0/createdAt").asText())
        .isEqualTo("2026-08-05T04:00:00Z");
    assertThat(response(awaitingReview).at("/0/proposal/memoId").asText())
        .isEqualTo(reviewMemo.toString());

    var bounded =
        mvc.perform(
                get("/api/v1/analysis-proposals")
                    .param("status", "POSTPONED")
                    .param("limit", "0"))
            .andReturn();
    assertThat(response(bounded).size()).isEqualTo(1);

    var unsupported =
        mvc.perform(
                get("/api/v1/analysis-proposals")
                    .param("status", "APPLIED")
                    .param("limit", "1"))
            .andReturn();
    assertThat(unsupported.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(unsupported).path("code").asText())
        .isEqualTo("INVALID_PROPOSAL_STATUS");
  }

  private void postpone(UUID proposalId, String key) throws Exception {
    var result =
        mvc.perform(
                post("/api/v1/analysis-proposals/{id}/postpone", proposalId)
                    .header("Idempotency-Key", key))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
  }

  private void forceRunStatus(UUID proposalId, String status) {
    db.sql(
            """
            update analysis_runs r
               set status = :status
             where r.id = (
               select p.analysis_run_id
                 from analysis_proposals p
                where p.id = :proposalId
                  and p.owner_id = :ownerId
             )
               and r.owner_id = :ownerId
            """)
        .param("status", status)
        .param("proposalId", proposalId)
        .param("ownerId", OWNER_ID)
        .update();
  }

  private void setProposalCreatedAt(UUID proposalId, Instant createdAt) {
    db.sql(
            "update analysis_proposals set created_at=:createdAt "
                + "where id=:proposalId and owner_id=:ownerId")
        .param("createdAt", Timestamp.from(createdAt))
        .param("proposalId", proposalId)
        .param("ownerId", OWNER_ID)
        .update();
  }

  private UUID seedForeignApplication(Instant when, String applicationStatus) {
    ForeignProposal foreign = seedForeignProposal(when, "APPLIED");
    UUID applicationId = UUID.randomUUID();
    db.sql(
            """
            insert into analysis_applications(
              id, owner_id, proposal_id, memo_id, memo_revision, idempotency_key,
              status, selection_json, applied_at, undone_at
            ) values (
              :id, :ownerId, :proposalId, :memoId, 1, :key,
              :status, '{}', :when, null
            )
            """)
        .param("id", applicationId)
        .param("ownerId", foreign.ownerId())
        .param("proposalId", foreign.proposalId())
        .param("memoId", foreign.memoId())
        .param("key", "foreign-application-" + applicationId)
        .param("status", applicationStatus)
        .param("when", Timestamp.from(when))
        .update();
    return applicationId;
  }

  private ForeignProposal seedForeignProposal(Instant when, String runStatus) {
    UUID ownerId = UUID.randomUUID();
    UUID memoId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID proposalId = UUID.randomUUID();
    Timestamp timestamp = Timestamp.from(when);
    db.sql("insert into users(id,created_at,updated_at) values(:id,:when,:when)")
        .param("id", ownerId)
        .param("when", timestamp)
        .update();
    db.sql(
            "insert into memos(id,owner_id,current_revision,status,pinned,created_at,updated_at) "
                + "values(:id,:ownerId,1,'ACTIVE',false,:when,:when)")
        .param("id", memoId)
        .param("ownerId", ownerId)
        .param("when", timestamp)
        .update();
    db.sql(
            "insert into memo_revisions(memo_id,owner_id,revision,content,content_hash,created_at,created_by) "
                + "values(:memoId,:ownerId,1,'foreign',repeat('f',64),:when,:ownerId)")
        .param("memoId", memoId)
        .param("ownerId", ownerId)
        .param("when", timestamp)
        .update();
    db.sql(
            """
            insert into analysis_runs(
              id, owner_id, memo_id, memo_revision, route, status, schema_version,
              analyzer_version, ambiguity_reasons, created_at, completed_at
            ) values (
              :id, :ownerId, :memoId, 1, 'MOCK', :status, '1',
              'fake-v1', '[]', :when, :when
            )
            """)
        .param("id", runId)
        .param("ownerId", ownerId)
        .param("memoId", memoId)
        .param("status", runStatus)
        .param("when", timestamp)
        .update();
    db.sql(
            """
            insert into analysis_proposals(
              id, owner_id, analysis_run_id, proposal_json, proposal_hash, created_at
            ) values (
              :id, :ownerId, :runId,
              cast(:proposal as jsonb), repeat('e',64), :when
            )
            """)
        .param("id", proposalId)
        .param("ownerId", ownerId)
        .param("runId", runId)
        .param(
            "proposal",
            "{\"schemaVersion\":\"1\",\"memoId\":\"" + memoId + "\",\"memoRevision\":1}")
        .param("when", timestamp)
        .update();
    return new ForeignProposal(ownerId, memoId, proposalId);
  }

  private record ForeignProposal(UUID ownerId, UUID memoId, UUID proposalId) {}
}
