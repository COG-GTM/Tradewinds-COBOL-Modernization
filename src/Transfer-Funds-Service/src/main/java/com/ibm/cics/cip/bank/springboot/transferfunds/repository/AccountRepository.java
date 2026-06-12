/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.repository;

import java.util.Optional;

import com.ibm.cics.cip.bank.springboot.transferfunds.domain.Account;
import com.ibm.cics.cip.bank.springboot.transferfunds.domain.AccountKey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/**
 * Spring Data repository for {@link Account}.
 *
 * <p>
 * Replaces the embedded {@code EXEC SQL SELECT ... } and
 * {@code EXEC SQL UPDATE ... } statements in XFRFUN. The
 * {@link #findByKeyForUpdate} method takes a pessimistic write lock, which is
 * the JPA equivalent of the row locking Db2 performed under the CICS unit of
 * work in the original program.
 * </p>
 */
public interface AccountRepository extends JpaRepository<Account, AccountKey>
{

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT a FROM Account a WHERE a.key = :key")
	Optional<Account> findByKeyForUpdate(@Param("key") AccountKey key);
}
