package ssonin.searchapi.external;

import io.quarkiverse.httpproblem.HttpProblem;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import ssonin.searchapi.domain.error.ClientNotFoundError;
import ssonin.searchapi.domain.error.EmailAlreadyInUseError;
import ssonin.searchapi.domain.error.InvalidClientIdError;

import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static jakarta.ws.rs.core.Response.Status.CONFLICT;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static org.jboss.resteasy.reactive.RestResponse.ResponseBuilder.create;

public final class ApiExceptionMappers {

  @ServerExceptionMapper
  public RestResponse<HttpProblem> mapEmailAlreadyInUse(EmailAlreadyInUseError e) {
    final var body = HttpProblem.valueOf(CONFLICT, "Email already in use");
    return create(CONFLICT, body)
      .type(HttpProblem.MEDIA_TYPE)
      .build();
  }

  @ServerExceptionMapper
  public RestResponse<HttpProblem> mapClientNotFound(ClientNotFoundError e) {
    final var body = HttpProblem.valueOf(NOT_FOUND, "Client not found");
    return create(NOT_FOUND, body)
      .type(HttpProblem.MEDIA_TYPE)
      .build();
  }

  @ServerExceptionMapper
  public RestResponse<HttpProblem> mapInvalidClientId(InvalidClientIdError e) {
    final var body = HttpProblem.valueOf(BAD_REQUEST, "Invalid client id");
    return create(BAD_REQUEST, body)
      .type(HttpProblem.MEDIA_TYPE)
      .build();
  }
}
