package io.github.maaasu.astralRecord.feature.account.repository;

/** API がアカウント名の重複を拒否したことを表します。 */
public final class AccountNameConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AccountNameConflictException(String message) {
        super(message);
    }
}
