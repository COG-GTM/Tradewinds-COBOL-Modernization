package com.ibm.cics.cip.bank.core.common.bank;

import com.ibm.cics.cip.bank.core.common.config.CbsaProperties;
import org.springframework.stereotype.Component;

/** Port of GETSCODE (sort code, SORTCODE.cpy) and GETCOMPY (company name). */
@Component
public class BankIdentity {

    private final String sortCode;
    private final String companyName;

    public BankIdentity(CbsaProperties properties) {
        this.sortCode = properties.sortcode();
        this.companyName = properties.companyName();
    }

    /** GETSCODE: the 6-digit sort code of this bank. */
    public String sortCode() {
        return sortCode;
    }

    /** GETCOMPY: {@code 'CICS Bank Sample Application'}. */
    public String companyName() {
        return companyName;
    }

    public boolean isOurSortCode(String candidate) {
        return candidate != null && sortCode.equals(candidate.trim());
    }
}
