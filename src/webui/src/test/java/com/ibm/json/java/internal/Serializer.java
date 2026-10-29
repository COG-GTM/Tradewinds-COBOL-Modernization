/*
 *
 *    Copyright IBM Corp. 2023, 2025
 *
 */
package com.ibm.json.java.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.ibm.json.java.JSONArray;
import com.ibm.json.java.JSONObject;

/**
 * Test-only stand-in for the JSON4J serializer that Liberty's json-1.0
 * feature provides at runtime (the Maven API jar omits it).
 */
public class Serializer
{

	private final Writer writer;


	public Serializer(Writer writer)
	{
		this.writer = writer;
	}


	public Serializer writeObject(JSONObject object)
	{
		writeValue(object);
		return this;
	}


	public Serializer writeArray(JSONArray array)
	{
		writeValue(array);
		return this;
	}


	public void flush() throws IOException
	{
		writer.flush();
	}


	private void writeValue(Object value)
	{
		try
		{
			if (value == null)
			{
				writer.write("null");
			}
			else if (value instanceof Map)
			{
				writer.write('{');
				Iterator<?> it = ((Map<?, ?>) value).entrySet().iterator();
				while (it.hasNext())
				{
					Map.Entry<?, ?> e = (Map.Entry<?, ?>) it.next();
					writeString(String.valueOf(e.getKey()));
					writer.write(':');
					writeValue(e.getValue());
					if (it.hasNext())
					{
						writer.write(',');
					}
				}
				writer.write('}');
			}
			else if (value instanceof List)
			{
				writer.write('[');
				Iterator<?> it = ((List<?>) value).iterator();
				while (it.hasNext())
				{
					writeValue(it.next());
					if (it.hasNext())
					{
						writer.write(',');
					}
				}
				writer.write(']');
			}
			else if (value instanceof Number || value instanceof Boolean)
			{
				writer.write(value.toString());
			}
			else
			{
				writeString(value.toString());
			}
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}


	private void writeString(String s) throws IOException
	{
		writer.write('"');
		for (char c : s.toCharArray())
		{
			switch (c)
			{
			case '"':
				writer.write("\\\"");
				break;
			case '\\':
				writer.write("\\\\");
				break;
			case '\n':
				writer.write("\\n");
				break;
			default:
				if (c < 0x20)
				{
					writer.write(String.format("\\u%04x", (int) c));
				}
				else
				{
					writer.write(c);
				}
			}
		}
		writer.write('"');
	}
}
