import {
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
  type Ref,
  type ReactNode,
} from 'react';
import { api, SessionScopeChangedError } from '../../shared/api/client';
import { errorMessage } from '../../shared/api/errors';
import {
  CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION,
  type CalendarEvent,
  type CalendarFeedDetail,
  type CalendarFeedDisclosureMode,
  type CalendarFeedEntry,
  type CalendarFeedPublicationCapability,
  type CalendarFeedPublicationScope,
  type CalendarFeedSummary,
  type CreateCalendarFeedRequest,
  type EnableExternalCalendarFeedPublicationRequest,
  type RotateCalendarFeedRequest,
  type UpdateCalendarFeedRequest,
  type VersionedCalendarFeedRequest,
} from '../../shared/api/types';
import {
  buildCalendarFeedSubscriptionUrl,
  canDiscardCalendarFeedSensitiveRetry,
  canRotateCalendarFeedSubscription,
  calendarFeedScopedTransientFor,
  calendarFeedDisclosureLabel,
  calendarSharingEventTimeLabel,
  createCalendarFeedDraft,
  createCalendarFeedScopedTransientState,
  generateCalendarFeedSecret,
  isCalendarSharingProtected,
  replaceCalendarFeedSummary,
  requiresCalendarFeedTitleConfirmation,
  toggleCalendarFeedEvent,
  type CalendarFeedDraft,
  type CalendarFeedScopedTransientState,
  type CalendarSharingProtection,
} from './calendarSharingModel';

type Props = {
  disabled: boolean;
  online: boolean;
  onProtectionChange: (state: CalendarSharingProtection) => void;
};

type DialogProps = Props & {
  onClose: () => void;
};

type View = 'LIST' | 'CREATE' | 'MANAGE' | 'SECRET';

type OneTimeUrl = {
  action: 'CREATED' | 'ROTATED' | 'PUBLISHED';
  displayName: string;
  publicationMode: CalendarFeedPublicationScope;
  url: string;
};

type CalendarFeedSubscriptionPreparation = {
  publicationMode: CalendarFeedPublicationScope;
  url: string;
};

type CalendarFeedOverview = {
  publicationCapability: CalendarFeedPublicationCapability | null;
  feeds: CalendarFeedSummary[];
  eligibleEvents: CalendarEvent[];
  eligibleTruncated: boolean;
};

export function createUnavailableCalendarFeedOverview(): CalendarFeedOverview {
  return {
    publicationCapability: null,
    feeds: [],
    eligibleEvents: [],
    eligibleTruncated: false,
  };
}

export function prepareCalendarFeedSubscription(
  capability: CalendarFeedPublicationCapability | null,
  localOrigin: string,
  secret: string,
  publicationScope: CalendarFeedPublicationScope = capability?.mode ?? 'LOCAL_ONLY',
): CalendarFeedSubscriptionPreparation {
  if (capability === null) {
    throw new Error('Calendar feed publication capability is unavailable');
  }
  return {
    publicationMode: publicationScope,
    url: buildCalendarFeedSubscriptionUrl(
      capability,
      localOrigin,
      secret,
      publicationScope,
    ),
  };
}

export function prepareCreatedCalendarFeedSubscription(
  capability: CalendarFeedPublicationCapability | null,
  localOrigin: string,
  secret: string,
): CalendarFeedSubscriptionPreparation | null {
  if (capability === null) {
    throw new Error('Calendar feed publication capability is unavailable');
  }
  if (capability.mode === 'PUBLIC_HTTPS') {
    if (capability.consentPolicyVersion !== CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION) {
      throw new Error('Calendar feed publication consent policy is unavailable');
    }
    return null;
  }
  return prepareCalendarFeedSubscription(capability, localOrigin, secret, 'LOCAL_ONLY');
}

export function canCommitCalendarFeedOverviewResponse(
  currentGeneration: number,
  requestGeneration: number,
  aborted: boolean,
): boolean {
  return currentGeneration === requestGeneration && !aborted;
}

type RetryAction = {
  label: string;
  sensitive: boolean;
  run: () => void;
};

type MutationRequest = () => Promise<CalendarFeedDetail>;
type MutationSuccess = (detail: CalendarFeedDetail) => void;

export function CalendarSharingConfirmation({
  label,
  confirmationRef,
  children,
}: {
  label: string;
  confirmationRef?: Ref<HTMLDivElement>;
  children: ReactNode;
}) {
  return (
    <div
      ref={confirmationRef}
      className="calendar-sharing-confirm"
      role="alertdialog"
      aria-label={label}
      aria-live="assertive"
      aria-atomic="true"
      tabIndex={-1}
    >
      {children}
    </div>
  );
}

export function ExternalCalendarFeedPublicationConfirmation({
  disclosureMode,
  accepted,
  disabled,
  confirmationRef,
  onAcceptedChange,
  onConfirm,
  onCancel,
}: {
  disclosureMode: CalendarFeedDisclosureMode;
  accepted: boolean;
  disabled: boolean;
  confirmationRef?: Ref<HTMLDivElement>;
  onAcceptedChange: (accepted: boolean) => void;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <CalendarSharingConfirmation
      label="외부 일정 공개 시작 확인"
      confirmationRef={confirmationRef}
    >
      <p>
        새 주소를 아는 사람은 로그인 없이 이 공유에 직접 추가한 일정의
        {disclosureMode === 'BUSY_ONLY' ? ' 날짜와 시간을' : ' 제목과 시간을'} 읽을 수 있습니다.
        새 일정은 자동으로 추가되지 않습니다.
      </p>
      <p>
        Cloudflare가 query bearer와 요청 metadata를 처리합니다. 복사되거나 캘린더에
        저장·캐시된 사본은 앱이 회수할 수 없습니다.
      </p>
      <p>
        계속하면 기존 주소는 즉시 무효화되고 새 공개 HTTPS 주소는 한 번만 표시됩니다.
      </p>
      <label>
        <input
          type="checkbox"
          checked={accepted}
          disabled={disabled}
          onChange={(event) => onAcceptedChange(event.currentTarget.checked)}
        />
        위 공개 범위와 Cloudflare 처리 경계를 확인하고 동의합니다.
      </label>
      <div>
        <button
          type="button"
          className="danger-button"
          disabled={disabled || !accepted}
          onClick={onConfirm}
        >
          동의하고 외부 공개 시작
        </button>
        <button type="button" className="secondary-button" disabled={disabled} onClick={onCancel}>
          취소
        </button>
      </div>
    </CalendarSharingConfirmation>
  );
}

function detailSummary(detail: CalendarFeedDetail): CalendarFeedSummary {
  return {
    id: detail.id,
    displayName: detail.displayName,
    disclosureMode: detail.disclosureMode,
    status: detail.status,
    version: detail.version,
    eventCount: detail.eventCount,
    createdAt: detail.createdAt,
    updatedAt: detail.updatedAt,
    rotatedAt: detail.rotatedAt,
    revokedAt: detail.revokedAt,
    publicationScope: detail.publicationScope,
    publicConsentPolicyVersion: detail.publicConsentPolicyVersion,
    publicConsentGrantedAt: detail.publicConsentGrantedAt,
  };
}

function entryTimeLabel(entry: CalendarFeedEntry): string {
  return calendarSharingEventTimeLabel({
    id: entry.id,
    title: entry.title ?? '공유에서 제거된 일정',
    scheduleKind: entry.scheduleKind,
    startAt: entry.startAt,
    endAt: entry.endAt,
    startDate: entry.startDate,
    endDateExclusive: entry.endDateExclusive,
    sourceTimeZone: entry.sourceTimeZone,
  });
}

export function CalendarFeedDisclosureFields({
  value,
  disabled,
  onChange,
}: {
  value: CalendarFeedDisclosureMode;
  disabled: boolean;
  onChange: (value: CalendarFeedDisclosureMode) => void;
}) {
  return (
    <fieldset className="calendar-sharing-choice" disabled={disabled}>
      <legend>수신자에게 공개할 내용</legend>
      <label>
        <input
          type="radio"
          name="calendar-feed-disclosure"
          value="BUSY_ONLY"
          checked={value === 'BUSY_ONLY'}
          onChange={() => onChange('BUSY_ONLY')}
        />
        <span>
          <strong>시간만 (기본)</strong>
          일정 제목 없이 날짜·시각과 명시된 종료만 ‘바쁨’으로 공유합니다.
        </span>
      </label>
      <label>
        <input
          type="radio"
          name="calendar-feed-disclosure"
          value="TITLE"
          checked={value === 'TITLE'}
          onChange={() => onChange('TITLE')}
        />
        <span>
          <strong>제목과 시간</strong>
          선택한 일정의 승인된 제목까지 링크를 가진 사람에게 공개합니다.
        </span>
      </label>
      {value === 'TITLE' && (
        <p className="calendar-sharing-warning" role="alert">
          이미 받아 간 제목은 나중에 시간만 공유로 바꿔도 원격으로 회수할 수 없습니다.
        </p>
      )}
    </fieldset>
  );
}

export function CalendarFeedEventSelector({
  events,
  selectedEventIds,
  disabled,
  truncated,
  onToggle,
}: {
  events: CalendarEvent[];
  selectedEventIds: string[];
  disabled: boolean;
  truncated: boolean;
  onToggle: (eventId: string, selected: boolean) => void;
}) {
  return (
    <fieldset className="calendar-sharing-events" disabled={disabled || truncated}>
      <legend>공유할 일정 직접 선택</legend>
      <p>
        새 일정과 미래 일정은 자동으로 추가되지 않습니다. 아래 일정도 처음에는 모두 선택 해제되어
        있습니다.
      </p>
      {truncated && (
        <p className="calendar-sharing-warning" role="alert">
          선택 가능한 일정이 100개를 넘어 전체 범위를 확인할 수 없습니다. 안전을 위해 공유 생성과
          일정 추가를 중단했습니다.
        </p>
      )}
      {!truncated && events.length === 0 && (
        <p className="calendar-sharing-empty">공유할 수 있는 승인 일정이 없습니다.</p>
      )}
      {!truncated && events.map((event) => (
        <label className="calendar-sharing-event-choice" key={event.id}>
          <input
            type="checkbox"
            checked={selectedEventIds.includes(event.id)}
            onChange={(change) => onToggle(event.id, change.currentTarget.checked)}
          />
          <span>
            <strong>{event.title}</strong>
            {calendarSharingEventTimeLabel(event)}
          </span>
        </label>
      ))}
    </fieldset>
  );
}

export function CalendarFeedSecretPanel({
  oneTime,
  copied,
  secretHeadingRef,
  onCopy,
  onDone,
}: {
  oneTime: OneTimeUrl;
  copied: boolean;
  secretHeadingRef?: Ref<HTMLHeadingElement>;
  onCopy: () => void;
  onDone: () => void;
}) {
  const inputId = useId();
  const helpId = useId();
  return (
    <section className="calendar-sharing-secret" aria-labelledby={`${inputId}-heading`}>
      <h3 id={`${inputId}-heading`} ref={secretHeadingRef} tabIndex={-1}>
        {oneTime.action === 'CREATED'
          ? '새 구독 주소'
          : oneTime.action === 'PUBLISHED'
            ? '새 외부 공개 구독 주소'
            : '교체된 새 구독 주소'}
      </h3>
      <p className="calendar-sharing-warning" role="alert">
        이 주소는 비밀번호와 같습니다. 주소를 아는 사람은 로그인 없이 선택한 일정을 읽을 수
        있습니다. 닫으면 다시 표시할 수 없습니다.
      </p>
      {oneTime.publicationMode === 'LOCAL_ONLY' ? (
        <p className="calendar-sharing-edge-warning">
          현재 주소는 로컬·격리 검증용입니다. 외부 공유용 HTTPS edge는 아직 활성화되지 않았으므로
          외부 수신자에게 전달하지 마세요.
        </p>
      ) : (
        <p className="calendar-sharing-edge-warning">
          서버가 지정한 공개 HTTPS 일정 전용 주소입니다. 의도한 수신자에게만 전달하고, 이미 받은
          사본은 주소를 폐기해도 회수할 수 없다는 점을 확인하세요. Cloudflare가 query bearer와
          요청 metadata를 처리합니다.
        </p>
      )}
      <label htmlFor={inputId}>한 번만 표시되는 구독 주소</label>
      <input
        id={inputId}
        className="calendar-sharing-secret__value"
        value={oneTime.url}
        readOnly
        dir="ltr"
        spellCheck={false}
        autoComplete="off"
        aria-describedby={helpId}
        onFocus={(event) => event.currentTarget.select()}
      />
      <p id={helpId} className="calendar-sharing-secret__help">
        링크로 열지 않고 필요한 수신자에게만 안전하게 복사하세요. 클립보드와 이미 저장된 사본은
        앱이 회수하거나 지울 수 없습니다.
      </p>
      <div className="calendar-sharing-secret__actions">
        <button type="button" className="approve-button" onClick={onCopy}>
          구독 주소 복사
        </button>
        <button type="button" className="secondary-button" onClick={onDone}>
          확인하고 이 화면에서 지우기
        </button>
      </div>
      {copied && (
        <p className="calendar-sharing-status" role="status" aria-live="polite">
          구독 주소를 클립보드에 복사했습니다. 클립보드는 앱 밖에 남을 수 있습니다.
        </p>
      )}
    </section>
  );
}

function CalendarFeedCreateForm({
  draft,
  events,
  truncated,
  disabled,
  online,
  onChange,
  onSubmit,
  onCancel,
}: {
  draft: CalendarFeedDraft;
  events: CalendarEvent[];
  truncated: boolean;
  disabled: boolean;
  online: boolean;
  onChange: (draft: CalendarFeedDraft) => void;
  onSubmit: () => void;
  onCancel: () => void;
}) {
  const nameId = useId();
  const canSubmit = online && !disabled && !truncated && draft.displayName.trim().length > 0 &&
    draft.selectedEventIds.length > 0;
  return (
    <div className="calendar-sharing-form">
      <label htmlFor={nameId}>공유 대상 이름 (나만 봄)</label>
      <input
        id={nameId}
        value={draft.displayName}
        maxLength={80}
        disabled={disabled}
        autoComplete="off"
        onChange={(event) => onChange({ ...draft, displayName: event.currentTarget.value })}
      />
      <p className="calendar-sharing-help">
        이메일을 보내거나 초대하지 않습니다. 이 이름은 관리 화면에서만 사용됩니다.
      </p>
      <CalendarFeedDisclosureFields
        value={draft.disclosureMode}
        disabled={disabled}
        onChange={(disclosureMode) => onChange({ ...draft, disclosureMode })}
      />
      <CalendarFeedEventSelector
        events={events}
        selectedEventIds={draft.selectedEventIds}
        disabled={disabled}
        truncated={truncated}
        onToggle={(eventId, selected) => onChange({
          ...draft,
          selectedEventIds: toggleCalendarFeedEvent(
            draft.selectedEventIds,
            eventId,
            selected,
          ),
        })}
      />
      {!online && (
        <p className="calendar-sharing-warning" role="alert">
          일정 공유 설정은 오프라인에 저장하거나 나중에 전송하지 않습니다. 서버에 다시 연결해 주세요.
        </p>
      )}
      <div className="calendar-sharing-actions">
        <button type="button" className="approve-button" disabled={!canSubmit} onClick={onSubmit}>
          선택한 일정으로 공유 만들기
        </button>
        <button type="button" className="secondary-button" disabled={disabled} onClick={onCancel}>
          취소
        </button>
      </div>
    </div>
  );
}

export function EventCalendarSharingDialog({
  disabled,
  online,
  onProtectionChange,
  onClose,
}: DialogProps) {
  const [view, setView] = useState<View>('LIST');
  const [overview, setOverview] = useState(createUnavailableCalendarFeedOverview);
  const [detail, setDetail] = useState<CalendarFeedDetail | null>(null);
  const [draft, setDraft] = useState(createCalendarFeedDraft);
  const [editName, setEditName] = useState('');
  const [editDisclosure, setEditDisclosure] = useState<CalendarFeedDisclosureMode>('BUSY_ONLY');
  const [confirmCreateTitle, setConfirmCreateTitle] = useState(false);
  const [feedTransient, setFeedTransient] = useState(createCalendarFeedScopedTransientState);
  const [oneTime, setOneTime] = useState<OneTimeUrl | null>(null);
  const [copied, setCopied] = useState(false);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [operation, setOperation] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [retryAction, setRetryAction] = useState<RetryAction | null>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const secretHeadingRef = useRef<HTMLHeadingElement>(null);
  const createTitleConfirmationRef = useRef<HTMLDivElement>(null);
  const titleUpdateConfirmationRef = useRef<HTMLDivElement>(null);
  const removeConfirmationRef = useRef<HTMLDivElement>(null);
  const destructiveConfirmationRef = useRef<HTMLDivElement>(null);
  const externalPublicationConfirmationRef = useRef<HTMLDivElement>(null);
  const overviewAbortRef = useRef<AbortController | null>(null);
  const overviewGenerationRef = useRef(0);
  const detailAbortRef = useRef<AbortController | null>(null);
  const {
    publicationCapability,
    feeds,
    eligibleEvents,
    eligibleTruncated,
  } = overview;

  const createDirty = view === 'CREATE' && (
    draft.displayName.length > 0 ||
    draft.disclosureMode !== 'BUSY_ONLY' ||
    draft.selectedEventIds.length > 0
  );
  const editDirty = view === 'MANAGE' && detail !== null && (
    editName !== detail.displayName || editDisclosure !== detail.disclosureMode
  );
  const protectedState = isCalendarSharingProtected({
    dirty: createDirty || editDirty,
    oneTimeUrlVisible: oneTime !== null,
    retryContainsSecret: retryAction?.sensitive === true,
    operation,
  });
  const pending = operation !== null;
  const sensitiveRetryPending = retryAction?.sensitive === true;
  const scopedTransient = detail === null
    ? createCalendarFeedScopedTransientState()
    : calendarFeedScopedTransientFor(feedTransient, detail.id);
  const {
    selectedAddEventId,
    removeEntryId,
    confirmTitleUpdate,
    destructiveAction,
    confirmExternalPublication,
    externalPublicationConsentAccepted,
  } = scopedTransient;

  const resetFeedScopedTransient = useCallback((feedId: string | null = null) => {
    setFeedTransient(createCalendarFeedScopedTransientState(feedId));
  }, []);

  const clearRetryState = useCallback(() => {
    setRetryAction(null);
    setError(null);
  }, []);

  function updateFeedScopedTransient(update: Partial<CalendarFeedScopedTransientState>) {
    if (!detail) return;
    setFeedTransient((current) => ({
      ...calendarFeedScopedTransientFor(current, detail.id),
      ...update,
      feedId: detail.id,
    }));
  }

  useEffect(() => {
    onProtectionChange({ pending, protectedState });
  }, [onProtectionChange, pending, protectedState]);

  useEffect(() => () => {
    onProtectionChange({ pending: false, protectedState: false });
  }, [onProtectionChange]);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (!dialog.open) dialog.showModal();
    return () => {
      if (dialog.open) dialog.close();
    };
  }, []);

  useEffect(() => {
    const frame = window.requestAnimationFrame(() => {
      if (view === 'SECRET') secretHeadingRef.current?.focus({ preventScroll: true });
      else headingRef.current?.focus({ preventScroll: true });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [view]);

  useEffect(() => {
    const target = view === 'CREATE' && confirmCreateTitle
      ? createTitleConfirmationRef.current
      : view === 'MANAGE' && confirmTitleUpdate
        ? titleUpdateConfirmationRef.current
        : view === 'MANAGE' && removeEntryId !== null
          ? removeConfirmationRef.current
          : view === 'MANAGE' && confirmExternalPublication
            ? externalPublicationConfirmationRef.current
          : view === 'MANAGE' && destructiveAction !== null
            ? destructiveConfirmationRef.current
            : null;
    if (!target) return;
    const frame = window.requestAnimationFrame(() => target.focus({ preventScroll: true }));
    return () => window.cancelAnimationFrame(frame);
  }, [
    confirmCreateTitle,
    confirmExternalPublication,
    confirmTitleUpdate,
    destructiveAction,
    removeEntryId,
    view,
  ]);

  const loadOverview = useCallback(async () => {
    const generation = ++overviewGenerationRef.current;
    overviewAbortRef.current?.abort();
    const controller = new AbortController();
    overviewAbortRef.current = controller;
    setLoading(true);
    setError(null);
    setOverview(createUnavailableCalendarFeedOverview());
    try {
      const [capability, nextFeeds, eligible] = await Promise.all([
        api.calendarFeedPublicationCapability(controller.signal),
        api.calendarFeeds(controller.signal),
        api.calendarFeedEligibleEvents(controller.signal),
      ]);
      if (!canCommitCalendarFeedOverviewResponse(
        overviewGenerationRef.current,
        generation,
        controller.signal.aborted,
      )) return;
      setOverview({
        publicationCapability: capability,
        feeds: nextFeeds,
        eligibleEvents: eligible.items,
        eligibleTruncated: eligible.truncated,
      });
    } catch (caught) {
      if (!canCommitCalendarFeedOverviewResponse(
        overviewGenerationRef.current,
        generation,
        controller.signal.aborted,
      )) return;
      setOverview(createUnavailableCalendarFeedOverview());
      if (caught instanceof SessionScopeChangedError) {
        clearRetryState();
        resetFeedScopedTransient();
        return;
      }
      setError(errorMessage(caught));
    } finally {
      if (canCommitCalendarFeedOverviewResponse(
        overviewGenerationRef.current,
        generation,
        controller.signal.aborted,
      )) {
        overviewAbortRef.current = null;
        setLoading(false);
      }
    }
  }, [clearRetryState, resetFeedScopedTransient]);

  useEffect(() => {
    void loadOverview();
    return () => {
      overviewGenerationRef.current += 1;
      overviewAbortRef.current?.abort();
      detailAbortRef.current?.abort();
    };
  }, [loadOverview]);

  function updateLocalDetail(next: CalendarFeedDetail) {
    setDetail(next);
    setEditName(next.displayName);
    setEditDisclosure(next.disclosureMode);
    setOverview((current) => ({
      ...current,
      feeds: replaceCalendarFeedSummary(current.feeds, detailSummary(next)),
    }));
  }

  async function executeMutation(
    operationName: string,
    request: MutationRequest,
    onSuccess: MutationSuccess,
    retryLabel: string,
    sensitive: boolean,
  ): Promise<void> {
    setOperation(operationName);
    setError(null);
    setNotice(null);
    setRetryAction(null);
    try {
      const next = await request();
      onSuccess(next);
    } catch (caught) {
      if (caught instanceof SessionScopeChangedError) {
        clearRetryState();
        resetFeedScopedTransient();
        return;
      }
      setError(errorMessage(caught));
      setRetryAction({
        label: retryLabel,
        sensitive,
        run: () => void executeMutation(
          operationName,
          request,
          onSuccess,
          retryLabel,
          sensitive,
        ),
      });
    } finally {
      setOperation(null);
    }
  }

  function clearOneTimeUrl() {
    setOneTime(null);
    setCopied(false);
    setRetryAction(null);
  }

  function confirmSensitiveRetryDiscard(): boolean {
    const discardConfirmed = sensitiveRetryPending && window.confirm(
      '서버가 주소 생성·교체를 이미 완료했을 수 있습니다. 이 재시도 정보를 버리면 같은 비밀 주소와 요청 키로 결과를 복구할 수 없습니다. 그래도 버릴까요?',
    );
    return canDiscardCalendarFeedSensitiveRetry(sensitiveRetryPending, discardConfirmed);
  }

  function requestClose() {
    if (pending) return;
    if (
      oneTime !== null &&
      !window.confirm('이 주소는 닫으면 다시 표시할 수 없습니다. 화면에서 지울까요?')
    ) return;
    if (!confirmSensitiveRetryDiscard()) return;
    if (
      (createDirty || editDirty) &&
      !window.confirm('저장되지 않은 공유 설정 또는 재시도용 주소가 사라집니다. 닫을까요?')
    ) return;
    clearOneTimeUrl();
    resetFeedScopedTransient();
    onClose();
  }

  function openCreate() {
    if (publicationCapability === null) {
      setError('공유 주소 권한을 확인하지 못했습니다. 공유 설정을 다시 불러와 주세요.');
      return;
    }
    if (!confirmSensitiveRetryDiscard()) return;
    setDraft(createCalendarFeedDraft());
    setConfirmCreateTitle(false);
    setError(null);
    setNotice(null);
    setRetryAction(null);
    resetFeedScopedTransient();
    setView('CREATE');
  }

  async function openManage(summary: CalendarFeedSummary, retryDiscardConfirmed = false) {
    if (!retryDiscardConfirmed && !confirmSensitiveRetryDiscard()) return;
    detailAbortRef.current?.abort();
    const controller = new AbortController();
    detailAbortRef.current = controller;
    resetFeedScopedTransient(summary.id);
    setView('MANAGE');
    setDetail(null);
    setDetailLoading(true);
    setError(null);
    setNotice(null);
    setRetryAction(null);
    try {
      const next = await api.calendarFeed(summary.id, controller.signal);
      if (controller.signal.aborted) return;
      updateLocalDetail(next);
    } catch (caught) {
      if (controller.signal.aborted) return;
      if (caught instanceof SessionScopeChangedError) {
        clearRetryState();
        resetFeedScopedTransient();
        return;
      }
      setError(errorMessage(caught));
    } finally {
      if (!controller.signal.aborted) setDetailLoading(false);
      if (detailAbortRef.current === controller) detailAbortRef.current = null;
    }
  }

  function discardSensitiveRetry() {
    if (!sensitiveRetryPending || !confirmSensitiveRetryDiscard()) return;
    clearRetryState();
    setNotice('비밀 주소 재시도 정보를 버렸습니다. 서버의 최신 공유 상태를 다시 불러옵니다.');
    if (view === 'MANAGE' && detail) {
      void openManage(detailSummary(detail), true);
      return;
    }
    setDraft(createCalendarFeedDraft());
    setConfirmCreateTitle(false);
    resetFeedScopedTransient();
    setView('LIST');
    void loadOverview();
  }

  function createFeed(confirmedTitle = false) {
    if (
      publicationCapability === null || disabled || !online || sensitiveRetryPending ||
      eligibleTruncated ||
      draft.displayName.trim().length === 0 ||
      draft.selectedEventIds.length === 0
    ) return;
    if (requiresCalendarFeedTitleConfirmation(draft.disclosureMode, confirmedTitle)) {
      setConfirmCreateTitle(true);
      return;
    }
    let secret: string;
    try {
      secret = generateCalendarFeedSecret();
    } catch {
      setError('안전한 공유 주소를 만들지 못했습니다. 브라우저 보안 기능을 확인해 주세요.');
      return;
    }
    let subscription: CalendarFeedSubscriptionPreparation | null;
    try {
      subscription = prepareCreatedCalendarFeedSubscription(
        publicationCapability,
        window.location.origin,
        secret,
      );
    } catch {
      setError('공유 주소 권한을 검증하지 못했습니다. 공유 설정을 다시 불러와 주세요.');
      return;
    }
    const body: CreateCalendarFeedRequest = {
      displayName: draft.displayName.trim(),
      disclosureMode: draft.disclosureMode,
      eventIds: [...draft.selectedEventIds],
      bearerSecret: secret,
    };
    const idempotencyKey = crypto.randomUUID();
    void executeMutation(
      'CREATE',
      () => api.createCalendarFeed(body, idempotencyKey),
      (next) => {
        if (next.publicationScope !== 'LOCAL_ONLY') {
          throw new Error('New calendar feeds must remain local-only');
        }
        updateLocalDetail(next);
        setDraft(createCalendarFeedDraft());
        setConfirmCreateTitle(false);
        if (subscription === null) {
          clearOneTimeUrl();
          resetFeedScopedTransient(next.id);
          setNotice(
            '공유를 로컬 상태로 만들었습니다. 외부 공개는 아래에서 별도 동의 후 시작하세요.',
          );
          setView('MANAGE');
          return;
        }
        setOneTime({
          action: 'CREATED',
          displayName: next.displayName,
          publicationMode: subscription.publicationMode,
          url: subscription.url,
        });
        setCopied(false);
        setView('SECRET');
      },
      '같은 공유 생성 다시 시도',
      true,
    );
  }

  function saveFeedUpdate(confirmedTitle = false) {
    if (
      !detail || feedTransient.feedId !== detail.id || detail.status !== 'ACTIVE' || disabled ||
      !online || sensitiveRetryPending ||
      (detail.publicationScope === 'PUBLIC_HTTPS' && editDisclosure !== detail.disclosureMode)
    ) return;
    if (
      detail.disclosureMode === 'BUSY_ONLY' && editDisclosure === 'TITLE' && !confirmedTitle
    ) {
      updateFeedScopedTransient({
        confirmTitleUpdate: true,
        confirmExternalPublication: false,
        externalPublicationConsentAccepted: false,
        removeEntryId: null,
        destructiveAction: null,
      });
      return;
    }
    const body: UpdateCalendarFeedRequest = {
      displayName: editName.trim(),
      disclosureMode: editDisclosure,
      expectedVersion: detail.version,
    };
    if (body.displayName.length === 0) return;
    const idempotencyKey = crypto.randomUUID();
    void executeMutation(
      'UPDATE',
      () => api.updateCalendarFeed(detail.id, body, idempotencyKey),
      (next) => {
        updateLocalDetail(next);
        updateFeedScopedTransient({ confirmTitleUpdate: false });
        setNotice('공유 이름과 공개 범위를 저장했습니다. 외부 캘린더 반영 시점은 보장하지 않습니다.');
      },
      '같은 공유 설정 다시 저장',
      false,
    );
  }

  function rotateFeed() {
    if (
      !detail || feedTransient.feedId !== detail.id || detail.status !== 'ACTIVE' || disabled ||
      !online || sensitiveRetryPending ||
      !canRotateCalendarFeedSubscription(publicationCapability, detail)
    ) return;
    let secret: string;
    try {
      secret = generateCalendarFeedSecret();
    } catch {
      setError('안전한 공유 주소를 만들지 못했습니다. 브라우저 보안 기능을 확인해 주세요.');
      return;
    }
    let subscription: CalendarFeedSubscriptionPreparation;
    try {
      subscription = prepareCalendarFeedSubscription(
        publicationCapability,
        window.location.origin,
        secret,
        detail.publicationScope,
      );
    } catch {
      setError('공유 주소 권한을 검증하지 못했습니다. 공유 설정을 다시 불러와 주세요.');
      return;
    }
    const body: RotateCalendarFeedRequest = {
      bearerSecret: secret,
      expectedVersion: detail.version,
    };
    const idempotencyKey = crypto.randomUUID();
    void executeMutation(
      'ROTATE',
      () => api.rotateCalendarFeed(detail.id, body, idempotencyKey),
      (next) => {
        if (next.publicationScope !== detail.publicationScope) {
          throw new Error('Calendar feed publication scope changed during rotation');
        }
        updateLocalDetail(next);
        updateFeedScopedTransient({ destructiveAction: null });
        setOneTime({
          action: 'ROTATED',
          displayName: next.displayName,
          publicationMode: subscription.publicationMode,
          url: subscription.url,
        });
        setCopied(false);
        setView('SECRET');
      },
      '같은 주소 교체 다시 시도',
      true,
    );
  }

  function enableExternalPublication() {
    if (
      !detail || feedTransient.feedId !== detail.id || detail.status !== 'ACTIVE' ||
      detail.publicationScope !== 'LOCAL_ONLY' || disabled || !online || sensitiveRetryPending ||
      editDirty || !confirmExternalPublication || !externalPublicationConsentAccepted ||
      publicationCapability?.mode !== 'PUBLIC_HTTPS' ||
      publicationCapability.consentPolicyVersion !==
        CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION
    ) return;

    let secret: string;
    try {
      secret = generateCalendarFeedSecret();
    } catch {
      setError('안전한 공개 구독 주소를 만들지 못했습니다. 브라우저 보안 기능을 확인해 주세요.');
      return;
    }

    let subscription: CalendarFeedSubscriptionPreparation;
    try {
      subscription = prepareCalendarFeedSubscription(
        publicationCapability,
        window.location.origin,
        secret,
        'PUBLIC_HTTPS',
      );
    } catch {
      setError('외부 공개 정책과 주소 권한을 검증하지 못했습니다. 공유 설정을 다시 불러와 주세요.');
      return;
    }

    const body: EnableExternalCalendarFeedPublicationRequest = {
      expectedVersion: detail.version,
      bearerSecret: secret,
      consentPolicyVersion: CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION,
    };
    const idempotencyKey = crypto.randomUUID();
    void executeMutation(
      'ENABLE_EXTERNAL_PUBLICATION',
      () => api.enableExternalCalendarFeedPublication(detail.id, body, idempotencyKey),
      (next) => {
        if (
          next.publicationScope !== 'PUBLIC_HTTPS' ||
          next.publicConsentPolicyVersion !== CALENDAR_FEED_PUBLIC_CONSENT_POLICY_VERSION ||
          next.publicConsentGrantedAt === null
        ) {
          throw new Error('External calendar publication consent was not committed');
        }
        updateLocalDetail(next);
        updateFeedScopedTransient({
          confirmExternalPublication: false,
          externalPublicationConsentAccepted: false,
        });
        setOneTime({
          action: 'PUBLISHED',
          displayName: next.displayName,
          publicationMode: subscription.publicationMode,
          url: subscription.url,
        });
        setCopied(false);
        setView('SECRET');
      },
      '같은 외부 공개 시작 다시 시도',
      true,
    );
  }

  function revokeFeed() {
    if (
      !detail || feedTransient.feedId !== detail.id || detail.status !== 'ACTIVE' || disabled ||
      !online || sensitiveRetryPending
    ) return;
    const body: VersionedCalendarFeedRequest = { expectedVersion: detail.version };
    const idempotencyKey = crypto.randomUUID();
    void executeMutation(
      'REVOKE',
      () => api.revokeCalendarFeed(detail.id, body, idempotencyKey),
      (next) => {
        updateLocalDetail(next);
        updateFeedScopedTransient({ destructiveAction: null });
        setNotice(
          detail.publicationScope === 'PUBLIC_HTTPS'
            ? '외부 공개를 중지하고 공유 주소를 영구 폐기했습니다. 이미 저장되거나 캐시된 사본은 회수되지 않습니다.'
            : '공유 주소를 폐기했습니다. 이미 저장되거나 캐시된 사본은 회수되지 않습니다.',
        );
      },
      '같은 공유 폐기 다시 시도',
      false,
    );
  }

  function addEvent() {
    if (
      !detail || feedTransient.feedId !== detail.id || detail.status !== 'ACTIVE' ||
      !selectedAddEventId || eligibleTruncated || disabled || !online || sensitiveRetryPending
    ) return;
    const body = { eventId: selectedAddEventId, expectedVersion: detail.version };
    const idempotencyKey = crypto.randomUUID();
    void executeMutation(
      'ADD_EVENT',
      () => api.addCalendarFeedEvent(detail.id, body, idempotencyKey),
      (next) => {
        updateLocalDetail(next);
        updateFeedScopedTransient({ selectedAddEventId: '' });
        setNotice('선택한 일정을 공유에 추가했습니다.');
      },
      '같은 일정 추가 다시 시도',
      false,
    );
  }

  function removeEvent(entry: CalendarFeedEntry) {
    if (
      !detail || feedTransient.feedId !== detail.id || detail.status !== 'ACTIVE' || disabled ||
      !online || sensitiveRetryPending
    ) return;
    const body: VersionedCalendarFeedRequest = { expectedVersion: detail.version };
    const idempotencyKey = crypto.randomUUID();
    void executeMutation(
      'REMOVE_EVENT',
      () => api.removeCalendarFeedEvent(detail.id, entry.id, body, idempotencyKey),
      (next) => {
        updateLocalDetail(next);
        updateFeedScopedTransient({ removeEntryId: null });
        setNotice('일정을 공유에서 제거했습니다. 수신자 캘린더의 캐시 반영 시점은 보장하지 않습니다.');
      },
      '같은 일정 제거 다시 시도',
      false,
    );
  }

  async function copyOneTimeUrl() {
    if (!oneTime) return;
    try {
      if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable');
      await navigator.clipboard.writeText(oneTime.url);
      setCopied(true);
      setError(null);
    } catch {
      setCopied(false);
      setError('클립보드에 복사하지 못했습니다. 주소 입력란을 직접 선택해 복사해 주세요.');
    }
  }

  function finishSecretView() {
    clearOneTimeUrl();
    resetFeedScopedTransient();
    setDetail(null);
    setView('LIST');
    setNotice('구독 주소를 이 화면에서 지웠습니다. 필요하면 주소를 교체해야 새 주소를 받을 수 있습니다.');
  }

  const activeEntries = detail?.entries.filter((entry) => entry.state === 'ACTIVE') ?? [];
  const cancelledEntries = detail?.entries.filter((entry) => entry.state === 'CANCELLED') ?? [];
  const activeEventIds = new Set(activeEntries.flatMap((entry) =>
    entry.eventId === null ? [] : [entry.eventId]));
  const addableEvents = eligibleEvents.filter((event) => !activeEventIds.has(event.id));
  const dialogTitle = view === 'CREATE'
    ? '새 일정 공유'
    : view === 'MANAGE'
      ? '일정 공유 설정'
      : view === 'SECRET'
        ? '구독 주소 확인'
        : '일정 공유 관리';

  return (
    <dialog
      ref={dialogRef}
      className="graph-detail-dialog calendar-sharing-dialog"
      aria-labelledby="calendar-sharing-title"
      aria-describedby="calendar-sharing-boundary"
      aria-busy={loading || detailLoading || pending}
      onCancel={(event) => {
        event.preventDefault();
        requestClose();
      }}
    >
      <section className="graph-detail-drawer calendar-sharing-drawer">
        <header className="graph-detail-drawer__header">
          <div>
            <span className="eyebrow">RECIPIENT FEED · SOURCE ONLY</span>
            <h2 id="calendar-sharing-title" ref={headingRef} tabIndex={-1}>{dialogTitle}</h2>
          </div>
          <button
            type="button"
            className="graph-detail-drawer__close"
            aria-label="일정 공유 관리 창 닫기"
            disabled={pending}
            onClick={requestClose}
          >
            <span aria-hidden="true">×</span>
          </button>
        </header>

        <p id="calendar-sharing-boundary" className="graph-detail-drawer__scope">
          사용자가 직접 선택한 승인 일정만 읽기 전용으로 공유합니다. 새 일정, 원문 메모, 할 일,
          태그, 관계와 AI 정보는 자동으로 추가하지 않습니다.
        </p>

        {error && (
          <div className="calendar-sharing-error" role="alert">
            <p>{error}</p>
            {retryAction && (
              <button type="button" className="secondary-button" disabled={pending} onClick={retryAction.run}>
                {retryAction.label}
              </button>
            )}
            {retryAction?.sensitive === true && (
              <button
                type="button"
                className="danger-button"
                disabled={pending}
                onClick={discardSensitiveRetry}
              >
                재시도 정보 버리고 최신 상태 확인
              </button>
            )}
          </div>
        )}
        {notice && (
          <p className="calendar-sharing-status" role="status" aria-live="polite">{notice}</p>
        )}

        {view === 'SECRET' && oneTime && (
          <CalendarFeedSecretPanel
            oneTime={oneTime}
            copied={copied}
            secretHeadingRef={secretHeadingRef}
            onCopy={() => void copyOneTimeUrl()}
            onDone={finishSecretView}
          />
        )}

        {view === 'LIST' && (
          <>
            {publicationCapability === null ? (
              <p className="calendar-sharing-warning" role={loading ? 'status' : 'alert'}>
                {loading
                  ? '공개 구독 주소 권한을 확인하는 중입니다. 확인 전에는 공유를 만들거나 주소를 교체하지 않습니다.'
                  : '공개 구독 주소 권한을 확인하지 못했습니다. 다시 불러온 뒤 공유를 만들어 주세요.'}
              </p>
            ) : publicationCapability.mode === 'LOCAL_ONLY' ? (
              <p className="calendar-sharing-caveat">
                구독 주소를 가진 사람은 로그인 없이 계속 읽을 수 있습니다. 현재는 로컬·격리
                주소만 만들며 외부 공유 edge, 외부 캘린더 갱신 주기와 알람 전달을 활성화하지 않습니다.
              </p>
            ) : (
              <p className="calendar-sharing-caveat">
                구독 주소를 가진 사람은 로그인 없이 선택한 일정을 읽을 수 있습니다. 주소의 공개
                origin은 서버 설정만 사용하며 외부 캘린더 갱신 주기나 알람 전달은 보장하지 않습니다.
              </p>
            )}
            {loading && <p className="calendar-sharing-status" role="status">공유 설정을 불러오는 중…</p>}
            {!loading && !online && (
              <p className="calendar-sharing-warning" role="alert">
                오프라인에서는 공유를 만들거나 변경하지 않습니다. 서버에 다시 연결해 주세요.
              </p>
            )}
            {!loading && eligibleTruncated && (
              <p className="calendar-sharing-warning" role="alert">
                선택 가능한 일정이 100개를 넘어 생성과 일정 추가를 중단했습니다.
              </p>
            )}
            {!loading && (
              <button
                type="button"
                className="approve-button calendar-sharing-create"
                disabled={
                  publicationCapability === null || disabled || !online || pending ||
                  eligibleTruncated || eligibleEvents.length === 0
                }
                onClick={openCreate}
              >
                새 공유 만들기
              </button>
            )}
            {!loading && feeds.length === 0 && (
              <p className="calendar-sharing-empty">만든 일정 공유가 없습니다.</p>
            )}
            {!loading && feeds.length > 0 && (
              <div className="calendar-sharing-feed-list">
                {feeds.map((feed) => (
                  <article className="calendar-sharing-feed" key={feed.id}>
                    <div>
                      <strong>{feed.displayName}</strong>
                      <span>{calendarFeedDisclosureLabel(feed.disclosureMode)}</span>
                      <span>
                        {feed.publicationScope === 'PUBLIC_HTTPS'
                          ? '외부 공개 중'
                          : '로컬·격리 전용'}
                      </span>
                      <span>선택 일정 {feed.eventCount}개 · {feed.status === 'ACTIVE' ? '활성' : '폐기됨'}</span>
                    </div>
                    <button
                      type="button"
                      className="secondary-button"
                      disabled={disabled || pending}
                      onClick={() => void openManage(feed)}
                    >
                      설정 보기
                    </button>
                  </article>
                ))}
              </div>
            )}
          </>
        )}

        {view === 'CREATE' && (
          <>
          <CalendarFeedCreateForm
            draft={draft}
            events={eligibleEvents}
            truncated={eligibleTruncated}
            disabled={
              publicationCapability === null || disabled || pending || sensitiveRetryPending
            }
            online={online}
            onChange={(next) => {
              setDraft(next);
              setConfirmCreateTitle(false);
              setError(null);
            }}
            onSubmit={() => createFeed(false)}
            onCancel={() => {
              if (createDirty && !window.confirm('입력한 공유 설정을 버릴까요?')) return;
              setDraft(createCalendarFeedDraft());
              setConfirmCreateTitle(false);
              setView('LIST');
            }}
          />
          {confirmCreateTitle && (
            <CalendarSharingConfirmation
              label="제목 공개 공유 생성 확인"
              confirmationRef={createTitleConfirmationRef}
            >
              <p>
                선택한 일정 제목이 링크를 가진 사람에게 공개됩니다. 이미 전달된 제목은 나중에
                BUSY_ONLY로 바꿔도 회수할 수 없습니다.
              </p>
              <div>
                <button
                  type="button"
                  className="danger-button"
                  disabled={
                    publicationCapability === null || disabled || pending ||
                    sensitiveRetryPending || !online
                  }
                  onClick={() => createFeed(true)}
                >
                  제목 공개 확인·공유 만들기
                </button>
                <button
                  type="button"
                  className="secondary-button"
                  disabled={pending || sensitiveRetryPending}
                  onClick={() => setConfirmCreateTitle(false)}
                >
                  취소
                </button>
              </div>
            </CalendarSharingConfirmation>
          )}
          </>
        )}

        {view === 'MANAGE' && (
          <>
            {detailLoading && <p className="calendar-sharing-status" role="status">공유 설정을 불러오는 중…</p>}
            {!detailLoading && detail && (
              <div className="calendar-sharing-manage">
                <button
                  type="button"
                  className="secondary-button calendar-sharing-back"
                  disabled={pending}
                  onClick={() => {
                    if (!confirmSensitiveRetryDiscard()) return;
                    if (editDirty && !window.confirm('저장하지 않은 공유 설정을 버릴까요?')) return;
                    clearRetryState();
                    resetFeedScopedTransient();
                    setDetail(null);
                    setView('LIST');
                    void loadOverview();
                  }}
                >
                  공유 목록으로
                </button>

                <div className="calendar-sharing-form">
                  <label htmlFor="calendar-sharing-edit-name">공유 대상 이름 (나만 봄)</label>
                  <input
                    id="calendar-sharing-edit-name"
                    value={editName}
                    maxLength={80}
                    disabled={disabled || pending || sensitiveRetryPending || detail.status !== 'ACTIVE'}
                    autoComplete="off"
                    onChange={(event) => {
                      setEditName(event.currentTarget.value);
                      updateFeedScopedTransient({ confirmTitleUpdate: false });
                    }}
                  />
                  <CalendarFeedDisclosureFields
                    value={editDisclosure}
                    disabled={
                      disabled || pending || sensitiveRetryPending || detail.status !== 'ACTIVE' ||
                      detail.publicationScope === 'PUBLIC_HTTPS'
                    }
                    onChange={(value) => {
                      setEditDisclosure(value);
                      updateFeedScopedTransient({
                        confirmTitleUpdate: false,
                        confirmExternalPublication: false,
                        externalPublicationConsentAccepted: false,
                      });
                    }}
                  />
                  {detail.publicationScope === 'PUBLIC_HTTPS' && detail.status === 'ACTIVE' && (
                    <p className="calendar-sharing-warning" role="alert">
                      외부 공개 중에는 공개 범위를 변경할 수 없습니다. 공유를 폐기한 뒤 원하는 공개
                      범위로 새 공유를 만들어 주세요.
                    </p>
                  )}
                  {confirmTitleUpdate && (
                    <CalendarSharingConfirmation
                      label="일정 제목 공개 확인"
                      confirmationRef={titleUpdateConfirmationRef}
                    >
                      <p>
                        다음 구독 새로고침부터 선택한 일정 제목이 공개됩니다. 이미 전달된 제목은 회수할
                        수 없습니다.
                      </p>
                      <div>
                        <button type="button" className="danger-button" disabled={sensitiveRetryPending} onClick={() => saveFeedUpdate(true)}>
                          제목 공개 확인·저장
                        </button>
                        <button type="button" className="secondary-button" onClick={() => updateFeedScopedTransient({ confirmTitleUpdate: false })}>
                          취소
                        </button>
                      </div>
                    </CalendarSharingConfirmation>
                  )}
                  {!confirmTitleUpdate && detail.status === 'ACTIVE' && (
                    <button
                      type="button"
                      className="approve-button"
                      disabled={disabled || pending || sensitiveRetryPending || !online || !editDirty || editName.trim().length === 0}
                      onClick={() => saveFeedUpdate(false)}
                    >
                      {detail.publicationScope === 'PUBLIC_HTTPS'
                        ? '공유 대상 이름 저장'
                        : '이름·공개 범위 저장'}
                    </button>
                  )}
                </div>

                {detail.status === 'ACTIVE' && detail.publicationScope === 'LOCAL_ONLY' && (
                  <section className="calendar-sharing-publication">
                    {publicationCapability?.mode !== 'PUBLIC_HTTPS' ? (
                      <p className="calendar-sharing-caveat">
                        서버의 외부 일정 전용 HTTPS 경로와 동의 정책이 준비되지 않아 이 공유는
                        로컬·격리 전용으로 유지됩니다.
                      </p>
                    ) : !confirmExternalPublication ? (
                      <>
                        <p className="calendar-sharing-caveat">
                          이 로컬 공유 주소는 공개 배포에서 제공되지 않습니다. 외부 공개를
                          시작해 새 HTTPS 주소를 받거나 공유를 폐기하세요.
                        </p>
                        <button
                          type="button"
                          className="danger-button"
                          disabled={disabled || pending || sensitiveRetryPending || !online || editDirty}
                          onClick={() => updateFeedScopedTransient({
                            confirmExternalPublication: true,
                            externalPublicationConsentAccepted: false,
                            confirmTitleUpdate: false,
                            removeEntryId: null,
                            destructiveAction: null,
                          })}
                        >
                          외부 공개 시작
                        </button>
                      </>
                    ) : (
                      <ExternalCalendarFeedPublicationConfirmation
                        disclosureMode={detail.disclosureMode}
                        accepted={externalPublicationConsentAccepted}
                        disabled={
                          disabled || pending || sensitiveRetryPending || !online || editDirty
                        }
                        confirmationRef={externalPublicationConfirmationRef}
                        onAcceptedChange={(accepted) => updateFeedScopedTransient({
                          externalPublicationConsentAccepted: accepted,
                        })}
                        onConfirm={enableExternalPublication}
                        onCancel={() => updateFeedScopedTransient({
                          confirmExternalPublication: false,
                          externalPublicationConsentAccepted: false,
                        })}
                      />
                    )}
                  </section>
                )}

                {detail.status === 'ACTIVE' && detail.publicationScope === 'PUBLIC_HTTPS' && (
                  <p className="calendar-sharing-warning" role="status">
                    외부 공개 중입니다. 주소를 아는 사람은 로그인 없이 선택한 일정을 읽을 수
                    있습니다. 중지하려면 아래에서 공유를 영구 폐기해야 합니다.
                  </p>
                )}

                <section className="calendar-sharing-members" aria-labelledby="calendar-sharing-members-title">
                  <h3 id="calendar-sharing-members-title">현재 공유 일정</h3>
                  {activeEntries.length === 0 && <p className="calendar-sharing-empty">현재 공유 중인 일정이 없습니다.</p>}
                  {activeEntries.map((entry) => (
                    <article className="calendar-sharing-member" key={entry.id}>
                      <div>
                        <strong>{entry.title}</strong>
                        <span>{entryTimeLabel(entry)}</span>
                      </div>
                      {detail.status === 'ACTIVE' && removeEntryId !== entry.id && (
                        <button
                          type="button"
                          className="danger-button"
                          disabled={disabled || pending || sensitiveRetryPending || !online}
                          onClick={() => updateFeedScopedTransient({
                            removeEntryId: entry.id,
                            confirmTitleUpdate: false,
                            confirmExternalPublication: false,
                            externalPublicationConsentAccepted: false,
                            destructiveAction: null,
                          })}
                        >
                          공유에서 제거
                        </button>
                      )}
                      {removeEntryId === entry.id && (
                        <CalendarSharingConfirmation
                          label={`${entry.title} 공유 제거 확인`}
                          confirmationRef={removeConfirmationRef}
                        >
                          <p>다음 새로고침부터 제외하지만 수신자의 저장·캐시 사본은 즉시 회수되지 않을 수 있습니다.</p>
                          <div>
                            <button type="button" className="danger-button" onClick={() => removeEvent(entry)}>
                              제거 확인
                            </button>
                            <button type="button" className="secondary-button" onClick={() => updateFeedScopedTransient({ removeEntryId: null })}>
                              취소
                            </button>
                          </div>
                        </CalendarSharingConfirmation>
                      )}
                    </article>
                  ))}
                </section>

                {detail.status === 'ACTIVE' && (
                  <fieldset className="calendar-sharing-add" disabled={disabled || pending || sensitiveRetryPending || !online || eligibleTruncated}>
                    <legend>일정 하나 직접 추가</legend>
                    {eligibleTruncated && (
                      <p className="calendar-sharing-warning" role="alert">
                        전체 일정 범위를 확인할 수 없어 추가를 중단했습니다.
                      </p>
                    )}
                    {!eligibleTruncated && addableEvents.length === 0 && (
                      <p className="calendar-sharing-empty">추가할 수 있는 다른 승인 일정이 없습니다.</p>
                    )}
                    {!eligibleTruncated && addableEvents.map((event) => (
                      <label className="calendar-sharing-event-choice" key={event.id}>
                        <input
                          type="radio"
                          name="calendar-sharing-add-event"
                          value={event.id}
                          checked={selectedAddEventId === event.id}
                          onChange={() => updateFeedScopedTransient({ selectedAddEventId: event.id })}
                        />
                        <span>
                          <strong>{event.title}</strong>
                          {calendarSharingEventTimeLabel(event)}
                        </span>
                      </label>
                    ))}
                    <button
                      type="button"
                      className="secondary-button"
                      disabled={!selectedAddEventId || eligibleTruncated}
                      onClick={addEvent}
                    >
                      선택 일정 추가
                    </button>
                  </fieldset>
                )}

                {cancelledEntries.length > 0 && (
                  <details className="calendar-sharing-cancelled">
                    <summary>제거·취소 동기화 기록 {cancelledEntries.length}개</summary>
                    <p>같은 공개 UID의 제거 동기화를 위해 시간 정보만 보존하며 제목은 표시하지 않습니다.</p>
                    {cancelledEntries.map((entry) => (
                      <div key={entry.id}>제거된 일정 · {entryTimeLabel(entry)} · sequence {entry.sequence}</div>
                    ))}
                  </details>
                )}

                {detail.status === 'ACTIVE' && destructiveAction === null && (
                  <div className="calendar-sharing-danger-actions">
                    {canRotateCalendarFeedSubscription(publicationCapability, detail) && (
                      <button
                        type="button"
                        className="secondary-button"
                        disabled={disabled || pending || sensitiveRetryPending || !online}
                        onClick={() => updateFeedScopedTransient({
                          destructiveAction: 'ROTATE',
                          confirmTitleUpdate: false,
                          confirmExternalPublication: false,
                          externalPublicationConsentAccepted: false,
                          removeEntryId: null,
                        })}
                      >
                        구독 주소 교체
                      </button>
                    )}
                    <button
                      type="button"
                      className="danger-button"
                      disabled={disabled || pending || sensitiveRetryPending || !online}
                      onClick={() => updateFeedScopedTransient({
                        destructiveAction: 'REVOKE',
                        confirmTitleUpdate: false,
                        confirmExternalPublication: false,
                        externalPublicationConsentAccepted: false,
                        removeEntryId: null,
                      })}
                    >
                      공유 폐기
                    </button>
                  </div>
                )}
                {destructiveAction === 'ROTATE' &&
                  canRotateCalendarFeedSubscription(publicationCapability, detail) && (
                  <CalendarSharingConfirmation
                    label="구독 주소 교체 확인"
                    confirmationRef={destructiveConfirmationRef}
                  >
                    <p>
                      기존 주소는 즉시 무효화되고 새 주소는 한 번만 표시됩니다. 수신자에게 새 주소를
                      다시 전달해야 하며 기존 캐시는 회수할 수 없습니다.
                    </p>
                    <div>
                      <button
                        type="button"
                        className="danger-button"
                        disabled={
                          publicationCapability === null || disabled || pending ||
                          sensitiveRetryPending || !online
                        }
                        onClick={rotateFeed}
                      >
                        주소 교체 확인
                      </button>
                      <button type="button" className="secondary-button" onClick={() => updateFeedScopedTransient({ destructiveAction: null })}>취소</button>
                    </div>
                  </CalendarSharingConfirmation>
                )}
                {destructiveAction === 'REVOKE' && (
                  <CalendarSharingConfirmation
                    label="일정 공유 폐기 확인"
                    confirmationRef={destructiveConfirmationRef}
                  >
                    <p>
                      {detail.publicationScope === 'PUBLIC_HTTPS'
                        ? '외부 공개가 즉시 중지되고 이 공유는 영구 폐기됩니다. '
                        : '이 공유는 복구할 수 없습니다. '}
                      canonical 일정은 삭제되지 않지만 이미 저장·캐시된 사본은 회수할 수 없습니다.
                    </p>
                    <div>
                      <button type="button" className="danger-button" onClick={revokeFeed}>공유 폐기 확인</button>
                      <button type="button" className="secondary-button" onClick={() => updateFeedScopedTransient({ destructiveAction: null })}>취소</button>
                    </div>
                  </CalendarSharingConfirmation>
                )}

                {detail.status === 'REVOKED' && (
                  <p className="calendar-sharing-warning" role="status">
                    이 공유는 폐기되었습니다. 주소를 다시 활성화할 수 없으며 canonical 일정은 그대로
                    보존됩니다.
                  </p>
                )}
              </div>
            )}
          </>
        )}
      </section>
    </dialog>
  );
}

export function EventCalendarSharing({ disabled, online, onProtectionChange }: Props) {
  const [open, setOpen] = useState(false);
  const openerRef = useRef<HTMLButtonElement>(null);

  function closeDialog() {
    setOpen(false);
    window.requestAnimationFrame(() => openerRef.current?.focus({ preventScroll: true }));
  }

  return (
    <div className="calendar-sharing-launcher">
      <button
        ref={openerRef}
        type="button"
        className="secondary-button"
        disabled={disabled || !online}
        onClick={() => setOpen(true)}
      >
        일정 공유 관리
      </button>
      {!online && <span>온라인에서만 관리</span>}
      {open && (
        <EventCalendarSharingDialog
          disabled={disabled}
          online={online}
          onProtectionChange={onProtectionChange}
          onClose={closeDialog}
        />
      )}
    </div>
  );
}
