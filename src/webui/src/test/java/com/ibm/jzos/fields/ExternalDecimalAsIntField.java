/*
 *
 *    Copyright IBM Corp. 2023, 2025
 *
 */
package com.ibm.jzos.fields;

import java.math.BigInteger;

/** Test-only JZOS stand-in. */
public class ExternalDecimalAsIntField extends ZonedDecimal
{

	ExternalDecimalAsIntField(int offset, int precision, boolean signed)
	{
		super(offset, precision, signed);
	}


	public int getInt(byte[] bytes)
	{
		return read(bytes).intValue();
	}


	public void putInt(int value, byte[] bytes)
	{
		write(BigInteger.valueOf(value), bytes);
	}


	public boolean equals(Integer a, int b)
	{
		return a != null && a.intValue() == b;
	}
}
