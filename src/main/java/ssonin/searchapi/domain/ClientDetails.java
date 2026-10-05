package ssonin.searchapi.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record ClientDetails(
  UUID id,
  Instant createdAt,
  Instant updatedAt,
  GenericState state,
  String firstName,
  String lastName,
  String email,
  Optional<String> description
) {
}
