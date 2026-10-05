package ssonin.searchapi;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.when;
import static io.smallrye.mutiny.Uni.combine;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusIntegrationTest
@QuarkusTestResource(PostgresTestResource.class)
class BootIT {

  private Pool pool;

  @Test
  void returns_not_found_for_undefined_root() {
    // when
    var response = when().get("/");

    // then
    response.then().statusCode(404);
  }

  @Test
  void applies_all_database_migrations() {
    // when
    var actual = combine()
      .all()
      .unis(
        selectCountAll("SELECT count(*) FROM flyway_schema_history WHERE success"),
        selectCountAll("SELECT count(*) FROM clients"),
        selectCountAll("SELECT count(*) FROM documents")
      )
      .with(TableCounts::new)
      .await()
      .atMost(ofSeconds(10));

    // then
    assertThat(actual.migrationsCount()).isEqualTo(3L);
    assertThat(actual.documentsCount()).isZero();
  }

  private Uni<Long> selectCountAll(String sql) {
    return pool.query(sql)
      .execute()
      .map(rows -> rows.iterator().next().getLong(0));
  }

  private record TableCounts(
    long migrationsCount,
    long clientsCount,
    long documentsCount
  ) {}
}
