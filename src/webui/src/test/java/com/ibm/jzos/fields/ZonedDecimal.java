/*
 *
 *    Copyright IBM Corp. 2023, 2025
 *
 */
package com.ibm.jzos.fields;

import java.math.BigInteger;

/** Zoned (external) decimal encode/decode for the test JZOS stand-in. */
abstract class ZonedDecimal
{

	protected final int offset;

	protected final int precision;

	protected final boolean signed;


	ZonedDecimal(int offset, int precision, boolean signed)
	{
		this.offset = offset;
		this.precision = precision;
		this.signed = signed;
	}


	public int getOffset()
	{
		return offset;
	}


	protected BigInteger read(byte[] bytes)
	{
		StringBuilder digits = new StringBuilder();
		boolean negative = false;
		for (int i = 0; i < precision; i++)
		{
			int b = bytes[offset + i] & 0xFF;
			digits.append((char) ('0' + (b & 0x0F)));
			if (i == precision - 1 && (b & 0xF0) == 0xD0)
			{
				negative = true;
			}
		}
		BigInteger value = new BigInteger(digits.toString());
		return negative ? value.negate() : value;
	}


	protected void write(BigInteger value, byte[] bytes)
	{
		String digits = value.abs().toString();
		if (digits.length() > precision)
		{
			digits = digits.substring(digits.length() - precision);
		}
		while (digits.length() < precision)
		{
			digits = "0" + digits;
		}
		for (int i = 0; i < precision; i++)
		{
			int zone = 0xF0;
			if (i == precision - 1 && signed)
			{
				zone = value.signum() < 0 ? 0xD0 : 0xC0;
			}
			bytes[offset + i] = (byte) (zone | (digits.charAt(i) - '0'));
		}
	}
}
