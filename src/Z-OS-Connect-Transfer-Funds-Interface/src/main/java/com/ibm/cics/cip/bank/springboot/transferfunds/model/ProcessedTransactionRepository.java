/*                                                                        */
/* Copyright IBM Corp. 2023                                               */
/*                                                                        */
package com.ibm.cics.cip.bank.springboot.transferfunds.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for PROCTRAN table access.
 *
 * Maps the COBOL INSERT INTO PROCTRAN pattern used in WRITE-TO-PROCTRAN-DB2
 * section of XFRFUN.cbl.
 */
@Repository
public interface ProcessedTransactionRepository
		extends JpaRepository<ProcessedTransactionEntity, Long>
{
}
