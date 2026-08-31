import type { MemoView } from '../../shared/api/types';

type Props = {
  memo: MemoView | null;
  loading: boolean;
  error: string | null;
  onRetry: () => void;
  headingId: string;
  expectedRevision?: number;
};

export function CurrentMemoDetail({
  memo,
  loading,
  error,
  onRetry,
  headingId,
  expectedRevision,
}: Props) {
  if (loading) {
    return <p className="graph-detail-state" role="status">최신 원문을 불러오는 중…</p>;
  }
  if (error) {
    return (
      <aside className="graph-detail-state graph-detail-state--error" role="alert">
        <p>{error}</p>
        <button type="button" className="secondary-button" onClick={onRetry}>
          최신 원문 다시 불러오기
        </button>
      </aside>
    );
  }
  if (!memo) {
    return <p className="graph-detail-state">표시할 최신 원문이 없습니다.</p>;
  }

  const revisionChanged = expectedRevision !== undefined && memo.currentRevision !== expectedRevision;
  return (
    <>
      {revisionChanged && (
        <p className="memo-detail-revision-warning" role="status">
          검색 후 원문이 변경되었습니다. 아래에는 현재 revision {memo.currentRevision}을 표시합니다.
        </p>
      )}
      <section className="graph-detail-block" aria-labelledby={headingId}>
        <div className="graph-detail-block__heading">
          <h3 id={headingId}>현재 원문</h3>
          <span>revision {memo.currentRevision}</span>
        </div>
        <pre aria-label="현재 원문">{memo.content}</pre>
      </section>
    </>
  );
}
