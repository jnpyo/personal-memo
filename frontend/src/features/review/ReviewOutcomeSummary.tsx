import type { AnalysisReviewOutcomeSummary } from '../../shared/api/types';

type Props = {
  summary: AnalysisReviewOutcomeSummary | null;
  loading: boolean;
  error: string | null;
  onRetry: () => void;
};

type MetricProps = {
  label: string;
  value: number;
};

function Metric({ label, value }: MetricProps) {
  return (
    <div className="review-outcome-metric">
      <dt>{label}</dt>
      <dd>{value.toLocaleString('ko-KR')}</dd>
    </div>
  );
}

export function ReviewOutcomeSummary({ summary, loading, error, onRetry }: Props) {
  const days = summary?.cohort.days ?? 14;
  const otherStates = summary
    ? summary.proposals.currentStates.queued +
      summary.proposals.currentStates.running +
      summary.proposals.currentStates.failed +
      summary.proposals.currentStates.other
    : 0;

  return (
    <section className="review-outcome-section" aria-labelledby="review-outcome-title">
      <div className="section-heading">
        <div>
          <span className="eyebrow">REVIEW HISTORY</span>
          <h2 id="review-outcome-title">최근 {days}일 제안 검토</h2>
        </div>
        {summary && (
          <span className="count-badge">
            제안 총 {summary.proposals.total.toLocaleString('ko-KR')}개
          </span>
        )}
      </div>

      <p className="review-outcome-note">
        최근 생성된 제안과 사용자의 검토·적용 상태입니다. AI의 정답률이나 정확도를 뜻하지
        않습니다.
      </p>

      {loading && (
        <p className="panel-state" role="status" aria-live="polite">
          제안 검토 기록을 불러오는 중…
        </p>
      )}

      {!loading && error && (
        <div className="panel-state panel-state--error" role="alert">
          <p>{error}</p>
          <button type="button" className="secondary-button" onClick={onRetry}>
            집계 다시 불러오기
          </button>
        </div>
      )}

      {!loading && !error && summary?.proposals.total === 0 && (
        <p className="panel-state">아직 집계할 제안 검토 기록이 없습니다.</p>
      )}

      {!loading && !error && summary && summary.proposals.total > 0 && (
        <div className="review-outcome-groups">
          <div className="review-outcome-group">
            <h3>적용 내용 비교</h3>
            <p>제안별 최신 적용 내용을 기본 제안과 의미상 비교한 건수입니다.</p>
            <dl className="review-outcome-grid">
              <Metric label="제안 그대로 적용" value={summary.outcomes.exact} />
              <Metric label="수정 후 적용" value={summary.outcomes.corrected} />
              <Metric label="사용자가 보완해 적용" value={summary.outcomes.userResolved} />
              <Metric label="현재 비교 규칙으로 판정할 수 없음" value={summary.outcomes.unclassifiable} />
            </dl>
          </div>

          <div className="review-outcome-group">
            <h3>현재 제안 상태</h3>
            <p>최근 생성된 각 제안의 현재 상태이며, 과거의 모든 처리 횟수는 아닙니다.</p>
            <dl className="review-outcome-grid">
              <Metric label="검토 대기" value={summary.proposals.currentStates.reviewRequired} />
              <Metric label="보류" value={summary.proposals.currentStates.currentPostponed} />
              <Metric label="적용" value={summary.proposals.currentStates.applied} />
              <Metric label="거절" value={summary.proposals.currentStates.rejected} />
              <Metric label="오래된 제안" value={summary.proposals.currentStates.stale} />
              <Metric label="기타 상태" value={otherStates} />
            </dl>
          </div>

          <div className="review-outcome-group">
            <h3>최신 적용 상태</h3>
            <p>현재 제안 상태와 중복될 수 있는 별도 기준이며, 제안별 최신 적용만 셉니다.</p>
            <dl className="review-outcome-grid">
              <Metric label="적용 없음" value={summary.latestApplications.none} />
              <Metric label="적용 유지" value={summary.latestApplications.applied} />
              <Metric label="되돌림" value={summary.latestApplications.undone} />
            </dl>
          </div>
        </div>
      )}
    </section>
  );
}
