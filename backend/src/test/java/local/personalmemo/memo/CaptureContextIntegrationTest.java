package local.personalmemo.memo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

@PostgresIntegration
class CaptureContextIntegrationTest extends PostgresIntegrationTestSupport {

  @Test
  void createStoresTheClientCaptureContextOnRevisionOne() throws Exception {
    UUID memoId = UUID.randomUUID();
    OffsetDateTime clientCreatedAt = OffsetDateTime.parse("2026-07-15T08:45:12-04:00");

    var created =
        createWithContext(
            memoId,
            "capture-context-create",
            "original memo",
            clientCreatedAt,
            "America/New_York");

    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    CaptureContext stored = captureContext(memoId, 1);
    assertThat(stored.clientRecordedAt()).isEqualTo(clientCreatedAt.toInstant());
    assertThat(stored.sourceTimeZone()).isEqualTo("America/New_York");
  }

  @Test
  void updateStoresPairedContextAndIncludesItInTheIdempotencyHash() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "capture-context-update-create", "revision one");
    OffsetDateTime clientUpdatedAt = OffsetDateTime.parse("2026-11-01T01:30:00-04:00");

    Map<String, Object> body =
        Map.of(
            "expectedRevision", 1,
            "content", "revision two",
            "clientUpdatedAt", clientUpdatedAt,
            "timeZone", "America/New_York");
    var first = updateWithBody(memoId, "capture-context-update", body);
    var replay = updateWithBody(memoId, "capture-context-update", body);
    var mismatch =
        updateWithBody(
            memoId,
            "capture-context-update",
            Map.of(
                "expectedRevision", 1,
                "content", "revision two",
                "clientUpdatedAt", clientUpdatedAt.plusMinutes(1),
                "timeZone", "America/New_York"));

    assertThat(first.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(replay)).isEqualTo(response(first));
    assertThat(mismatch.getResponse().getStatus()).isEqualTo(409);
    assertThat(response(mismatch).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    CaptureContext stored = captureContext(memoId, 2);
    assertThat(stored.clientRecordedAt()).isEqualTo(clientUpdatedAt.toInstant());
    assertThat(stored.sourceTimeZone()).isEqualTo("America/New_York");
  }

  @Test
  void updateWithoutClientContextUsesServerTimeAndInheritsThePreviousZone() throws Exception {
    UUID memoId = UUID.randomUUID();
    createWithContext(
        memoId,
        "capture-context-legacy-create",
        "revision one",
        OffsetDateTime.parse("2024-02-01T10:00:00+01:00"),
        "Europe/Paris");

    var updated = updateMemo(memoId, "capture-context-legacy-update", 1, "revision two");

    assertThat(updated.getResponse().getStatus()).isEqualTo(200);
    CaptureContext stored = captureContext(memoId, 2);
    assertThat(stored.clientRecordedAt()).isEqualTo(stored.revisionCreatedAt());
    assertThat(stored.sourceTimeZone()).isEqualTo("Europe/Paris");
  }

  @Test
  void updateRejectsPartialOrInvalidCaptureContext() throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, "capture-context-validation-create", "revision one");

    var missingZone =
        updateWithBody(
            memoId,
            "capture-context-missing-zone",
            Map.of(
                "expectedRevision", 1,
                "content", "revision two",
                "clientUpdatedAt", OffsetDateTime.parse("2026-08-05T11:05:00+09:00")));
    var missingTimestamp =
        updateWithBody(
            memoId,
            "capture-context-missing-timestamp",
            Map.of(
                "expectedRevision", 1,
                "content", "revision two",
                "timeZone", "Asia/Seoul"));
    var invalidZone =
        updateWithBody(
            memoId,
            "capture-context-invalid-zone",
            Map.of(
                "expectedRevision", 1,
                "content", "revision two",
                "clientUpdatedAt", OffsetDateTime.parse("2026-08-05T11:05:00+09:00"),
                "timeZone", "Mars/Olympus_Mons"));

    assertThat(missingZone.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(missingZone).path("code").asText()).isEqualTo("INVALID_CAPTURE_CONTEXT");
    assertThat(missingTimestamp.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(missingTimestamp).path("code").asText())
        .isEqualTo("INVALID_CAPTURE_CONTEXT");
    assertThat(invalidZone.getResponse().getStatus()).isEqualTo(422);
    assertThat(response(invalidZone).path("code").asText()).isEqualTo("INVALID_TIME_ZONE");
    assertThat(
            db.sql("select count(*) from memo_revisions where memo_id=:memoId")
                .param("memoId", memoId)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  void delayedAnalysisUsesTheRevisionCaptureInstantAndZone() throws Exception {
    UUID memoId = UUID.randomUUID();
    OffsetDateTime capturedAt = OffsetDateTime.parse("2019-12-31T16:30:00-08:00");
    createWithContext(
        memoId,
        "capture-context-delayed-create",
        "12.31 submit report",
        capturedAt,
        "America/Los_Angeles");
    db.sql("update user_settings set time_zone='Pacific/Kiritimati' where user_id=:ownerId")
        .param("ownerId", OWNER_ID)
        .update();

    var started = startAnalysis(memoId, "capture-context-delayed-analysis", 1);
    UUID proposalId = UUID.fromString(response(started).path("proposalId").asText());
    var proposal = mvc.perform(get("/api/v1/analysis-proposals/{id}", proposalId)).andReturn();

    assertThat(started.getResponse().getStatus()).isEqualTo(200);
    assertThat(proposal.getResponse().getStatus()).isEqualTo(200);
    assertThat(response(proposal).at("/dateCandidates/0/value").asText())
        .isEqualTo("2019-12-31");
    Instant runCreatedAt =
        db.sql("select created_at from analysis_runs where memo_id=:memoId")
            .param("memoId", memoId)
            .query((resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant())
            .single();
    assertThat(runCreatedAt).isAfter(capturedAt.toInstant());
  }

  private MvcResult createWithContext(
      UUID memoId,
      String key,
      String content,
      OffsetDateTime clientCreatedAt,
      String timeZone)
      throws Exception {
    return mvc.perform(
            post("/api/v1/memos")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of(
                            "id", memoId,
                            "content", content,
                            "clientCreatedAt", clientCreatedAt,
                            "timeZone", timeZone))))
        .andReturn();
  }

  private MvcResult updateWithBody(UUID memoId, String key, Map<String, Object> body)
      throws Exception {
    return mvc.perform(
            patch("/api/v1/memos/{id}", memoId)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(json.writeValueAsBytes(body)))
        .andReturn();
  }

  private CaptureContext captureContext(UUID memoId, int revision) {
    return db.sql(
            """
            select client_recorded_at, source_time_zone, created_at
              from memo_revisions
             where memo_id=:memoId
               and owner_id=:ownerId
               and revision=:revision
            """)
        .param("memoId", memoId)
        .param("ownerId", OWNER_ID)
        .param("revision", revision)
        .query(
            (resultSet, rowNumber) ->
                new CaptureContext(
                    resultSet.getTimestamp("client_recorded_at").toInstant(),
                    resultSet.getString("source_time_zone"),
                    resultSet.getTimestamp("created_at").toInstant()))
        .single();
  }

  private record CaptureContext(
      Instant clientRecordedAt, String sourceTimeZone, Instant revisionCreatedAt) {}
}
