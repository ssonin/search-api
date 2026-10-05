package ssonin.searchapi.service;

import java.util.Optional;

public record ClientInput(
  String firstName,
  String lastName,
  String email,
  Optional<String> description
) {}
