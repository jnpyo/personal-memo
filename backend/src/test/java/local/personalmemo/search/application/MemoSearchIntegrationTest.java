package local.personalmemo.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

@PostgresIntegration
class MemoSearchIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID FOREIGN_OWNER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000002");

  @Test
  void currentRawRevisionAndLatestAppliedCanonicalTitleStayIndependentFromProposals()
      throws Exception {
    UUID memoId = UUID.fromString("81000000-0000-0000-0000-000000000001");
    AppliedMemo applied =
        createAppliedMemo(
            memoId,
            "revision-canonical",
            "처음 원문 키워드",
            "승인 제목 전용",
            List.of(OPERATING_SYSTEMS_TAG_ID),
            null);
    assertThat(updateMemo(memoId, 1, "현재 원문 키워드").getResponse().getStatus()).isEqualTo(200);

    JsonNode current = successfulSearch(request("현재 원문 키워드"));
    assertThat(current.path("items")).hasSize(1);
    assertThat(current.at("/items/0/memoId").asText()).isEqualTo(memoId.toString());
    assertThat(current.at("/items/0/currentRevision").asInt()).isEqualTo(2);
    assertThat(current.at("/items/0/canonicalRevision").asInt()).isEqualTo(1);
    assertThat(current.at("/items/0/title").asText()).isEqualTo("승인 제목 전용");
    assertThat(texts(current.at("/items/0/matchedFields"))).containsExactly("BODY");
    assertThat(current.at("/items/0/preview").asText()).isEqualTo("현재 원문 키워드");

    assertThat(successfulSearch(request("처음 원문 키워드")).path("items")).isEmpty();
    JsonNode canonical = successfulSearch(request("승인 제목"));
    assertThat(canonical.path("items")).hasSize(1);
    assertThat(texts(canonical.at("/items/0/matchedFields"))).containsExactly("TITLE");
    assertThat(canonical.at("/items/0/canonicalRevision").asInt()).isEqualTo(1);

    UUID proposalOnlyMemo = UUID.fromString("81000000-0000-0000-0000-000000000002");
    assertThat(
            createMemo(proposalOnlyMemo, "proposal-only-create", "제안과 무관한 원문")
                .getResponse()
                .getStatus())
        .isEqualTo(201);
    MvcResult started = startAnalysis(proposalOnlyMemo, "proposal-only-start", 1);
    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());
    db.sql(
            "update analysis_proposals set proposal_json="
                + "jsonb_build_object('suggestedTitle',cast(:secret as text),"
                + "'tagCandidates',jsonb_build_array(jsonb_build_object("
                + "'canonicalName',cast(:secret as text)))) where id=:id and owner_id=:owner")
        .param("secret", "제안비밀검색어")
        .param("id", proposalId)
        .param("owner", OWNER_ID)
        .update();

    assertThat(successfulSearch(request("제안비밀검색어")).path("items")).isEmpty();
    assertThat(applied.applicationId()).isNotNull();
  }

  @Test
  void canonicalTagAndAliasAreExactActiveAppliedAndMatchingTagIsFirstWithinEight()
      throws Exception {
    UUID memoId = UUID.fromString("82000000-0000-0000-0000-000000000001");
    String raw = "본문일치 " + "가".repeat(300);
    AppliedMemo memo =
        createAppliedMemo(
            memoId, "tag-alias", raw, "분류와 무관한 승인 제목", List.of(OPERATING_SYSTEMS_TAG_ID), null);
    addTags(memo, 10);

    JsonNode tag = successfulSearch(request("운영체제"));
    assertThat(tag.path("items")).hasSize(1);
    assertThat(texts(tag.at("/items/0/matchedFields"))).containsExactly("TAG");

    JsonNode alias = successfulSearch(request("ＯＳ"));
    assertThat(alias.path("items")).hasSize(1);
    assertThat(texts(alias.at("/items/0/matchedFields"))).containsExactly("ALIAS");
    assertThat(alias.at("/items/0/canonicalTags")).hasSize(8);
    assertThat(alias.at("/items/0/canonicalTags/0/id").asText())
        .isEqualTo(OPERATING_SYSTEMS_TAG_ID.toString());
    assertThat(alias.at("/items/0/canonicalTags/0/name").asText()).isEqualTo("운영체제");

    assertThat(successfulSearch(request("운영")).path("items")).isEmpty();

    JsonNode body = successfulSearch(request("본문일치"));
    String preview = body.at("/items/0/preview").asText();
    assertThat(preview.codePointCount(0, preview.length())).isEqualTo(240);
    assertThat(preview).endsWith("…");

    db.sql("update tags set state='INACTIVE' where id=:id and owner_id=:owner")
        .param("id", OPERATING_SYSTEMS_TAG_ID)
        .param("owner", OWNER_ID)
        .update();
    assertThat(successfulSearch(request("OS")).path("items")).isEmpty();
    assertThat(ids(successfulSearch(request("본문일치")).at("/items/0/canonicalTags")))
        .doesNotContain(OPERATING_SYSTEMS_TAG_ID);

    db.sql("update tags set state='ACTIVE' where id=:id and owner_id=:owner")
        .param("id", OPERATING_SYSTEMS_TAG_ID)
        .param("owner", OWNER_ID)
        .update();
    assertThat(undoApplication(memo.applicationId(), "tag-alias-undo").getResponse().getStatus())
        .isEqualTo(200);
    assertThat(successfulSearch(request("OS")).path("items")).isEmpty();
    assertThat(successfulSearch(request("분류와 무관한 승인 제목")).path("items")).isEmpty();
    JsonNode afterUndo = successfulSearch(request("본문일치"));
    assertThat(afterUndo.at("/items/0/canonicalRevision").isNull()).isTrue();
    assertThat(afterUndo.at("/items/0/title").isNull()).isTrue();
    assertThat(afterUndo.at("/items/0/canonicalTags")).isEmpty();
    assertThat(afterUndo.at("/items/0/taskState").asText()).isEqualTo("NONE");
  }

  @Test
  void supplementaryCanonicalTagKeepsHundredCodePointResponseContract() throws Exception {
    UUID memoId = UUID.fromString("82500000-0000-0000-0000-000000000001");
    UUID tagId = UUID.fromString("82500000-0000-0000-0000-000000000002");
    String canonicalName = "😀".repeat(100);
    String raw = "보충 문자 태그 회귀 본문";
    AppliedMemo memo =
        createAppliedMemo(
            memoId,
            "supplementary-tag",
            raw,
            "보충 문자 태그 승인 제목",
            List.of(OPERATING_SYSTEMS_TAG_ID),
            null);
    Timestamp now = Timestamp.from(Instant.parse("2026-08-11T00:00:00Z"));
    db.sql(
            "insert into tags(id,owner_id,canonical_name,normalized_name,state,created_at,updated_at) "
                + "values(:id,:owner,:name,:name,'ACTIVE',:now,:now)")
        .param("id", tagId)
        .param("owner", OWNER_ID)
        .param("name", canonicalName)
        .param("now", now)
        .update();
    db.sql(
            "insert into item_tags(memo_item_id,owner_id,tag_id,application_id,source,confirmed_at) "
                + "values(:item,:owner,:tag,:application,'USER',:now)")
        .param("item", memo.itemId())
        .param("owner", OWNER_ID)
        .param("tag", tagId)
        .param("application", memo.applicationId())
        .param("now", now)
        .update();

    JsonNode page = successfulSearch(request(canonicalName));

    assertThat(page.path("items")).hasSize(1);
    assertThat(texts(page.at("/items/0/matchedFields"))).containsExactly("TAG");
    assertThat(page.at("/items/0/canonicalTags/0/id").asText()).isEqualTo(tagId.toString());
    String responseName = page.at("/items/0/canonicalTags/0/name").asText();
    assertThat(responseName).isEqualTo(canonicalName).hasSize(200);
    assertThat(responseName.codePointCount(0, responseName.length())).isEqualTo(100);
    assertThat(page.at("/items/0/preview").asText()).isEqualTo(raw);
  }

  @Test
  void lifecycleTaskOverdueDateAndOwnerFiltersUseOnlyCurrentOwnerCanonicalState() throws Exception {
    UUID taskMemoId = UUID.fromString("83000000-0000-0000-0000-000000000001");
    UUID rawMemoId = UUID.fromString("83000000-0000-0000-0000-000000000002");
    Map<String, Object> due =
        Map.of(
            "surfaceText",
            "지난 시각",
            "value",
            "2020-01-01T18:00:00+09:00",
            "precision",
            "EXACT_TIME",
            "timeZone",
            "Asia/Seoul",
            "timeSpecified",
            true);
    AppliedMemo task =
        createAppliedMemo(taskMemoId, "filters-task", "필터공통 작업 원문", "필터 작업 승인", List.of(), due);
    assertThat(createMemo(rawMemoId, "filters-raw", "필터공통 일반 원문").getResponse().getStatus())
        .isEqualTo(201);
    setRevisionCreatedAt(taskMemoId, 1, "2026-08-10T01:00:00Z");
    setRevisionCreatedAt(rawMemoId, 1, "2026-08-09T01:00:00Z");
    seedForeignRawMemo("필터공통 외부 소유자 원문");

    JsonNode defaults = successfulSearch(request("필터공통"));
    assertThat(ids(defaults.path("items"))).containsExactly(taskMemoId, rawMemoId);
    assertThat(defaults.toString()).doesNotContain(FOREIGN_OWNER_ID.toString(), "외부 소유자");

    JsonNode todo = successfulSearch(request("필터공통", "taskState", "TODO"));
    assertThat(ids(todo.path("items"))).containsExactly(taskMemoId);
    assertThat(todo.at("/items/0/overdue").asBoolean()).isTrue();
    JsonNode overdue = successfulSearch(request("필터공통", "overdue", true));
    assertThat(ids(overdue.path("items"))).containsExactly(taskMemoId);
    assertThat(overdue.at("/items/0/taskState").asText()).isEqualTo("TODO");
    assertThat(ids(successfulSearch(request("필터공통", "overdue", false)).path("items")))
        .containsExactly(rawMemoId);
    assertThat(ids(successfulSearch(request("필터공통", "taskState", "NONE")).path("items")))
        .containsExactly(rawMemoId);

    MvcResult contradiction = search(request("필터공통", Map.of("taskState", "DONE", "overdue", true)));
    assertThat(contradiction.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(contradiction).path("code").asText()).isEqualTo("INVALID_SEARCH_FILTERS");

    markTaskDone(task.itemId());
    JsonNode done = successfulSearch(request("필터공통", "taskState", "DONE"));
    assertThat(ids(done.path("items"))).containsExactly(taskMemoId);
    assertThat(done.at("/items/0/overdue").asBoolean()).isFalse();
    assertThat(successfulSearch(request("필터공통", "overdue", true)).path("items")).isEmpty();

    assertThat(trashMemo(rawMemoId, "filters-trash").getResponse().getStatus()).isEqualTo(200);
    assertThat(ids(successfulSearch(request("필터공통")).path("items"))).containsExactly(taskMemoId);
    JsonNode trashed =
        successfulSearch(
            request("필터공통", Map.of("lifecycleStatus", "TRASHED", "taskState", "NONE")));
    assertThat(ids(trashed.path("items"))).containsExactly(rawMemoId);

    JsonNode halfOpen =
        successfulSearch(
            request(
                "필터공통",
                Map.of(
                    "revisedFrom",
                    "2026-08-10T01:00:00Z",
                    "revisedBefore",
                    "2026-08-10T01:00:01Z")));
    assertThat(ids(halfOpen.path("items"))).containsExactly(taskMemoId);
    assertThat(
            successfulSearch(request("필터공통", "revisedBefore", "2026-08-10T01:00:00Z"))
                .path("items"))
        .isEmpty();
  }

  @Test
  void dateRangeRejectsExtendedYearPoisonAndAcceptsExactUtcBounds() throws Exception {
    UUID memoId = UUID.fromString("83500000-0000-0000-0000-000000000001");
    assertThat(
            createMemo(memoId, "date-boundary", "date-boundary-marker").getResponse().getStatus())
        .isEqualTo(201);

    JsonNode bounded =
        successfulSearch(
            request(
                "date-boundary-marker",
                Map.of(
                    "revisedFrom",
                    "0001-01-01T00:00:00Z",
                    "revisedBefore",
                    "9999-12-31T23:59:59.999999Z")));
    assertThat(ids(bounded.path("items"))).containsExactly(memoId);

    assertInvalidDateRange(
        search(request("date-boundary-marker", "revisedFrom", "+300000-01-01T00:00:00Z")));
    assertInvalidDateRange(
        search(request("date-boundary-marker", "revisedBefore", "-300000-01-01T00:00:00Z")));
    assertInvalidDateRange(
        search(request("date-boundary-marker", "revisedBefore", "9999-12-31T23:59:59.999999001Z")));
  }

  @Test
  void cursorIsOpaqueStableKeysetAndRejectsPoisonReplayAndVisibleMutation() throws Exception {
    UUID firstId = UUID.fromString("84000000-0000-0000-0000-000000000001");
    UUID secondId = UUID.fromString("84000000-0000-0000-0000-000000000002");
    UUID thirdId = UUID.fromString("84000000-0000-0000-0000-000000000003");
    for (UUID id : List.of(firstId, secondId, thirdId)) {
      assertThat(createMemo(id, "cursor-create-" + id, "커서공통 비공개 본문").getResponse().getStatus())
          .isEqualTo(201);
    }
    setRevisionCreatedAt(firstId, 1, "2026-08-10T02:00:00Z");
    setRevisionCreatedAt(secondId, 1, "2026-08-10T02:00:00Z");
    setRevisionCreatedAt(thirdId, 1, "2026-08-09T02:00:00Z");

    MvcResult firstResult = search(request("커서공통", "limit", 1));
    assertThat(firstResult.getResponse().getStatus()).isEqualTo(200);
    assertThat(firstResult.getResponse().getHeader("Cache-Control")).contains("no-store");
    JsonNode first = response(firstResult);
    assertThat(ids(first.path("items"))).containsExactly(firstId);
    assertThat(first.path("truncated").asBoolean()).isTrue();
    String cursor = first.path("nextCursor").asText();
    String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
    assertThat(decoded).doesNotContain("커서공통", "비공개 본문", "preview", "title", "revisedAt");

    List<UUID> visited = new ArrayList<>();
    visited.add(firstId);
    String continuation = cursor;
    while (continuation != null) {
      JsonNode page = successfulSearch(request("커서공통", Map.of("limit", 1, "cursor", continuation)));
      visited.addAll(ids(page.path("items")));
      continuation = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
    }
    assertThat(visited).containsExactly(firstId, secondId, thirdId);
    assertThat(new HashSet<>(visited)).hasSize(3);

    assertInvalidCursor(search(request("커서공통", "cursor", "not-a-cursor")));
    assertInvalidCursor(search(request("커서공", Map.of("limit", 1, "cursor", cursor))));
    assertInvalidCursor(
        search(
            request("커서공통", Map.of("limit", 1, "lifecycleStatus", "TRASHED", "cursor", cursor))));

    db.sql("update memos set pinned=true where id=:id and owner_id=:owner")
        .param("id", secondId)
        .param("owner", OWNER_ID)
        .update();
    assertInvalidCursor(search(request("커서공통", Map.of("limit", 1, "cursor", cursor))));
  }

  @Test
  void unicodeNormalizationRejectsUnpairedSurrogatesAndPinsRootCaseBehavior() throws Exception {
    String supplementaryBoundary = "😀".repeat(100);
    UUID supplementaryMemo = UUID.fromString("85000000-0000-0000-0000-000000000001");
    assertThat(
            createMemo(supplementaryMemo, "unicode-supplementary", supplementaryBoundary + " 경계")
                .getResponse()
                .getStatus())
        .isEqualTo(201);
    assertThat(successfulSearch(request(supplementaryBoundary)).path("items")).hasSize(1);

    MvcResult overBound = search(request("😀".repeat(101)));
    assertThat(overBound.getResponse().getStatus()).isEqualTo(422);
    assertInvalidQuery(search(request(" ".repeat(200) + "a")));

    assertInvalidQuery(searchRaw("{\"query\":\"" + "\\" + "uD800\"}"));
    assertInvalidQuery(searchRaw("{\"query\":\"" + "\\" + "uDC00\"}"));
    assertInvalidQuery(searchRaw("{\"query\":\"" + "\\" + "u0000\"}"));
    assertInvalidQuery(searchRaw("{\"query\":\"a" + "\\" + "u0000b\"}"));

    MvcResult unknownField = searchRaw("{\"query\":\"검색\",\"ownerId\":\"ignored\"}");
    assertThat(unknownField.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(unknownField).path("code").asText()).isEqualTo("MALFORMED_JSON");

    UUID rootCaseMemo = UUID.fromString("85000000-0000-0000-0000-000000000002");
    assertThat(
            createMemo(rootCaseMemo, "unicode-root-case", "ASCII ISTANBUL 한국어정규화 İSTANBUL ıSTANBUL")
                .getResponse()
                .getStatus())
        .isEqualTo(201);
    assertThat(ids(successfulSearch(request("istanbul")).path("items")))
        .containsExactly(rootCaseMemo);
    assertThat(ids(successfulSearch(request("한국어정규화")).path("items")))
        .containsExactly(rootCaseMemo);
    assertThat(ids(successfulSearch(request("İSTANBUL")).path("items")))
        .containsExactly(rootCaseMemo);
    assertThat(ids(successfulSearch(request("ıstanbul")).path("items")))
        .containsExactly(rootCaseMemo);
  }

  private AppliedMemo createAppliedMemo(
      UUID memoId, String keyPrefix, String content, String title, List<UUID> tagIds, Object due)
      throws Exception {
    assertThat(createMemo(memoId, keyPrefix + "-create", content).getResponse().getStatus())
        .isEqualTo(201);
    MvcResult started = startAnalysis(memoId, keyPrefix + "-start", 1);
    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("kind", "TASK");
    item.put("title", title);
    item.put("due", due);
    Map<String, Object> selection =
        Map.of(
            "expectedMemoRevision",
            1,
            "selectedType",
            "TASK",
            "title",
            title,
            "selectedTags",
            tagIds.stream().map(tagId -> Map.of("existingTagId", tagId)).toList(),
            "items",
            List.of(item));
    MvcResult applied = applyProposal(proposalId, keyPrefix + "-apply", selection);
    assertThat(applied.getResponse().getStatus()).isEqualTo(200);
    UUID applicationId = UUID.fromString(response(applied).path("applicationId").asText());
    UUID itemId =
        db.sql(
                "select id from memo_items where owner_id=:owner and application_id=:application "
                    + "order by id limit 1")
            .param("owner", OWNER_ID)
            .param("application", applicationId)
            .query(UUID.class)
            .single();
    return new AppliedMemo(memoId, applicationId, itemId);
  }

  private void addTags(AppliedMemo memo, int count) {
    Timestamp now = Timestamp.from(Instant.parse("2026-08-11T00:00:00Z"));
    for (int index = 0; index < count; index++) {
      UUID tagId = UUID.fromString("86000000-0000-0000-0000-" + String.format("%012d", index));
      String name = String.format("보조태그%02d", index);
      db.sql(
              "insert into tags(id,owner_id,canonical_name,normalized_name,state,created_at,updated_at) "
                  + "values(:id,:owner,:name,:name,'ACTIVE',:now,:now)")
          .param("id", tagId)
          .param("owner", OWNER_ID)
          .param("name", name)
          .param("now", now)
          .update();
      db.sql(
              "insert into item_tags(memo_item_id,owner_id,tag_id,application_id,source,confirmed_at) "
                  + "values(:item,:owner,:tag,:application,'USER',:now)")
          .param("item", memo.itemId())
          .param("owner", OWNER_ID)
          .param("tag", tagId)
          .param("application", memo.applicationId())
          .param("now", now)
          .update();
    }
  }

  private void seedForeignRawMemo(String content) {
    Timestamp now = Timestamp.from(Instant.parse("2026-08-10T03:00:00Z"));
    UUID memoId = UUID.fromString("87000000-0000-0000-0000-000000000001");
    db.sql("insert into users(id,created_at,updated_at) values(:id,:now,:now)")
        .param("id", FOREIGN_OWNER_ID)
        .param("now", now)
        .update();
    db.sql(
            "insert into user_settings(user_id,time_zone,cloud_analysis_consent) "
                + "values(:id,'Asia/Seoul',false)")
        .param("id", FOREIGN_OWNER_ID)
        .update();
    db.sql(
            "insert into memos(id,owner_id,current_revision,status,pinned,created_at,updated_at) "
                + "values(:id,:owner,1,'ACTIVE',false,:now,:now)")
        .param("id", memoId)
        .param("owner", FOREIGN_OWNER_ID)
        .param("now", now)
        .update();
    db.sql(
            "insert into memo_revisions(memo_id,owner_id,revision,content,content_hash,created_at,"
                + "created_by,client_recorded_at,source_time_zone) values(:id,:owner,1,:content,"
                + "repeat('f',64),:now,:owner,:now,'Asia/Seoul')")
        .param("id", memoId)
        .param("owner", FOREIGN_OWNER_ID)
        .param("content", content)
        .param("now", now)
        .update();
  }

  private void setRevisionCreatedAt(UUID memoId, int revision, String instant) {
    db.sql(
            "update memo_revisions set created_at=:createdAt "
                + "where memo_id=:memo and owner_id=:owner and revision=:revision")
        .param("createdAt", Timestamp.from(Instant.parse(instant)))
        .param("memo", memoId)
        .param("owner", OWNER_ID)
        .param("revision", revision)
        .update();
  }

  private void markTaskDone(UUID itemId) {
    db.sql(
            "update task_details set status='DONE',completed_at=current_timestamp "
                + "where memo_item_id=:item and owner_id=:owner")
        .param("item", itemId)
        .param("owner", OWNER_ID)
        .update();
  }

  private Map<String, Object> request(String query) {
    return request(query, Map.of());
  }

  private Map<String, Object> request(String query, String field, Object value) {
    return request(query, Map.of(field, value));
  }

  private Map<String, Object> request(String query, Map<String, Object> filters) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("query", query);
    body.putAll(filters);
    return body;
  }

  private MvcResult search(Map<String, Object> body) throws Exception {
    return searchRaw(json.writeValueAsString(body));
  }

  private MvcResult searchRaw(String body) throws Exception {
    return mvc.perform(post("/api/v1/search/memos").contentType("application/json").content(body))
        .andReturn();
  }

  private JsonNode successfulSearch(Map<String, Object> body) throws Exception {
    MvcResult result = search(body);
    assertThat(result.getResponse().getStatus())
        .withFailMessage(
            () -> new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
        .isEqualTo(200);
    assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
    return response(result);
  }

  private List<String> texts(JsonNode values) {
    List<String> result = new ArrayList<>();
    values.forEach(value -> result.add(value.asText()));
    return result;
  }

  private List<UUID> ids(JsonNode values) {
    List<UUID> result = new ArrayList<>();
    values.forEach(
        value -> {
          JsonNode id = value.has("memoId") ? value.path("memoId") : value.path("id");
          result.add(UUID.fromString(id.asText()));
        });
    return result;
  }

  private void assertInvalidCursor(MvcResult result) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(result).path("code").asText()).isEqualTo("INVALID_SEARCH_CURSOR");
  }

  private void assertInvalidQuery(MvcResult result) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(result).path("code").asText()).isEqualTo("INVALID_SEARCH_QUERY");
  }

  private void assertInvalidDateRange(MvcResult result) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(result).path("code").asText()).isEqualTo("INVALID_SEARCH_DATE_RANGE");
  }

  private record AppliedMemo(UUID memoId, UUID applicationId, UUID itemId) {}
}
