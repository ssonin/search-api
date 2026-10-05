package ssonin.searchapi;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static java.time.Duration.ofSeconds;
import static java.util.Locale.ROOT;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusIntegrationTest
@QuarkusTestResource(PostgresTestResource.class)
class ClientCreateIT {

  private Pool pool;

  @Test
  void creates_client() {
    // given
    var firstName = "Chandler";
    var lastName = "Bing";
    var email = "chandler.%s@centralperk.com".formatted(randomUUID());
    var description = "Sarcastic, self-deprecating office worker with a sharp sense of humor, known for cracking jokes to deflect awkward situations.";
    var request = given()
      .contentType(JSON)
      .body("""
        {
          "first_name": "%s",
          "last_name": "%s",
          "email": "%s",
          "description": "%s",
          "unknown": "WOOPAH"
        }
        """.formatted(firstName, lastName, email, description));

    // when
    var response = request.when().post("/api/v1/clients");

    // then
    response.then()
      .statusCode(201)
      .contentType(JSON);

    var body = response.body().jsonPath();
    var id = body.getString("id");
    var location = URI.create(response.getHeader("Location"));
    assertThat(location.getPath()).isEqualTo("/api/v1/clients/" + id);

    var createdAt = body.getString("created_at");
    assertThat(body.getMap("$")).isEqualTo(Map.of(
      "id", id,
      "created_at", createdAt,
      "first_name", firstName,
      "last_name", lastName,
      "email", email,
      "description", description
    ));

    var result = pool.preparedQuery("SELECT * FROM clients WHERE id = $1")
      .execute(Tuple.of(id))
      .map(rows -> rows.iterator().next())
      .await()
      .atMost(ofSeconds(10));
    assertThat(result).satisfies(it -> {
      assertThat(it.getOffsetDateTime("created_at")).isEqualTo(OffsetDateTime.parse(createdAt));
      assertThat(it.getString("first_name")).isEqualTo(firstName);
      assertThat(it.getString("last_name")).isEqualTo(lastName);
      assertThat(it.getString("email")).isEqualTo(email);
      assertThat(it.getString("description")).isEqualTo(description);
    });
  }

  @Test
  void rejects_if_email_already_in_use() {
    // given
    var email = "chandler.%s@centralperk.com".formatted(randomUUID());
    var payload = """
        {
          "first_name": "Chandler",
          "last_name": "Bing",
          "email": "%s"
        }
        """;
    var request = given()
      .contentType(JSON)
      .body(payload.formatted(email));

    // when
    var response = request.when().post("/api/v1/clients");

    // then
    response.then()
      .statusCode(201)
      .contentType(JSON);

    var body = response.body().jsonPath();
    assertThat(body).satisfies(it -> {
      assertThat(it.getString("id")).isNotNull();
      assertThat(it.getString("first_name")).isNotNull();
      assertThat(it.getString("last_name")).isNotNull();
      assertThat(it.getString("email")).isNotNull();
    });
    assertThat(body.getMap("$")).doesNotContainKey("description");

    // when
    response = request.body(payload.formatted(email.toUpperCase(ROOT)))
      .when()
      .post("/api/v1/clients");

    // then
    response.then()
      .statusCode(409)
      .contentType("application/problem+json");

    body = response.body().jsonPath();
    assertThat(body.getMap("$")).isEqualTo(Map.of(
      "status", 409,
      "title", "Conflict",
      "detail", "Email already in use"
    ));
  }

  @Test
  void rejects_if_description_is_null() {
    // given
    var request = given()
      .contentType(JSON)
      .body("""
        {
          "first_name": "Chandler",
          "last_name": "Bing",
          "email": "chandler.%s@centralperk.com",
          "description": null
        }
        """.formatted(randomUUID()));

    // when
    var response = request.when().post("/api/v1/clients");

    // then
    response.then()
      .statusCode(400)
      .contentType("application/problem+json");

    var body = response.body().jsonPath();

    assertThat(body.getMap("$")).isEqualTo(Map.of(
      "status", 400,
      "title", "Bad Request",
      "detail", "Malformed request body",
      "instance", "/clients",
      "field", "description"
    ));
  }

  @Test
  void rejects_if_body_is_absent() {
    // given
    var request = given()
      .contentType(JSON);

    // when
    var response = request.when().post("/api/v1/clients");

    // then
    response.then()
      .statusCode(400)
      .contentType("application/problem+json");

    var body = response.body().jsonPath();
    assertThat(body.getMap("$")).isEqualTo(Map.of(
      "status", 400,
      "title", "Bad Request",
      "instance", "/clients",
      "violations", List.of(
        Map.of(
          "field", "",
          "in", "body",
          "message", "must not be null"
        )
      )
    ));
  }

  @Test
  void rejects_if_body_is_malformed() {
    // given
    var request = given()
      .contentType(JSON)
      .body("bamboozled");

    // when
    var response = request.when().post("/api/v1/clients");

    // then
    response.then()
      .statusCode(400)
      .contentType("application/problem+json");

    var body = response.body().jsonPath();

    assertThat(body.getMap("$")).isEqualTo(Map.of(
      "status", 400,
      "title", "Bad Request",
      "detail", "HTTP 400 Bad Request",
      "instance", "/clients"
    ));
  }

  @ParameterizedTest
  @ValueSource(strings = {"42", "3.14", "true"})
  void rejects_if_first_name_is_not_textual(String firstName) {
    // given
    var request = given()
      .contentType(JSON)
      .body("""
        {
          "first_name": %s,
          "last_name": "Bing",
          "email": "chandler.%s@centralperk.com"
        }
        """.formatted(firstName, randomUUID()));

    // when
    var response = request.when().post("/api/v1/clients");

    // then
    response.then()
      .statusCode(400)
      .contentType("application/problem+json");

    var body = response.body().jsonPath();

    assertThat(body.getMap("$")).isEqualTo(Map.of(
      "status", 400,
      "title", "Bad Request",
      "detail", "Malformed request body",
      "instance", "/clients",
      "field", "first_name"
    ));
  }
}
