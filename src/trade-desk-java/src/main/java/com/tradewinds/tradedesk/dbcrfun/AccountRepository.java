package com.tradewinds.tradedesk.dbcrfun;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, AccountId> {
}
