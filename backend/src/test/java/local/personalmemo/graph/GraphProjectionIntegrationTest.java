package local.personalmemo.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import local.personalmemo.support.PostgresIntegration;
import local.personalmemo.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@PostgresIntegration
class GraphProjectionIntegrationTest extends PostgresIntegrationTestSupport {

  @Test
  void uniqueNodesShareTagBudgetAndProjectionOrderingIsDeterministic() throws Exception {
    UUID firstMemo = createAppliedMemo("graph-first");
    UUID secondMemo = createAppliedMemo("graph-second");

    JsonNode firstProjection = graph(4);
    JsonNode repeatedProjection = graph(4);

    assertThat(firstProjection.path("nodes").size()).isEqualTo(4);
    assertThat(firstProjection.path("truncated").asBoolean()).isFalse();
    assertThat(firstProjection.path("nodes").toString())
        .contains("memo:" + firstMemo)
        .contains("memo:" + secondMemo)
        .contains("tag:" + OPERATING_SYSTEMS_TAG_ID)
        .contains("tag:" + ASSIGNMENT_TAG_ID);
    assertThat(firstProjection.path("edges").size()).isEqualTo(4);
    assertThat(repeatedProjection.path("nodes")).isEqualTo(firstProjection.path("nodes"));
    assertThat(repeatedProjection.path("edges")).isEqualTo(firstProjection.path("edges"));
    assertThat(repeatedProjection.path("projectionVersion"))
        .isEqualTo(firstProjection.path("projectionVersion"));
  }

  @Test
  void totalNodeLimitUsesOneExtraUniqueCandidateAndEdgesNeverDangle() throws Exception {
    createAppliedMemo("graph-bound-first");
    createAppliedMemo("graph-bound-second");

    JsonNode projection = graph(3);

    assertThat(projection.path("nodes").size()).isEqualTo(3);
    assertThat(projection.path("truncated").asBoolean()).isTrue();
    Set<String> nodeIds =
        java.util.stream.StreamSupport.stream(projection.path("nodes").spliterator(), false)
            .map(node -> node.path("id").asText())
            .collect(Collectors.toSet());
    projection
        .path("edges")
        .forEach(
            edge -> {
              assertThat(nodeIds).contains(edge.path("source").asText());
              assertThat(nodeIds).contains(edge.path("target").asText());
            });
  }

  private UUID createAppliedMemo(String keyPrefix) throws Exception {
    UUID memoId = UUID.randomUUID();
    createMemo(memoId, keyPrefix + "-create", keyPrefix + " 작업");
    UUID proposalId =
        UUID.fromString(
            response(startAnalysis(memoId, keyPrefix + "-start", 1))
                .path("proposalId")
                .asText());
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("kind", "TASK");
    item.put("title", keyPrefix + " 작업");
    item.put("due", null);
    Map<String, Object> selection =
        Map.of(
            "expectedMemoRevision", 1,
            "selectedType", "TASK",
            "title", keyPrefix + " 작업",
            "selectedTags",
                List.of(
                    Map.of("existingTagId", OPERATING_SYSTEMS_TAG_ID),
                    Map.of("existingTagId", ASSIGNMENT_TAG_ID)),
            "items", List.of(item));
    var applied = applyProposal(proposalId, keyPrefix + "-apply", selection);
    assertThat(applied.getResponse().getStatus()).isEqualTo(200);
    return memoId;
  }

  private JsonNode graph(int limit) throws Exception {
    var result =
        mvc.perform(get("/api/v1/graph/home").param("limit", Integer.toString(limit)))
            .andReturn();
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    return response(result);
  }
}
