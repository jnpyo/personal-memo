import { useState } from 'react';
import { useRegisterSW } from 'virtual:pwa-register/react';
import { PwaUpdatePrompt } from './PwaUpdatePrompt';
import './pwaUpdate.css';

type Props = {
  hasUnsavedChanges: boolean;
  operationPending?: boolean;
  onUpdatingChange?: (updating: boolean) => void;
};

export function PwaUpdateManager({
  hasUnsavedChanges,
  operationPending = false,
  onUpdatingChange = () => undefined,
}: Props) {
  const [updating, setUpdating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const {
    needRefresh: [needRefresh, setNeedRefresh],
    updateServiceWorker,
  } = useRegisterSW();

  async function update() {
    if (hasUnsavedChanges || operationPending) return;
    setUpdating(true);
    onUpdatingChange(true);
    setError(null);
    try {
      await updateServiceWorker(true);
    } catch {
      setError('UPDATE_FAILED');
      setUpdating(false);
      onUpdatingChange(false);
    }
  }

  return (
    <PwaUpdatePrompt
      available={needRefresh}
      updating={updating}
      hasUnsavedChanges={hasUnsavedChanges}
      operationPending={operationPending}
      error={error}
      onUpdate={() => void update()}
      onDismiss={() => {
        setError(null);
        setNeedRefresh(false);
      }}
    />
  );
}
