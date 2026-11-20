/*
 *
 *    Copyright IBM Corp. 2023
 *
 */
package com.ibm.cics.cip.bank.springboot.transferfunds.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ibm.cics.cip.bank.springboot.transferfunds.domain.Account;
import com.ibm.cics.cip.bank.springboot.transferfunds.domain.AccountId;

import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<Account, AccountId>
{
	/**
	 * SELECT ... FOR UPDATE, the equivalent of XFRFUN's SELECT followed by
	 * UPDATE within the same unit of work.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT a FROM Account a WHERE a.sortCode = :sortCode AND a.accountNumber = :accountNumber")
	Optional<Account> findForUpdate(@Param("sortCode") String sortCode,
			@Param("accountNumber") String accountNumber);
}
