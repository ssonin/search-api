package ssonin.searchapi.domain.error;

public final class EmailAlreadyInUseError extends ApplicationError {

  public EmailAlreadyInUseError(Throwable cause) {
    super("email_already_in_use", cause);
  }
}
