package local.personalmemo.common.api;
import java.util.List; import java.util.Map; import java.util.UUID;
import org.springframework.http.*; import org.springframework.web.bind.annotation.*;
@RestControllerAdvice public class ApiExceptionHandler {
  @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String,Object>> invalid(IllegalArgumentException e){
    var code=e.getMessage()!=null&&e.getMessage().startsWith("STALE")?"STALE_MEMO_REVISION":"INVALID_REQUEST";
    var status=code.startsWith("STALE")?HttpStatus.CONFLICT:HttpStatus.UNPROCESSABLE_ENTITY;
    return ResponseEntity.status(status).body(Map.of("code",code,"message",e.getMessage(),"fieldErrors",List.of(),"correlationId",UUID.randomUUID()));
  }
}

