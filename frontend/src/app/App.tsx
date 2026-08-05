import { MemoCapture } from '../features/capture/MemoCapture';
import { MemoTagGraph } from '../features/graph/MemoTagGraph';
import { ConnectionStatus } from '../features/health/ConnectionStatus';
import { MemoLibrary } from '../features/memos/MemoLibrary';
import { PostponedReview } from '../features/review/PostponedReview';
import { ProposalReview } from '../features/review/ProposalReview';
import { TaskList } from '../features/tasks/TaskList';
import { FeedbackBanner } from '../shared/ui/FeedbackBanner';
import { useMemoWorkspace } from './useMemoWorkspace';

export function App() {
  const workspace = useMemoWorkspace();

  const capturePrompt = workspace.recoveryLoading
    ? '서버에 저장된 검토 상태를 복원하고 있습니다.'
    : workspace.recoveryError
      ? '원문은 지금 저장할 수 있습니다. Fake 분석은 검토 상태를 복구한 뒤 시작합니다.'
      : workspace.review
        ? '현재 제안을 먼저 승인·보류·거절해 주세요.'
        : workspace.postponedReview
          ? '보류한 제안을 먼저 검토해 주세요.'
          : '메모 원문은 AI 결과와 별도로 먼저 저장됩니다.';

  return (
    <main>
      <header className="hero">
        <div>
          <span className="eyebrow">PERSONAL MEMO</span>
          <h1>생각을 먼저 적으세요.</h1>
          <p>Fake 분석은 제안만 만듭니다. 수정하고 승인한 내용만 실제 항목이 됩니다.</p>
        </div>
        <ConnectionStatus status={workspace.connection} onRetry={workspace.checkConnection} />
      </header>

      <FeedbackBanner
        feedback={workspace.feedback}
        retryLabel={workspace.retryAction?.label}
        onRetry={workspace.retryAction ? workspace.retry : undefined}
        onDismiss={workspace.dismissFeedback}
      />

      {workspace.recoveryLoading && (
        <p className="recovery-state" role="status" aria-live="polite">
          마지막 적용과 검토 중·보류한 제안을 불러오는 중입니다…
        </p>
      )}

      {workspace.recoveryError && (
        <aside className="recovery-state recovery-state--error" role="alert">
          <p>저장된 검토 상태를 불러오지 못했습니다. 원문 저장은 가능하지만 Fake 분석은 잠시 보류됩니다.</p>
          <button type="button" className="secondary-button" onClick={workspace.refreshRecovery}>
            검토 상태 다시 불러오기
          </button>
        </aside>
      )}

      <MemoLibrary
        activeMemos={workspace.activeMemos}
        trashedMemos={workspace.trashedMemos}
        loading={workspace.memosLoading}
        error={workspace.memosError}
        busy={workspace.busy}
        pendingScope={workspace.pendingMemoScope}
        analysisBlocked={
          workspace.recoveryLoading ||
          workspace.recoveryError !== null ||
          workspace.review !== null ||
          workspace.postponedReview !== null
        }
        onRetry={workspace.refreshMemos}
        onUpdate={workspace.updateMemo}
        onTrash={workspace.trashMemo}
        onRestore={workspace.restoreMemo}
        onAnalyze={workspace.analyzeMemo}
      />

      <MemoTagGraph
        projection={workspace.graph}
        loading={workspace.workspaceLoading}
        error={workspace.workspaceError}
        onRetry={workspace.refreshWorkspace}
      />

      <TaskList
        tasks={workspace.tasks}
        loading={workspace.workspaceLoading}
        error={workspace.workspaceError}
        pendingTaskId={workspace.pendingTaskId}
        onRetry={workspace.refreshWorkspace}
        onStatusChange={workspace.updateTaskStatus}
      />

      {workspace.applicationId && (
        <aside className="undo-card">
          <div>
            <strong>마지막 적용을 되돌릴 수 있습니다.</strong>
            <p>적용으로 생성된 태그 연결과 할 일만 제거하고 원문 메모는 보존합니다.</p>
          </div>
          <button
            type="button"
            className="secondary-button"
            disabled={workspace.busy}
            onClick={workspace.undoApplication}
          >
            {workspace.busy ? '처리 중…' : '마지막 적용 되돌리기'}
          </button>
        </aside>
      )}

      {workspace.postponedReview && (
        <PostponedReview
          title={workspace.postponedReview.title}
          onResume={workspace.resumePostponedReview}
        />
      )}

      {workspace.review && (
        <ProposalReview
          review={workspace.review}
          busy={workspace.busy}
          onChange={workspace.changeReview}
          onApply={workspace.applyCurrentReview}
          onPostpone={workspace.postponeCurrentReview}
          onReject={workspace.rejectCurrentReview}
        />
      )}

      <MemoCapture
        content={workspace.content}
        disabled={workspace.captureLocked}
        submitting={workspace.captureSubmitting}
        rawOnly={workspace.recoveryError !== null}
        prompt={capturePrompt}
        onContentChange={workspace.changeContent}
        onSubmit={workspace.captureMemo}
      />
    </main>
  );
}
