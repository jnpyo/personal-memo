import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { TaskList } from './TaskList';

const tasks = [
  { id: 'task-a', title: '첫 번째 할 일', status: 'TODO' as const, dueAt: null, overdue: false },
  { id: 'task-b', title: '두 번째 할 일', status: 'TODO' as const, dueAt: null, overdue: false },
];

describe('task operation lock', () => {
  it('disables every task mutation while one task request or another workspace operation is pending', () => {
    const markup = renderToStaticMarkup(
      <TaskList
        tasks={tasks}
        loading={false}
        error={null}
        busy
        pendingTaskId="task-a"
        onRetry={vi.fn()}
        onStatusChange={vi.fn()}
      />,
    );

    expect(markup.match(/disabled=""/g)).toHaveLength(tasks.length * 3);
    expect(markup).toContain('첫 번째 할 일');
    expect(markup).toContain('두 번째 할 일');
  });
});
