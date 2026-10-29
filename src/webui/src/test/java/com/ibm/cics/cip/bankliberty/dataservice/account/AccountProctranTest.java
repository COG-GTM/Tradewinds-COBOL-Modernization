package com.ibm.cics.cip.bankliberty.dataservice.account;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountProctranTest
{

	@Test
	@DisplayName("CREACC OCA description is customer(10) type(8) last DDMMYYYY next DDMMYYYY then six spaces")
	void creaccDescriptionLayout()
	{
		String desc = AccountProctran.createAccountDescription("42", "ISA",
				LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 28));
		assertEquals(40, desc.length());
		assertEquals("0000000042ISA     2910202628112026      ", desc);
	}


	@Test
	@DisplayName("DELACC ODA description ends with the DELETE footer at offset 35")
	void delaccDescriptionLayout()
	{
		String desc = AccountProctran.deleteAccountDescription("0000000001",
				"MORTGAGE", LocalDate.of(2021, 7, 1), LocalDate.of(2021, 8, 1));
		assertEquals("0000000001MORTGAGE0107202101082021DELETE", desc);
	}


	@Test
	@DisplayName("Description round-trips through the fixed offsets ProcessedTransactionResource decodes")
	void descriptionRoundTripsThroughJavaDecoderOffsets()
	{
		String desc = AccountProctran.deleteAccountDescription("123",
				"CURRENT", LocalDate.of(2021, 7, 1), LocalDate.of(2021, 8, 2));
		assertEquals("0000000123", desc.substring(0, 10));
		assertEquals("CURRENT ", desc.substring(10, 18));
		assertEquals("01", desc.substring(18, 20));
		assertEquals("07", desc.substring(20, 22));
		assertEquals("2021", desc.substring(22, 26));
		assertEquals("02", desc.substring(26, 28));
		assertEquals("08", desc.substring(28, 30));
		assertEquals("2021", desc.substring(30, 34));
	}
}
