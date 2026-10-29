/*
 *
 *    Copyright IBM Corp. 2023, 2025
 *
 */
package com.ibm.jzos.fields;

import java.math.BigInteger;

/** Test-only JZOS stand-in. */
public class ExternalDecimalAsLongField extends ZonedDecimal
{

	ExternalDecimalAsLongField(int offset, int precision, boolean signed)
	{
		super(offset, precision, signed);
	}


	public long getLong(byte[] bytes)
	{
		return read(bytes).longValue();
	}


	public void putLong(long value, byte[] bytes)
	{
		write(BigInteger.valueOf(value), bytes);
	}


	public boolean equals(Long a, long b)
	{
		return a != null && a.longValue() == b;
	}
}
