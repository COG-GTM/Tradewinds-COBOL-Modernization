/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.model;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for ACCOUNT table access.
 *
 * Maps the COBOL SELECT ... FROM ACCOUNT WHERE (ACCOUNT_SORTCODE = ? AND
 * ACCOUNT_NUMBER = ?) pattern used in UPDATE-ACCOUNT-DB2-FROM and
 * UPDATE-ACCOUNT-DB2-TO sections of XFRFUN.cbl.
 */
@Repository
public interface AccountRepository
		extends JpaRepository<AccountEntity, AccountKey>
{


	Optional<AccountEntity> findBySortcodeAndAccountNumber(String sortcode,
			String accountNumber);
}
