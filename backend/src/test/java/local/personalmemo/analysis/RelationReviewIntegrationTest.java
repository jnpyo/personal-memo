package local.personalmemo.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;

@PostgresIntegration
class RelationReviewIntegrationTest extends PostgresIntegrationTestSupport {

  @Test
  void returnsOrderedOwnerActiveLabelsWithUnicodeSafeMemoPreviewAndNoStore() throws Exception {
    UUID sourceMemoId = UUID.randomUUID();
    UUID targetMemoId = UUID.randomUUID();
    String targetContent = "가".repeat(238) + "😀" + "나".repeat(10);
    createMemo(targetMemoId, "relation-review-target", targetContent);
    UUID proposalId =
        createProposalWithRelations(
            sourceMemoId,
            "relation-review",
            List.of(
                relation("MEMO", targetMemoId, "RELATED_TO", 0.9),
                relation("MEMO", targetMemoId, "REFERENCES", 0.8),
                relation("TAG", ASSIGNMENT_TAG_ID, "RELATED_TO", 0.7)));

    var result =
        mvc.perform(get("/api/v1/analysis-proposals/{id}/relation-review-candidates", proposalId))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
    var body = response(result);
    assertThat(body).hasSize(3);
    assertThat(body.get(0).path("proposalIndex").asInt()).isZero();
    assertThat(body.get(1).path("proposalIndex").asInt()).isEqualTo(1);
    assertThat(body.get(2).path("proposalIndex").asInt()).isEqualTo(2);
    String memoLabel = body.get(0).path("targetLabel").asText();
    assertThat(memoLabel).isEqualTo("가".repeat(238) + "😀…");
    assertThat(memoLabel).isEqualTo(body.get(1).path("targetLabel").asText());
    assertThat(memoLabel.codePointCount(0, memoLabel.length())).isEqualTo(240);
    assertThat(body.get(0).path("available").asBoolean()).isTrue();
    assertThat(body.get(2).path("targetLabel").asText()).isEqualTo("과제");
    assertThat(body.get(2).path("available").asBoolean()).isTrue();
  }

  @Test
  void marksInactiveTargetUnavailableWithoutLeakingItsLabel() throws Exception {
    UUID sourceMemoId = UUID.randomUUID();
    UUID targetMemoId = UUID.randomUUID();
    createMemo(targetMemoId, "relation-review-unavailable-target", "Hidden after trash");
    UUID proposalId =
        createProposalWithRelations(
            sourceMemoId,
            "relation-review-unavailable",
            List.of(relation("MEMO", targetMemoId, "DEPENDS_ON", 0.9)));
    trashMemo(targetMemoId, "relation-review-unavailable-trash");

    var result =
        mvc.perform(get("/api/v1/analysis-proposals/{id}/relation-review-candidates", proposalId))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    var candidate = response(result).get(0);
    assertThat(candidate.path("available").asBoolean()).isFalse();
    assertThat(candidate.path("targetLabel").isNull()).isTrue();
  }

  @Test
  void marksCrossOwnerTargetUnavailableWithoutLeakingItsRawMemo() throws Exception {
    UUID sourceMemoId = UUID.randomUUID();
    UUID foreignMemoId = seedForeignMemo("Foreign owner secret");
    UUID proposalId =
        createProposalWithRelations(
            sourceMemoId,
            "relation-review-cross-owner",
            List.of(relation("MEMO", foreignMemoId, "REFERENCES", 0.9)));

    var result =
        mvc.perform(get("/api/v1/analysis-proposals/{id}/relation-review-candidates", proposalId))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    var candidate = response(result).get(0);
    assertThat(candidate.path("available").asBoolean()).isFalse();
    assertThat(candidate.path("targetLabel").isNull()).isTrue();
    assertThat(result.getResponse().getContentAsString()).doesNotContain("Foreign owner secret");
  }

  @Test
  void corruptedDuplicateProposalFailsClosedBeforeProjectingTargetLabels() throws Exception {
    UUID sourceMemoId = UUID.randomUUID();
    UUID targetMemoId = UUID.randomUUID();
    createMemo(targetMemoId, "relation-review-invalid-target", "Must not be projected");
    Map<String, Object> duplicate = relation("MEMO", targetMemoId, "CONTINUES", 0.9);
    UUID proposalId =
        createProposalWithRelations(
            sourceMemoId, "relation-review-invalid", List.of(duplicate, duplicate));

    var result =
        mvc.perform(get("/api/v1/analysis-proposals/{id}/relation-review-candidates", proposalId))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(result).path("code").asText()).isEqualTo("PROPOSAL_CHANGED");
    assertThat(result.getResponse().getContentAsString()).doesNotContain("Must not be projected");
  }

  private UUID createProposalWithRelations(
      UUID sourceMemoId, String keyPrefix, List<Map<String, Object>> relations) throws Exception {
    createMemo(sourceMemoId, keyPrefix + "-create", "Call supplier tomorrow");
    UUID proposalId =
        UUID.fromString(
            response(startAnalysis(sourceMemoId, keyPrefix + "-start", 1))
                .path("proposalId")
                .asText());
    db.sql(
            """
            update analysis_proposals
               set proposal_json = jsonb_set(
                 proposal_json,
                 '{relationCandidates}',
                 cast(:relations as jsonb),
                 false
               )
             where id = :proposalId
               and owner_id = :ownerId
            """)
        .param("relations", json.writeValueAsString(relations))
        .param("proposalId", proposalId)
        .param("ownerId", OWNER_ID)
        .update();
    return proposalId;
  }

  private Map<String, Object> relation(
      String targetType, UUID targetId, String relationType, double score) {
    return Map.of(
        "sourceCandidateId", "item-1",
        "targetType", targetType,
        "targetId", targetId,
        "relationType", relationType,
        "score", score);
  }

  private UUID seedForeignMemo(String content) {
    UUID ownerId = UUID.randomUUID();
    UUID memoId = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.parse("2026-08-05T02:00:00Z"));
    db.sql("insert into users(id,created_at,updated_at) values(:id,:now,:now)")
        .param("id", ownerId)
        .param("now", now)
        .update();
    db.sql(
            """
            insert into memos(
              id, owner_id, current_revision, status, pinned, created_at, updated_at
            ) values (
              :id, :ownerId, 1, 'ACTIVE', false, :now, :now
            )
            """)
        .param("id", memoId)
        .param("ownerId", ownerId)
        .param("now", now)
        .update();
    db.sql(
            """
            insert into memo_revisions(
              memo_id, owner_id, revision, content, content_hash, created_at, created_by,
              client_recorded_at, source_time_zone
            ) values (
              :memoId, :ownerId, 1, :content, repeat('f', 64), :now, :ownerId,
              :now, 'Asia/Seoul'
            )
            """)
        .param("memoId", memoId)
        .param("ownerId", ownerId)
        .param("content", content)
        .param("now", now)
        .update();
    return memoId;
  }
}
