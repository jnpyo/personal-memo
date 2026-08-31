import { useId, useRef, useState } from 'react';
import type { AnalysisPathEvidenceSummary } from '../../shared/api/types';

type Props = {
  summary: AnalysisPathEvidenceSummary | null;
  loading: boolean;
  error: string | null;
  onLoad: () => void;
  onRefresh: () => void;
};

type ViewProps = Omit<Props, 'onLoad'> & {
  expanded: boolean;
  onToggle: () => void;
};

type MetricProps = {
  label: string;
  value: number;
};

const FALLBACK_LABELS: ReadonlyArray<[
  keyof AnalysisPathEvidenceSummary['fallbackReasons'],
  string,
]> = [
  ['defaultRecordFallback', '기본 레코드로 복구'],
  ['unparsedTemporalCue', '시간 표현 해석 보류'],
  ['unrecognizedActionCue', '행동 표현 인식 보류'],
  ['lowTypeMargin', '유형 후보 차이 부족'],
  ['tagUncertainty', '태그 불확실성'],
  ['dateUncertainty', '날짜 불확실성'],
  ['unresolvedReference', '참조 대상 미확정'],
  ['incompleteTask', '할 일 정보 미완성'],
  ['multiIntent', '여러 의도 감지'],
  ['candidateLimit', '후보 수 제한'],
  ['localConflict', '로컬 후보 충돌'],
];

const CHANGED_FIELD_LABELS: ReadonlyArray<[
  keyof AnalysisPathEvidenceSummary['changedFields'],
  string,
]> = [
  ['suggestedTitle', '제안 제목'],
  ['typeCandidates', '유형 후보'],
  ['dateCandidates', '날짜 후보'],
  ['tagCandidates', '태그 후보'],
  ['itemCandidates', '항목 후보'],
  ['relationCandidates', '관계 후보'],
  ['ambiguityReasons', '모호성 사유'],
];

function Metric({ label, value }: MetricProps) {
  return (
    <div className="ai-diagnostic-metric">
      <dt>{label}</dt>
      <dd>{value.toLocaleString('ko-KR')}</dd>
    </div>
  );
}

function NonExclusiveCounts({
  title,
  note,
  entries,
}: {
  title: string;
  note: string;
  entries: ReadonlyArray<readonly [string, number]>;
}) {
  const visibleEntries = entries.filter(([, value]) => value > 0);
  return (
    <details className="ai-diagnostic-details">
      <summary>{title}</summary>
      <p>{note}</p>
      {visibleEntries.length === 0 ? (
        <p className="ai-diagnostic-empty-detail">기록된 항목이 없습니다.</p>
      ) : (
        <dl className="ai-diagnostic-compact-list">
          {visibleEntries.map(([label, value]) => (
            <div key={label}>
              <dt>{label}</dt>
              <dd>{value.toLocaleString('ko-KR')}</dd>
            </div>
          ))}
        </dl>
      )}
    </details>
  );
}

export function shouldLoadAiProcessingDiagnostic(
  nextExpanded: boolean,
  hasRequested: boolean,
): boolean {
  return nextExpanded && !hasRequested;
}

export function requestAiProcessingDiagnostic(request: () => void): void {
  request();
}

export function AiProcessingDiagnosticView({
  summary,
  loading,
  error,
  onRefresh,
  expanded,
  onToggle,
}: ViewProps) {
  const identity = useId();
  const titleId = `${identity}-ai-processing-diagnostic-title`;
  const panelId = `${identity}-ai-processing-diagnostic-panel`;

  return (
    <section className="ai-processing-diagnostic">
      <h2 className="ai-diagnostic-heading">
        <button
          type="button"
          className="ai-diagnostic-toggle"
          aria-expanded={expanded}
          aria-controls={panelId}
          onClick={onToggle}
        >
          <span id={titleId}>분석 경로 진단</span>
          <span className="ai-diagnostic-toggle__state" aria-hidden="true">
            {expanded ? '접기' : '펼치기'}
          </span>
        </button>
      </h2>

      {expanded && (
        <div
          id={panelId}
          className="ai-diagnostic-panel"
          role="region"
          aria-labelledby={titleId}
        >
          <p className="ai-diagnostic-note">
            이 진단은 저장된 분석 경로 기록의 건수만 보여 줍니다. 설정 경로·처리 준비 기록과
            처리 결과 대기·로컬 제안으로 복구 상태만으로 실제 모델 호출이 있었다고 볼 수
            없습니다. 표시된 집계는 불러온 시점의 기록입니다.
          </p>

          {loading && (
            <p className="panel-state" role="status" aria-live="polite">
              분석 경로 기록을 불러오는 중…
            </p>
          )}

          {!loading && error && (
            <div className="panel-state panel-state--error" role="alert">
              <p>{error}</p>
              <button
                type="button"
                className="secondary-button"
                onClick={() => requestAiProcessingDiagnostic(onRefresh)}
              >
                진단 다시 불러오기
              </button>
            </div>
          )}

          {!loading && !error && !summary && (
            <p className="panel-state" role="status" aria-live="polite">
              분석 경로 기록을 기다리는 중…
            </p>
          )}

          {!loading && !error && summary?.runs.total === 0 && (
            <p className="panel-state">
              최근 {summary.cohort.days}일 동안 집계할 분석 실행 기록이 없습니다.
            </p>
          )}

          {!loading && !error && summary && summary.runs.total > 0 && (
            <div className="ai-diagnostic-groups">
              <div className="ai-diagnostic-group">
                <h3>설정 경로 기록</h3>
                <p>저장된 경로 분류이며 실제 모델 호출 횟수가 아닙니다.</p>
                <dl className="ai-diagnostic-grid">
                  <Metric label="로컬 모델 설정 경로" value={summary.dispatchRoutes.localModel} />
                  <Metric
                    label="외부 전송 설정 경로"
                    value={summary.dispatchRoutes.externalMemoTransfer}
                  />
                  <Metric label="기본 Fake 경로" value={summary.dispatchRoutes.builtInFake} />
                  <Metric label="이전·기타 경로" value={summary.dispatchRoutes.legacyOrOther} />
                </dl>
              </div>

              <div className="ai-diagnostic-group">
                <h3>집계 범위</h3>
                <p>
                  최근 {summary.cohort.days}일 동안 생성된 분석 실행을 기준으로 집계합니다.
                </p>
                <dl className="ai-diagnostic-grid">
                  <Metric label="분석 실행" value={summary.runs.total} />
                  <Metric label="경로 분류 있음" value={summary.runs.withDispatch} />
                  <Metric label="경로 분류 없음" value={summary.runs.withoutDispatch} />
                  <Metric
                    label="현재 로컬 판단 근거"
                    value={summary.localDecisionEvidence.current}
                  />
                  <Metric
                    label="이전 로컬 판단 근거"
                    value={summary.localDecisionEvidence.legacy}
                  />
                </dl>
              </div>

              <div className="ai-diagnostic-group">
                <h3>로컬 모델 결과 반영 기록</h3>
                <p>
                  로컬 모델 설정 경로의 현재 저장된 기여 상태입니다. 모델 변경 포함과 변경
                  없음만 해당 설정 경로에서 성공으로 기록된 결과가 제안에 반영된 상태입니다.
                  두 결과도 제안 내용이 옳거나 이전보다 나아졌다는 뜻은 아닙니다.
                </p>
                <dl className="ai-diagnostic-grid">
                  <Metric
                    label="모델 변경 포함"
                    value={summary.localModelContributions.acceptedChanged}
                  />
                  <Metric
                    label="변경 없음"
                    value={summary.localModelContributions.acceptedUnchanged}
                  />
                  <Metric
                    label="로컬 제안으로 복구"
                    value={summary.localModelContributions.localFallback}
                  />
                  <Metric
                    label="처리 결과 대기"
                    value={summary.localModelContributions.pending}
                  />
                  <Metric
                    label="기여 기록 없음"
                    value={summary.localModelContributions.notRecorded}
                  />
                </dl>
              </div>

              <div className="ai-diagnostic-group">
                <h3>분석 정책 기록</h3>
                <p>각 항목은 서로 겹치지 않는 실행 건수입니다.</p>
                <dl className="ai-diagnostic-grid">
                  <Metric label="AI 우선 정책" value={summary.invocationModes.aiPreferred} />
                  <Metric label="불확실성 기반 경로" value={summary.invocationModes.uncertaintyOnly} />
                  <Metric label="이전 정책 기록" value={summary.invocationModes.legacyUnknown} />
                </dl>
              </div>

              <div className="ai-diagnostic-group ai-diagnostic-group--signals">
                <h3>승인 교정 신호</h3>
                <dl className="ai-diagnostic-grid">
                  <Metric
                    label="승인 교정 신호가 고정된 처리"
                    value={summary.approvedCorrectionSnapshots.withSignals}
                  />
                  <Metric
                    label="고정된 신호"
                    value={summary.approvedCorrectionSnapshots.totalSignals}
                  />
                </dl>
                <p className="ai-diagnostic-signal-note">
                  이 수치는 해당 처리에 신호 스냅샷이 고정되었다는 뜻이며, 모델이 그 신호를
                  사용했다는 증거는 아닙니다.
                </p>
              </div>

              <NonExclusiveCounts
                title="로컬 판단 보완 사유 기록"
                note="한 실행에 여러 보완 사유가 함께 기록될 수 있으며 경로 선택 원인과는 구분됩니다."
                entries={FALLBACK_LABELS.map(([key, label]) => [
                  label,
                  summary.fallbackReasons[key],
                ])}
              />
              <NonExclusiveCounts
                title="모델 변경 포함 필드 기록"
                note="한 실행에서 여러 필드가 함께 집계될 수 있습니다."
                entries={CHANGED_FIELD_LABELS.map(([key, label]) => [
                  label,
                  summary.changedFields[key],
                ])}
              />
            </div>
          )}

          {!loading && !error && summary && (
            <div className="ai-diagnostic-refresh">
              <button
                type="button"
                className="secondary-button"
                onClick={() => requestAiProcessingDiagnostic(onRefresh)}
              >
                진단 새로고침
              </button>
            </div>
          )}
        </div>
      )}
    </section>
  );
}

export function AiProcessingDiagnostic({ onLoad, ...viewProps }: Props) {
  const [expanded, setExpanded] = useState(false);
  const hasRequested = useRef(false);

  const toggle = () => {
    const nextExpanded = !expanded;
    setExpanded(nextExpanded);
    if (shouldLoadAiProcessingDiagnostic(nextExpanded, hasRequested.current)) {
      hasRequested.current = true;
      requestAiProcessingDiagnostic(onLoad);
    }
  };

  return (
    <AiProcessingDiagnosticView
      {...viewProps}
      expanded={expanded}
      onToggle={toggle}
    />
  );
}
