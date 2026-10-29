/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.account;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Request validation and JSON / HTTP-status mapping for the account data
 * service. Independent of JAX-RS and JCICS so it can be tested against a
 * plain JDBC connection. See API.md in this package for the contract.
 */
public class AccountDataService
{

	private static final Logger LOGGER = Logger
			.getLogger(AccountDataService.class.getName());

	private static final String SUCCESS = "success";

	private static final String FAIL_CODE = "failCode";

	private static final BigDecimal MAX_INTEREST_RATE = new BigDecimal(
			"9999.99");

	private static final BigDecimal MAX_BALANCE = new BigDecimal(
			"9999999999.99");

	private static final long MAX_OVERDRAFT = 99999999L;

	private static final String[] CREACC_VALID_TYPE_PREFIXES = { "ISA",
			"MORTGAGE", "SAVING", "CURRENT", "LOAN" };

	private static final DateTimeFormatter DDMMYYYY = DateTimeFormatter
			.ofPattern("ddMMuuuu");

	private final AccountDao dao;

	private final Supplier<String> defaultTaskRef;


	public AccountDataService(Connection conn, Clock clock,
			Supplier<String> defaultTaskRef)
	{
		this.dao = new AccountDao(conn, clock);
		this.defaultTaskRef = defaultTaskRef;
	}


	// ------------------------------------------------------------- INQACC

	/** GET /data/account/{sortcode}/{number}. */
	public ServiceResult inquire(String sortcode, String accountNumber)
	{
		try
		{
			String sc = CobolFormat.digits(sortcode,
					CobolFormat.SORTCODE_LENGTH, "sortcode");
			String acc = CobolFormat.digits(accountNumber,
					CobolFormat.ACCOUNT_NUMBER_LENGTH, "accountNumber");
			if (AccountDao.LAST_ACCOUNT_SENTINEL.equals(acc))
			{
				return inquireLast(sc);
			}
			Optional<AccountRecord> rec;
			try
			{
				rec = dao.findAccount(sc, acc);
			}
			catch (SQLException e)
			{
				throw new DataServiceException(500, " ", "HRAC",
						"ACCOUNT select failed: " + e.getMessage(), e);
			}
			if (!rec.isPresent())
			{
				throw DataServiceException.notFound("1",
						"Account " + sc + "/" + acc + " not found");
			}
			return accountResult(200, rec.get());
		}
		catch (DataServiceException e)
		{
			return error(e);
		}
	}


	/** GET /data/account/{sortcode}/last. */
	public ServiceResult inquireLast(String sortcode)
	{
		try
		{
			String sc = CobolFormat.digits(sortcode,
					CobolFormat.SORTCODE_LENGTH, "sortcode");
			Optional<AccountRecord> rec;
			try
			{
				rec = dao.findLastAccount(sc);
			}
			catch (SQLException e)
			{
				throw new DataServiceException(500, " ", "HNCS",
						"ACCOUNT select failed: " + e.getMessage(), e);
			}
			if (!rec.isPresent())
			{
				throw new DataServiceException(404, "1", "HNCS",
						"No accounts for sortcode " + sc, null);
			}
			return accountResult(200, rec.get());
		}
		catch (DataServiceException e)
		{
			return error(e);
		}
	}


	// ----------------------------------------------------------- INQACCCU

	/** GET /data/account/{sortcode}?customerNumber=... */
	public ServiceResult inquireByCustomer(String sortcode,
			String customerNumber)
	{
		try
		{
			String sc = CobolFormat.digits(sortcode,
					CobolFormat.SORTCODE_LENGTH, "sortcode");
			String cust = CobolFormat.digits(customerNumber,
					CobolFormat.CUSTOMER_NUMBER_LENGTH, "customerNumber");
			if (Long.parseLong(cust) == 0L || "9999999999".equals(cust))
			{
				DataServiceException nf = DataServiceException.notFound("1",
						"Customer " + cust + " is not a valid customer");
				ServiceResult r = error(nf);
				r.getBody().put("customerFound", "N");
				r.getBody().put("numberOfAccounts", Long.valueOf(0));
				return r;
			}
			List<AccountRecord> accounts;
			try
			{
				accounts = dao.findAccountsForCustomer(sc, cust);
			}
			catch (SQLException e)
			{
				ServiceResult r = error(new DataServiceException(500, "3",
						null, "ACCOUNT fetch failed: " + e.getMessage(), e));
				r.getBody().put("customerFound", "N");
				r.getBody().put("numberOfAccounts", Long.valueOf(0));
				return r;
			}
			Map<String, Object> body = ok();
			body.put("customerNumber", cust);
			body.put("numberOfAccounts", Long.valueOf(accounts.size()));
			List<Object> arr = new ArrayList<>();
			for (AccountRecord rec : accounts)
			{
				arr.add(rec.toJson());
			}
			body.put("accounts", arr);
			return new ServiceResult(200, body);
		}
		catch (DataServiceException e)
		{
			return error(e);
		}
	}


	// ------------------------------------------------------------- CREACC

	/** POST /data/account. */
	public ServiceResult create(String requestBody)
	{
		try
		{
			Map<String, Object> in = parseObject(requestBody);
			AccountDao.NewAccount req = new AccountDao.NewAccount();
			req.sortcode = CobolFormat.digits(string(in, "sortcode"),
					CobolFormat.SORTCODE_LENGTH, "sortcode");
			req.customerNumber = CobolFormat.digits(
					string(in, "customerNumber"),
					CobolFormat.CUSTOMER_NUMBER_LENGTH, "customerNumber");
			String accountNumber = string(in, "accountNumber");
			if (accountNumber != null)
			{
				req.accountNumber = CobolFormat.digits(accountNumber,
						CobolFormat.ACCOUNT_NUMBER_LENGTH, "accountNumber");
			}
			req.accountType = creaccAccountType(string(in, "accountType"));
			req.interestRate = interestRate(in, false);
			req.overdraftLimit = overdraft(in, false);
			req.availableBalance = balance(in, "availableBalance");
			req.actualBalance = balance(in, "actualBalance");
			String taskRef = taskRef(string(in, "taskRef"));
			return accountResult(201, dao.createAccount(req, taskRef));
		}
		catch (DataServiceException e)
		{
			return error(e);
		}
	}


	// ------------------------------------------------------------- UPDACC

	/** PUT /data/account/{sortcode}/{number}. */
	public ServiceResult update(String sortcode, String accountNumber,
			String requestBody)
	{
		try
		{
			String sc = CobolFormat.digits(sortcode,
					CobolFormat.SORTCODE_LENGTH, "sortcode");
			String acc = CobolFormat.digits(accountNumber,
					CobolFormat.ACCOUNT_NUMBER_LENGTH, "accountNumber");
			Map<String, Object> in = parseObject(requestBody);
			String type = in.get("accountType") == null ? ""
					: in.get("accountType").toString();
			if (type.length() > CobolFormat.ACCOUNT_TYPE_LENGTH)
			{
				throw DataServiceException.validation("V",
						"accountType must be at most 8 characters");
			}
			BigDecimal rate = interestRate(in, true);
			long overdraft = overdraft(in, true);
			return accountResult(200,
					dao.updateAccount(sc, acc, type, rate, overdraft));
		}
		catch (DataServiceException e)
		{
			return error(e);
		}
	}


	// ------------------------------------------------------------- DELACC

	/** DELETE /data/account/{sortcode}/{number}?taskRef=... */
	public ServiceResult delete(String sortcode, String accountNumber,
			String taskRef)
	{
		try
		{
			String sc = CobolFormat.digits(sortcode,
					CobolFormat.SORTCODE_LENGTH, "sortcode");
			String acc = CobolFormat.digits(accountNumber,
					CobolFormat.ACCOUNT_NUMBER_LENGTH, "accountNumber");
			return accountResult(200,
					dao.deleteAccount(sc, acc, taskRef(taskRef)));
		}
		catch (DataServiceException e)
		{
			return error(e);
		}
	}


	// ----------------------------------------------------------- BANKDATA

	/**
	 * POST /data/admin/account/reset?sortcode=...[&lastAccountNumber=...]
	 * [&numberOfAccounts=...] with a JSON array of accounts.
	 */
	@SuppressWarnings("unchecked")
	public ServiceResult reset(String sortcode, String requestBody,
			String lastAccountNumber, String numberOfAccounts)
	{
		try
		{
			String sc = CobolFormat.digits(sortcode,
					CobolFormat.SORTCODE_LENGTH, "sortcode");
			Long last = lastAccountNumber == null ? null
					: Long.valueOf(CobolFormat.digits(lastAccountNumber,
							CobolFormat.ACCOUNT_NUMBER_LENGTH,
							"lastAccountNumber"));
			Long count = numberOfAccounts == null ? null
					: Long.valueOf(CobolFormat.digits(numberOfAccounts, 9,
							"numberOfAccounts"));
			Object parsed;
			try
			{
				parsed = JsonCodec.parse(requestBody);
			}
			catch (IllegalArgumentException e)
			{
				parsed = null;
			}
			if (!(parsed instanceof List))
			{
				throw DataServiceException.validation("V",
						"Request body must be a JSON array of accounts");
			}
			List<?> arr = (List<?>) parsed;
			List<AccountRecord> accounts = new ArrayList<>(arr.size());
			for (int i = 0; i < arr.size(); i++)
			{
				if (!(arr.get(i) instanceof Map))
				{
					throw DataServiceException.validation("V",
							"Element " + i + " is not a JSON object");
				}
				accounts.add(bulkAccount(sc, (Map<String, Object>) arr.get(i), i));
			}
			int inserted = dao.resetAccounts(sc, accounts, last, count);
			Map<String, Object> body = ok();
			body.put("sortcode", sc);
			body.put("accountsInserted", Long.valueOf(inserted));
			return new ServiceResult(200, body);
		}
		catch (DataServiceException e)
		{
			return error(e);
		}
	}


	// ----------------------------------------------------------- helpers

	private AccountRecord bulkAccount(String sortcode, Map<String, Object> in,
			int index) throws DataServiceException
	{
		String where = "accounts[" + index + "].";
		String recSort = string(in, "sortcode");
		if (recSort != null && !sortcode.equals(CobolFormat.digits(recSort,
				CobolFormat.SORTCODE_LENGTH, where + "sortcode")))
		{
			throw DataServiceException.validation("V",
					where + "sortcode does not match the reset sortcode");
		}
		AccountRecord rec = new AccountRecord();
		String eye = string(in, "eyeCatcher");
		rec.setEyecatcher(eye == null ? AccountRecord.EYECATCHER : eye);
		rec.setSortcode(sortcode);
		rec.setCustomerNumber(CobolFormat.digits(string(in, "customerNumber"),
				CobolFormat.CUSTOMER_NUMBER_LENGTH, where + "customerNumber"));
		rec.setAccountNumber(CobolFormat.digits(string(in, "accountNumber"),
				CobolFormat.ACCOUNT_NUMBER_LENGTH, where + "accountNumber"));
		String type = string(in, "accountType");
		if (type == null || type.trim().isEmpty()
				|| type.length() > CobolFormat.ACCOUNT_TYPE_LENGTH)
		{
			throw DataServiceException.validation("V",
					where + "accountType must be 1 to 8 characters");
		}
		rec.setAccountType(
				CobolFormat.blankPad(type, CobolFormat.ACCOUNT_TYPE_LENGTH));
		rec.setInterestRate(interestRate(in, false));
		rec.setOverdraftLimit(overdraft(in, false));
		rec.setOpened(date(in, "opened", where));
		rec.setLastStatement(date(in, "lastStatementDate", where));
		rec.setNextStatement(date(in, "nextStatementDate", where));
		rec.setAvailableBalance(balance(in, "availableBalance"));
		rec.setActualBalance(balance(in, "actualBalance"));
		return rec;
	}


	/** CREACC ACCOUNT-TYPE-CHECK (ATC010): prefix match, else fail code A. */
	static String creaccAccountType(String type) throws DataServiceException
	{
		if (type != null && type.length() <= CobolFormat.ACCOUNT_TYPE_LENGTH)
		{
			String padded = CobolFormat.blankPad(type,
					CobolFormat.ACCOUNT_TYPE_LENGTH);
			for (String prefix : CREACC_VALID_TYPE_PREFIXES)
			{
				if (padded.startsWith(prefix))
				{
					return padded;
				}
			}
		}
		throw DataServiceException.validation("A",
				"accountType must start with ISA, MORTGAGE, SAVING, CURRENT or LOAN");
	}


	private String taskRef(String supplied) throws DataServiceException
	{
		String raw = supplied != null ? supplied : defaultTaskRef.get();
		return CobolFormat.digits(raw, CobolFormat.TASK_REF_LENGTH, "taskRef");
	}


	private static BigDecimal interestRate(Map<String, Object> in, boolean required)
			throws DataServiceException
	{
		BigDecimal rate = decimal(in, "interestRate", required);
		if (rate.signum() < 0 || rate.compareTo(MAX_INTEREST_RATE) > 0)
		{
			throw DataServiceException.validation("V",
					"interestRate must be between 0 and 9999.99");
		}
		return rate;
	}


	private static long overdraft(Map<String, Object> in, boolean required)
			throws DataServiceException
	{
		BigDecimal value = decimal(in, "overdraftLimit", required);
		if (value.signum() < 0
				|| value.compareTo(BigDecimal.valueOf(MAX_OVERDRAFT)) > 0)
		{
			throw DataServiceException.validation("V",
					"overdraftLimit must be between 0 and 99999999");
		}
		return value.longValue();
	}


	private static BigDecimal balance(Map<String, Object> in, String key)
			throws DataServiceException
	{
		BigDecimal value = decimal(in, key, false);
		if (value.abs().compareTo(MAX_BALANCE) > 0)
		{
			throw DataServiceException.validation("V",
					key + " exceeds PIC S9(10)V99");
		}
		return value;
	}


	/**
	 * Decimal with COBOL MOVE semantics into a V99 field: digits beyond two
	 * places are truncated. Missing optional values default to zero.
	 */
	private static BigDecimal decimal(Map<String, Object> in, String key,
			boolean required) throws DataServiceException
	{
		Object raw = in.get(key);
		if (raw == null)
		{
			if (required)
			{
				throw DataServiceException.validation("V", key + " is required");
			}
			return BigDecimal.ZERO.setScale(2);
		}
		try
		{
			return new BigDecimal(raw.toString().trim()).setScale(2,
					RoundingMode.DOWN);
		}
		catch (NumberFormatException e)
		{
			throw DataServiceException.validation("V",
					key + " must be numeric");
		}
	}


	private static LocalDate date(Map<String, Object> in, String key, String where)
			throws DataServiceException
	{
		String raw = string(in, key);
		if (raw == null)
		{
			throw DataServiceException.validation("V",
					where + key + " is required");
		}
		try
		{
			return raw.length() == 8 ? LocalDate.parse(raw, DDMMYYYY)
					: LocalDate.parse(raw);
		}
		catch (DateTimeParseException e)
		{
			throw DataServiceException.validation("V",
					where + key + " must be DDMMYYYY or YYYY-MM-DD");
		}
	}


	private static String string(Map<String, Object> in, String key)
	{
		Object v = in.get(key);
		return v == null ? null : v.toString();
	}


	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseObject(String body)
			throws DataServiceException
	{
		Object parsed;
		try
		{
			parsed = JsonCodec.parse(body);
		}
		catch (IllegalArgumentException e)
		{
			parsed = null;
		}
		if (!(parsed instanceof Map))
		{
			throw DataServiceException.validation("V",
					"Request body must be a JSON object");
		}
		return (Map<String, Object>) parsed;
	}


	private static Map<String, Object> ok()
	{
		Map<String, Object> body = new LinkedHashMap<>();
		body.put(SUCCESS, "Y");
		body.put(FAIL_CODE, " ");
		return body;
	}


	private static ServiceResult accountResult(int status, AccountRecord rec)
	{
		Map<String, Object> body = ok();
		body.put("account", rec.toJson());
		return new ServiceResult(status, body);
	}


	static ServiceResult error(DataServiceException e)
	{
		if (e.getHttpStatus() >= 500)
		{
			LOGGER.log(Level.SEVERE, e.getMessage(), e.getCause());
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put(SUCCESS, "N");
		body.put(FAIL_CODE, e.getFailCode());
		if (e.getAbendCode() != null)
		{
			body.put("abendCode", e.getAbendCode());
		}
		body.put("message", e.getMessage());
		return new ServiceResult(e.getHttpStatus(), body);
	}
}
