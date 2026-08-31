import { describe, expect, it } from 'vitest';
import {
  AnalysisPathEvidenceSummaryContractError,
  decodeAnalysisPathEvidenceSummary,
} from './analysisPathEvidenceSummaryDecoder';

function validSummary() {
  return {
    schemaVersion: '1',
    aggregationPolicyVersion: 'analysis-path-evidence-summary-v1',
    cohort: {
      basis: 'ANALYSIS_RUN_CREATED_AT',
      days: 14,
      fromInclusive: '2026-07-25T00:00:00Z',
      toExclusive: '2026-08-08T00:00:00Z',
      maxRuns: 1_000,
    },
    runs: { total: 8, withDispatch: 6, withoutDispatch: 2 },
    localDecisionEvidence: { current: 5, legacy: 1 },
    dispatchRoutes: {
      localModel: 4,
      externalMemoTransfer: 1,
      builtInFake: 0,
      legacyOrOther: 1,
    },
    lifecycle: { prepared: 1, running: 1, finalized: 4 },
    invocationModes: { legacyUnknown: 1, uncertaintyOnly: 2, aiPreferred: 3 },
    invocationReasons: {
      legacyUnknown: 1,
      semanticUncertainty: 2,
      aiPreferredPolicy: 3,
    },
    localModelContributions: {
      notRecorded: 0,
      pending: 1,
      acceptedChanged: 1,
      acceptedUnchanged: 1,
      localFallback: 1,
    },
    approvedCorrectionSnapshots: { withSignals: 2, totalSignals: 3 },
    fallbackReasons: {
      defaultRecordFallback: 1,
      unparsedTemporalCue: 2,
      unrecognizedActionCue: 1,
      lowTypeMargin: 1,
      tagUncertainty: 1,
      dateUncertainty: 2,
      unresolvedReference: 1,
      incompleteTask: 1,
      multiIntent: 1,
      candidateLimit: 0,
      localConflict: 0,
    },
    changedFields: {
      suggestedTitle: 1,
      typeCandidates: 0,
      dateCandidates: 0,
      tagCandidates: 0,
      itemCandidates: 0,
      relationCandidates: 0,
      ambiguityReasons: 0,
    },
  };
}

function expectRejected(summary: unknown) {
  expect(() => decodeAnalysisPathEvidenceSummary(summary)).toThrow(
    AnalysisPathEvidenceSummaryContractError,
  );
}

describe('analysis path evidence summary decoder', () => {
  it('accepts the closed aggregate-only contract', () => {
    expect(decodeAnalysisPathEvidenceSummary(validSummary())).toEqual(validSummary());
  });

  it('accepts an exact fractional rolling interval and current evidence with legacy invocation', () => {
    const summary = {
      ...validSummary(),
      cohort: {
        ...validSummary().cohort,
        fromInclusive: '2026-07-25T00:00:00.250000001Z',
        toExclusive: '2026-08-08T00:00:00.250000001Z',
      },
      localDecisionEvidence: { current: 5, legacy: 1 },
      dispatchRoutes: {
        localModel: 3,
        externalMemoTransfer: 0,
        builtInFake: 1,
        legacyOrOther: 2,
      },
      invocationModes: { legacyUnknown: 2, uncertaintyOnly: 1, aiPreferred: 3 },
      invocationReasons: {
        legacyUnknown: 2,
        semanticUncertainty: 1,
        aiPreferredPolicy: 3,
      },
      localModelContributions: {
        notRecorded: 0,
        pending: 0,
        acceptedChanged: 1,
        acceptedUnchanged: 1,
        localFallback: 1,
      },
      fallbackReasons: {
        ...validSummary().fallbackReasons,
        dateUncertainty: 5,
      },
    };

    expect(decodeAnalysisPathEvidenceSummary(summary)).toEqual(summary);
  });

  it('accepts an exact interval without losing nanosecond precision', () => {
    const summary = {
      ...validSummary(),
      cohort: {
        ...validSummary().cohort,
        fromInclusive: '2026-07-25T00:00:00.000000001Z',
        toExclusive: '2026-08-08T00:00:00.000000001Z',
      },
    };

    expect(decodeAnalysisPathEvidenceSummary(summary)).toEqual(summary);
  });

  it('accepts approved snapshots outside the local-model route aggregate', () => {
    const summary = {
      ...validSummary(),
      dispatchRoutes: {
        localModel: 1,
        externalMemoTransfer: 3,
        builtInFake: 1,
        legacyOrOther: 1,
      },
      localModelContributions: {
        notRecorded: 0,
        pending: 0,
        acceptedChanged: 1,
        acceptedUnchanged: 0,
        localFallback: 0,
      },
    };

    expect(decodeAnalysisPathEvidenceSummary(summary)).toEqual(summary);
  });

  it.each([
    ['top-level extra field', () => ({ ...validSummary(), memoContent: 'private' })],
    ['nested evidence JSON', () => ({
      ...validSummary(),
      localDecisionEvidence: {
        ...validSummary().localDecisionEvidence,
        evidenceJson: { raw: true },
      },
    })],
    ['provider output', () => ({
      ...validSummary(),
      localModelContributions: {
        ...validSummary().localModelContributions,
        providerOutput: 'must-not-reach-the-browser',
      },
    })],
    ['private identifier', () => ({
      ...validSummary(),
      runs: { ...validSummary().runs, memoId: 'private-id' },
    })],
    ['superseded evidence field', () => ({
      ...validSummary(),
      evidence: validSummary().localDecisionEvidence,
    })],
    ['superseded contribution field', () => ({
      ...validSummary(),
      modelContributions: validSummary().localModelContributions,
    })],
  ])('rejects %s', (_label, makeSummary) => {
    expectRejected(makeSummary());
  });

  it.each([
    ['negative count', () => ({
      ...validSummary(),
      runs: { ...validSummary().runs, total: -1 },
    })],
    ['run-count overflow', () => ({
      ...validSummary(),
      lifecycle: { ...validSummary().lifecycle, finalized: 1_001 },
    })],
    ['signal-count overflow', () => ({
      ...validSummary(),
      approvedCorrectionSnapshots: { withSignals: 1_000, totalSignals: 3_001 },
    })],
    ['fractional count', () => ({
      ...validSummary(),
      fallbackReasons: { ...validSummary().fallbackReasons, multiIntent: 0.5 },
    })],
  ])('rejects %s', (_label, makeSummary) => {
    expectRejected(makeSummary());
  });

  it.each([
    ['malformed start', () => ({
      ...validSummary(),
      cohort: { ...validSummary().cohort, fromInclusive: 'not-an-instant' },
    })],
    ['empty interval', () => ({
      ...validSummary(),
      cohort: {
        ...validSummary().cohort,
        fromInclusive: validSummary().cohort.toExclusive,
      },
    })],
    ['reversed interval', () => ({
      ...validSummary(),
      cohort: {
        ...validSummary().cohort,
        fromInclusive: '2026-08-09T00:00:00Z',
      },
    })],
    ['duration beyond the requested rolling window', () => ({
      ...validSummary(),
      cohort: {
        ...validSummary().cohort,
        toExclusive: '2026-08-08T00:00:00.000000001Z',
      },
    })],
    ['unsupported day bound', () => ({
      ...validSummary(),
      cohort: { ...validSummary().cohort, days: 91 },
    })],
  ])('rejects %s', (_label, makeSummary) => {
    expectRejected(makeSummary());
  });

  it.each([
    ['run partition', () => ({
      ...validSummary(),
      runs: { ...validSummary().runs, withoutDispatch: 1 },
    })],
    ['local-decision evidence partition', () => ({
      ...validSummary(),
      localDecisionEvidence: { ...validSummary().localDecisionEvidence, current: 4 },
    })],
    ['dispatch-route partition', () => ({
      ...validSummary(),
      dispatchRoutes: { ...validSummary().dispatchRoutes, externalMemoTransfer: 0 },
    })],
    ['lifecycle partition', () => ({
      ...validSummary(),
      lifecycle: { ...validSummary().lifecycle, finalized: 3 },
    })],
    ['invocation-mode partition', () => ({
      ...validSummary(),
      invocationModes: { ...validSummary().invocationModes, aiPreferred: 2 },
    })],
    ['invocation-reason partition', () => ({
      ...validSummary(),
      invocationReasons: { ...validSummary().invocationReasons, aiPreferredPolicy: 2 },
    })],
    ['local-model contribution partition', () => ({
      ...validSummary(),
      localModelContributions: {
        ...validSummary().localModelContributions,
        localFallback: 0,
      },
    })],
  ])('rejects a mismatched %s', (_label, makeSummary) => {
    expectRejected(makeSummary());
  });

  it.each([
    ['legacy evidence beyond legacy invocation', () => ({
      ...validSummary(),
      localDecisionEvidence: { current: 4, legacy: 2 },
    })],
    ['legacy invocation mode and reason', () => ({
      ...validSummary(),
      invocationModes: { legacyUnknown: 2, uncertaintyOnly: 1, aiPreferred: 3 },
    })],
    ['unrecorded contribution beyond legacy evidence', () => ({
      ...validSummary(),
      localModelContributions: {
        notRecorded: 2,
        pending: 0,
        acceptedChanged: 1,
        acceptedUnchanged: 0,
        localFallback: 1,
      },
    })],
    ['uncertainty-only mode beyond semantic-uncertainty reason', () => ({
      ...validSummary(),
      invocationModes: { legacyUnknown: 1, uncertaintyOnly: 3, aiPreferred: 2 },
      invocationReasons: {
        legacyUnknown: 1,
        semanticUncertainty: 2,
        aiPreferredPolicy: 3,
      },
    })],
    ['approved correction snapshot beyond AI-preferred runs', () => ({
      ...validSummary(),
      invocationModes: { legacyUnknown: 1, uncertaintyOnly: 4, aiPreferred: 1 },
      invocationReasons: {
        legacyUnknown: 1,
        semanticUncertainty: 4,
        aiPreferredPolicy: 1,
      },
    })],
    ['pending contribution beyond prepared and running runs', () => ({
      ...validSummary(),
      localModelContributions: {
        notRecorded: 0,
        pending: 3,
        acceptedChanged: 1,
        acceptedUnchanged: 0,
        localFallback: 0,
      },
    })],
    ['settled contribution beyond finalized runs', () => ({
      ...validSummary(),
      lifecycle: { prepared: 2, running: 2, finalized: 2 },
    })],
    ['current local-model contribution beyond current evidence', () => ({
      ...validSummary(),
      localDecisionEvidence: { current: 3, legacy: 3 },
      invocationModes: { legacyUnknown: 3, uncertaintyOnly: 1, aiPreferred: 2 },
      invocationReasons: {
        legacyUnknown: 3,
        semanticUncertainty: 1,
        aiPreferredPolicy: 2,
      },
    })],
  ])('rejects a cross-group mismatch: %s', (_label, makeSummary) => {
    expectRejected(makeSummary());
  });

  it.each([
    ['snapshots beyond eligible evidence and policy counts', () => ({
      ...validSummary(),
      approvedCorrectionSnapshots: { withSignals: 5, totalSignals: 5 },
    })],
    ['fewer signals than signaled snapshots', () => ({
      ...validSummary(),
      approvedCorrectionSnapshots: { withSignals: 2, totalSignals: 1 },
    })],
    ['more than three signals per snapshot', () => ({
      ...validSummary(),
      approvedCorrectionSnapshots: { withSignals: 2, totalSignals: 7 },
    })],
    ['fallback reason beyond current local-decision evidence', () => ({
      ...validSummary(),
      fallbackReasons: { ...validSummary().fallbackReasons, dateUncertainty: 6 },
    })],
    ['too few fallback memberships for current non-policy evidence', () => ({
      ...validSummary(),
      fallbackReasons: {
        defaultRecordFallback: 1,
        unparsedTemporalCue: 0,
        unrecognizedActionCue: 0,
        lowTypeMargin: 0,
        tagUncertainty: 0,
        dateUncertainty: 0,
        unresolvedReference: 0,
        incompleteTask: 0,
        multiIntent: 0,
        candidateLimit: 0,
        localConflict: 0,
      },
    })],
    ['changed field beyond accepted-changed count', () => ({
      ...validSummary(),
      changedFields: { ...validSummary().changedFields, suggestedTitle: 2 },
    })],
    ['accepted change without a changed field', () => ({
      ...validSummary(),
      changedFields: {
        suggestedTitle: 0,
        typeCandidates: 0,
        dateCandidates: 0,
        tagCandidates: 0,
        itemCandidates: 0,
        relationCandidates: 0,
        ambiguityReasons: 0,
      },
    })],
  ])('rejects incoherent evidence: %s', (_label, makeSummary) => {
    expectRejected(makeSummary());
  });
});
