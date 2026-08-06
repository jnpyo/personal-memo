package local.personalmemo.graph.api;

import java.util.List;
import java.util.UUID;

public final class GraphDtos {
  private GraphDtos() {}

  public record Node(
      String id, String kind, String label, String memoType, String taskState, boolean overdue) {}

  public record Edge(String id, String source, String target, String kind) {}

  public record Home(
      List<Node> nodes, List<Edge> edges, boolean truncated, UUID projectionVersion) {
    public Home {
      nodes = List.copyOf(nodes);
      edges = List.copyOf(edges);
    }
  }
}
