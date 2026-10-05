package ssonin.searchapi.domain.error;

public abstract sealed class ApplicationError extends RuntimeException
  permits ClientNotFoundError, EmailAlreadyInUseError, InvalidClientIdError {

  public final String code;

  protected ApplicationError(String code) {
    this.code = code;
  }

  protected ApplicationError(String code, Throwable cause) {
    super(cause);
    this.code = code;
  }

  protected ApplicationError(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }
}
