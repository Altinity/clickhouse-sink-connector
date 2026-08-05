-- Seed schema for the end-to-end MySQL -> ClickHouse data-integrity IT
-- (EndToEndDDLDataIntegrityIT). Tables are intentionally simple so the
-- assertions focus on data correctness across DDL column changes, not on
-- exotic type mapping (which other ITs already cover).

-- Primary table used for the ADD COLUMN data-loss / race scenarios.
CREATE TABLE trades (
    id        INT NOT NULL,
    symbol    VARCHAR(32) NOT NULL,
    price     DECIMAL(18, 6) NOT NULL,
    qty       INT NOT NULL,
    PRIMARY KEY (id)
);

INSERT INTO trades VALUES (1, 'AAA', 10.500000, 100);
INSERT INTO trades VALUES (2, 'BBB', 20.250000, 200);

-- Secondary table used for rename / modify / drop column scenarios.
CREATE TABLE positions (
    id        INT NOT NULL,
    book      VARCHAR(32) NOT NULL,
    notional  DECIMAL(18, 6) NOT NULL,
    PRIMARY KEY (id)
);

INSERT INTO positions VALUES (1, 'BOOK_A', 1000.000000);
INSERT INTO positions VALUES (2, 'BOOK_B', 2000.000000);
