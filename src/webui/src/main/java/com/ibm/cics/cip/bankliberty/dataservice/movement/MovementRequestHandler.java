/*
 *
 *    Copyright IBM Corp. 2023
 *
 */

package com.ibm.cics.cip.bankliberty.dataservice.movement;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.ibm.cics.cip.bankliberty.dataservice.movement.MovementJson.CustomerRequest;
import com.ibm.cics.cip.bankliberty.dataservice.movement.MovementJson.DebitCreditRequest;
import com.ibm.cics.cip.bankliberty.dataservice.movement.MovementJson.MovementResponse;
import com.ibm.cics.cip.bankliberty.dataservice.movement.MovementJson.ProctranListResponse;
import com.ibm.cics.cip.bankliberty.dataservice.movement.MovementJson.ProctranRow;
import com.ibm.cics.cip.bankliberty.dataservice.movement.MovementJson.ProctranWriteRequest;
import com.ibm.cics.cip.bankliberty.dataservice.movement.MovementJson.TransferRequest;

/**
 * Request validation and JSON mapping for the movement / proctran data
 * service. Kept free of JAX-RS and JCICS so it can be tested directly
 * against a JDBC connection.
 */
public final class MovementRequestHandler
{

	private static final Logger logger = Logger
			.getLogger("com.ibm.cics.cip.bankliberty.dataservice.movement");

	/** Fail code for a malformed request (no COBOL equivalent). */
	public static final String FAIL_CODE_INVALID_REQUEST = "V";

	static final int DEFAULT_LIMIT = 250000;

	private MovementRequestHandler()
	{
	}

	/** POST /data/movement/debitCredit (DBCRFUN). */
	public static DataServiceResult debitCredit(Connection conn, Clock clock,
			DebitCreditRequest req)
	{
		try
		{
			require(req != null, "request body is required");
			MovementResult r = new MovementDao(conn, clock).debitCredit(
					required(req.sortCode, "sortCode"),
					required(req.accountNumber, "accountNumber"),
					decimal(req.amount, "amount"),
					req.facilityType == null ? 0 : req.facilityType,
					req.applId, req.userId, taskNumber(req.taskNumber));
			MovementResponse json = toJson(r);
			json.availableBalance = money(r.getAvailableBalance());
			json.actualBalance = money(r.getActualBalance());
			return new DataServiceResult(r.getHttpStatus(), json);
		}
		catch (IllegalArgumentException e)
		{
			return invalid(e);
		}
	}

	/** POST /data/movement/transfer (XFRFUN). */
	public static DataServiceResult transfer(Connection conn, Clock clock,
			TransferRequest req)
	{
		try
		{
			require(req != null, "request body is required");
			MovementResult r = new MovementDao(conn, clock).transfer(
					required(req.sortCode, "sortCode"),
					required(req.fromAccountNumber, "fromAccountNumber"),
					required(req.toAccountNumber, "toAccountNumber"),
					decimal(req.amount, "amount"), taskNumber(req.taskNumber));
			MovementResponse json = toJson(r);
			json.fromAvailableBalance = money(r.getAvailableBalance());
			json.fromActualBalance = money(r.getActualBalance());
			json.toAvailableBalance = money(r.getToAvailableBalance());
			json.toActualBalance = money(r.getToActualBalance());
			return new DataServiceResult(r.getHttpStatus(), json);
		}
		catch (IllegalArgumentException e)
		{
			return invalid(e);
		}
	}

	/** POST /data/proctran/createCustomer (CRECUST WPD010). */
	public static DataServiceResult createCustomer(Connection conn,
			Clock clock, CustomerRequest req)
	{
		return customer(conn, clock, req, true);
	}

	/** POST /data/proctran/deleteCustomer (DELCUS WPCD010). */
	public static DataServiceResult deleteCustomer(Connection conn,
			Clock clock, CustomerRequest req)
	{
		return customer(conn, clock, req, false);
	}

	private static DataServiceResult customer(Connection conn, Clock clock,
			CustomerRequest req, boolean create)
	{
		try
		{
			require(req != null, "request body is required");
			String sortCode = required(req.sortCode, "sortCode");
			String customerNumber = required(req.customerNumber,
					"customerNumber");
			String customerName = required(req.customerName, "customerName");
			String dateOfBirth = required(req.dateOfBirth, "dateOfBirth");
			long taskNumber = taskNumber(req.taskNumber);
			ProctranDao dao = new ProctranDao(conn, clock);
			MovementResult r = create
					? dao.writeCreateCustomer(sortCode, customerNumber,
							customerName, dateOfBirth, taskNumber)
					: dao.writeDeleteCustomer(sortCode, customerNumber,
							customerName, dateOfBirth, taskNumber);
			return new DataServiceResult(r.getHttpStatus(), toJson(r));
		}
		catch (IllegalArgumentException e)
		{
			return invalid(e);
		}
	}

	/** POST /data/proctran (generic validated writer). */
	public static DataServiceResult writeProctran(Connection conn,
			Clock clock, ProctranWriteRequest req)
	{
		try
		{
			require(req != null, "request body is required");
			ProctranDao dao = new ProctranDao(conn, clock);
			ProctranRecord rec = dao.newRecord(
					required(req.sortCode, "sortCode"),
					required(req.accountNumber, "accountNumber"),
					taskNumber(req.taskNumber), required(req.type, "type"),
					req.description, decimal(req.amount, "amount"));
			MovementResult r = dao.write(rec, null);
			return new DataServiceResult(r.getHttpStatus(), toJson(r));
		}
		catch (IllegalArgumentException e)
		{
			return invalid(e);
		}
	}

	/** GET /data/proctran/{sortcode}?limit=&amp;offset= */
	public static DataServiceResult listProctran(Connection conn,
			String sortCode, Integer limit, Integer offset)
	{
		ProctranListResponse json = new ProctranListResponse();
		try
		{
			int lim = limit == null ? DEFAULT_LIMIT : limit;
			int off = offset == null ? 0 : offset;
			require(lim >= 1 && off >= 0,
					"limit must be >= 1 and offset must be >= 0");
			List<ProctranRecord> rows = new ProctranDao(conn, null)
					.list(sortCode, lim, off);
			json.records = new ArrayList<>(rows.size());
			for (ProctranRecord row : rows)
			{
				json.records.add(toJson(row));
			}
			json.success = "Y";
			json.failCode = "0";
			json.numberOfRecords = rows.size();
			return new DataServiceResult(200, json);
		}
		catch (IllegalArgumentException e)
		{
			json.success = "N";
			json.failCode = FAIL_CODE_INVALID_REQUEST;
			json.errorMessage = e.getMessage();
			return new DataServiceResult(400, json);
		}
		catch (SQLException e)
		{
			logger.log(Level.SEVERE, "Unable to read PROCTRAN", e);
			json.success = "N";
			json.failCode = "2";
			json.errorMessage = e.getMessage();
			return new DataServiceResult(500, json);
		}
	}

	static MovementResponse toJson(MovementResult r)
	{
		MovementResponse json = new MovementResponse();
		json.success = r.isSuccess() ? "Y" : "N";
		json.failCode = r.getFailCode();
		json.abendCode = r.getAbendCode();
		json.errorMessage = r.getMessage();
		if (r.getProctran() != null)
		{
			json.proctran = toJson(r.getProctran());
		}
		return json;
	}

	static ProctranRow toJson(ProctranRecord row)
	{
		ProctranRow json = new ProctranRow();
		json.eyecatcher = row.getEyecatcher();
		json.sortCode = row.getSortCode();
		json.accountNumber = row.getAccountNumber();
		json.date = row.getDate() == null ? null : row.getDate().toString();
		json.time = row.getTime();
		json.reference = row.getReference();
		json.type = row.getType();
		json.description = row.getDescription();
		json.amount = money(row.getAmount());
		return json;
	}

	private static String money(BigDecimal value)
	{
		return value == null ? null : value.setScale(2).toPlainString();
	}

	private static DataServiceResult invalid(IllegalArgumentException e)
	{
		MovementResponse json = new MovementResponse();
		json.success = "N";
		json.failCode = FAIL_CODE_INVALID_REQUEST;
		json.errorMessage = e.getMessage();
		return new DataServiceResult(400, json);
	}

	private static void require(boolean condition, String message)
	{
		if (!condition)
		{
			throw new IllegalArgumentException(message);
		}
	}

	private static String required(String value, String name)
	{
		require(value != null && !value.trim().isEmpty(), name + " is required");
		return value;
	}

	private static BigDecimal decimal(String value, String name)
	{
		required(value, name);
		try
		{
			return new BigDecimal(value.trim());
		}
		catch (NumberFormatException e)
		{
			throw new IllegalArgumentException(
					name + " must be a decimal number");
		}
	}

	private static long taskNumber(Long value)
	{
		require(value != null, "taskNumber is required");
		return value;
	}
}
