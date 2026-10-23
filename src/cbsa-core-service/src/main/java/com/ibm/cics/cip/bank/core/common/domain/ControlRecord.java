package com.ibm.cics.cip.bank.core.common.domain;

/** Row of DB2 table CONTROL (named-counter / control values, copybook CONTDB2.cpy). */
public record ControlRecord(String name, Long valueNum, String valueStr) {
}
