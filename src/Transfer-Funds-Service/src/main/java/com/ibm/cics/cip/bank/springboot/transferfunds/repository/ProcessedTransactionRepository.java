/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.repository;

import com.ibm.cics.cip.bank.springboot.transferfunds.domain.ProcessedTransaction;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link ProcessedTransaction}.
 *
 * <p>
 * Replaces the {@code EXEC SQL INSERT INTO PROCTRAN} statement in XFRFUN section
 * {@code WRITE-TO-PROCTRAN-DB2}.
 * </p>
 */
public interface ProcessedTransactionRepository
		extends JpaRepository<ProcessedTransaction, Long>
{
}
