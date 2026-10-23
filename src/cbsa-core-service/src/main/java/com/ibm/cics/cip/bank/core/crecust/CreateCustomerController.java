package com.ibm.cics.cip.bank.core.crecust;

import com.ibm.cics.cip.bank.core.common.bank.BankIdentity;
import com.ibm.cics.cip.bank.core.common.domain.Customer;
import com.ibm.cics.cip.bank.core.common.error.CbsaException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /banking/customer}: request checks of the legacy Liberty {@code CustomerResource.createCustomerInternal}
 * (name, title, sort code, address, date of birth present), then CRECUST via {@link CreateCustomerService}.
 */
@RestController
@RequestMapping("/banking/customer")
public class CreateCustomerController {

    private static final Pattern ISO_DATE = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})(?:T.*)?$");

    private final CreateCustomerService service;
    private final TitleValidator titles;
    private final BankIdentity bank;

    public CreateCustomerController(CreateCustomerService service, TitleValidator titles, BankIdentity bank) {
        this.service = service;
        this.titles = titles;
        this.bank = bank;
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> createCustomer(@RequestBody CreateCustomerRequest request) {
        if (request.customerName() == null) {
            throw CbsaException.badRequest("Customer name is null");
        }
        if (!titles.isValid(request.customerName())) {
            throw CbsaException.badRequest(
                    "Customer title " + TitleValidator.firstWord(request.customerName()) + " is not valid", "T");
        }
        if (request.sortCode() == null) {
            throw CbsaException.badRequest("Sort Code is null");
        }
        if (!isOurSortCode(request.sortCode())) {
            throw CbsaException.badRequest(
                    "Sortcode " + request.sortCode().trim() + " not valid for this bank (" + bank.sortCode() + ")");
        }
        if (request.customerAddress() == null) {
            throw CbsaException.badRequest("Customer address is null");
        }
        if (request.dateOfBirth() == null) {
            throw CbsaException.badRequest("Date of Birth is null");
        }
        Matcher dob = ISO_DATE.matcher(request.dateOfBirth().trim());
        if (!dob.matches()) {
            throw CbsaException.badRequest("Date of Birth " + request.dateOfBirth() + " is not a valid date",
                    DateOfBirthValidator.FAIL_INVALID);
        }
        Customer created = service.createCustomer(request.customerName(), request.customerAddress(),
                Integer.parseInt(dob.group(1)), Integer.parseInt(dob.group(2)), Integer.parseInt(dob.group(3)));
        return ResponseEntity.status(HttpStatus.CREATED).body(CustomerResponse.from(created));
    }

    private boolean isOurSortCode(String candidate) {
        try {
            return Integer.parseInt(candidate.trim()) == Integer.parseInt(bank.sortCode());
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
