package ssonin.searchapi.domain.error;

public final class ClientNotFoundError extends ApplicationError {

  public ClientNotFoundError() {
    super("client_not_found");
  }
}
