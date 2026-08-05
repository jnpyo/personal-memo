package local.personalmemo.analysis.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import local.personalmemo.analysis.domain.LocalAnalyzer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class FakeAnalyzer implements LocalAnalyzer {
  private static final Pattern MONTH_AND_DAY =
      Pattern.compile("(?<!\\d)(\\d{1,2})\\.(\\d{1,2})(?!\\d)");
  private static final String OPERATING_SYSTEMS_TAG_ID =
      "10000000-0000-0000-0000-000000000001";
  private static final String ASSIGNMENT_TAG_ID =
      "10000000-0000-0000-0000-000000000002";

  private final ObjectMapper json;

  public FakeAnalyzer(ObjectMapper json) {
    this.json = json;
  }

  @Override
  public ObjectNode analyze(
      UUID memoId, int revision, String content, Instant baseInstant, String timeZone) {
    ObjectNode proposal = json.createObjectNode();
    proposal
        .put("schemaVersion", "1")
        .put("memoId", memoId.toString())
        .put("memoRevision", revision);

    String title = content.replaceFirst("^\\s*\\d{1,2}\\.\\d{1,2}\\s*", "").trim();
    if (title.isBlank()) {
      title = content.strip();
    }
    proposal.set(
        "suggestedTitle",
        json.createObjectNode()
            .put("value", title)
            .put("confidence", 0.95)
            .put("needsConfirmation", true));
    proposal.set(
        "typeCandidates",
        json.createArrayNode()
            .add(json.createObjectNode().put("value", "TASK").put("score", 0.96)));

    ArrayNode ambiguityReasons = json.createArrayNode();
    proposal.set(
        "dateCandidates", extractDateCandidates(content, baseInstant, timeZone, ambiguityReasons));
    proposal.set("tagCandidates", extractTagCandidates(content));
    proposal.set("itemCandidates", createItemCandidates(title));
    proposal.set("relationCandidates", json.createArrayNode());
    proposal.set("ambiguityReasons", ambiguityReasons);
    proposal.set(
        "providerMetadata",
        json.createObjectNode()
            .put("analyzerVersion", "fake-v1")
            .put("promptVersion", "none")
            .put("localModelVersion", "fake-v1")
            .put("embeddingModelVersion", "none"));
    return proposal;
  }

  private ArrayNode extractDateCandidates(
      String content, Instant baseInstant, String timeZone, ArrayNode ambiguityReasons) {
    ArrayNode candidates = json.createArrayNode();
    var matcher = MONTH_AND_DAY.matcher(content);
    if (!matcher.find()) {
      return candidates;
    }

    int month = Integer.parseInt(matcher.group(1));
    int day = Integer.parseInt(matcher.group(2));
    LocalDate localBaseDate =
        LocalDateTime.ofInstant(baseInstant, ZoneId.of(timeZone)).toLocalDate();
    LocalDate candidateDate = LocalDate.of(localBaseDate.getYear(), month, day);
    if (candidateDate.isBefore(localBaseDate)) {
      candidateDate = candidateDate.plusYears(1);
    }

    ArrayNode dateAmbiguities =
        json.createArrayNode().add("MISSING_YEAR").add("MISSING_TIME");
    candidates.add(
        json.createObjectNode()
            .put("surfaceText", matcher.group())
            .put("value", candidateDate.toString())
            .put("precision", "DATE_ONLY")
            .put("timeSpecified", false)
            .put("confidence", 0.9)
            .set("ambiguityReasons", dateAmbiguities));
    ambiguityReasons.add("MISSING_YEAR").add("MISSING_TIME");
    return candidates;
  }

  private ArrayNode extractTagCandidates(String content) {
    ArrayNode tags = json.createArrayNode();
    if (content.toLowerCase(Locale.ROOT).contains("os")) {
      tags.add(tagCandidate(OPERATING_SYSTEMS_TAG_ID, "운영체제", "OS", 0.98));
    }
    if (content.contains("과제")) {
      tags.add(tagCandidate(ASSIGNMENT_TAG_ID, "과제", null, 0.96));
    }
    return tags;
  }

  private ArrayNode createItemCandidates(String title) {
    ObjectNode item =
        json.createObjectNode()
            .put("candidateId", "task-1")
            .put("kind", "TASK")
            .put("title", title)
            .putNull("sourceSpan")
            .put("action", "제출")
            .put("object", title.replace("제출", "").trim())
            .put("confidence", 0.95);
    return json.createArrayNode().add(item);
  }

  private ObjectNode tagCandidate(
      String id, String canonicalName, String matchedAlias, double score) {
    ObjectNode candidate =
        json.createObjectNode()
            .put("existingTagId", id)
            .put("canonicalName", canonicalName)
            .put("score", score)
            .put("isNewProposal", false);
    if (matchedAlias == null) {
      candidate.putNull("matchedAlias");
    } else {
      candidate.put("matchedAlias", matchedAlias);
    }
    return candidate;
  }
}
