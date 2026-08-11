package local.personalmemo.analysis.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bounded, owner-free tag vocabulary context for one optional gateway request. */
public record TagRetrievalContext(String version, List<Candidate> candidates) {
  public static final String CURRENT_VERSION = "tag-alias-exact-k8-v1";
  public static final int MAX_CANDIDATES = 8;
  public static final int MAX_SOURCE_CANDIDATE_INDEX = 9;
  private static final int MAX_NAME_LENGTH = 100;

  public TagRetrievalContext {
    if (!CURRENT_VERSION.equals(version)) {
      throw new IllegalArgumentException("Unsupported tag retrieval context version.");
    }
    candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
    if (candidates.size() > MAX_CANDIDATES) {
      throw new IllegalArgumentException("Tag retrieval context cannot exceed eight candidates.");
    }
    Set<UUID> uniqueIds = new HashSet<>();
    for (int index = 0; index < candidates.size(); index++) {
      Candidate candidate = Objects.requireNonNull(candidates.get(index), "candidate");
      if (candidate.rank() != index + 1) {
        throw new IllegalArgumentException("Tag retrieval context ranks must be sequential.");
      }
      if (!uniqueIds.add(candidate.existingTagId())) {
        throw new IllegalArgumentException("Tag retrieval context IDs must be unique.");
      }
    }
  }

  public int candidateCount() {
    return candidates.size();
  }

  @Override
  public String toString() {
    return "TagRetrievalContext[version="
        + version
        + ", candidateCount="
        + candidates.size()
        + ", candidates=redacted]";
  }

  public enum MatchKind {
    CANONICAL,
    ALIAS
  }

  public record Candidate(
      int rank,
      UUID existingTagId,
      String canonicalName,
      String matchedAlias,
      MatchKind matchKind,
      int sourceCandidateIndex) {
    public Candidate {
      if (rank < 1 || rank > MAX_CANDIDATES) {
        throw new IllegalArgumentException("Tag retrieval context rank is out of range.");
      }
      Objects.requireNonNull(existingTagId, "existingTagId");
      requireName(canonicalName, "canonicalName");
      matchKind = Objects.requireNonNull(matchKind, "matchKind");
      if (matchKind == MatchKind.ALIAS) {
        requireName(matchedAlias, "matchedAlias");
      } else if (matchedAlias != null) {
        throw new IllegalArgumentException("A canonical match cannot carry a matched alias.");
      }
      if (sourceCandidateIndex < 0 || sourceCandidateIndex > MAX_SOURCE_CANDIDATE_INDEX) {
        throw new IllegalArgumentException("Tag retrieval source candidate index is out of range.");
      }
    }

    @Override
    public String toString() {
      return "Candidate[rank="
          + rank
          + ", matchKind="
          + matchKind
          + ", sourceCandidateIndex="
          + sourceCandidateIndex
          + ", tag=redacted]";
    }
  }

  private static void requireName(String value, String field) {
    if (value == null
        || value.isBlank()
        || value.codePointCount(0, value.length()) > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException(field + " must contain 1 to 100 characters.");
    }
  }
}
