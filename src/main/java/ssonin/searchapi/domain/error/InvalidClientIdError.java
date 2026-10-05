package ssonin.searchapi.domain.error;

public final class InvalidClientIdError extends ApplicationError {

  public InvalidClientIdError(Throwable cause) {
    super("invalid_client_id", cause);
  }
}
