package local.personalmemo.analysis.domain;

/** Durable classification of whether an accepted model result changed the local proposal. */
public enum ModelContributionStatus {
  NOT_RECORDED,
  PENDING,
  ACCEPTED_CHANGED,
  ACCEPTED_UNCHANGED,
  LOCAL_FALLBACK
}
