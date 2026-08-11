package local.personalmemo.analysis.infrastructure;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import local.personalmemo.analysis.domain.TagRetrievalContext;
import local.personalmemo.analysis.domain.TagRetrievalContext.MatchKind;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Strict, deterministic JSON codec for the internal durable retrieval snapshot. */
@Component
public class TagRetrievalContextCodec {
  private static final Set<String> ROOT_FIELDS = Set.of("version", "candidates");
  private static final Set<String> CANDIDATE_FIELDS =
      Set.of(
          "rank",
          "existingTagId",
          "canonicalName",
          "matchedAlias",
          "matchKind",
          "sourceCandidateIndex");

  private final ObjectMapper json;

  public TagRetrievalContextCodec(ObjectMapper json) {
    this.json = json;
  }

  public String serialize(TagRetrievalContext context) {
    ObjectNode root = json.createObjectNode().put("version", context.version());
    ArrayNode candidates = root.putArray("candidates");
    for (TagRetrievalContext.Candidate candidate : context.candidates()) {
      ObjectNode value =
          candidates
              .addObject()
              .put("rank", candidate.rank())
              .put("existingTagId", candidate.existingTagId().toString())
              .put("canonicalName", candidate.canonicalName());
      if (candidate.matchedAlias() == null) {
        value.putNull("matchedAlias");
      } else {
        value.put("matchedAlias", candidate.matchedAlias());
      }
      value
          .put("matchKind", candidate.matchKind().name())
          .put("sourceCandidateIndex", candidate.sourceCandidateIndex());
    }
    return root.toString();
  }

  public TagRetrievalContext deserialize(String value) {
    JsonNode parsed = parse(value);
    if (!(parsed instanceof ObjectNode root) || !hasExactly(root, ROOT_FIELDS)) {
      throw invalid();
    }
    JsonNode version = root.path("version");
    JsonNode candidatesNode = root.path("candidates");
    if (!version.isTextual() || !(candidatesNode instanceof ArrayNode candidates)) {
      throw invalid();
    }
    List<TagRetrievalContext.Candidate> decoded = new ArrayList<>();
    for (JsonNode node : candidates) {
      if (!(node instanceof ObjectNode candidate) || !hasExactly(candidate, CANDIDATE_FIELDS)) {
        throw invalid();
      }
      try {
        JsonNode alias = candidate.path("matchedAlias");
        if (!candidate.path("rank").isIntegralNumber()
            || !candidate.path("rank").canConvertToInt()
            || !candidate.path("existingTagId").isTextual()
            || !candidate.path("canonicalName").isTextual()
            || (!alias.isNull() && !alias.isTextual())
            || !candidate.path("matchKind").isTextual()
            || !candidate.path("sourceCandidateIndex").isIntegralNumber()
            || !candidate.path("sourceCandidateIndex").canConvertToInt()) {
          throw invalid();
        }
        decoded.add(
            new TagRetrievalContext.Candidate(
                candidate.path("rank").intValue(),
                UUID.fromString(candidate.path("existingTagId").asText()),
                candidate.path("canonicalName").asText(),
                alias.isNull() ? null : alias.asText(),
                MatchKind.valueOf(candidate.path("matchKind").asText()),
                candidate.path("sourceCandidateIndex").intValue()));
      } catch (RuntimeException exception) {
        throw invalid();
      }
    }
    try {
      return new TagRetrievalContext(version.asText(), decoded);
    } catch (RuntimeException exception) {
      throw invalid();
    }
  }

  private JsonNode parse(String value) {
    try {
      return json.readTree(value);
    } catch (Exception exception) {
      throw invalid();
    }
  }

  private boolean hasExactly(ObjectNode value, Set<String> expected) {
    return new HashSet<>(value.propertyNames()).equals(expected);
  }

  private IllegalStateException invalid() {
    return new IllegalStateException("The durable tag retrieval context is invalid.");
  }
}
