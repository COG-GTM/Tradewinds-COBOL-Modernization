/*
 *
 *    Copyright IBM Corp. 2023
 *
 */
package com.ibm.cics.cip.bank.springboot.transferfunds.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ibm.cics.cip.bank.springboot.transferfunds.domain.ProcessedTransaction;

public interface ProcessedTransactionRepository
		extends JpaRepository<ProcessedTransaction, Long>
{
}
