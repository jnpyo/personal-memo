package local.personalmemo.analysis.infrastructure;

final class OllamaProtocolException extends RuntimeException {
  OllamaProtocolException() {
    super("The local-model response violated the bounded protocol.");
  }
}
