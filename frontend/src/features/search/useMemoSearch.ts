import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../../shared/api/client';
import { ApiError, errorMessage } from '../../shared/api/errors';
import { MemoSearchContractError } from '../../shared/api/searchDecoder';
import type { MemoSearchRequest } from '../../shared/api/types';
import {
  memoSearchRetryRequest,
  MemoSearchMergeError,
  mergeMemoSearchPage,
  type MemoSearchCollection,
  withoutMemoSearchCursor,
} from './searchModel';

type RetrySnapshot = {
  request: MemoSearchRequest;
  append: boolean;
};

export function useMemoSearch() {
  const [submittedRequest, setSubmittedRequest] = useState<MemoSearchRequest | null>(null);
  const [collection, setCollection] = useState<MemoSearchCollection | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [restartRequired, setRestartRequired] = useState(false);
  const latestRequest = useRef(0);
  const abortRef = useRef<AbortController | null>(null);
  const collectionRef = useRef<MemoSearchCollection | null>(null);
  const submittedRequestRef = useRef<MemoSearchRequest | null>(null);
  const retryRef = useRef<RetrySnapshot | null>(null);

  const replaceCollection = useCallback((next: MemoSearchCollection | null) => {
    collectionRef.current = next;
    setCollection(next);
  }, []);

  const execute = useCallback(async (request: MemoSearchRequest, append: boolean) => {
    const generation = ++latestRequest.current;
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    retryRef.current = { request: { ...request }, append };
    setError(null);
    setRestartRequired(false);
    if (append) {
      setLoadingMore(true);
    } else {
      replaceCollection(null);
      setLoading(true);
      setLoadingMore(false);
    }

    try {
      const page = await api.searchMemos(request, controller.signal);
      if (latestRequest.current !== generation || controller.signal.aborted) return;
      const merged = mergeMemoSearchPage(append ? collectionRef.current : null, page, request);
      replaceCollection(merged);
      retryRef.current = null;
    } catch (caught) {
      if (latestRequest.current !== generation || controller.signal.aborted) return;
      const mustRestart = append && (
        caught instanceof MemoSearchMergeError ||
        caught instanceof MemoSearchContractError ||
        (
          caught instanceof ApiError &&
          caught.status === 422 &&
          caught.code === 'INVALID_SEARCH_CURSOR'
        )
      );
      if (mustRestart) {
        setRestartRequired(true);
        setError(
          '검색 결과가 변경되었거나 페이지 정보가 만료되었습니다. 현재 목록은 이전 검색 시점의 결과이므로 처음부터 다시 검색해 주세요.',
        );
        retryRef.current = {
          request: memoSearchRetryRequest(request, true),
          append: false,
        };
      } else {
        setError(errorMessage(caught));
      }
    } finally {
      if (latestRequest.current === generation && !controller.signal.aborted) {
        setLoading(false);
        setLoadingMore(false);
      }
    }
  }, [replaceCollection]);

  const submit = useCallback((request: MemoSearchRequest) => {
    const firstPage = withoutMemoSearchCursor(request);
    submittedRequestRef.current = firstPage;
    setSubmittedRequest(firstPage);
    void execute(firstPage, false);
  }, [execute]);

  const loadMore = useCallback(() => {
    const current = collectionRef.current;
    const submitted = submittedRequestRef.current;
    if (!current?.nextCursor || !submitted || loading || loadingMore || restartRequired) return;
    void execute({ ...submitted, cursor: current.nextCursor }, true);
  }, [execute, loading, loadingMore, restartRequired]);

  const retry = useCallback(() => {
    const snapshot = retryRef.current;
    if (!snapshot || loading || loadingMore) return;
    if (!snapshot.append) {
      submittedRequestRef.current = withoutMemoSearchCursor(snapshot.request);
      setSubmittedRequest(submittedRequestRef.current);
    }
    void execute(snapshot.request, snapshot.append);
  }, [execute, loading, loadingMore]);

  const clear = useCallback(() => {
    latestRequest.current += 1;
    abortRef.current?.abort();
    abortRef.current = null;
    submittedRequestRef.current = null;
    retryRef.current = null;
    setSubmittedRequest(null);
    replaceCollection(null);
    setLoading(false);
    setLoadingMore(false);
    setError(null);
    setRestartRequired(false);
  }, [replaceCollection]);

  useEffect(() => () => {
    latestRequest.current += 1;
    abortRef.current?.abort();
  }, []);

  return {
    submittedRequest,
    collection,
    loading,
    loadingMore,
    error,
    restartRequired,
    submit,
    loadMore,
    retry,
    clear,
  };
}
