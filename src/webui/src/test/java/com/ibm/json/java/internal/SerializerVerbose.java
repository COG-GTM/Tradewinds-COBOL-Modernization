/*
 *
 *    Copyright IBM Corp. 2023, 2025
 *
 */
package com.ibm.json.java.internal;

import java.io.Writer;

/** Test-only stand-in; output is not indented. */
public class SerializerVerbose extends Serializer
{

	public SerializerVerbose(Writer writer)
	{
		super(writer);
	}
}
