package com.tradewinds.tradedesk.dbcrfun;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AccountRepository extends JpaRepository<Account, AccountId> {

    /** SELECT ... FOR UPDATE, so concurrent postings to one account cannot overwrite each other's balances. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Override
    Optional<Account> findById(AccountId id);
}
