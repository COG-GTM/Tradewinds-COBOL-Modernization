package com.ibm.cics.cip.bankliberty.dataservice.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Account-number allocation and bulk reset are serialised per sortcode")
class SortcodeLockTest extends PostgresTestBase
{

	private static final String BODY = "{\"sortcode\":\"987654\",\"customerNumber\":\"42\","
			+ "\"accountType\":\"ISA\",\"taskRef\":\"1\"}";


	private ServiceResult blockedUntilLockReleased(Function<AccountDataService, ServiceResult> call)
			throws Exception
	{
		try (Connection holder = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
				POSTGRES.getUsername(), POSTGRES.getPassword());
				Connection worker = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
						POSTGRES.getUsername(), POSTGRES.getPassword()))
		{
			holder.setAutoCommit(false);
			try (PreparedStatement ps = holder.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)"))
			{
				ps.setInt(1, AccountDao.ACCOUNT_LOCK_CLASS);
				ps.setInt(2, Integer.parseInt(SORTCODE));
				ps.executeQuery().close();
			}
			ExecutorService exec = Executors.newSingleThreadExecutor();
			try
			{
				Future<ServiceResult> f = exec.submit(
						() -> call.apply(new AccountDataService(worker, CLOCK, () -> "1")));
				Thread.sleep(500);
				assertFalse(f.isDone(), "must wait for the sortcode lock");
				holder.commit();
				return f.get(10, TimeUnit.SECONDS);
			}
			finally
			{
				exec.shutdownNow();
			}
		}
	}


	@Test
	@DisplayName("Create waits while another transaction holds the sortcode lock")
	void createWaitsForSortcodeLock() throws Exception
	{
		insertAccountControls(0, 0);
		assertEquals(201, blockedUntilLockReleased(s -> s.create(BODY)).getStatus());
	}


	@Test
	@DisplayName("Reset waits while another transaction holds the sortcode lock, so a concurrent create cannot slip in between")
	void resetWaitsForSortcodeLock() throws Exception
	{
		assertEquals(200, blockedUntilLockReleased(s -> s.reset(SORTCODE, "[]", null, null)).getStatus());
	}
}
