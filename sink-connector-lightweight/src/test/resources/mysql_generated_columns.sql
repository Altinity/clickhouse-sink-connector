-- MySQL Generated Columns Test Setup
CREATE DATABASE IF NOT EXISTS employees;
USE employees;

-- 1. Basic Generated Column (Stored)
CREATE TABLE basic_generated_column (
id INT PRIMARY KEY,
width DECIMAL(10,2),
height DECIMAL(10,2),
area DECIMAL(10,2) GENERATED ALWAYS AS (width * height) STORED
);

-- 2. Virtual Generated Column
CREATE TABLE virtual_column_example (
id INT PRIMARY KEY,
first_name VARCHAR(50),
last_name VARCHAR(50),
full_name VARCHAR(100) GENERATED ALWAYS AS (CONCAT(first_name, ' ', last_name)) VIRTUAL
);

-- 3. Multiple Generated Columns
CREATE TABLE complex_generated_columns (
id INT PRIMARY KEY,
base_salary DECIMAL(10,2),
tax_rate DECIMAL(5,2),
gross_salary DECIMAL(10,2) GENERATED ALWAYS AS (base_salary * (1 + tax_rate/100)) STORED,
annual_salary DECIMAL(10,2) GENERATED ALWAYS AS (gross_salary * 12) STORED
);

-- 4. Date-based Generated Columns
CREATE TABLE date_generated_columns (
id INT PRIMARY KEY,
order_date DATETIME,
year_month INT GENERATED ALWAYS AS (YEAR(order_date) * 100 + MONTH(order_date)) STORED,
quarter INT GENERATED ALWAYS AS (QUARTER(order_date)) VIRTUAL,
is_weekend TINYINT GENERATED ALWAYS AS (DAYOFWEEK(order_date) IN (1,7)) STORED
);

-- 5. JSON Generated Columns
CREATE TABLE json_generated_columns (
id INT PRIMARY KEY,
order_details JSON,
total_amount DECIMAL(10,2) GENERATED ALWAYS AS (
JSON_EXTRACT(order_details, '$.price') * JSON_EXTRACT(order_details, '$.quantity')
) STORED,
product_name VARCHAR(255) GENERATED ALWAYS AS (
JSON_UNQUOTE(JSON_EXTRACT(order_details, '$.product_name'))
) VIRTUAL
);

-- 6. Mathematical Generated Columns
CREATE TABLE mathematical_generated_columns (
id INT PRIMARY KEY,
radius DECIMAL(10,2),
circle_area DECIMAL(10,2) GENERATED ALWAYS AS (PI() * radius * radius) STORED,
circle_circumference DECIMAL(10,2) GENERATED ALWAYS AS (2 * PI() * radius) STORED
);

-- 7. Alter Table - Add Generated Column
CREATE TABLE base_table (
id INT PRIMARY KEY,
first_name VARCHAR(50),
last_name VARCHAR(50)
);

ALTER TABLE base_table
ADD COLUMN full_name VARCHAR(100)
GENERATED ALWAYS AS (CONCAT(first_name, ' ', last_name)) VIRTUAL;

-- 8. Alter Table - Modify Generated Column
ALTER TABLE base_table
MODIFY COLUMN full_name VARCHAR(150)
GENERATED ALWAYS AS (CONCAT(first_name, ' ', IFNULL(last_name, ''))) VIRTUAL;

-- 9. Complex Conditional Generated Column
CREATE TABLE employee_status (
id INT PRIMARY KEY,
hire_date DATE,
termination_date DATE,
employment_status VARCHAR(20) GENERATED ALWAYS AS (
CASE
WHEN termination_date IS NULL THEN 'Active'
WHEN termination_date < CURRENT_DATE THEN 'Terminated'
ELSE 'Pending Termination'
END
) VIRTUAL
);

-- 10. Enum-based Generated Column
CREATE TABLE product_pricing (
id INT PRIMARY KEY,
base_price DECIMAL(10,2),
category ENUM('Low', 'Medium', 'High'),
price_category VARCHAR(20) GENERATED ALWAYS AS (
CASE
WHEN base_price < 10 THEN 'Budget'
WHEN base_price BETWEEN 10 AND 50 THEN 'Mid-range'
ELSE 'Premium'
END
) STORED
);

-- 11. Constraints with Generated Columns
CREATE TABLE constrained_generated_columns (
id INT PRIMARY KEY,
temperature DECIMAL(5,2),
temperature_category VARCHAR(20)
GENERATED ALWAYS AS (
CASE
WHEN temperature < 0 THEN 'Freezing'
WHEN temperature BETWEEN 0 AND 20 THEN 'Cold'
WHEN temperature BETWEEN 20 AND 30 THEN 'Warm'
ELSE 'Hot'
END
) STORED,
CONSTRAINT chk_temperature CHECK (temperature BETWEEN -50 AND 100)
);

-- 12. Index on Generated Column
CREATE TABLE indexable_generated_columns (
id INT PRIMARY KEY,
first_name VARCHAR(50),
last_name VARCHAR(50),
full_name VARCHAR(100) GENERATED ALWAYS AS (CONCAT(first_name, ' ', last_name)) STORED,
INDEX idx_full_name (full_name)
);

-- 13. Generated Column with External Function (MySQL 8.0+)
DELIMITER //
CREATE FUNCTION calculate_age(birth_date DATE)
RETURNS INT DETERMINISTIC
BEGIN
RETURN TIMESTAMPDIFF(YEAR, birth_date, CURRENT_DATE);
END //
DELIMITER ;

CREATE TABLE person (
id INT PRIMARY KEY,
birth_date DATE,
age INT GENERATED ALWAYS AS (calculate_age(birth_date)) STORED
);

-- Insert sample data for testing
INSERT INTO basic_generated_column (id, width, height) VALUES 
(1, 10.5, 20.3),
(2, 15.7, 8.9),
(3, 12.0, 25.5);

INSERT INTO virtual_column_example (id, first_name, last_name) VALUES 
(1, 'John', 'Doe'),
(2, 'Jane', 'Smith'),
(3, 'Bob', 'Johnson');

INSERT INTO complex_generated_columns (id, base_salary, tax_rate) VALUES 
(1, 5000.00, 15.0),
(2, 7500.00, 20.0),
(3, 10000.00, 25.0);

INSERT INTO date_generated_columns (id, order_date) VALUES 
(1, '2024-03-15 10:30:00'),
(2, '2024-06-22 14:45:00'),
(3, '2024-12-01 09:15:00');

INSERT INTO json_generated_columns (id, order_details) VALUES 
(1, '{"price": 25.50, "quantity": 2, "product_name": "Widget A"}'),
(2, '{"price": 15.75, "quantity": 3, "product_name": "Widget B"}'),
(3, '{"price": 100.00, "quantity": 1, "product_name": "Premium Widget"}');

INSERT INTO mathematical_generated_columns (id, radius) VALUES 
(1, 5.0),
(2, 10.0),
(3, 7.5);

INSERT INTO base_table (id, first_name, last_name) VALUES 
(1, 'Alice', 'Cooper'),
(2, 'Charlie', 'Brown'),
(3, 'Diana', 'Prince');

INSERT INTO employee_status (id, hire_date, termination_date) VALUES 
(1, '2020-01-15', NULL),
(2, '2019-03-20', '2023-12-31'),
(3, '2021-06-10', '2025-03-15');

INSERT INTO product_pricing (id, base_price, category) VALUES 
(1, 5.99, 'Low'),
(2, 25.50, 'Medium'),
(3, 150.00, 'High');

INSERT INTO constrained_generated_columns (id, temperature) VALUES 
(1, -10.5),
(2, 15.0),
(3, 25.5),
(4, 45.0);

INSERT INTO indexable_generated_columns (id, first_name, last_name) VALUES 
(1, 'Emma', 'Watson'),
(2, 'Robert', 'Downey'),
(3, 'Scarlett', 'Johansson');

INSERT INTO person (id, birth_date) VALUES 
(1, '1990-05-15'),
(2, '1985-12-20'),
(3, '1995-08-30');