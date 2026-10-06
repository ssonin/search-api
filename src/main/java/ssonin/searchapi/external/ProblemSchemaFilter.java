package ssonin.searchapi.external;

import io.quarkus.smallrye.openapi.OpenApiFilter;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;

import java.util.List;

import static io.quarkus.smallrye.openapi.OpenApiFilter.RunStage.BUILD;

@OpenApiFilter(stages = BUILD)
public final class ProblemSchemaFilter implements OASFilter {

  @Override
  public void filterOpenAPI(OpenAPI openAPI) {
    final var schemas = openAPI.getComponents().getSchemas();
    for (final var schema : List.of("HttpProblem", "HttpValidationProblem")) {
      final var properties = schemas.get(schema).getProperties();
      for (final var property : List.of("type", "instance")) {
        properties.get(property).setFormat("uri-reference");
      }
    }
  }
}
