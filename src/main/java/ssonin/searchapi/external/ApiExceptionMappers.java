package ssonin.searchapi.external;

import io.quarkiverse.httpproblem.HttpProblem;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import ssonin.searchapi.domain.error.EmailAlreadyInUseError;

import static jakarta.ws.rs.core.Response.Status.CONFLICT;
import static org.jboss.resteasy.reactive.RestResponse.ResponseBuilder.create;

public final class ApiExceptionMappers {

  @ServerExceptionMapper
  public RestResponse<HttpProblem> mapEmailAlreadyInUse(EmailAlreadyInUseError e) {
    final var body = HttpProblem.valueOf(CONFLICT, "Email already in use");
    return create(CONFLICT, body)
      .type(HttpProblem.MEDIA_TYPE)
      .build();
  }
}
