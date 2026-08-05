import { useEffect, useState } from 'react';
import type { MemoStatus, MemoView } from '../../shared/api/types';
import {
  analysisStateLabel,
  analysisStateTone,
  isMemoContentValid,
} from './memoModel';

type EditDraft = {
  memo: MemoView;
  content: string;
};

type Props = {
  activeMemos: MemoView[];
  trashedMemos: MemoView[];
  loading: boolean;
  error: string | null;
  busy: boolean;
  pendingScope: string | null;
  analysisBlocked: boolean;
  onRetry: () => void;
  onUpdate: (memo: MemoView, content: string) => Promise<boolean>;
  onTrash: (memo: MemoView) => void;
  onRestore: (memo: MemoView) => void;
  onAnalyze: (memo: MemoView) => void;
};

function formatCreatedAt(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}

export function MemoLibrary({
  activeMemos,
  trashedMemos,
  loading,
  error,
  busy,
  pendingScope,
  analysisBlocked,
  onRetry,
  onUpdate,
  onTrash,
  onRestore,
  onAnalyze,
}: Props) {
  const [filter, setFilter] = useState<MemoStatus>('ACTIVE');
  const [editDraft, setEditDraft] = useState<EditDraft | null>(null);
  // The API already orders by updated_at, which is intentionally not exposed in MemoView yet.
  const memos = filter === 'ACTIVE' ? activeMemos : trashedMemos;
  const pending = busy;

  useEffect(() => {
    if (!editDraft) return;
    const current = activeMemos.find(({ id }) => id === editDraft.memo.id);
    if (
      current &&
      current.currentRevision > editDraft.memo.currentRevision &&
      current.content === editDraft.content
    ) {
      setEditDraft(null);
    }
  }, [activeMemos, editDraft]);

  async function saveEdit() {
    if (!editDraft || !isMemoContentValid(editDraft.content)) return;
    const saved = await onUpdate(editDraft.memo, editDraft.content);
    if (saved) setEditDraft(null);
  }

  return (
    <section className="memo-section" aria-labelledby="memo-library-title">
      <div className="section-heading">
        <div>
          <span className="eyebrow">RAW MEMOS</span>
          <h2 id="memo-library-title">최근 원본 메모</h2>
        </div>
        <span className="count-badge">최대 50개</span>
      </div>

      <p className="memo-preservation-note">
        원문과 AI 제안·태그·할 일은 별도로 보존됩니다. 원문 수정은 새 revision을 만들고,
        휴지통 이동이나 제안 거절도 기존 원문을 지우지 않습니다.
      </p>

      <div className="memo-filter" role="tablist" aria-label="메모 상태">
        <button
          type="button"
          role="tab"
          aria-selected={filter === 'ACTIVE'}
          onClick={() => setFilter('ACTIVE')}
        >
          활성 <span>{activeMemos.length}</span>
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={filter === 'TRASHED'}
          onClick={() => setFilter('TRASHED')}
        >
          휴지통 <span>{trashedMemos.length}</span>
        </button>
      </div>

      {loading && <p className="panel-state">원본 메모를 불러오고 있습니다…</p>}
      {!loading && error && (
        <div className="panel-state panel-state--error">
          <p>{error}</p>
          <button type="button" className="secondary-button" onClick={onRetry}>
            다시 불러오기
          </button>
        </div>
      )}
      {!loading && !error && memos.length === 0 && (
        <p className="panel-state">
          {filter === 'ACTIVE' ? '아직 저장한 원본 메모가 없습니다.' : '휴지통이 비어 있습니다.'}
        </p>
      )}

      {!loading && !error && memos.length > 0 && (
        <div className="memo-list" role="tabpanel">
          {memos.map((memo) => {
            const editing = editDraft?.memo.id === memo.id;
            const editIsStale =
              editing && editDraft !== null && editDraft.memo.currentRevision !== memo.currentRevision;
            const tone = analysisStateTone(memo.analysisState);
            return (
              <article className="memo-card" key={memo.id}>
                <div className="memo-card__meta">
                  <span>revision {memo.currentRevision}</span>
                  <span>{memo.status === 'ACTIVE' ? '활성' : '휴지통'}</span>
                  <span>{formatCreatedAt(memo.createdAt)}</span>
                  <span className={`analysis-badge analysis-badge--${tone}`}>
                    {analysisStateLabel(memo.analysisState)}
                  </span>
                </div>

                {editing && editDraft ? (
                  <div className="memo-editor">
                    <label htmlFor={`memo-edit-${memo.id}`}>
                      원문 수정 · 저장하면 revision {memo.currentRevision + 1}
                    </label>
                    <textarea
                      id={`memo-edit-${memo.id}`}
                      maxLength={20_000}
                      rows={5}
                      value={editDraft.content}
                      disabled={pending}
                      onChange={(event) =>
                        setEditDraft({ ...editDraft, content: event.target.value })
                      }
                    />
                    {editIsStale && (
                      <p className="memo-editor__warning">
                        편집을 시작한 뒤 새 revision이 확인되었습니다. 수정 내용을 복사한 뒤 취소하고
                        최신 원문에서 다시 편집해 주세요.
                      </p>
                    )}
                    <div className="memo-editor__footer">
                      <span>{editDraft.content.length.toLocaleString()} / 20,000</span>
                      <div>
                        <button
                          type="button"
                          className="secondary-button"
                          disabled={pending}
                          onClick={() => setEditDraft(null)}
                        >
                          취소
                        </button>
                        <button
                          type="button"
                          className="approve-button"
                          disabled={
                            pending || editIsStale || !isMemoContentValid(editDraft.content)
                          }
                          onClick={() => void saveEdit()}
                        >
                          {pendingScope === `update:${memo.id}` ? '저장 중…' : '새 revision 저장'}
                        </button>
                      </div>
                    </div>
                  </div>
                ) : (
                  <p className="memo-card__content">{memo.content}</p>
                )}

                {!editing && memo.status === 'ACTIVE' && (
                  <div className="memo-card__actions">
                    <button
                      type="button"
                      className="approve-button"
                      disabled={pending || analysisBlocked}
                      title={analysisBlocked ? '열려 있는 제안을 먼저 처리해 주세요.' : undefined}
                      onClick={() => onAnalyze(memo)}
                    >
                      {pendingScope === `analyze:${memo.id}` ? '분석 중…' : '최신 revision 제안 분석'}
                    </button>
                    <button
                      type="button"
                      className="secondary-button"
                      disabled={pending}
                      onClick={() => setEditDraft({ memo, content: memo.content })}
                    >
                      원문 수정
                    </button>
                    <button
                      type="button"
                      className="danger-button"
                      disabled={pending}
                      onClick={() => onTrash(memo)}
                    >
                      {pendingScope === `trash:${memo.id}` ? '이동 중…' : '휴지통으로'}
                    </button>
                  </div>
                )}

                {memo.status === 'TRASHED' && (
                  <div className="memo-card__actions memo-card__actions--restore">
                    <span>휴지통 메모는 복원 후 수정하거나 분석할 수 있습니다.</span>
                    <button
                      type="button"
                      className="secondary-button"
                      disabled={pending}
                      onClick={() => onRestore(memo)}
                    >
                      {pendingScope === `restore:${memo.id}` ? '복원 중…' : '활성 메모로 복원'}
                    </button>
                  </div>
                )}
              </article>
            );
          })}
        </div>
      )}
    </section>
  );
}
