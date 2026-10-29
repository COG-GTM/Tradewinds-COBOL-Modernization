/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class ProctranFormatTest
{

	@Test
	void transferDescriptionIsTransferPaddedTo26ThenTargetSortcodeThenTargetAccount()
	{
		String desc = ProctranFormat.transferDescription("987654", "123");
		assertEquals(40, desc.length());
		assertEquals("TRANSFER                  98765400000123", desc);
	}

	@Test
	void customerDescriptionIsSortcodeCustomerName14AndDobWithSlashes()
	{
		String desc = ProctranFormat.customerDescription("987654", "42",
				"Mrs Alexandra Q Smith", "05031980");
		assertEquals("9876540000000042Mrs Alexandra 05/03/1980", desc);
		assertEquals(40, desc.length());
	}

	@Test
	void shortCustomerNameIsBlankPaddedNotZeroPadded()
	{
		String desc = ProctranFormat.customerDescription("1", "1", "Bob",
				"01012000");
		assertEquals("0000010000000001Bob           01/01/2000", desc);
	}

	@Test
	void customerDobMustBeEightDigitsDdmmyyyy()
	{
		assertThrows(IllegalArgumentException.class, () -> ProctranFormat
				.customerDescription("1", "1", "Bob", "2000-01-01"));
	}

	@Test
	void referenceIsTheCicsTaskNumberZeroPaddedTo12Digits()
	{
		assertEquals("000000012345", ProctranFormat.reference(12345));
		assertThrows(IllegalArgumentException.class,
				() -> ProctranFormat.reference(1_000_000_000_000L));
	}

	@Test
	void timeIsHhmmss()
	{
		assertEquals("090503", ProctranFormat.time(LocalTime.of(9, 5, 3)));
	}

	@Test
	void tellerDebitAndCreditUseCounterDescriptions()
	{
		assertEquals("DEB", ProctranFormat.debitCreditType(true, 0));
		assertEquals("CRE", ProctranFormat.debitCreditType(false, 0));
		assertEquals("COUNTER WTHDRW",
				ProctranFormat.debitCreditDescription(true, 0, "A", "B").trim());
		assertEquals("COUNTER RECVED", ProctranFormat
				.debitCreditDescription(false, 0, "A", "B").trim());
	}

	@Test
	void paymentInterfaceDescriptionIsFirst14BytesOfCommOriginApplidThenUserid()
	{
		assertEquals("PDR", ProctranFormat.debitCreditType(true, 496));
		assertEquals("PCR", ProctranFormat.debitCreditType(false, 496));
		String desc = ProctranFormat.debitCreditDescription(true, 496,
				"CICSAPPL", "USER0001");
		assertEquals("CICSAPPLUSER00", desc.trim());
		assertEquals(40, desc.length());
		assertEquals("APP     USR   ", ProctranFormat
				.debitCreditDescription(false, 496, "APP", "USR")
				.substring(0, 14));
	}

	@Test
	void typeMustBeOneOfTheProctranCopybookCodes()
	{
		assertEquals(18, ProctranFormat.ALLOWED_TYPES.size());
		assertEquals("TFR", ProctranFormat.type("TFR"));
		assertThrows(IllegalArgumentException.class,
				() -> ProctranFormat.type("XYZ"));
		assertThrows(IllegalArgumentException.class,
				() -> ProctranFormat.type("tfr"));
	}

	@Test
	void amountMustFitPicS9_10V99()
	{
		assertEquals(new BigDecimal("-9999999999.99"),
				ProctranFormat.amount(new BigDecimal("-9999999999.99")));
		assertEquals(new BigDecimal("5.00"),
				ProctranFormat.amount(new BigDecimal("5")));
		assertThrows(IllegalArgumentException.class,
				() -> ProctranFormat.amount(new BigDecimal("10000000000.00")));
		assertThrows(IllegalArgumentException.class,
				() -> ProctranFormat.amount(new BigDecimal("1.001")));
	}

	@Test
	void numericKeysShorterThanTheColumnAreZeroPaddedAndNonDigitsRejected()
	{
		assertEquals("00000042", ProctranFormat.accountNumber("42"));
		assertEquals("000001", ProctranFormat.sortCode("1"));
		assertThrows(IllegalArgumentException.class,
				() -> ProctranFormat.accountNumber("123456789"));
		assertThrows(IllegalArgumentException.class,
				() -> ProctranFormat.accountNumber("12A"));
	}
}
