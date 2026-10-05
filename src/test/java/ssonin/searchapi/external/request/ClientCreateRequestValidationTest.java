package ssonin.searchapi.external.request;

import org.junit.jupiter.api.Test;

import static jakarta.validation.Validation.buildDefaultValidatorFactory;
import static java.util.Optional.empty;
import static org.assertj.core.api.Assertions.assertThat;

class ClientCreateRequestValidationTest {

  @Test
  void reports_blank_names_and_invalid_email() {
    // given
    var request = new ClientCreateRequest(
      " ",
      "",
      "not-an-email",
      empty()
    );

    try (var factory = buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();

      // when
      var invalidFields = validator.validate(request)
        .stream()
        .map(violation -> violation.getPropertyPath().toString())
        .toList();

      // then
      assertThat(invalidFields).containsExactlyInAnyOrder("firstName", "lastName", "email");
    }
  }
}
