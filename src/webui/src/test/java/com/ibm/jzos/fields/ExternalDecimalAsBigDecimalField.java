/*
 *
 *    Copyright IBM Corp. 2023, 2025
 *
 */
package com.ibm.jzos.fields;

import java.math.BigDecimal;

/** Test-only JZOS stand-in. */
public class ExternalDecimalAsBigDecimalField extends ZonedDecimal
{

	private final int scale;


	ExternalDecimalAsBigDecimalField(int offset, int precision, int scale,
			boolean signed)
	{
		super(offset, precision, signed);
		this.scale = scale;
	}


	public BigDecimal getBigDecimal(byte[] bytes)
	{
		return new BigDecimal(read(bytes), scale);
	}


	public void putBigDecimal(BigDecimal value, byte[] bytes)
	{
		write(value.setScale(scale, java.math.RoundingMode.HALF_UP)
				.unscaledValue(), bytes);
	}


	public boolean equals(BigDecimal a, BigDecimal b)
	{
		return a != null && b != null && a.compareTo(b) == 0;
	}
}
