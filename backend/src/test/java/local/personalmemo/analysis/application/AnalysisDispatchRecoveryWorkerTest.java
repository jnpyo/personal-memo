package local.personalmemo.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AnalysisDispatchRecoveryWorkerTest {
  @Test
  void delegatesUsingTheConfiguredBoundedBatch() {
    AnalysisService service = mock(AnalysisService.class);
    when(service.recoverPendingDispatches(17)).thenReturn(3);
    AnalysisDispatchRecoveryWorker worker = worker(service, 17);

    worker.recoverPendingDispatches();

    verify(service).recoverPendingDispatches(17);
  }

  @Test
  void preventsOverlappingCyclesWithinTheProcess() throws Exception {
    AnalysisService service = mock(AnalysisService.class);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              entered.countDown();
              release.await();
              return 0;
            })
        .when(service)
        .recoverPendingDispatches(5);
    AnalysisDispatchRecoveryWorker worker = worker(service, 5);
    Thread first = new Thread(worker::recoverPendingDispatches);

    try {
      first.start();
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

      worker.recoverPendingDispatches();

      verify(service).recoverPendingDispatches(5);
    } finally {
      release.countDown();
      first.join(2_000);
    }
    assertThat(first.isAlive()).isFalse();

    worker.recoverPendingDispatches();
    verify(service, times(2)).recoverPendingDispatches(5);
  }

  @Test
  void containsFailuresSoFutureCyclesCanRetryWithoutExposingTheCause() {
    AnalysisService service = mock(AnalysisService.class);
    when(service.recoverPendingDispatches(9))
        .thenThrow(new IllegalStateException("raw-memo-or-provider-secret"))
        .thenReturn(1);
    AnalysisDispatchRecoveryWorker worker = worker(service, 9);

    assertThatNoException().isThrownBy(worker::recoverPendingDispatches);
    assertThatNoException().isThrownBy(worker::recoverPendingDispatches);

    verify(service, times(2)).recoverPendingDispatches(9);
  }

  private AnalysisDispatchRecoveryWorker worker(AnalysisService service, int batchSize) {
    AnalysisDispatchRecoveryProperties properties = new AnalysisDispatchRecoveryProperties();
    properties.setBatchSize(batchSize);
    return new AnalysisDispatchRecoveryWorker(service, properties);
  }
}
