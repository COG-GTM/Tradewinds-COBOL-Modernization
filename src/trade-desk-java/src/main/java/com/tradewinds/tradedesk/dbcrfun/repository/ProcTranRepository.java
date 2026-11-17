package com.tradewinds.tradedesk.dbcrfun.repository;

import com.tradewinds.tradedesk.dbcrfun.entity.ProcTran;
import com.tradewinds.tradedesk.dbcrfun.entity.ProcTranId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcTranRepository extends JpaRepository<ProcTran, ProcTranId> {
}
