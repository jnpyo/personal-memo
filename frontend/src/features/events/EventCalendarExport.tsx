import { useCallback, useEffect, useRef, useState, type Ref } from 'react';
import {
  api,
  SessionScopeChangedError,
} from '../../shared/api/client';
import { errorMessage } from '../../shared/api/errors';

export const EVENT_CALENDAR_FILENAME = 'personal-memo-calendar.ics';

type Props = {
  disabled: boolean;
  snapshotIdentity: string;
};

type DialogProps = {
  disabled: boolean;
  onClose: () => void;
};

type CalendarSnapshot = {
  blob: Blob;
  preview: string;
};

type DialogContentProps = {
  disabled: boolean;
  loading: boolean;
  error: string | null;
  empty: boolean;
  preview: string | null;
  downloadNotice: string | null;
  previewHeadingRef?: Ref<HTMLHeadingElement>;
  onLoadPreview: () => void;
  onDownload: () => void;
};

function calendarExportError(error: unknown): string {
  if (error instanceof Error && error.message === 'Invalid calendar export response') {
    return '서버가 올바른 iCalendar 파일을 반환하지 않았습니다.';
  }
  return errorMessage(error);
}

export function EventCalendarExportDialogContent({
  disabled,
  loading,
  error,
  empty,
  preview,
  downloadNotice,
  previewHeadingRef,
  onLoadPreview,
  onDownload,
}: DialogContentProps) {
  return (
    <>
      <p id="calendar-export-boundary" className="graph-detail-drawer__scope">
        사용자가 승인한 시간 정보가 있는 일정만 포함합니다. 원문 메모, 할 일, 태그와 AI 분석 정보는
        파일에 넣지 않습니다.
      </p>
      <p className="calendar-export-caveat">
        이 파일은 현재 일정의 1회 가져오기용 복사본입니다. 자동 동기화, 공유 링크와 알람을 만들지
        않으며, 다운로드한 파일은 기기에 남습니다.
      </p>

      <div className="calendar-export-actions">
        <button
          type="button"
          className="secondary-button"
          disabled={disabled || loading}
          onClick={onLoadPreview}
        >
          {loading ? '파일 불러오는 중…' : preview ? '최신 파일 다시 미리보기' : '최신 파일 미리보기'}
        </button>
        <button
          type="button"
          className="approve-button"
          disabled={disabled || loading || preview === null}
          onClick={onDownload}
        >
          미리본 파일 다운로드
        </button>
      </div>

      {loading && (
        <p className="calendar-export-status" role="status" aria-live="polite">
          최신 승인 일정을 iCalendar 파일로 불러오는 중입니다…
        </p>
      )}
      {!loading && empty && (
        <p className="calendar-export-empty" role="status" aria-live="polite">
          내보낼 승인 일정이 없습니다. 시간 미정 EVENT와 할 일은 파일로 만들지 않습니다.
        </p>
      )}
      {!loading && error && (
        <div className="calendar-export-error" role="alert">
          <p>{error}</p>
          <button
            type="button"
            className="secondary-button"
            disabled={disabled}
            onClick={onLoadPreview}
          >
            다시 불러오기
          </button>
        </div>
      )}
      {preview !== null && (
        <section className="calendar-export-preview" aria-labelledby="calendar-export-preview-title">
          <div className="calendar-export-preview__heading">
            <h3 id="calendar-export-preview-title" ref={previewHeadingRef} tabIndex={-1}>
              다운로드할 파일 미리보기
            </h3>
            <span>UTF-8 · 읽기 전용</span>
          </div>
          <pre tabIndex={0} aria-label="iCalendar 파일 원문 미리보기">{preview}</pre>
        </section>
      )}
      {downloadNotice && (
        <p className="calendar-export-status" role="status" aria-live="polite">
          {downloadNotice}
        </p>
      )}
    </>
  );
}

export function EventCalendarExportDialog({ disabled, onClose }: DialogProps) {
  const [snapshot, setSnapshot] = useState<CalendarSnapshot | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [empty, setEmpty] = useState(false);
  const [downloadNotice, setDownloadNotice] = useState<string | null>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const previewHeadingRef = useRef<HTMLHeadingElement>(null);
  const requestGenerationRef = useRef(0);
  const requestAbortRef = useRef<AbortController | null>(null);
  const objectUrlsRef = useRef(new Set<string>());

  const releaseObjectUrl = useCallback((objectUrl: string) => {
    if (!objectUrlsRef.current.delete(objectUrl)) return;
    URL.revokeObjectURL(objectUrl);
  }, []);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (!dialog.open) dialog.showModal();
    const frame = window.requestAnimationFrame(() => {
      headingRef.current?.focus({ preventScroll: true });
    });
    return () => {
      window.cancelAnimationFrame(frame);
      if (dialog.open) dialog.close();
    };
  }, []);

  useEffect(() => {
    if (!snapshot) return;
    const frame = window.requestAnimationFrame(() => {
      previewHeadingRef.current?.focus({ preventScroll: true });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [snapshot]);

  useEffect(() => () => {
    requestGenerationRef.current += 1;
    requestAbortRef.current?.abort();
    for (const objectUrl of objectUrlsRef.current) URL.revokeObjectURL(objectUrl);
    objectUrlsRef.current.clear();
  }, []);

  const loadPreview = useCallback(async () => {
    const generation = ++requestGenerationRef.current;
    requestAbortRef.current?.abort();
    const controller = new AbortController();
    requestAbortRef.current = controller;
    setLoading(true);
    setError(null);
    setEmpty(false);
    setDownloadNotice(null);
    setSnapshot(null);

    try {
      const blob = await api.eventCalendarExport(controller.signal);
      if (
        controller.signal.aborted ||
        requestGenerationRef.current !== generation
      ) return;
      if (blob === null) {
        setEmpty(true);
        return;
      }
      const preview = await blob.text();
      if (
        controller.signal.aborted ||
        requestGenerationRef.current !== generation
      ) return;
      setSnapshot({ blob, preview });
    } catch (caught) {
      if (
        controller.signal.aborted ||
        requestGenerationRef.current !== generation ||
        caught instanceof SessionScopeChangedError
      ) return;
      setError(calendarExportError(caught));
    } finally {
      if (
        !controller.signal.aborted &&
        requestGenerationRef.current === generation
      ) {
        requestAbortRef.current = null;
        setLoading(false);
      }
    }
  }, []);

  function downloadSnapshot() {
    if (!snapshot || disabled) return;
    const objectUrl = URL.createObjectURL(snapshot.blob);
    objectUrlsRef.current.add(objectUrl);
    const anchor = document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = EVENT_CALENDAR_FILENAME;
    anchor.rel = 'noopener';
    document.body.append(anchor);
    anchor.click();
    anchor.remove();
    window.setTimeout(() => releaseObjectUrl(objectUrl), 0);
    setDownloadNotice(
      `${EVENT_CALENDAR_FILENAME} 다운로드를 시작했습니다. 저장된 파일은 앱에서 회수할 수 없습니다.`,
    );
  }

  return (
    <dialog
      ref={dialogRef}
      className="graph-detail-dialog calendar-export-dialog"
      aria-labelledby="calendar-export-title"
      aria-describedby="calendar-export-boundary"
      aria-busy={loading}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
    >
      <section className="graph-detail-drawer calendar-export-drawer">
        <header className="graph-detail-drawer__header">
          <div>
            <span className="eyebrow">AUTHENTICATED EXPORT</span>
            <h2 id="calendar-export-title" ref={headingRef} tabIndex={-1}>
              캘린더 파일 (.ics)
            </h2>
          </div>
          <button
            type="button"
            className="graph-detail-drawer__close"
            aria-label="캘린더 파일 창 닫기"
            onClick={onClose}
          >
            <span aria-hidden="true">×</span>
          </button>
        </header>

        <EventCalendarExportDialogContent
          disabled={disabled}
          loading={loading}
          error={error}
          empty={empty}
          preview={snapshot?.preview ?? null}
          downloadNotice={downloadNotice}
          previewHeadingRef={previewHeadingRef}
          onLoadPreview={() => void loadPreview()}
          onDownload={downloadSnapshot}
        />
      </section>
    </dialog>
  );
}

export function EventCalendarExport({ disabled, snapshotIdentity }: Props) {
  const [open, setOpen] = useState(false);
  const openerRef = useRef<HTMLButtonElement>(null);

  function closeDialog() {
    setOpen(false);
    window.requestAnimationFrame(() => openerRef.current?.focus({ preventScroll: true }));
  }

  return (
    <div className="calendar-export-launcher">
      <button
        ref={openerRef}
        type="button"
        className="secondary-button"
        disabled={disabled}
        onClick={() => setOpen(true)}
      >
        캘린더 파일 (.ics)
      </button>
      {open && (
        <EventCalendarExportDialog
          key={snapshotIdentity}
          disabled={disabled}
          onClose={closeDialog}
        />
      )}
    </div>
  );
}
