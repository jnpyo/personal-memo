package local.personalmemo.analysis.application;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

final class AnalysisDispatchRecoveryWorker {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(AnalysisDispatchRecoveryWorker.class);

  private final AnalysisService analysisService;
  private final AnalysisDispatchRecoveryProperties properties;
  private final AtomicBoolean running = new AtomicBoolean();

  AnalysisDispatchRecoveryWorker(
      AnalysisService analysisService, AnalysisDispatchRecoveryProperties properties) {
    this.analysisService = analysisService;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${app.analysis.dispatch-recovery.fixed-delay:PT30S}",
      initialDelayString = "${app.analysis.dispatch-recovery.fixed-delay:PT30S}")
  void recoverPendingDispatches() {
    if (!running.compareAndSet(false, true)) {
      return;
    }

    try {
      analysisService.recoverPendingDispatches(properties.getBatchSize());
    } catch (RuntimeException ignored) {
      LOGGER.warn("Analysis dispatch recovery cycle failed; it will be retried.");
    } finally {
      running.set(false);
    }
  }
}
