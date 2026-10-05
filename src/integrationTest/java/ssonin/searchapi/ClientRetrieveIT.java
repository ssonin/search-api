package ssonin.searchapi;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static io.restassured.http.ContentType.JSON;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusIntegrationTest
class ClientRetrieveIT {

  @Test
  void retrieves_existing_client() {
    // given
    var firstName = "Chandler";
    var lastName = "Bing";
    var email = "chandler.%s@centralperk.com".formatted(randomUUID());
    var description = "Sarcastic, self-deprecating office worker with a sharp sense of humor, known for cracking jokes to deflect awkward situations.";
    var created = given()
      .contentType(JSON)
      .body("""
        {
          "first_name": "%s",
          "last_name": "%s",
          "email": "%s",
          "description": "%s"
        }
        """.formatted(firstName, lastName, email, description))
      .when()
      .post("/api/v1/clients");

    created.then().statusCode(201);
    var body = created.jsonPath();
    var id = body.getString("id");

    // when
    var retrieved = when().get("/api/v1/clients/" + id);

    // then
    retrieved.then()
      .statusCode(200)
      .contentType(JSON);

    assertThat(retrieved.jsonPath().getMap("$"))
      .isEqualTo(created.jsonPath().getMap("$"));
  }

  @Test
  void returns_not_found_for_unknown_client() {
    // when
    var response = when().get("/api/v1/clients/" + randomUUID());

    // then
    response.then()
      .statusCode(404)
      .contentType("application/problem+json");

    var body = response.body().jsonPath();
    assertThat(body.getMap("$")).isEqualTo(Map.of(
      "status", 404,
      "title", "Not Found",
      "detail", "Client not found"
    ));
  }

  @Test
  void rejects_invalid_client_id() {
    // given
    var id = "bamboozled";

    // when
    var response = when().get("/api/v1/clients/" + id);

    // then
    response.then()
      .statusCode(400)
      .contentType("application/problem+json");

    var body = response.body().jsonPath();
    assertThat(body.getMap("$")).isEqualTo(Map.of(
      "status", 400,
      "title", "Bad Request",
      "detail", "Invalid client id"
    ));
  }
}
