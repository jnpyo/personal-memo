package local.personalmemo.analysis.api;
import jakarta.validation.constraints.*; import java.util.*;
public final class AnalysisDtos { private AnalysisDtos(){}
 public record Start(@Min(1) int memoRevision,String policy){}
 public record RunView(UUID id,UUID memoId,int memoRevision,String status,UUID proposalId){}
 public record Due(String surfaceText,String value,String precision,String timeZone,boolean timeSpecified){}
 public record Item(@NotBlank String kind,@NotBlank String title,Due due){}
 public record Tag(UUID existingTagId,String newCanonicalName){}
 public record Apply(@Min(1) int expectedMemoRevision,@NotBlank String selectedType,@NotBlank String title,List<Tag> selectedTags,@Size(max=3) List<Item> items){}
}

