import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { AnalysisPathEvidenceSummary } from '../../shared/api/types';
import {
  AiProcessingDiagnostic,
  AiProcessingDiagnosticView,
  requestAiProcessingDiagnostic,
  shouldLoadAiProcessingDiagnostic,
} from './AiProcessingDiagnostic';

function summary(total = 8): AnalysisPathEvidenceSummary {
  const empty = total === 0;
  return {
    schemaVersion: '1',
    aggregationPolicyVersion: 'analysis-path-evidence-summary-v1',
    cohort: {
      basis: 'ANALYSIS_RUN_CREATED_AT',
      days: 14,
      fromInclusive: '2026-08-14T00:00:00Z',
      toExclusive: '2026-08-28T00:00:00.250Z',
      maxRuns: 1_000,
    },
    runs: empty
      ? { total: 0, withDispatch: 0, withoutDispatch: 0 }
      : { total: 8, withDispatch: 6, withoutDispatch: 2 },
    localDecisionEvidence: empty
      ? { current: 0, legacy: 0 }
      : { current: 5, legacy: 1 },
    dispatchRoutes: empty
      ? { localModel: 0, externalMemoTransfer: 0, builtInFake: 0, legacyOrOther: 0 }
      : { localModel: 4, externalMemoTransfer: 1, builtInFake: 0, legacyOrOther: 1 },
    lifecycle: empty
      ? { prepared: 0, running: 0, finalized: 0 }
      : { prepared: 1, running: 1, finalized: 4 },
    invocationModes: empty
      ? { legacyUnknown: 0, uncertaintyOnly: 0, aiPreferred: 0 }
      : { legacyUnknown: 1, uncertaintyOnly: 2, aiPreferred: 3 },
    invocationReasons: empty
      ? { legacyUnknown: 0, semanticUncertainty: 0, aiPreferredPolicy: 0 }
      : { legacyUnknown: 1, semanticUncertainty: 2, aiPreferredPolicy: 3 },
    localModelContributions: empty
      ? {
          notRecorded: 0,
          pending: 0,
          acceptedChanged: 0,
          acceptedUnchanged: 0,
          localFallback: 0,
        }
      : {
          notRecorded: 0,
          pending: 1,
          acceptedChanged: 1,
          acceptedUnchanged: 1,
          localFallback: 1,
        },
    approvedCorrectionSnapshots: empty
      ? { withSignals: 0, totalSignals: 0 }
      : { withSignals: 2, totalSignals: 3 },
    fallbackReasons: {
      defaultRecordFallback: empty ? 0 : 1,
      unparsedTemporalCue: empty ? 0 : 2,
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
    changedFields: {
      suggestedTitle: empty ? 0 : 1,
      typeCandidates: 0,
      dateCandidates: 0,
      tagCandidates: 0,
      itemCandidates: 0,
      relationCandidates: 0,
      ambiguityReasons: 0,
    },
  };
}

function view(overrides: Partial<Parameters<typeof AiProcessingDiagnosticView>[0]> = {}) {
  return renderToStaticMarkup(
    <AiProcessingDiagnosticView
      summary={summary()}
      loading={false}
      error={null}
      onRefresh={vi.fn()}
      expanded
      onToggle={vi.fn()}
      {...overrides}
    />,
  );
}

describe('AI processing diagnostic', () => {
  it('does not load or expose diagnostic content before the first expand', () => {
    const onLoad = vi.fn();
    const markup = renderToStaticMarkup(
      <AiProcessingDiagnostic
        summary={null}
        loading={false}
        error={null}
        onLoad={onLoad}
        onRefresh={vi.fn()}
      />,
    );

    expect(onLoad).not.toHaveBeenCalled();
    expect(markup).toContain('분석 경로 진단');
    expect(markup).toContain('aria-expanded="false"');
    expect(markup).not.toContain('role="region"');
  });

  it('requests only when opening for the first time', () => {
    expect(shouldLoadAiProcessingDiagnostic(false, false)).toBe(false);
    expect(shouldLoadAiProcessingDiagnostic(true, false)).toBe(true);
    expect(shouldLoadAiProcessingDiagnostic(false, true)).toBe(false);
    expect(shouldLoadAiProcessingDiagnostic(true, true)).toBe(false);
  });

  it('allows an explicit refresh after the single first-expand request', () => {
    const request = vi.fn();
    let hasRequested = false;

    if (shouldLoadAiProcessingDiagnostic(true, hasRequested)) {
      hasRequested = true;
      requestAiProcessingDiagnostic(request);
    }
    if (shouldLoadAiProcessingDiagnostic(true, hasRequested)) {
      requestAiProcessingDiagnostic(request);
    }
    expect(request).toHaveBeenCalledTimes(1);

    requestAiProcessingDiagnostic(request);
    expect(request).toHaveBeenCalledTimes(2);
  });

  it('renders factual contribution and approved-signal labels', () => {
    const markup = view();

    expect(markup).toContain('모델 변경 포함');
    expect(markup).toContain('변경 없음');
    expect(markup).toContain('로컬 제안으로 복구');
    expect(markup).toContain('로컬 모델 설정 경로');
    expect(markup).toContain('외부 전송 설정 경로');
    expect(markup).toContain('기본 Fake 경로');
    expect(markup).toContain('이전·기타 경로');
    expect(markup).toContain('현재 저장된 기여 상태입니다');
    expect(markup).toContain('로컬 판단 보완 사유 기록');
    expect(markup).toContain('경로 선택 원인과는 구분됩니다');
    expect(markup).toContain('표시된 집계는 불러온 시점의 기록입니다');
    expect(markup).toContain('진단 새로고침');
    expect(markup).toContain('승인 교정 신호가 고정된 처리');
    expect(markup).toContain('모델이 그 신호를 사용했다는 증거는 아닙니다');
    expect(markup).toContain('실제 모델 호출이 있었다고 볼 수 없습니다');
    expect(markup).toContain('해당 설정 경로에서 성공으로 기록된 결과가 제안에 반영된 상태입니다');
    expect(markup).toContain('내용이 옳거나 이전보다 나아졌다는 뜻은 아닙니다');
    expect(markup.indexOf('로컬 모델 설정 경로')).toBeLessThan(
      markup.indexOf('모델 변경 포함'),
    );
    expect(markup).not.toContain('LOCAL MODEL EVIDENCE');
    expect(markup).not.toContain('AI 처리 진단');
    expect(markup).not.toContain('모델 전달 기록');
    expect(markup).not.toContain('최종 기여 상태입니다');
    expect(markup).not.toContain('모델 경로 판단 사유');
    expect(markup).not.toContain('정확도');
    expect(markup).not.toContain('개선');
    expect(markup).not.toContain('불필요한 호출');
  });

  it('renders the expanded empty state', () => {
    const markup = view({ summary: summary(0) });

    expect(markup).toContain('최근 14일 동안 집계할 분석 실행 기록이 없습니다.');
    expect(markup).toContain('진단 새로고침');
    expect(markup).not.toContain('로컬 모델 결과 반영 기록');
  });

  it('renders local loading and error/retry states', () => {
    const loading = view({ summary: null, loading: true });
    const failed = view({ summary: null, error: '진단 집계를 불러오지 못했습니다.' });

    expect(loading).toContain('role="status"');
    expect(loading).toContain('분석 경로 기록을 불러오는 중');
    expect(failed).toContain('role="alert"');
    expect(failed).toContain('진단 다시 불러오기');
    expect(failed).not.toContain('진단 새로고침');
  });

  it('exposes an accessible disclosure and labelled region', () => {
    const collapsed = view({ expanded: false });
    const expanded = view();

    expect(collapsed).toContain('aria-expanded="false"');
    expect(collapsed).toContain('aria-controls=');
    expect(expanded).toContain('aria-expanded="true"');
    expect(expanded.match(/role="region"/g)).toHaveLength(1);
    expect(expanded.match(/aria-labelledby=/g)).toHaveLength(1);
    expect(expanded).toMatch(/<h2[^>]*>.*분석 경로 진단.*<\/h2>/);
    expect(expanded).toMatch(/<h3>설정 경로 기록<\/h3>/);
  });
});
