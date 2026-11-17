package com.tradewinds.tradedesk.dbcrfun.repository;

import com.tradewinds.tradedesk.dbcrfun.entity.Account;
import com.tradewinds.tradedesk.dbcrfun.entity.AccountId;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, AccountId> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.sortcode = :sortcode and a.number = :number")
    Optional<Account> findForUpdate(@Param("sortcode") String sortcode, @Param("number") String number);
}
