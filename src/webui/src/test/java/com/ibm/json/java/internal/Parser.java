/*
 *
 *    Copyright IBM Corp. 2023, 2025
 *
 */
package com.ibm.json.java.internal;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;

import com.ibm.json.java.JSONArray;
import com.ibm.json.java.JSONObject;

/** Test-only stand-in for the JSON4J parser (numbers become Long or Double). */
public class Parser
{

	private final String text;

	private int pos;


	public Parser(Reader reader) throws IOException
	{
		StringWriter w = new StringWriter();
		reader.transferTo(w);
		this.text = w.toString();
	}


	public JSONObject parse() throws IOException
	{
		Object value = value();
		if (!(value instanceof JSONObject))
		{
			throw new IOException("Expected JSON object");
		}
		return (JSONObject) value;
	}


	private Object value() throws IOException
	{
		skip();
		char c = text.charAt(pos);
		if (c == '{')
		{
			JSONObject o = new JSONObject();
			pos++;
			skip();
			if (text.charAt(pos) == '}')
			{
				pos++;
				return o;
			}
			while (true)
			{
				skip();
				String key = string();
				skip();
				expect(':');
				o.put(key, value());
				skip();
				if (text.charAt(pos++) == '}')
				{
					return o;
				}
			}
		}
		if (c == '[')
		{
			JSONArray a = new JSONArray();
			pos++;
			skip();
			if (text.charAt(pos) == ']')
			{
				pos++;
				return a;
			}
			while (true)
			{
				a.add(value());
				skip();
				if (text.charAt(pos++) == ']')
				{
					return a;
				}
			}
		}
		if (c == '"')
		{
			return string();
		}
		if (text.startsWith("true", pos))
		{
			pos += 4;
			return Boolean.TRUE;
		}
		if (text.startsWith("false", pos))
		{
			pos += 5;
			return Boolean.FALSE;
		}
		if (text.startsWith("null", pos))
		{
			pos += 4;
			return null;
		}
		int start = pos;
		while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0)
		{
			pos++;
		}
		String n = text.substring(start, pos);
		if (n.isEmpty())
		{
			throw new IOException("Unexpected character at " + pos);
		}
		return n.matches("-?\\d+") ? (Object) Long.valueOf(n) : (Object) Double.valueOf(n);
	}


	private String string() throws IOException
	{
		expect('"');
		StringBuilder sb = new StringBuilder();
		while (true)
		{
			char c = text.charAt(pos++);
			if (c == '"')
			{
				return sb.toString();
			}
			if (c == '\\')
			{
				char e = text.charAt(pos++);
				switch (e)
				{
				case 'n':
					sb.append('\n');
					break;
				case 't':
					sb.append('\t');
					break;
				case 'u':
					sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
					pos += 4;
					break;
				default:
					sb.append(e);
				}
			}
			else
			{
				sb.append(c);
			}
		}
	}


	private void expect(char c) throws IOException
	{
		if (text.charAt(pos++) != c)
		{
			throw new IOException("Expected '" + c + "' at " + (pos - 1));
		}
	}


	private void skip()
	{
		while (pos < text.length() && Character.isWhitespace(text.charAt(pos)))
		{
			pos++;
		}
	}
}
