package com.ibm.cics.cip.bank.core.bankdata;

import java.math.BigDecimal;
import java.util.List;

/**
 * BANKDATA {@code INITIALISE-ARRAYS} (and the {@code TITLE-ALPHABET} working storage), copied verbatim
 * including the legacy quirks: SURNAME(24) is assigned twice ('Ramsbottom' then 'Lloyd'), the initials
 * string is 'ABCDEFGHIJLKMNOPQRSTUVWXYZ' followed by four spaces, and ACCOUNT types are taken by position
 * (WS-CNT), not at random. Lists are 0-based; COBOL subscript {@code i} is element {@code i - 1}.
 */
final class BankDataTables {

    static final List<String> TITLES = List.of(
            "Mr", "Mrs", "Miss", "Ms", "Mr", "Mrs", "Miss", "Ms", "Mr", "Mrs", "Miss", "Ms",
            "Mr", "Mrs", "Miss", "Ms", "Mr", "Mrs", "Miss", "Ms", "Dr", "Drs", "Dr", "Ms",
            "Dr", "Ms", "Dr", "Ms", "Professor", "Professor", "Professor", "Lord", "Sir", "Sir", "Lady", "Lady");

    static final List<String> FORENAMES = List.of(
            "Michael", "Will", "Geoff", "Chris", "Dave", "Luke", "Adam", "Giuseppe", "James", "Jon",
            "Andy", "Lou", "Robert", "Sam", "Frederick", "Buford", "William", "Howard", "Anthony", "Bruce",
            "Peter", "Stephen", "Donald", "Dennis", "Harold", "Amy", "Belinda", "Charlotte", "Donna", "Felicia",
            "Gretchen", "Henrietta", "Imogen", "Josephine", "Kimberley", "Lucy", "Monica", "Natalie", "Ophelia",
            "Patricia", "Querida", "Rachel", "Samantha", "Tanya", "Ulrika", "Virginia", "Wendy", "Xaviera",
            "Yvonne", "Zsa Zsa");

    static final String INITIALS = "ABCDEFGHIJLKMNOPQRSTUVWXYZ    ";

    static final List<String> SURNAMES = List.of(
            "Jones", "Davidson", "Baker", "Smith", "Taylor", "Evans", "Roberts", "Wright", "Walker", "Green",
            "Price", "Downton", "Gatting", "Robinson", "Justice", "Tell", "Stark", "Strange", "Parker", "Blake",
            "Jackson", "Groves", "Palmer", "Lloyd", "Hughes", "Briggs", "Higins", "Goodwin", "Valmont", "Brown",
            "Hopkins", "Bonney", "Jenkins", "Lloyd", "Wilmore", "Franklin", "Renton", "Seward", "Morris",
            "Johnson", "Brennan", "Thomson", "Barker", "Corbett", "Weber", "Leigh", "Croft", "Walken", "Dubois",
            "Stephens");

    static final List<String> STREET_TREES = List.of(
            "Acacia", "Birch", "Cypress", "Douglas", "Elm", "Fir", "Gorse", "Holly", "Ironwood", "Joshua",
            "Kapok", "Laburnam", "Maple", "Nutmeg", "Oak", "Pine", "Quercine", "Rowan", "Sycamore", "Thorn",
            "Ulmus", "Viburnum", "Willow", "Xylophone", "Yew", "Zebratree");

    static final List<String> STREET_ROADS = List.of(
            "Avenue", "Boulevard", "Close", "Crescent", "Drive", "Escalade", "Frontage", "Lane", "Mews", "Rise",
            "Court", "Opening", "Loke", "Square", "Houses", "Gate", "Street", "Grove", "March");

    static final List<String> TOWNS = List.of(
            "Norwich", "Acle", "Aylsham", "Wymondham", "Attleborough", "Cromer", "Cambridge", "Peterborough",
            "Weobley", "Wembley", "Hereford", "Ross-on-Wye", "Hay-on-Wye", "Nottingham", "Northampton",
            "Nuneaton", "Oxford", "Oswestry", "Ormskirk", "Royston", "Chilcomb", "Winchester", "Wrexham", "Crewe",
            "Plymouth", "Portsmouth", "Forfar", "Fife", "Aberdeen", "Glasgow", "Birmingham", "Bolton", "Whitby",
            "Manchester", "Chester", "Leicester", "Lowestoft", "Ipswich", "Colchester", "Dover", "Brighton",
            "Salisbury", "Bristol", "Bath", "Gloucester", "Cheltenham", "Durham", "Carlisle", "York", "Exeter");

    static final List<String> ACCOUNT_TYPES = List.of("ISA", "SAVING", "CURRENT", "LOAN", "MORTGAGE");

    static final List<BigDecimal> INTEREST_RATES = List.of(
            new BigDecimal("2.10"), new BigDecimal("1.75"), new BigDecimal("0.00"), new BigDecimal("17.90"),
            new BigDecimal("5.25"));

    static final List<Integer> OVERDRAFT_LIMITS = List.of(0, 0, 100, 0, 0);

    private BankDataTables() {
    }
}
