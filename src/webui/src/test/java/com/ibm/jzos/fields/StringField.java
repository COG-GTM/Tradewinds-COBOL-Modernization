/*
 *
 *    Copyright IBM Corp. 2023, 2025
 *
 */
package com.ibm.jzos.fields;

import java.nio.charset.Charset;
import java.util.Arrays;

/** Test-only JZOS stand-in: blank-padded EBCDIC (IBM-1047) text field. */
public class StringField
{

	private static final Charset EBCDIC = Charset.forName("IBM1047");

	private final int offset;

	private final int length;


	StringField(int offset, int length)
	{
		this.offset = offset;
		this.length = length;
	}


	public int getOffset()
	{
		return offset;
	}


	public String getString(byte[] bytes)
	{
		return new String(bytes, offset, length, EBCDIC);
	}


	public void putString(String value, byte[] bytes)
	{
		byte[] encoded = value.getBytes(EBCDIC);
		Arrays.fill(bytes, offset, offset + length,
				" ".getBytes(EBCDIC)[0]);
		System.arraycopy(encoded, 0, bytes, offset,
				Math.min(encoded.length, length));
	}


	public boolean equals(String a, String b)
	{
		return a != null && a.equals(b);
	}
}
