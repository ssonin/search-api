package ssonin.searchapi.external.request;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Optional;

import static com.fasterxml.jackson.annotation.Nulls.FAIL;
import static com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;

@JsonDeserialize(builder = ClientCreateRequest.Builder.class)
@JsonNaming(SnakeCaseStrategy.class)
public record ClientCreateRequest(

  @NotBlank
  String firstName,

  @NotBlank
  String lastName,

  @NotBlank
  @Email
  @Schema(format = "email")
  String email,

  @Schema(nullable = false)
  Optional<String> description
) {

  @JsonPOJOBuilder(withPrefix = "")
  @JsonNaming(SnakeCaseStrategy.class)
  public static final class Builder {

    private String firstName;
    private String lastName;
    private String email;
    private String description;

    public Builder firstName(String firstName) {
      this.firstName = firstName;
      return this;
    }

    public Builder lastName(String lastName) {
      this.lastName = lastName;
      return this;
    }

    public Builder email(String email) {
      this.email = email;
      return this;
    }

    @JsonSetter(nulls = FAIL)
    public Builder description(String description) {
      this.description = description;
      return this;
    }

    public ClientCreateRequest build() {
      return new ClientCreateRequest(
        this.firstName,
        this.lastName,
        this.email,
        Optional.ofNullable(this.description)
      );
    }
  }
}
