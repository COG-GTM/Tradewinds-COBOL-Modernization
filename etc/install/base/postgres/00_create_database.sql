-- Copyright IBM Corp. 2023, 2025
-- Replaces CREATE DATABASE CBSA / STOGROUP / TABLESPACE from INSTDB2.jcl.
-- Run as a superuser against the "postgres" maintenance database.
-- :cbsa_password is supplied with psql -v cbsa_password=...
CREATE ROLE cbsa LOGIN PASSWORD :'cbsa_password';
CREATE DATABASE cbsa OWNER cbsa ENCODING 'UTF8';
