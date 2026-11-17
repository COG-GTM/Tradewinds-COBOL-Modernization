package com.tradewinds.tradedesk.dbcrfun.service;

/** Supplies PROCTRAN_REF; replaces the CICS task number (EIBTASKN) used by DBCRFUN. */
@FunctionalInterface
public interface ProcTranReferenceGenerator {

    /** Returns a 12-character reference. */
    String next();
}
