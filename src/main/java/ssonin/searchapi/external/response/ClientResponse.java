package ssonin.searchapi.external.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_ABSENT;
import static com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;

@JsonNaming(SnakeCaseStrategy.class)
public record ClientResponse(
  UUID id,
  Instant createdAt,
  String firstName,
  String lastName,
  String email,
  @JsonInclude(NON_ABSENT)
  Optional<String> description
) {
}
