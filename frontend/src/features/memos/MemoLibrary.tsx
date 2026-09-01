import { useEffect, useState } from 'react';
import type { MemoStatus, MemoView } from '../../shared/api/types';
import { isMemoContentValid } from './memoModel';

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
  onDirtyChange: (dirty: boolean) => void;
};

export function memoEditHasChanges(originalContent: string, draftContent: string): boolean {
  return originalContent !== draftContent;
}

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
  onDirtyChange,
}: Props) {
  const [filter, setFilter] = useState<MemoStatus>('ACTIVE');
  const [editDraft, setEditDraft] = useState<EditDraft | null>(null);
  // The API already orders by updated_at, which is intentionally not exposed in MemoView yet.
  const memos = filter === 'ACTIVE' ? activeMemos : trashedMemos;
  const pending = busy;

  useEffect(() => () => onDirtyChange(false), [onDirtyChange]);

  useEffect(() => {
    if (!editDraft) return;
    const current = activeMemos.find(({ id }) => id === editDraft.memo.id);
    if (
      current &&
      current.currentRevision > editDraft.memo.currentRevision &&
      current.content === editDraft.content
    ) {
      setEditDraft(null);
      onDirtyChange(false);
    }
  }, [activeMemos, editDraft, onDirtyChange]);

  async function saveEdit() {
    if (!editDraft || !isMemoContentValid(editDraft.content)) return;
    const saved = await onUpdate(editDraft.memo, editDraft.content);
    if (saved) {
      setEditDraft(null);
      onDirtyChange(false);
    }
  }

  return (
    <section className="memo-section" aria-labelledby="memo-library-title">
      <div className="section-heading">
        <h2 id="memo-library-title">모든 메모</h2>
      </div>

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

      {loading && <p className="panel-state">메모를 불러오고 있습니다…</p>}
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
          {filter === 'ACTIVE' ? '아직 저장한 메모가 없습니다.' : '휴지통이 비어 있습니다.'}
        </p>
      )}

      {!loading && !error && memos.length > 0 && (
        <div className="memo-list" role="tabpanel">
          {memos.map((memo) => {
            const editing = editDraft?.memo.id === memo.id;
            const editIsStale =
              editing && editDraft !== null && editDraft.memo.currentRevision !== memo.currentRevision;
            return (
              <article className="memo-card" key={memo.id}>
                <div className="memo-card__meta">
                  <span>{formatCreatedAt(memo.createdAt)}</span>
                  {memo.status === 'TRASHED' && <span>휴지통</span>}
                </div>

                {editing && editDraft ? (
                  <div className="memo-editor">
                    <label htmlFor={`memo-edit-${memo.id}`}>메모 수정</label>
                    <textarea
                      id={`memo-edit-${memo.id}`}
                      maxLength={20_000}
                      rows={5}
                      value={editDraft.content}
                      disabled={pending}
                      onChange={(event) => {
                        const content = event.target.value;
                        setEditDraft({ ...editDraft, content });
                        onDirtyChange(memoEditHasChanges(editDraft.memo.content, content));
                      }}
                    />
                    {editIsStale && (
                      <p className="memo-editor__warning">
                        편집하는 동안 메모가 바뀌었습니다. 내용을 복사한 뒤 취소하고, 최신 메모에서
                        다시 수정해 주세요.
                      </p>
                    )}
                    <div className="memo-editor__footer">
                      <span>{editDraft.content.length.toLocaleString()} / 20,000</span>
                      <div>
                        <button
                          type="button"
                          className="secondary-button"
                          disabled={pending}
                          onClick={() => {
                            setEditDraft(null);
                            onDirtyChange(false);
                          }}
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
                          {pendingScope === `update:${memo.id}` ? '저장 중…' : '저장'}
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
                      disabled={pending || analysisBlocked || editDraft !== null}
                      title={analysisBlocked ? '열려 있는 제안을 먼저 처리해 주세요.' : undefined}
                      onClick={() => onAnalyze(memo)}
                    >
                      {pendingScope === `analyze:${memo.id}` ? '정리 중…' : '정리하기'}
                    </button>
                    <button
                      type="button"
                      className="secondary-button"
                      disabled={pending || editDraft !== null}
                      onClick={() => {
                        setEditDraft({ memo, content: memo.content });
                        onDirtyChange(false);
                      }}
                    >
                      수정
                    </button>
                    <button
                      type="button"
                      className="danger-button"
                      disabled={pending || editDraft !== null}
                      onClick={() => onTrash(memo)}
                    >
                      {pendingScope === `trash:${memo.id}` ? '이동 중…' : '휴지통'}
                    </button>
                  </div>
                )}

                {memo.status === 'TRASHED' && (
                  <div className="memo-card__actions memo-card__actions--restore">
                    <button
                      type="button"
                      className="secondary-button"
                      disabled={pending || editDraft !== null}
                      onClick={() => onRestore(memo)}
                    >
                      {pendingScope === `restore:${memo.id}` ? '복원 중…' : '복원'}
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
