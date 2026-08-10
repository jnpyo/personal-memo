package local.personalmemo.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@PostgresIntegration
class AnalysisReviewOutcomeIntegrationTest extends PostgresIntegrationTestSupport {

  @Test
  void summarizesLatestOwnerSelectionsWithoutReturningPrivateContentOrIdentifiers()
      throws Exception {
    UUID exactMemo = UUID.randomUUID();
    UUID exactProposal = createProposal(exactMemo, "exact-memo", "11.25 OS과제 제출");
    JsonNode exactProposalJson = proposal(exactProposal);
    var exactApplied =
        applyProposal(exactProposal, "exact-application", defaultApplication(exactProposalJson));
    assertThat(exactApplied.getResponse().getStatus()).isEqualTo(200);

    UUID correctedMemo = UUID.randomUUID();
    UUID correctedProposal = createProposal(correctedMemo, "corrected-memo", "11.25 OS과제 제출");
    JsonNode correctedProposalJson = proposal(correctedProposal);
    var firstCorrectedApply =
        applyProposal(
            correctedProposal,
            "corrected-first-application",
            defaultApplication(correctedProposalJson));
    assertThat(firstCorrectedApply.getResponse().getStatus()).isEqualTo(200);
    UUID firstCorrectedApplicationId =
        UUID.fromString(response(firstCorrectedApply).path("applicationId").asText());
    assertThat(
            undoApplication(firstCorrectedApplicationId, "corrected-first-undo")
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    Map<String, Object> correctedApplication = defaultApplication(correctedProposalJson);
    correctedApplication.put("title", "사용자가 고친 제목");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> correctedItems =
        (List<Map<String, Object>>) correctedApplication.get("items");
    correctedItems.getFirst().put("title", "사용자가 고친 제목");
    correctedItems.getFirst().put("due", null);
    var correctedApplied =
        applyProposal(correctedProposal, "corrected-application", correctedApplication);
    assertThat(correctedApplied.getResponse().getStatus()).isEqualTo(200);

    UUID unresolvedMemo = UUID.randomUUID();
    UUID unresolvedProposal = createProposal(unresolvedMemo, "unresolved-memo", "11.25 운영체제 과제");
    var resolvedApplied =
        applyProposal(unresolvedProposal, "resolved-application", resolvedApplication());
    assertThat(resolvedApplied.getResponse().getStatus()).isEqualTo(200);

    UUID postponedMemo = UUID.randomUUID();
    UUID postponedProposal = createProposal(postponedMemo, "postponed-memo", "11.25 OS과제 제출");
    var postponed =
        mvc.perform(
                post("/api/v1/analysis-proposals/{id}/postpone", postponedProposal)
                    .header("Idempotency-Key", "postpone-outcome"))
            .andReturn();
    assertThat(postponed.getResponse().getStatus()).isEqualTo(200);

    UUID rejectedMemo = UUID.randomUUID();
    UUID rejectedProposal = createProposal(rejectedMemo, "rejected-memo", "11.25 OS과제 제출");
    var rejectedApply =
        applyProposal(
            rejectedProposal,
            "rejected-application",
            defaultApplication(proposal(rejectedProposal)));
    UUID rejectedApplicationId =
        UUID.fromString(response(rejectedApply).path("applicationId").asText());
    assertThat(undoApplication(rejectedApplicationId, "rejected-undo").getResponse().getStatus())
        .isEqualTo(200);
    var rejected =
        mvc.perform(
                post("/api/v1/analysis-proposals/{id}/reject", rejectedProposal)
                    .header("Idempotency-Key", "reject-outcome"))
            .andReturn();
    assertThat(rejected.getResponse().getStatus()).isEqualTo(200);

    seedForeignProposal("foreign-owner-private-content");

    var result = mvc.perform(get("/api/v1/analysis-review-outcomes/summary")).andReturn();
    JsonNode body = response(result);

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
    assertThat(body.path("schemaVersion").asText()).isEqualTo("1");
    assertThat(body.path("comparisonPolicyVersion").asText()).isEqualTo("review-default-v3");
    assertThat(body.at("/cohort/basis").asText()).isEqualTo("PROPOSAL_CREATED_AT");
    assertThat(body.at("/cohort/days").asInt()).isEqualTo(14);
    assertThat(body.at("/cohort/maxProposals").asInt()).isEqualTo(1000);
    assertThat(body.at("/proposals/total").asInt()).isEqualTo(5);
    assertThat(body.at("/proposals/withApplication").asInt()).isEqualTo(4);
    assertThat(body.at("/proposals/currentStates/applied").asInt()).isEqualTo(3);
    assertThat(body.at("/proposals/currentStates/currentPostponed").asInt()).isEqualTo(1);
    assertThat(body.at("/proposals/currentStates/rejected").asInt()).isEqualTo(1);
    assertThat(body.at("/latestApplications/none").asInt()).isEqualTo(1);
    assertThat(body.at("/latestApplications/applied").asInt()).isEqualTo(3);
    assertThat(body.at("/latestApplications/undone").asInt()).isEqualTo(1);
    assertThat(body.at("/outcomes/exact").asInt()).isEqualTo(2);
    assertThat(body.at("/outcomes/corrected").asInt()).isEqualTo(1);
    assertThat(body.at("/outcomes/userResolved").asInt()).isEqualTo(1);
    assertThat(body.at("/outcomes/unclassifiable").asInt()).isZero();
    assertThat(body.at("/outcomes/correctedFields/title").asInt()).isEqualTo(1);
    assertThat(body.at("/outcomes/correctedFields/due").asInt()).isEqualTo(1);
    assertThat(sum(body.path("byAnalysisVersion"), "/proposals/total")).isEqualTo(5);
    assertThat(sum(body.path("byAnalysisVersion"), "/outcomes/exact")).isEqualTo(2);
    assertThat(body.toString())
        .doesNotContain(
            "foreign-owner-private-content",
            exactMemo.toString(),
            exactProposal.toString(),
            "사용자가 고친 제목");

    assertThat(
            db.sql(
                    "select count(*) from pg_indexes "
                        + "where indexname='idx_analysis_applications_owner_proposal_latest'")
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void persistedSchemaAndApplicationContextCorruptionFailClosed() throws Exception {
    UUID danglingBindingMemo = UUID.randomUUID();
    UUID danglingBindingProposal =
        createProposal(danglingBindingMemo, "dangling-binding-outcome", "11.25 OS과제 제출");
    var danglingBindingApplied =
        applyProposal(
            danglingBindingProposal,
            "dangling-binding-outcome-apply",
            defaultApplication(proposal(danglingBindingProposal)));
    assertThat(danglingBindingApplied.getResponse().getStatus()).isEqualTo(200);
    assertThat(
            db.sql(
                    "update analysis_proposals "
                        + "set proposal_json=jsonb_set(proposal_json,"
                        + "'{itemCandidates,0,dueDateCandidateId}',"
                        + "'\"date-missing\"'::jsonb,false) "
                        + "where id=:proposal and owner_id=:owner")
                .param("proposal", danglingBindingProposal)
                .param("owner", OWNER_ID)
                .update())
        .isEqualTo(1);

    UUID mismatchedMemo = UUID.randomUUID();
    UUID mismatchedProposal =
        createProposal(mismatchedMemo, "mismatched-outcome", "11.25 OS assignment submit");
    var mismatchedApplied =
        applyProposal(
            mismatchedProposal,
            "mismatched-outcome-apply",
            defaultApplication(proposal(mismatchedProposal)));
    assertThat(mismatchedApplied.getResponse().getStatus()).isEqualTo(200);
    UUID mismatchedApplicationId =
        UUID.fromString(response(mismatchedApplied).path("applicationId").asText());
    assertThat(
            undoApplication(mismatchedApplicationId, "mismatched-outcome-undo")
                .getResponse()
                .getStatus())
        .isEqualTo(200);

    UUID otherMemo = UUID.randomUUID();
    assertThat(
            createMemo(otherMemo, "mismatched-context-memo", "separate private memo")
                .getResponse()
                .getStatus())
        .isEqualTo(201);
    assertThat(
            db.sql(
                    "update analysis_applications set memo_id=:memo "
                        + "where id=:application and owner_id=:owner")
                .param("memo", otherMemo)
                .param("application", mismatchedApplicationId)
                .param("owner", OWNER_ID)
                .update())
        .isEqualTo(1);

    var result = mvc.perform(get("/api/v1/analysis-review-outcomes/summary")).andReturn();
    JsonNode body = response(result);

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(body.at("/proposals/total").asInt()).isEqualTo(2);
    assertThat(body.at("/proposals/withApplication").asInt()).isEqualTo(2);
    assertThat(body.at("/outcomes/exact").asInt()).isZero();
    assertThat(body.at("/outcomes/corrected").asInt()).isZero();
    assertThat(body.at("/outcomes/userResolved").asInt()).isZero();
    assertThat(body.at("/outcomes/unclassifiable").asInt()).isEqualTo(2);
  }

  @Test
  void rejectsInvalidDaysAndWindowsAboveTheExplicitProposalCap() throws Exception {
    var invalidDays =
        mvc.perform(get("/api/v1/analysis-review-outcomes/summary").param("days", "0")).andReturn();
    assertThat(invalidDays.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(invalidDays).path("code").asText()).isEqualTo("VALIDATION_FAILED");

    var malformedDays =
        mvc.perform(get("/api/v1/analysis-review-outcomes/summary").param("days", "not-an-integer"))
            .andReturn();
    JsonNode malformedDaysBody = response(malformedDays);
    assertThat(malformedDays.getResponse().getStatus()).isEqualTo(422);
    assertThat(malformedDaysBody.path("code").asText()).isEqualTo("VALIDATION_FAILED");
    assertThat(malformedDaysBody.at("/fieldErrors/0/field").asText()).isEqualTo("days");

    UUID memoId = UUID.randomUUID();
    assertThat(createMemo(memoId, "cap-memo", "private-cap-source").getResponse().getStatus())
        .isEqualTo(201);
    seedProposalCap(memoId);

    var aboveCap =
        mvc.perform(get("/api/v1/analysis-review-outcomes/summary").param("days", "1")).andReturn();
    assertThat(aboveCap.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(aboveCap).path("code").asText())
        .isEqualTo("REVIEW_OUTCOME_WINDOW_TOO_LARGE");
  }

  private UUID createProposal(UUID memoId, String keyPrefix, String content) throws Exception {
    assertThat(createMemo(memoId, keyPrefix + "-create", content).getResponse().getStatus())
        .isEqualTo(201);
    var started = startAnalysis(memoId, keyPrefix + "-analyze", 1);
    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    return UUID.fromString(response(started).path("proposalId").asText());
  }

  private JsonNode proposal(UUID proposalId) throws Exception {
    var result = mvc.perform(get("/api/v1/analysis-proposals/{id}", proposalId)).andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    return response(result);
  }

  private Map<String, Object> defaultApplication(JsonNode proposal) {
    Map<String, Object> body = new LinkedHashMap<>();
    String selectedType =
        java.util.stream.StreamSupport.stream(proposal.path("typeCandidates").spliterator(), false)
            .max(Comparator.comparingDouble(candidate -> candidate.path("score").asDouble()))
            .orElseThrow()
            .path("value")
            .asText();
    String title = proposal.at("/suggestedTitle/value").asText().strip();
    body.put("expectedMemoRevision", proposal.path("memoRevision").asInt());
    body.put("selectedType", selectedType);
    body.put("title", title);

    List<Map<String, Object>> tags = new ArrayList<>();
    for (JsonNode candidate : proposal.path("tagCandidates")) {
      Map<String, Object> tag = new LinkedHashMap<>();
      String existing =
          candidate.path("existingTagId").isTextual()
              ? candidate.path("existingTagId").asText()
              : null;
      tag.put("existingTagId", existing);
      tag.put(
          "newCanonicalName",
          existing == null ? candidate.path("canonicalName").asText().strip() : null);
      tags.add(tag);
    }
    body.put("selectedTags", tags);

    long taskCount =
        java.util.stream.StreamSupport.stream(proposal.path("itemCandidates").spliterator(), false)
            .filter(candidate -> "TASK".equals(candidate.path("kind").asText()))
            .count();
    JsonNode dates = proposal.path("dateCandidates");
    JsonNode legacyPreferredDue = taskCount == 1 && dates.size() == 1 ? dates.get(0) : null;
    Map<String, JsonNode> datesById = new LinkedHashMap<>();
    for (JsonNode date : dates) {
      if (date.path("candidateId").isTextual()) {
        datesById.put(date.path("candidateId").asText(), date);
      }
    }
    List<Map<String, Object>> items = new ArrayList<>();
    int index = 0;
    for (JsonNode candidate : proposal.path("itemCandidates")) {
      Map<String, Object> item = new LinkedHashMap<>();
      String kind = candidate.path("kind").asText();
      item.put("kind", kind);
      item.put("title", index++ == 0 ? title : candidate.path("title").asText().strip());
      JsonNode binding = candidate.path("dueDateCandidateId");
      JsonNode preferredDue =
          "2".equals(proposal.path("schemaVersion").asText())
              ? datesById.get(binding.isTextual() ? binding.asText() : null)
              : legacyPreferredDue;
      if ("TASK".equals(kind) && preferredDue != null) {
        Map<String, Object> due = new LinkedHashMap<>();
        due.put("surfaceText", preferredDue.path("surfaceText").asText());
        due.put(
            "value",
            preferredDue.path("value").isNull() ? null : preferredDue.path("value").asText());
        due.put("precision", preferredDue.path("precision").asText());
        due.put("timeZone", "Asia/Seoul");
        due.put("timeSpecified", preferredDue.path("timeSpecified").asBoolean());
        item.put("due", due);
      } else {
        item.put("due", null);
      }
      items.add(item);
    }
    if (items.stream().noneMatch(item -> selectedType.equals(item.get("kind")))) {
      items.getFirst().put("kind", selectedType);
      if (!"TASK".equals(selectedType)) {
        items.getFirst().put("due", null);
      }
    }
    body.put("items", items);
    return body;
  }

  private Map<String, Object> resolvedApplication() {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("kind", "TASK");
    item.put("title", "사용자가 정한 할 일");
    item.put("due", null);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("expectedMemoRevision", 1);
    body.put("selectedType", "TASK");
    body.put("title", "사용자가 정한 할 일");
    body.put("selectedTags", List.of());
    body.put("items", List.of(item));
    return body;
  }

  private void seedForeignProposal(String content) {
    UUID owner = UUID.randomUUID();
    UUID memo = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID proposal = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now().minusSeconds(60));
    db.sql("insert into users(id,created_at,updated_at) values(:id,:now,:now)")
        .param("id", owner)
        .param("now", now)
        .update();
    db.sql(
            "insert into user_settings(user_id,time_zone,cloud_analysis_consent) values(:id,'Asia/Seoul',false)")
        .param("id", owner)
        .update();
    db.sql(
            "insert into memos(id,owner_id,current_revision,status,created_at,updated_at) values(:memo,:owner,1,'ACTIVE',:now,:now)")
        .param("memo", memo)
        .param("owner", owner)
        .param("now", now)
        .update();
    db.sql(
            "insert into memo_revisions(memo_id,revision,content,content_hash,created_at,created_by,owner_id,client_recorded_at,source_time_zone) values(:memo,1,:content,'hash',:now,:owner,:owner,:now,'Asia/Seoul')")
        .param("memo", memo)
        .param("content", content)
        .param("now", now)
        .param("owner", owner)
        .update();
    db.sql(
            "insert into analysis_runs(id,owner_id,memo_id,memo_revision,route,status,schema_version,analyzer_version,ambiguity_reasons,created_at,completed_at,routing_policy_version,prompt_version,local_model_version,embedding_model_version) values(:run,:owner,:memo,1,'LOCAL','POSTPONED','1','foreign-v1','[]',:now,:now,'foreign-policy','none','none','none')")
        .param("run", run)
        .param("owner", owner)
        .param("memo", memo)
        .param("now", now)
        .update();
    db.sql(
            "insert into analysis_proposals(id,owner_id,analysis_run_id,proposal_json,proposal_hash,created_at) values(:proposal,:owner,:run,cast(:json as jsonb),'hash',:now)")
        .param("proposal", proposal)
        .param("owner", owner)
        .param("run", run)
        .param("json", "{\"private\":\"" + content + "\"}")
        .param("now", now)
        .update();
  }

  private void seedProposalCap(UUID memoId) {
    db.sql(
            """
            insert into analysis_runs(
              id, owner_id, memo_id, memo_revision, route, status, schema_version,
              analyzer_version, ambiguity_reasons, created_at, completed_at,
              routing_policy_version, prompt_version, local_model_version,
              embedding_model_version
            )
            select md5('review-outcome-run-' || value)::uuid, :owner, :memo, 1,
                   'LOCAL', 'REVIEW_REQUIRED', '1', 'cap-v1', '[]',
                   current_timestamp - interval '1 minute', current_timestamp - interval '1 minute',
                   'cap-policy', 'none', 'none', 'none'
              from generate_series(1, 1001) value
            """)
        .param("owner", OWNER_ID)
        .param("memo", memoId)
        .update();
    db.sql(
            """
            insert into analysis_proposals(
              id, owner_id, analysis_run_id, proposal_json, proposal_hash, created_at
            )
            select md5('review-outcome-proposal-' || value)::uuid,
                   :owner,
                   md5('review-outcome-run-' || value)::uuid,
                   '{}'::jsonb,
                   'hash',
                   current_timestamp - interval '1 minute'
              from generate_series(1, 1001) value
            """)
        .param("owner", OWNER_ID)
        .update();
  }

  private int sum(JsonNode summaries, String pointer) {
    int total = 0;
    for (JsonNode summary : summaries) {
      total += summary.at(pointer).asInt();
    }
    return total;
  }
}
