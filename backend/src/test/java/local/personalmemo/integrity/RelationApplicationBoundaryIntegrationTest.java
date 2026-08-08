package local.personalmemo.integrity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;

@PostgresIntegration
class RelationApplicationBoundaryIntegrationTest extends PostgresIntegrationTestSupport {

  @Test
  void relationCandidatesFailClosedUntilExplicitSelectionAndPersistenceAreSupported()
      throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "create-relation-boundary", "Call supplier tomorrow");
    var started = startAnalysis(memoId, "start-relation-boundary", 1);
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());
    UUID runId = UUID.fromString(response(started).path("id").asText());

    String relations =
        json.writeValueAsString(
            List.of(
                Map.of(
                    "sourceCandidateId", "item-1",
                    "targetType", "MEMO",
                    "targetId", memoId,
                    "relationType", "RELATED_TO",
                    "score", 0.9)));
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
        .param("relations", relations)
        .param("proposalId", proposalId)
        .param("ownerId", OWNER_ID)
        .update();

    var rejected =
        applyProposal(proposalId, "apply-relation-boundary", 1, "Call supplier tomorrow", null);

    assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(rejected).path("code").asText())
        .isEqualTo("PROPOSAL_RELATIONS_UNSUPPORTED");
    assertThat(db.sql("select count(*) from analysis_applications").query(Long.class).single())
        .isZero();
    assertThat(db.sql("select count(*) from memo_items").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from task_details").query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from item_tags").query(Long.class).single()).isZero();
    assertThat(
            db.sql("select status from analysis_runs where id = :runId")
                .param("runId", runId)
                .query(String.class)
                .single())
        .isEqualTo("REVIEW_REQUIRED");
    assertThat(
            db.sql("select content from memo_revisions where memo_id = :memoId and revision = 1")
                .param("memoId", memoId)
                .query(String.class)
                .single())
        .isEqualTo("Call supplier tomorrow");
  }
}
