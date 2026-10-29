/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.account;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal RFC 8259 JSON reader/writer over Map / List / String / Number /
 * Boolean / null. Used instead of com.ibm.json.java because that API jar has
 * no implementation outside a Liberty runtime, which would make the data
 * service untestable off-platform. Numbers parse to Long when integral,
 * otherwise BigDecimal (never double, so money keeps its exact value).
 */
public final class JsonCodec
{

	private final String text;

	private int pos;


	private JsonCodec(String text)
	{
		this.text = text;
	}


	public static Object parse(String text)
	{
		if (text == null)
		{
			throw new IllegalArgumentException("No JSON supplied");
		}
		JsonCodec p = new JsonCodec(text);
		p.skipWhitespace();
		Object value = p.readValue();
		p.skipWhitespace();
		if (p.pos != text.length())
		{
			throw p.error("Unexpected trailing content");
		}
		return value;
	}


	public static String serialize(Object value)
	{
		StringBuilder sb = new StringBuilder();
		write(sb, value);
		return sb.toString();
	}


	private Object readValue()
	{
		if (pos >= text.length())
		{
			throw error("Unexpected end of input");
		}
		char c = text.charAt(pos);
		switch (c)
		{
		case '{':
			return readObject();
		case '[':
			return readArray();
		case '"':
			return readString();
		case 't':
			return literal("true", Boolean.TRUE);
		case 'f':
			return literal("false", Boolean.FALSE);
		case 'n':
			return literal("null", null);
		default:
			if (c == '-' || (c >= '0' && c <= '9'))
			{
				return readNumber();
			}
			throw error("Unexpected character '" + c + "'");
		}
	}


	private Map<String, Object> readObject()
	{
		Map<String, Object> map = new LinkedHashMap<>();
		pos++;
		skipWhitespace();
		if (peek() == '}')
		{
			pos++;
			return map;
		}
		while (true)
		{
			skipWhitespace();
			if (peek() != '"')
			{
				throw error("Expected object key");
			}
			String key = readString();
			skipWhitespace();
			expect(':');
			skipWhitespace();
			map.put(key, readValue());
			skipWhitespace();
			char c = next();
			if (c == '}')
			{
				return map;
			}
			if (c != ',')
			{
				throw error("Expected ',' or '}'");
			}
		}
	}


	private List<Object> readArray()
	{
		List<Object> list = new ArrayList<>();
		pos++;
		skipWhitespace();
		if (peek() == ']')
		{
			pos++;
			return list;
		}
		while (true)
		{
			skipWhitespace();
			list.add(readValue());
			skipWhitespace();
			char c = next();
			if (c == ']')
			{
				return list;
			}
			if (c != ',')
			{
				throw error("Expected ',' or ']'");
			}
		}
	}


	private String readString()
	{
		pos++;
		StringBuilder sb = new StringBuilder();
		while (true)
		{
			char c = next();
			if (c == '"')
			{
				return sb.toString();
			}
			if (c == '\\')
			{
				char e = next();
				switch (e)
				{
				case '"':
				case '\\':
				case '/':
					sb.append(e);
					break;
				case 'b':
					sb.append('\b');
					break;
				case 'f':
					sb.append('\f');
					break;
				case 'n':
					sb.append('\n');
					break;
				case 'r':
					sb.append('\r');
					break;
				case 't':
					sb.append('\t');
					break;
				case 'u':
					if (pos + 4 > text.length())
					{
						throw error("Bad unicode escape");
					}
					sb.append((char) Integer
							.parseInt(text.substring(pos, pos + 4), 16));
					pos += 4;
					break;
				default:
					throw error("Bad escape '\\" + e + "'");
				}
			}
			else if (c < 0x20)
			{
				throw error("Control character in string");
			}
			else
			{
				sb.append(c);
			}
		}
	}


	private Object readNumber()
	{
		int start = pos;
		while (pos < text.length()
				&& "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0)
		{
			pos++;
		}
		String n = text.substring(start, pos);
		try
		{
			BigDecimal d = new BigDecimal(n);
			if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0
					&& d.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0
					&& d.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) >= 0)
			{
				return Long.valueOf(d.longValueExact());
			}
			return d;
		}
		catch (NumberFormatException | ArithmeticException e)
		{
			throw error("Bad number '" + n + "'");
		}
	}


	private Object literal(String word, Object value)
	{
		if (!text.startsWith(word, pos))
		{
			throw error("Expected " + word);
		}
		pos += word.length();
		return value;
	}


	private void skipWhitespace()
	{
		while (pos < text.length() && " \t\r\n".indexOf(text.charAt(pos)) >= 0)
		{
			pos++;
		}
	}


	private char peek()
	{
		if (pos >= text.length())
		{
			throw error("Unexpected end of input");
		}
		return text.charAt(pos);
	}


	private char next()
	{
		char c = peek();
		pos++;
		return c;
	}


	private void expect(char c)
	{
		if (next() != c)
		{
			throw error("Expected '" + c + "'");
		}
	}


	private IllegalArgumentException error(String message)
	{
		return new IllegalArgumentException(
				message + " at position " + pos);
	}


	private static void write(StringBuilder sb, Object value)
	{
		if (value == null)
		{
			sb.append("null");
		}
		else if (value instanceof String)
		{
			writeString(sb, (String) value);
		}
		else if (value instanceof BigDecimal)
		{
			sb.append(((BigDecimal) value).toPlainString());
		}
		else if (value instanceof Number || value instanceof Boolean)
		{
			sb.append(value);
		}
		else if (value instanceof Map)
		{
			sb.append('{');
			Iterator<? extends Map.Entry<?, ?>> it = ((Map<?, ?>) value)
					.entrySet().iterator();
			while (it.hasNext())
			{
				Map.Entry<?, ?> e = it.next();
				writeString(sb, String.valueOf(e.getKey()));
				sb.append(':');
				write(sb, e.getValue());
				if (it.hasNext())
				{
					sb.append(',');
				}
			}
			sb.append('}');
		}
		else if (value instanceof Iterable)
		{
			sb.append('[');
			Iterator<?> it = ((Iterable<?>) value).iterator();
			while (it.hasNext())
			{
				write(sb, it.next());
				if (it.hasNext())
				{
					sb.append(',');
				}
			}
			sb.append(']');
		}
		else
		{
			writeString(sb, value.toString());
		}
	}


	private static void writeString(StringBuilder sb, String s)
	{
		sb.append('"');
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			switch (c)
			{
			case '"':
				sb.append("\\\"");
				break;
			case '\\':
				sb.append("\\\\");
				break;
			case '\n':
				sb.append("\\n");
				break;
			case '\r':
				sb.append("\\r");
				break;
			case '\t':
				sb.append("\\t");
				break;
			default:
				if (c < 0x20)
				{
					sb.append(String.format("\\u%04x", (int) c));
				}
				else
				{
					sb.append(c);
				}
			}
		}
		sb.append('"');
	}
}
