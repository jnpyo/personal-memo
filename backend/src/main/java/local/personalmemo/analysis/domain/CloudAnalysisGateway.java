package local.personalmemo.analysis.domain;

public interface CloudAnalysisGateway {
  /**
   * Captures one immutable descriptor/executor pair without performing provider I/O.
   *
   * <p>The returned binding is the only executor that may be used with its descriptor. A recovery
   * path must create a new binding and compare its descriptor with the durable preparation before
   * executing it.
   */
  CloudGatewayBinding bind();
}
