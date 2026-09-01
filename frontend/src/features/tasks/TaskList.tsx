import type { Task, TaskStatus } from '../../shared/api/types';

type Props = {
  tasks: Task[];
  loading: boolean;
  error: string | null;
  busy: boolean;
  pendingTaskId: string | null;
  onRetry: () => void;
  onStatusChange: (task: Task, status: TaskStatus) => void;
};

const STATUS_LABEL: Record<TaskStatus, string> = {
  TODO: '할 일',
  DONE: '완료',
  CANCELLED: '취소',
};

const TASK_STATUSES: TaskStatus[] = ['TODO', 'DONE', 'CANCELLED'];

function dueLabel(task: Task): string | null {
  if (task.dueDate) {
    const [year, month, day] = task.dueDate.split('-');
    return `${year}. ${Number(month)}. ${Number(day)}. (날짜만 지정)`;
  }
  if (!task.dueAt) return null;
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(task.dueAt));
}

export function TaskList({
  tasks,
  loading,
  error,
  busy,
  pendingTaskId,
  onRetry,
  onStatusChange,
}: Props) {
  return (
    <section className="task-section" aria-labelledby="tasks-title">
      <div className="section-heading">
        <div>
          <span className="eyebrow">CONFIRMED</span>
          <h2 id="tasks-title">할 일</h2>
        </div>
        <span className="count-badge">{tasks.length}</span>
      </div>

      {loading && <p className="panel-state">할 일을 불러오는 중…</p>}
      {!loading && error && (
        <div className="panel-state panel-state--error" role="alert">
          <p>{error}</p>
          <button type="button" className="secondary-button" onClick={onRetry}>
            다시 불러오기
          </button>
        </div>
      )}
      {!loading && !error && tasks.length === 0 && (
        <p className="panel-state">할 일이 없습니다.</p>
      )}

      {!loading && !error && tasks.length > 0 && (
        <div className="task-list">
          {tasks.map((task) => {
            const pending = pendingTaskId === task.id;
            return (
              <article className={`task-row task-row--${task.status.toLowerCase()}`} key={task.id}>
                <div className="task-copy">
                  <strong>{task.title}</strong>
                  <span className={task.overdue ? 'overdue-label' : undefined}>
                    {task.overdue ? '기한 초과' : dueLabel(task) ?? '기한 없음'}
                  </span>
                </div>
                <div className="status-control" aria-label={`${task.title} 상태`}>
                  {TASK_STATUSES.map((status) => (
                    <button
                      type="button"
                      key={status}
                      aria-pressed={task.status === status}
                      disabled={busy}
                      onClick={() => onStatusChange(task, status)}
                    >
                      {pending && task.status !== status ? '…' : STATUS_LABEL[status]}
                    </button>
                  ))}
                </div>
              </article>
            );
          })}
        </div>
      )}
    </section>
  );
}
