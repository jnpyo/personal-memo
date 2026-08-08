import { isValidOffsetDateTime } from '../validation/dateTime';
import type {
  AnalysisReviewOutcomeSummary,
  ReviewOutcomeAnalysisVersion,
  ReviewOutcomeCounters,
  ReviewOutcomeCurrentStates,
  ReviewOutcomeLatestApplications,
  ReviewOutcomeProposalCounters,
} from './types';

const CURRENT_STATE_KEYS = [
  'queued',
  'running',
  'reviewRequired',
  'currentPostponed',
  'failed',
  'stale',
  'applied',
  'rejected',
  'other',
] as const;
const CORRECTED_FIELD_KEYS = ['type', 'title', 'tags', 'items', 'due'] as const;
const ROUTES = new Set<ReviewOutcomeAnalysisVersion['route']>([
  'MOCK',
  'LOCAL',
  'CLOUD',
  'HYBRID',
]);

export class ReviewOutcomeContractError extends Error {
  constructor(readonly field: string) {
    super(`Unsupported review outcome summary at ${field}.`);
    this.name = 'ReviewOutcomeContractError';
  }
}

function fail(field: string): never {
  throw new ReviewOutcomeContractError(field);
}

function closedRecord(
  value: unknown,
  field: string,
  allowedKeys: readonly string[],
): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) fail(field);
  const result = value as Record<string, unknown>;
  const allowed = new Set(allowedKeys);
  const unexpected = Object.keys(result).find((key) => !allowed.has(key));
  return unexpected ? fail(`${field}.${unexpected}`) : result;
}

function count(value: unknown, field: string): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0 || (value as number) > 1_000) {
    fail(field);
  }
  return value as number;
}

function boundedText(value: unknown, field: string): string {
  if (typeof value !== 'string' || !value.trim() || [...value].length > 64) fail(field);
  return value;
}

function currentStates(value: unknown, field: string): ReviewOutcomeCurrentStates {
  const state = closedRecord(value, field, CURRENT_STATE_KEYS);
  return {
    queued: count(state.queued, `${field}.queued`),
    running: count(state.running, `${field}.running`),
    reviewRequired: count(state.reviewRequired, `${field}.reviewRequired`),
    currentPostponed: count(state.currentPostponed, `${field}.currentPostponed`),
    failed: count(state.failed, `${field}.failed`),
    stale: count(state.stale, `${field}.stale`),
    applied: count(state.applied, `${field}.applied`),
    rejected: count(state.rejected, `${field}.rejected`),
    other: count(state.other, `${field}.other`),
  };
}

function proposalCounters(value: unknown, field: string): ReviewOutcomeProposalCounters {
  const counters = closedRecord(value, field, ['total', 'withApplication', 'currentStates']);
  const total = count(counters.total, `${field}.total`);
  const withApplication = count(counters.withApplication, `${field}.withApplication`);
  const states = currentStates(counters.currentStates, `${field}.currentStates`);
  if (withApplication > total) fail(`${field}.withApplication`);
  if (CURRENT_STATE_KEYS.reduce((sum, key) => sum + states[key], 0) !== total) {
    fail(`${field}.currentStates`);
  }
  return { total, withApplication, currentStates: states };
}

function latestApplications(
  value: unknown,
  field: string,
  expectedTotal: number,
): ReviewOutcomeLatestApplications {
  const counters = closedRecord(value, field, ['none', 'applied', 'undone']);
  const result = {
    none: count(counters.none, `${field}.none`),
    applied: count(counters.applied, `${field}.applied`),
    undone: count(counters.undone, `${field}.undone`),
  };
  if (result.none + result.applied + result.undone !== expectedTotal) fail(field);
  return result;
}

function outcomeCounters(
  value: unknown,
  field: string,
  expectedWithApplication: number,
): ReviewOutcomeCounters {
  const counters = closedRecord(value, field, [
    'exact',
    'corrected',
    'userResolved',
    'unclassifiable',
    'correctedFields',
  ]);
  const correctedFieldsRecord = closedRecord(
    counters.correctedFields,
    `${field}.correctedFields`,
    CORRECTED_FIELD_KEYS,
  );
  const correctedFields = {
    type: count(correctedFieldsRecord.type, `${field}.correctedFields.type`),
    title: count(correctedFieldsRecord.title, `${field}.correctedFields.title`),
    tags: count(correctedFieldsRecord.tags, `${field}.correctedFields.tags`),
    items: count(correctedFieldsRecord.items, `${field}.correctedFields.items`),
    due: count(correctedFieldsRecord.due, `${field}.correctedFields.due`),
  };
  const result = {
    exact: count(counters.exact, `${field}.exact`),
    corrected: count(counters.corrected, `${field}.corrected`),
    userResolved: count(counters.userResolved, `${field}.userResolved`),
    unclassifiable: count(counters.unclassifiable, `${field}.unclassifiable`),
    correctedFields,
  };
  if (
    result.exact + result.corrected + result.userResolved + result.unclassifiable !==
    expectedWithApplication
  ) {
    fail(field);
  }
  if (CORRECTED_FIELD_KEYS.some((key) => correctedFields[key] > result.corrected)) {
    fail(`${field}.correctedFields`);
  }
  return result;
}

function versionSummary(value: unknown, index: number): ReviewOutcomeAnalysisVersion {
  const field = `byAnalysisVersion[${index}]`;
  const version = closedRecord(value, field, [
    'route',
    'analyzerVersion',
    'promptVersion',
    'localModelVersion',
    'embeddingModelVersion',
    'routingPolicyVersion',
    'proposals',
    'latestApplications',
    'outcomes',
  ]);
  if (typeof version.route !== 'string' || !ROUTES.has(version.route as ReviewOutcomeAnalysisVersion['route'])) {
    fail(`${field}.route`);
  }
  const proposals = proposalCounters(version.proposals, `${field}.proposals`);
  return {
    route: version.route as ReviewOutcomeAnalysisVersion['route'],
    analyzerVersion: boundedText(version.analyzerVersion, `${field}.analyzerVersion`),
    promptVersion: boundedText(version.promptVersion, `${field}.promptVersion`),
    localModelVersion: boundedText(version.localModelVersion, `${field}.localModelVersion`),
    embeddingModelVersion: boundedText(
      version.embeddingModelVersion,
      `${field}.embeddingModelVersion`,
    ),
    routingPolicyVersion: boundedText(
      version.routingPolicyVersion,
      `${field}.routingPolicyVersion`,
    ),
    proposals,
    latestApplications: latestApplications(
      version.latestApplications,
      `${field}.latestApplications`,
      proposals.total,
    ),
    outcomes: outcomeCounters(
      version.outcomes,
      `${field}.outcomes`,
      proposals.withApplication,
    ),
  };
}

function validateVersionSums(
  versions: ReviewOutcomeAnalysisVersion[],
  proposals: ReviewOutcomeProposalCounters,
  latest: ReviewOutcomeLatestApplications,
  outcomes: ReviewOutcomeCounters,
): void {
  const sum = (select: (version: ReviewOutcomeAnalysisVersion) => number) =>
    versions.reduce((total, version) => total + select(version), 0);

  if (
    sum((version) => version.proposals.total) !== proposals.total ||
    sum((version) => version.proposals.withApplication) !== proposals.withApplication ||
    CURRENT_STATE_KEYS.some(
      (key) => sum((version) => version.proposals.currentStates[key]) !== proposals.currentStates[key],
    ) ||
    sum((version) => version.latestApplications.none) !== latest.none ||
    sum((version) => version.latestApplications.applied) !== latest.applied ||
    sum((version) => version.latestApplications.undone) !== latest.undone ||
    sum((version) => version.outcomes.exact) !== outcomes.exact ||
    sum((version) => version.outcomes.corrected) !== outcomes.corrected ||
    sum((version) => version.outcomes.userResolved) !== outcomes.userResolved ||
    sum((version) => version.outcomes.unclassifiable) !== outcomes.unclassifiable ||
    CORRECTED_FIELD_KEYS.some(
      (key) => sum((version) => version.outcomes.correctedFields[key]) !== outcomes.correctedFields[key],
    )
  ) {
    fail('byAnalysisVersion');
  }

  const identities = versions.map((version) =>
    [
      version.route,
      version.analyzerVersion,
      version.promptVersion,
      version.localModelVersion,
      version.embeddingModelVersion,
      version.routingPolicyVersion,
    ].join('\u0000'),
  );
  if (new Set(identities).size !== identities.length) fail('byAnalysisVersion');
}

export function decodeReviewOutcomeSummary(value: unknown): AnalysisReviewOutcomeSummary {
  const summary = closedRecord(value, 'reviewOutcomeSummary', [
    'schemaVersion',
    'comparisonPolicyVersion',
    'cohort',
    'proposals',
    'latestApplications',
    'outcomes',
    'byAnalysisVersion',
  ]);
  if (summary.schemaVersion !== '1') fail('schemaVersion');
  if (summary.comparisonPolicyVersion !== 'review-default-v2') {
    fail('comparisonPolicyVersion');
  }

  const cohort = closedRecord(summary.cohort, 'cohort', [
    'basis',
    'days',
    'fromInclusive',
    'toExclusive',
    'maxProposals',
  ]);
  if (cohort.basis !== 'PROPOSAL_CREATED_AT') fail('cohort.basis');
  const days = count(cohort.days, 'cohort.days');
  if (days < 1 || days > 90) fail('cohort.days');
  if (cohort.maxProposals !== 1_000) fail('cohort.maxProposals');
  if (
    typeof cohort.fromInclusive !== 'string' ||
    !isValidOffsetDateTime(cohort.fromInclusive) ||
    typeof cohort.toExclusive !== 'string' ||
    !isValidOffsetDateTime(cohort.toExclusive) ||
    Date.parse(cohort.toExclusive) - Date.parse(cohort.fromInclusive) !== days * 86_400_000
  ) {
    fail('cohort.interval');
  }

  const proposals = proposalCounters(summary.proposals, 'proposals');
  const latest = latestApplications(
    summary.latestApplications,
    'latestApplications',
    proposals.total,
  );
  const outcomes = outcomeCounters(summary.outcomes, 'outcomes', proposals.withApplication);
  if (!Array.isArray(summary.byAnalysisVersion) || summary.byAnalysisVersion.length > 1_000) {
    fail('byAnalysisVersion');
  }
  const versions = summary.byAnalysisVersion.map(versionSummary);
  validateVersionSums(versions, proposals, latest, outcomes);

  return {
    schemaVersion: '1',
    comparisonPolicyVersion: 'review-default-v2',
    cohort: {
      basis: 'PROPOSAL_CREATED_AT',
      days,
      fromInclusive: cohort.fromInclusive,
      toExclusive: cohort.toExclusive,
      maxProposals: 1_000,
    },
    proposals,
    latestApplications: latest,
    outcomes,
    byAnalysisVersion: versions,
  };
}
