package local.personalmemo.memo.api;
import jakarta.validation.constraints.*; import java.time.*; import java.util.UUID;
public final class MemoDtos {
  private MemoDtos(){}
  public record Create(@NotNull UUID id,@NotBlank @Size(max=20000) String content,OffsetDateTime clientCreatedAt,@NotBlank String timeZone){}
  public record Update(@Min(1) int expectedRevision,@NotBlank @Size(max=20000) String content){}
  public record View(UUID id,int currentRevision,String content,String status,String analysisState,Instant createdAt){}
}

