type Props = {
  available: boolean;
  updating: boolean;
  hasUnsavedChanges: boolean;
  operationPending: boolean;
  error: string | null;
  onUpdate: () => void;
  onDismiss: () => void;
};

export function PwaUpdatePrompt({
  available,
  updating,
  hasUnsavedChanges,
  operationPending,
  error,
  onUpdate,
  onDismiss,
}: Props) {
  if (!available) return null;

  return (
    <aside className="pwa-update" role="status" aria-live="polite" aria-atomic="true">
      <div>
        <strong>새 버전을 사용할 수 있습니다.</strong>
        <p>
          {hasUnsavedChanges
            ? '서버에 아직 반영되지 않은 편집 내용이 있습니다. 먼저 저장하거나 검토를 마친 뒤 업데이트해 주세요.'
            : operationPending
              ? '진행 중인 작업이 끝난 뒤 업데이트해 주세요.'
            : '작성 중인 입력을 확인한 뒤 직접 업데이트해 주세요.'}
        </p>
        {error && <p className="pwa-update__error" role="alert">업데이트하지 못했습니다. 다시 시도해 주세요.</p>}
      </div>
      <div className="pwa-update__actions">
        <button type="button" className="secondary-button" disabled={updating} onClick={onDismiss}>
          나중에
        </button>
        <button
          type="button"
          className="approve-button"
          disabled={updating || hasUnsavedChanges || operationPending}
          onClick={onUpdate}
        >
          {updating ? '업데이트 중…' : '지금 업데이트'}
        </button>
      </div>
    </aside>
  );
}
