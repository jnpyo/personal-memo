package local.personalmemo.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import local.personalmemo.analysis.application.OwnerTagContextRetriever;
import local.personalmemo.analysis.domain.TagRetrievalContext;
import local.personalmemo.analysis.domain.TagRetrievalContext.MatchKind;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@PostgresIntegration
class OwnerTagContextRetrieverIntegrationTest extends PostgresIntegrationTestSupport {
  private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-08-05T02:00:00Z"));

  @Autowired private OwnerTagContextRetriever retriever;

  @Test
  void exactAliasLookupNeverCrossesTheAuthenticatedOwnerBoundary() {
    UUID ownerTagId = UUID.fromString("30000000-0000-0000-0000-000000000001");
    UUID otherOwnerId = UUID.fromString("30000000-0000-0000-0000-000000000002");
    UUID otherTagId = UUID.fromString("30000000-0000-0000-0000-000000000003");
    insertUser(otherOwnerId);
    insertTag(ownerTagId, OWNER_ID, "내 태그", "내 태그");
    insertAlias(
        UUID.fromString("31000000-0000-0000-0000-000000000001"),
        OWNER_ID,
        ownerTagId,
        "공통 별칭",
        "공통 별칭");
    insertTag(otherTagId, otherOwnerId, "다른 사용자 비밀", "다른 사용자 비밀");
    insertAlias(
        UUID.fromString("31000000-0000-0000-0000-000000000002"),
        otherOwnerId,
        otherTagId,
        "공통 별칭",
        "공통 별칭");
    ObjectNode proposal = proposal(candidate("공통 별칭", null, 0.91));

    TagRetrievalContext context = retriever.resolve(OWNER_ID, proposal);

    ObjectNode resolved = candidateAt(proposal, 0);
    assertThat(resolved.path("existingTagId").asText()).isEqualTo(ownerTagId.toString());
    assertThat(resolved.path("canonicalName").asText()).isEqualTo("내 태그");
    assertThat(resolved.path("matchedAlias").asText()).isEqualTo("공통 별칭");
    assertThat(resolved.path("isNewProposal").asBoolean()).isFalse();
    assertThat(context.candidates())
        .extracting(TagRetrievalContext.Candidate::existingTagId)
        .containsExactly(ownerTagId);
    assertThat(context.toString())
        .doesNotContain(otherTagId.toString())
        .doesNotContain("다른 사용자 비밀");
  }

  @Test
  void canonicalAliasCrossTableCollisionRemainsUnresolvedAndKeepsBothOptions() {
    UUID canonicalOsTagId = UUID.fromString("32000000-0000-0000-0000-000000000001");
    insertTag(canonicalOsTagId, OWNER_ID, "OS", "os");
    ObjectNode proposal = proposal(candidate("ＯＳ", null, 0.88));

    TagRetrievalContext context = retriever.resolve(OWNER_ID, proposal);

    ObjectNode unresolved = candidateAt(proposal, 0);
    assertThat(unresolved.path("existingTagId").isNull()).isTrue();
    assertThat(unresolved.path("canonicalName").asText()).isEqualTo("ＯＳ");
    assertThat(unresolved.path("isNewProposal").asBoolean()).isTrue();
    assertThat(context.candidates())
        .extracting(TagRetrievalContext.Candidate::existingTagId)
        .containsExactly(OPERATING_SYSTEMS_TAG_ID, canonicalOsTagId);
    assertThat(context.candidates())
        .extracting(TagRetrievalContext.Candidate::matchKind)
        .containsExactly(MatchKind.ALIAS, MatchKind.CANONICAL);
  }

  @Test
  void explicitMatchedAliasDeterministicallyWinsWhenOneTagMatchesMultipleAliases() {
    insertAlias(
        UUID.fromString("33000000-0000-0000-0000-000000000001"),
        OWNER_ID,
        OPERATING_SYSTEMS_TAG_ID,
        "Operating Systems",
        "operating systems");

    List<String> resolvedAliases = new ArrayList<>();
    List<String> contextAliases = new ArrayList<>();
    for (int attempt = 0; attempt < 5; attempt++) {
      ObjectNode proposal = proposal(candidate("Operating Systems", "ＯＳ", 0.93));

      TagRetrievalContext context = retriever.resolve(OWNER_ID, proposal);

      resolvedAliases.add(candidateAt(proposal, 0).path("matchedAlias").asText());
      contextAliases.add(context.candidates().getFirst().matchedAlias());
    }

    assertThat(resolvedAliases).containsOnly("OS");
    assertThat(contextAliases).containsOnly("OS");
  }

  @Test
  void stableTopEightIsRankedAfterCompleteCollisionAwareResolution() {
    List<UUID> preferredTagIds = new ArrayList<>();
    List<CandidateInput> inputs = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      UUID tagId = UUID.fromString("34000000-0000-0000-0000-00000000000" + (index + 1));
      String name = "우선 태그 " + (index + 1);
      insertTag(tagId, OWNER_ID, name, name);
      preferredTagIds.add(tagId);
      inputs.add(candidate(name, null, 0.99 - (index * 0.05)));
    }
    UUID collisionCanonicalId = UUID.fromString("34000000-0000-0000-0000-000000000009");
    UUID collisionAliasId = UUID.fromString("34000000-0000-0000-0000-000000000010");
    insertTag(collisionCanonicalId, OWNER_ID, "충돌", "충돌");
    insertTag(collisionAliasId, OWNER_ID, "별도 정식명", "별도 정식명");
    insertAlias(
        UUID.fromString("35000000-0000-0000-0000-000000000001"),
        OWNER_ID,
        collisionAliasId,
        "충돌",
        "충돌");
    inputs.add(candidate("충돌", null, 0.10));
    ObjectNode firstProposal = proposal(inputs.toArray(CandidateInput[]::new));
    ObjectNode secondProposal = proposal(inputs.toArray(CandidateInput[]::new));

    TagRetrievalContext first = retriever.resolve(OWNER_ID, firstProposal);
    TagRetrievalContext second = retriever.resolve(OWNER_ID, secondProposal);

    assertThat(first).isEqualTo(second);
    assertThat(first.candidates()).hasSize(TagRetrievalContext.MAX_CANDIDATES);
    assertThat(first.candidates())
        .extracting(TagRetrievalContext.Candidate::rank)
        .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    assertThat(first.candidates())
        .extracting(TagRetrievalContext.Candidate::existingTagId)
        .containsExactlyElementsOf(preferredTagIds);
    assertThat(candidateAt(firstProposal, 8).path("existingTagId").isNull()).isTrue();
    assertThat(candidateAt(firstProposal, 8).path("isNewProposal").asBoolean()).isTrue();
    assertThat(first.candidates())
        .extracting(TagRetrievalContext.Candidate::existingTagId)
        .doesNotContain(collisionCanonicalId, collisionAliasId);
  }

  private ObjectNode proposal(CandidateInput... inputs) {
    ObjectNode proposal = json.createObjectNode();
    ArrayNode candidates = proposal.putArray("tagCandidates");
    for (CandidateInput input : inputs) {
      ObjectNode candidate = candidates.addObject();
      candidate.putNull("existingTagId");
      candidate.put("canonicalName", input.canonicalName());
      if (input.matchedAlias() == null) {
        candidate.putNull("matchedAlias");
      } else {
        candidate.put("matchedAlias", input.matchedAlias());
      }
      candidate.put("score", input.score());
      candidate.put("isNewProposal", true);
    }
    return proposal;
  }

  private ObjectNode candidateAt(ObjectNode proposal, int index) {
    return (ObjectNode) proposal.path("tagCandidates").get(index);
  }

  private CandidateInput candidate(String canonicalName, String matchedAlias, double score) {
    return new CandidateInput(canonicalName, matchedAlias, score);
  }

  private void insertUser(UUID ownerId) {
    db.sql("insert into users(id,created_at,updated_at) values(:id,:now,:now)")
        .param("id", ownerId)
        .param("now", NOW)
        .update();
  }

  private void insertTag(UUID tagId, UUID ownerId, String canonicalName, String normalizedName) {
    db.sql(
            "insert into tags(id,owner_id,canonical_name,normalized_name,state,created_at,updated_at) "
                + "values(:id,:ownerId,:canonicalName,:normalizedName,'ACTIVE',:now,:now)")
        .param("id", tagId)
        .param("ownerId", ownerId)
        .param("canonicalName", canonicalName)
        .param("normalizedName", normalizedName)
        .param("now", NOW)
        .update();
  }

  private void insertAlias(
      UUID aliasId, UUID ownerId, UUID tagId, String alias, String normalizedAlias) {
    db.sql(
            "insert into tag_aliases(id,owner_id,tag_id,alias,normalized_alias,source,created_at) "
                + "values(:id,:ownerId,:tagId,:alias,:normalizedAlias,'USER',:now)")
        .param("id", aliasId)
        .param("ownerId", ownerId)
        .param("tagId", tagId)
        .param("alias", alias)
        .param("normalizedAlias", normalizedAlias)
        .param("now", NOW)
        .update();
  }

  private record CandidateInput(String canonicalName, String matchedAlias, double score) {}
}
