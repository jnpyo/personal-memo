import {
  differenceOffsetDateTimesInNanoseconds,
  isValidOffsetDateTime,
} from '../validation/dateTime';
import type { AnalysisPathEvidenceSummary } from './types';

const MAX_RUNS = 1_000;
const MAX_SIGNALS = 3_000;

const FALLBACK_REASON_KEYS = [
  'defaultRecordFallback',
  'unparsedTemporalCue',
  'unrecognizedActionCue',
  'lowTypeMargin',
  'tagUncertainty',
  'dateUncertainty',
  'unresolvedReference',
  'incompleteTask',
  'multiIntent',
  'candidateLimit',
  'localConflict',
] as const;

const CHANGED_FIELD_KEYS = [
  'suggestedTitle',
  'typeCandidates',
  'dateCandidates',
  'tagCandidates',
  'itemCandidates',
  'relationCandidates',
  'ambiguityReasons',
] as const;

export class AnalysisPathEvidenceSummaryContractError extends Error {
  constructor(readonly field: string) {
    super(`Unsupported analysis path evidence summary at ${field}.`);
    this.name = 'AnalysisPathEvidenceSummaryContractError';
  }
}

function fail(field: string): never {
  throw new AnalysisPathEvidenceSummaryContractError(field);
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

function count(value: unknown, field: string, maximum = MAX_RUNS): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0 || (value as number) > maximum) {
    fail(field);
  }
  return value as number;
}

function requireSum(field: string, expected: number, values: readonly number[]): void {
  if (values.reduce((sum, value) => sum + value, 0) !== expected) fail(field);
}

function countRecord<const Keys extends readonly string[]>(
  value: unknown,
  field: string,
  keys: Keys,
): { [Key in Keys[number]]: number } {
  const record = closedRecord(value, field, keys);
  return Object.fromEntries(
    keys.map((key) => [key, count(record[key], `${field}.${key}`)]),
  ) as { [Key in Keys[number]]: number };
}

export function decodeAnalysisPathEvidenceSummary(value: unknown): AnalysisPathEvidenceSummary {
  const summary = closedRecord(value, 'analysisPathEvidenceSummary', [
    'schemaVersion',
    'aggregationPolicyVersion',
    'cohort',
    'runs',
    'localDecisionEvidence',
    'lifecycle',
    'invocationModes',
    'invocationReasons',
    'dispatchRoutes',
    'localModelContributions',
    'approvedCorrectionSnapshots',
    'fallbackReasons',
    'changedFields',
  ]);
  if (summary.schemaVersion !== '1') fail('schemaVersion');
  if (summary.aggregationPolicyVersion !== 'analysis-path-evidence-summary-v1') {
    fail('aggregationPolicyVersion');
  }

  const cohort = closedRecord(summary.cohort, 'cohort', [
    'basis',
    'days',
    'fromInclusive',
    'toExclusive',
    'maxRuns',
  ]);
  if (cohort.basis !== 'ANALYSIS_RUN_CREATED_AT') fail('cohort.basis');
  const days = count(cohort.days, 'cohort.days', 90);
  if (days < 1) fail('cohort.days');
  if (cohort.maxRuns !== MAX_RUNS) fail('cohort.maxRuns');
  if (
    typeof cohort.fromInclusive !== 'string' ||
    !isValidOffsetDateTime(cohort.fromInclusive) ||
    typeof cohort.toExclusive !== 'string' ||
    !isValidOffsetDateTime(cohort.toExclusive)
  ) {
    fail('cohort.interval');
  }
  let intervalNanoseconds: bigint;
  try {
    intervalNanoseconds = differenceOffsetDateTimesInNanoseconds(
      cohort.fromInclusive,
      cohort.toExclusive,
    );
  } catch {
    fail('cohort.interval');
  }
  if (intervalNanoseconds !== BigInt(days) * 86_400_000_000_000n) {
    fail('cohort.interval');
  }

  const runsRecord = closedRecord(summary.runs, 'runs', [
    'total',
    'withDispatch',
    'withoutDispatch',
  ]);
  const runs = {
    total: count(runsRecord.total, 'runs.total'),
    withDispatch: count(runsRecord.withDispatch, 'runs.withDispatch'),
    withoutDispatch: count(runsRecord.withoutDispatch, 'runs.withoutDispatch'),
  };
  requireSum('runs', runs.total, [runs.withDispatch, runs.withoutDispatch]);

  const localDecisionEvidence = countRecord(
    summary.localDecisionEvidence,
    'localDecisionEvidence',
    ['current', 'legacy'] as const,
  );
  requireSum('localDecisionEvidence', runs.withDispatch, [
    localDecisionEvidence.current,
    localDecisionEvidence.legacy,
  ]);

  const dispatchRoutes = countRecord(summary.dispatchRoutes, 'dispatchRoutes', [
    'localModel',
    'externalMemoTransfer',
    'builtInFake',
    'legacyOrOther',
  ] as const);
  requireSum('dispatchRoutes', runs.withDispatch, [
    dispatchRoutes.localModel,
    dispatchRoutes.externalMemoTransfer,
    dispatchRoutes.builtInFake,
    dispatchRoutes.legacyOrOther,
  ]);

  const lifecycle = countRecord(summary.lifecycle, 'lifecycle', [
    'prepared',
    'running',
    'finalized',
  ] as const);
  requireSum('lifecycle', runs.withDispatch, [
    lifecycle.prepared,
    lifecycle.running,
    lifecycle.finalized,
  ]);

  const invocationModes = countRecord(summary.invocationModes, 'invocationModes', [
    'legacyUnknown',
    'uncertaintyOnly',
    'aiPreferred',
  ] as const);
  requireSum('invocationModes', runs.withDispatch, [
    invocationModes.legacyUnknown,
    invocationModes.uncertaintyOnly,
    invocationModes.aiPreferred,
  ]);

  const invocationReasons = countRecord(summary.invocationReasons, 'invocationReasons', [
    'legacyUnknown',
    'semanticUncertainty',
    'aiPreferredPolicy',
  ] as const);
  requireSum('invocationReasons', runs.withDispatch, [
    invocationReasons.legacyUnknown,
    invocationReasons.semanticUncertainty,
    invocationReasons.aiPreferredPolicy,
  ]);
  if (
    invocationModes.legacyUnknown !== invocationReasons.legacyUnknown ||
    localDecisionEvidence.legacy > invocationModes.legacyUnknown
  ) {
    fail('legacyEvidence');
  }
  if (invocationModes.uncertaintyOnly > invocationReasons.semanticUncertainty) {
    fail('invocationModes.uncertaintyOnly');
  }
  if (invocationReasons.aiPreferredPolicy > invocationModes.aiPreferred) {
    fail('invocationReasons.aiPreferredPolicy');
  }

  const localModelContributions = countRecord(
    summary.localModelContributions,
    'localModelContributions',
    [
      'notRecorded',
      'pending',
      'acceptedChanged',
      'acceptedUnchanged',
      'localFallback',
    ] as const,
  );
  requireSum('localModelContributions', dispatchRoutes.localModel, [
    localModelContributions.notRecorded,
    localModelContributions.pending,
    localModelContributions.acceptedChanged,
    localModelContributions.acceptedUnchanged,
    localModelContributions.localFallback,
  ]);
  if (localModelContributions.notRecorded > localDecisionEvidence.legacy) {
    fail('localModelContributions.notRecorded');
  }
  if (localModelContributions.pending > lifecycle.prepared + lifecycle.running) {
    fail('localModelContributions.pending');
  }
  if (
    localModelContributions.acceptedChanged +
      localModelContributions.acceptedUnchanged +
      localModelContributions.localFallback >
    lifecycle.finalized
  ) {
    fail('localModelContributions.finalized');
  }
  if (
    localModelContributions.pending +
      localModelContributions.acceptedChanged +
      localModelContributions.acceptedUnchanged +
      localModelContributions.localFallback >
    localDecisionEvidence.current
  ) {
    fail('localModelContributions.currentEvidence');
  }

  const approvedRecord = closedRecord(
    summary.approvedCorrectionSnapshots,
    'approvedCorrectionSnapshots',
    ['withSignals', 'totalSignals'],
  );
  const approvedCorrectionSnapshots = {
    withSignals: count(
      approvedRecord.withSignals,
      'approvedCorrectionSnapshots.withSignals',
    ),
    totalSignals: count(
      approvedRecord.totalSignals,
      'approvedCorrectionSnapshots.totalSignals',
      MAX_SIGNALS,
    ),
  };
  if (
    approvedCorrectionSnapshots.withSignals > localDecisionEvidence.current ||
    approvedCorrectionSnapshots.withSignals > invocationModes.aiPreferred ||
    approvedCorrectionSnapshots.totalSignals < approvedCorrectionSnapshots.withSignals ||
    approvedCorrectionSnapshots.totalSignals > approvedCorrectionSnapshots.withSignals * 3
  ) {
    fail('approvedCorrectionSnapshots');
  }

  const fallbackReasons = countRecord(
    summary.fallbackReasons,
    'fallbackReasons',
    FALLBACK_REASON_KEYS,
  );
  if (FALLBACK_REASON_KEYS.some((key) => fallbackReasons[key] > localDecisionEvidence.current)) {
    fail('fallbackReasons');
  }
  const fallbackReasonTotal = FALLBACK_REASON_KEYS.reduce(
    (sum, key) => sum + fallbackReasons[key],
    0,
  );
  if (fallbackReasonTotal < localDecisionEvidence.current - invocationReasons.aiPreferredPolicy) {
    fail('fallbackReasons.total');
  }

  const changedFields = countRecord(summary.changedFields, 'changedFields', CHANGED_FIELD_KEYS);
  const acceptedChanged = localModelContributions.acceptedChanged;
  if (CHANGED_FIELD_KEYS.some((key) => changedFields[key] > acceptedChanged)) {
    fail('changedFields');
  }
  const changedFieldTotal = CHANGED_FIELD_KEYS.reduce(
    (sum, key) => sum + changedFields[key],
    0,
  );
  if (
    (acceptedChanged === 0 && changedFieldTotal !== 0) ||
    (acceptedChanged > 0 &&
      (changedFieldTotal < acceptedChanged || changedFieldTotal > acceptedChanged * 7))
  ) {
    fail('changedFields');
  }

  return {
    schemaVersion: '1',
    aggregationPolicyVersion: 'analysis-path-evidence-summary-v1',
    cohort: {
      basis: 'ANALYSIS_RUN_CREATED_AT',
      days,
      fromInclusive: cohort.fromInclusive,
      toExclusive: cohort.toExclusive,
      maxRuns: MAX_RUNS,
    },
    runs,
    localDecisionEvidence,
    lifecycle,
    invocationModes,
    invocationReasons,
    dispatchRoutes,
    localModelContributions,
    approvedCorrectionSnapshots,
    fallbackReasons,
    changedFields,
  };
}
