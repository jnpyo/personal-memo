package local.personalmemo.common;
import java.util.UUID;
import org.springframework.stereotype.Component;
@Component public class DevIdentity { public UUID ownerId(){ return UUID.fromString("00000000-0000-0000-0000-000000000001"); } }

