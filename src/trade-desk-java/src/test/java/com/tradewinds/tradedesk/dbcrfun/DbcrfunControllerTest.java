package com.tradewinds.tradedesk.dbcrfun;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** End-to-end over HTTP: JSON request/response replace the PAYDBCR COMMAREA. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DbcrfunControllerTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.execute("DELETE FROM PROCTRAN");
        jdbc.execute("DELETE FROM ACCOUNT");
        jdbc.update("INSERT INTO ACCOUNT VALUES ('ACCT', '0000000001', '987654', '00000001', 'CURRENT', 0.00,"
                + " DATE '2020-01-01', 0, DATE '2026-10-01', DATE '2026-11-01', 500.00, 500.00)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void postDebitReturnsCommareaOutputsAsJson() {
        Map<String, Object> body = Map.of(
                "accNo", "00000001",
                "amt", -75.25,
                "sortc", "000000",
                "origin", Map.of("applid", "CBSAAPPL", "userid", "TELLER01", "facilType", 0));

        ResponseEntity<Map> response = http.postForEntity("/api/dbcrfun", body, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody())
                .containsEntry("success", "Y")
                .containsEntry("failCode", "0")
                .containsEntry("sortc", "987654")
                .containsEntry("avBal", 424.75)
                .containsEntry("actBal", 424.75);
    }

    @Test
    @SuppressWarnings("unchecked")
    void businessFailureIsHttp200WithSuccessN() {
        Map<String, Object> body = Map.of("accNo", "00000099", "amt", 10, "origin", Map.of("facilType", 496));

        ResponseEntity<Map> response = http.postForEntity("/api/dbcrfun", body, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("success", "N").containsEntry("failCode", "1");
    }

    @Test
    void requestThatCannotFitTheCommareaIsRejected() {
        Map<String, Object> tooManyDecimals = Map.of("accNo", "00000001", "amt", 1.001);
        Map<String, Object> nonNumericAccount = Map.of("accNo", "ABC", "amt", 1);
        Map<String, Object> fractionalFacilType = Map.of("accNo", "00000001", "amt", -600,
                "origin", Map.of("facilType", 495.99));

        assertThat(http.postForEntity("/api/dbcrfun", tooManyDecimals, String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> rejected = http.postForEntity("/api/dbcrfun", nonNumericAccount, String.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(http.postForEntity("/api/dbcrfun", fractionalFacilType, String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
