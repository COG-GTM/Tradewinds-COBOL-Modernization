package com.ibm.cics.cip.bankliberty.dataservice.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsonCodecTest
{

	@Test
	@DisplayName("Decimals parse to exact BigDecimal, integers to Long, so money never goes through double")
	void numbersAreExact()
	{
		Map<?, ?> m = (Map<?, ?>) JsonCodec.parse("{\"a\":0.1,\"b\":42,\"c\":-1234.56}");
		assertEquals(new BigDecimal("0.1"), m.get("a"));
		assertEquals(Long.valueOf(42), m.get("b"));
		assertEquals(new BigDecimal("-1234.56"), m.get("c"));
	}


	@Test
	@DisplayName("Strings, escapes, arrays, booleans and null round-trip")
	void roundTrip()
	{
		String json = "{\"s\":\"a\\\"b\\\\c\\n\\u0041\",\"l\":[1,true,null,\"x\"],\"o\":{}}";
		Object parsed = JsonCodec.parse(json);
		assertEquals("a\"b\\c\nA", ((Map<?, ?>) parsed).get("s"));
		assertEquals(4, ((List<?>) ((Map<?, ?>) parsed).get("l")).size());
		assertEquals(parsed, JsonCodec.parse(JsonCodec.serialize(parsed)));
	}


	@Test
	@DisplayName("Malformed JSON is rejected")
	void malformedIsRejected()
	{
		assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("{\"a\":}"));
		assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("[1,2"));
		assertThrows(IllegalArgumentException.class, () -> JsonCodec.parse("{} x"));
	}


	@Test
	@DisplayName("An error response serializes the COBOL success flag, fail code and abend code")
	void errorResponseShape()
	{
		ServiceResult r = AccountDataService.error(new DataServiceException(500, " ", "HWPT", "boom", null));
		assertEquals("{\"success\":\"N\",\"failCode\":\" \",\"abendCode\":\"HWPT\",\"message\":\"boom\"}",
				r.bodyAsJson());
	}
}
