/*
 *
 *    Copyright IBM Corp. 2023, 2025
 *
 */
package com.ibm.jzos.fields;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Test-only stand-in for the z/OS JZOS field factory (the ibm.jzos jar on
 * Maven is a compile-only stub). Implements just what the generated
 * datainterfaces records use: EBCDIC strings and unsigned/signed zoned
 * decimals laid out at running offsets.
 */
public class CobolDatatypeFactory
{

	private int offset;

	private final Deque<Integer> stack = new ArrayDeque<>();


	public void setStringTrimDefault(boolean trim)
	{
		// Records are built with trimming off, which is the only mode used
	}


	public int getOffset()
	{
		return offset;
	}


	public void setOffset(int newOffset)
	{
		offset = newOffset;
	}


	public void pushOffset()
	{
		stack.push(offset);
	}


	public void popOffset()
	{
		offset = stack.pop();
	}


	public StringField getStringField(int length)
	{
		StringField f = new StringField(offset, length);
		offset += length;
		return f;
	}


	public ExternalDecimalAsIntField getExternalDecimalAsIntField(int precision,
			boolean signed, boolean signTrailing, boolean signExternal,
			boolean blankWhenZero)
	{
		ExternalDecimalAsIntField f = new ExternalDecimalAsIntField(offset,
				precision, signed);
		offset += precision;
		return f;
	}


	public ExternalDecimalAsLongField getExternalDecimalAsLongField(
			int precision, boolean signed, boolean signTrailing,
			boolean signExternal, boolean blankWhenZero)
	{
		ExternalDecimalAsLongField f = new ExternalDecimalAsLongField(offset,
				precision, signed);
		offset += precision;
		return f;
	}


	public ExternalDecimalAsBigDecimalField getExternalDecimalAsBigDecimalField(
			int precision, int scale, boolean signed, boolean signTrailing,
			boolean signExternal, boolean blankWhenZero)
	{
		ExternalDecimalAsBigDecimalField f = new ExternalDecimalAsBigDecimalField(
				offset, precision, scale, signed);
		offset += precision;
		return f;
	}
}
