package com.ibm.cics.cip.bank.core.screens;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Named output fields of each BMS map (src/base/bms_src), in BMS order, with their {@code LENGTH=}.
 * {@code commareaLength} is the LENGTH of the COMMAREA passed on {@code RETURN TRANSID}.
 */
public enum BmsMap {
    BNK1ME("BNK1MAI", "OMEN", 1, "ACTION:1", "MESSAGE:79"),
    BNK1DC("BNK1DCM", "ODCS", 266, "CUSTNO:10", "SORTC:6", "CUSTNO2:10", "CUSTNAM:60", "CUSTAD1:60", "CUSTAD2:60",
            "CUSTAD3:40", "DOBDD:2", "DOBMM:2", "DOBYY:4", "CREDSC:3", "SCRDTDD:2", "SCRDTMM:2", "SCRDTYY:4",
            "MESSAGE:79"),
    BNK1CC("BNK1CCM", "OCCS", 248, "CUSTTIT:10", "CHRISTN:20", "CUSTINS:2", "CUSTSN:20", "CUSTAD1:60", "CUSTAD2:60",
            "CUSTAD3:40", "DOBDD:2", "DOBMM:2", "DOBYY:4", "SORTC:6", "CUSTNO2:10", "CREDSC:3", "SCRDTDD:2",
            "SCRDTMM:2", "SCRDTYY:4", "MESSAGE:79"),
    BNK1CA("BNK1CAM", "OCAC", 32, "CUSTNO:10", "ACCTYP:8", "INTRT:7", "OVERDR:8", "ACCNO:8", "SRTCD:6", "OPENDD:2",
            "OPENMM:2", "OPENYY:4", "LSTMDD:2", "LSTMMM:2", "LSTMYY:4", "NSTMTDD:2", "NSTMTMM:2", "NSTMTYY:4",
            "AVAIL:14", "ACTBAL:14", "MESSAGE:79"),
    BNK1UA("BNK1UAM", "OUAC", 99, "ACCNO:8", "CUSTNO:10", "SORTC:6", "ACCNO2:8", "ACTYPE:8", "INTRT:7", "OPENDD:2",
            "OPENMM:2", "OPENYY:4", "OVERDR:8", "LSTMTDD:2", "LSTMTMM:2", "LSTMTYY:4", "NSTMTDD:2", "NSTMTMM:2",
            "NSTMTYY:4", "AVBAL:14", "ACTBAL:14", "MESSAGE:79"),
    BNK1DA("BNK1DAM", "ODAC", 102, "ACCNO:8", "CUSTNO:10", "SORTC:6", "ACCNO2:10", "ACTYPE:8", "INTRT:7",
            "OPENDD:2", "OPENMM:2", "OPENYY:4", "OVERDR:8", "LSTMTDD:2", "LSTMTMM:2", "LSTMTYY:4", "NSTMTDD:2",
            "NSTMTMM:2", "NSTMTYY:4", "AVBAL:14", "ACTBAL:14", "MESSAGE:79"),
    BNK1CD("BNK1CDM", "OCRA", 21, "ACCNO:8", "SIGN:1", "AMT:13", "SORTC:6", "AVBAL:14", "ACTBAL:14", "MESSAGE:79"),
    BNK1TF("BNK1TFM", "OTFN", 29, "FACCNO:8", "TACCNO:8", "AMT:13", "FACCNO2:8", "TACCNO2:8", "FSORTC:6",
            "TSORTC:6", "FACTBAL:14", "TACTBAL:14", "FAVBAL:14", "TAVBAL:14", "MESSAGE:79"),
    BNK1ACC("BNK1ACC", "OCCA", 248, "CUSTNO:10", "ACCOUNT(1):79", "ACCOUNT(2):79", "ACCOUNT(3):79",
            "ACCOUNT(4):79", "ACCOUNT(5):79", "ACCOUNT(6):79", "ACCOUNT(7):79", "ACCOUNT(8):79", "ACCOUNT(9):79",
            "ACCOUNT(10):79", "MESSAGE:79");

    private final String mapset;
    private final String transid;
    private final int commareaLength;
    private final Map<String, Integer> fields = new LinkedHashMap<>();

    BmsMap(String mapset, String transid, int commareaLength, String... defs) {
        this.mapset = mapset;
        this.transid = transid;
        this.commareaLength = commareaLength;
        for (String d : defs) {
            int i = d.lastIndexOf(':');
            fields.put(d.substring(0, i), Integer.parseInt(d.substring(i + 1)));
        }
    }

    public String mapset() {
        return mapset;
    }

    public String transid() {
        return transid;
    }

    public int commareaLength() {
        return commareaLength;
    }

    /** Field name to BMS LENGTH, in map order. */
    public Map<String, Integer> fields() {
        return fields;
    }

    public int length(String field) {
        Integer len = fields.get(field);
        if (len == null) {
            throw new IllegalArgumentException(name() + " has no field " + field);
        }
        return len;
    }
}
