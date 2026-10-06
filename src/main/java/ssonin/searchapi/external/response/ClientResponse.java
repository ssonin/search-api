package ssonin.searchapi.external.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_ABSENT;
import static com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;

@JsonNaming(SnakeCaseStrategy.class)
public record ClientResponse(
  @Schema(required = true)
  UUID id,

  @Schema(required = true)
  Instant createdAt,

  @Schema(required = true)
  String firstName,

  @Schema(required = true)
  String lastName,

  @Schema(required = true, format = "email")
  String email,

  @Schema(nullable = false)
  @JsonInclude(NON_ABSENT)
  Optional<String> description
) {
}
