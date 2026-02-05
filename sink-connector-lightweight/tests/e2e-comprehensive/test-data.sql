-- ============================================================================
-- Comprehensive E2E Test Data
-- Generates realistic test data with 10K+ rows across multiple tables
-- ============================================================================

-- Drop existing tables if they exist
DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS customers;
DROP TABLE IF EXISTS data_types_test;

-- ============================================================================
-- Table: customers (10,000 rows)
-- ============================================================================
CREATE TABLE customers (
    customer_id INT PRIMARY KEY,
    first_name VARCHAR(50) NOT NULL,
    last_name VARCHAR(50) NOT NULL,
    email VARCHAR(100) NOT NULL UNIQUE,
    phone VARCHAR(20),
    address VARCHAR(200),
    city VARCHAR(50),
    state VARCHAR(2),
    zip_code VARCHAR(10),
    country VARCHAR(50) DEFAULT 'USA',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_email (email),
    INDEX idx_city_state (city, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================================
-- Table: products (5,000 rows)
-- ============================================================================
CREATE TABLE products (
    product_id INT PRIMARY KEY,
    product_name VARCHAR(200) NOT NULL,
    category VARCHAR(50) NOT NULL,
    description TEXT,
    price DECIMAL(10,2) NOT NULL,
    stock_quantity INT NOT NULL DEFAULT 0,
    supplier VARCHAR(100),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_category (category),
    INDEX idx_price (price)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================================
-- Table: orders (20,000 rows)
-- ============================================================================
CREATE TABLE orders (
    order_id INT PRIMARY KEY,
    customer_id INT NOT NULL,
    order_date DATETIME NOT NULL,
    total_amount DECIMAL(12,2) NOT NULL,
    status ENUM('pending', 'processing', 'shipped', 'delivered', 'cancelled') DEFAULT 'pending',
    payment_method VARCHAR(50),
    shipping_address TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (customer_id) REFERENCES customers(customer_id) ON DELETE CASCADE,
    INDEX idx_customer (customer_id),
    INDEX idx_order_date (order_date),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================================
-- Table: order_items (50,000 rows)
-- ============================================================================
CREATE TABLE order_items (
    order_item_id INT PRIMARY KEY,
    order_id INT NOT NULL,
    product_id INT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(10,2) NOT NULL,
    subtotal DECIMAL(12,2) NOT NULL,
    FOREIGN KEY (order_id) REFERENCES orders(order_id) ON DELETE CASCADE,
    FOREIGN KEY (product_id) REFERENCES products(product_id),
    INDEX idx_order (order_id),
    INDEX idx_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================================
-- Table: data_types_test (100 rows for edge case testing)
-- ============================================================================
CREATE TABLE data_types_test (
    test_id INT PRIMARY KEY,
    varchar_col VARCHAR(255),
    text_col TEXT,
    int_col INT,
    bigint_col BIGINT,
    decimal_col DECIMAL(10,2),
    float_col FLOAT,
    double_col DOUBLE,
    date_col DATE,
    datetime_col DATETIME,
    timestamp_col TIMESTAMP,
    bool_col BOOLEAN,
    json_col JSON,
    enum_col ENUM('active', 'inactive', 'pending'),
    INDEX idx_test_id (test_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================================
-- Insert realistic test data
-- ============================================================================

-- Insert 10,000 customers using a stored procedure
DELIMITER //
CREATE PROCEDURE generate_customers()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE first_names VARCHAR(1000) DEFAULT 'James,Mary,John,Patricia,Robert,Jennifer,Michael,Linda,William,Barbara,David,Elizabeth,Richard,Susan,Joseph,Jessica,Thomas,Sarah,Charles,Karen,Christopher,Nancy,Daniel,Lisa,Matthew,Betty,Anthony,Margaret,Mark,Sandra,Donald,Ashley,Steven,Kimberly,Paul,Emily,Andrew,Donna,Joshua,Michelle';
    DECLARE last_names VARCHAR(1000) DEFAULT 'Smith,Johnson,Williams,Brown,Jones,Garcia,Miller,Davis,Rodriguez,Martinez,Hernandez,Lopez,Gonzalez,Wilson,Anderson,Thomas,Taylor,Moore,Jackson,Martin,Lee,Perez,Thompson,White,Harris,Sanchez,Clark,Ramirez,Lewis,Robinson,Walker,Young,Allen,King,Wright,Scott,Torres,Nguyen,Hill';
    DECLARE cities VARCHAR(1000) DEFAULT 'New York,Los Angeles,Chicago,Houston,Phoenix,Philadelphia,San Antonio,San Diego,Dallas,San Jose,Austin,Jacksonville,Fort Worth,Columbus,Charlotte,San Francisco,Indianapolis,Seattle,Denver,Washington,Boston,El Paso,Nashville,Detroit,Oklahoma City,Portland,Las Vegas,Memphis,Louisville,Baltimore,Milwaukee,Albuquerque,Tucson,Fresno,Mesa,Sacramento,Atlanta,Kansas City,Colorado Springs,Omaha';
    DECLARE states VARCHAR(200) DEFAULT 'NY,CA,IL,TX,AZ,PA,TX,CA,TX,CA,TX,FL,TX,OH,NC,CA,IN,WA,CO,DC,MA,TX,TN,MI,OK,OR,NV,TN,KY,MD,WI,NM,AZ,CA,AZ,CA,GA,MO,CO,NE';
    
    WHILE i <= 10000 DO
        INSERT INTO customers (customer_id, first_name, last_name, email, phone, address, city, state, zip_code, country, created_at, updated_at)
        VALUES (
            i,
            SUBSTRING_INDEX(SUBSTRING_INDEX(first_names, ',', 1 + (i % 40)), ',', -1),
            SUBSTRING_INDEX(SUBSTRING_INDEX(last_names, ',', 1 + ((i * 7) % 40)), ',', -1),
            CONCAT('user', i, '@example.com'),
            CONCAT('555-', LPAD(i, 4, '0')),
            CONCAT(i, ' Main Street'),
            SUBSTRING_INDEX(SUBSTRING_INDEX(cities, ',', 1 + (i % 40)), ',', -1),
            SUBSTRING_INDEX(SUBSTRING_INDEX(states, ',', 1 + (i % 40)), ',', -1),
            LPAD((i % 99999), 5, '0'),
            'USA',
            DATE_SUB(NOW(), INTERVAL (i % 365) DAY),
            DATE_SUB(NOW(), INTERVAL (i % 30) DAY)
        );
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;

CALL generate_customers();
DROP PROCEDURE generate_customers;

-- Insert 5,000 products
DELIMITER //
CREATE PROCEDURE generate_products()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE categories VARCHAR(500) DEFAULT 'Electronics,Books,Clothing,Home & Garden,Sports,Toys,Automotive,Health,Beauty,Office';
    DECLARE suppliers VARCHAR(500) DEFAULT 'TechCorp,BookWorld,FashionHub,HomeGoods,SportsPro,ToyLand,AutoParts,HealthPlus,BeautySupply,OfficeMax';
    
    WHILE i <= 5000 DO
        INSERT INTO products (product_id, product_name, category, description, price, stock_quantity, supplier, created_at, updated_at)
        VALUES (
            i,
            CONCAT('Product ', i, ' - ', SUBSTRING_INDEX(SUBSTRING_INDEX(categories, ',', 1 + (i % 10)), ',', -1)),
            SUBSTRING_INDEX(SUBSTRING_INDEX(categories, ',', 1 + (i % 10)), ',', -1),
            CONCAT('High quality product for category ', SUBSTRING_INDEX(SUBSTRING_INDEX(categories, ',', 1 + (i % 10)), ',', -1)),
            ROUND(10 + (RAND() * 990), 2),
            FLOOR(10 + (RAND() * 990)),
            SUBSTRING_INDEX(SUBSTRING_INDEX(suppliers, ',', 1 + (i % 10)), ',', -1),
            DATE_SUB(NOW(), INTERVAL (i % 180) DAY),
            DATE_SUB(NOW(), INTERVAL (i % 30) DAY)
        );
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;

CALL generate_products();
DROP PROCEDURE generate_products;

-- Insert 20,000 orders
DELIMITER //
CREATE PROCEDURE generate_orders()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE statuses VARCHAR(200) DEFAULT 'pending,processing,shipped,delivered,cancelled';
    DECLARE payment_methods VARCHAR(200) DEFAULT 'credit_card,debit_card,paypal,bank_transfer,cash';
    
    WHILE i <= 20000 DO
        INSERT INTO orders (order_id, customer_id, order_date, total_amount, status, payment_method, shipping_address, created_at, updated_at)
        VALUES (
            i,
            1 + (i % 10000),
            DATE_SUB(NOW(), INTERVAL (i % 365) DAY),
            ROUND(20 + (RAND() * 980), 2),
            SUBSTRING_INDEX(SUBSTRING_INDEX(statuses, ',', 1 + (i % 5)), ',', -1),
            SUBSTRING_INDEX(SUBSTRING_INDEX(payment_methods, ',', 1 + (i % 5)), ',', -1),
            CONCAT((1 + (i % 10000)), ' Main Street, City, State 12345'),
            DATE_SUB(NOW(), INTERVAL (i % 365) DAY),
            DATE_SUB(NOW(), INTERVAL (i % 30) DAY)
        );
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;

CALL generate_orders();
DROP PROCEDURE generate_orders;

-- Insert 50,000 order items
DELIMITER //
CREATE PROCEDURE generate_order_items()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE unit_price DECIMAL(10,2);
    DECLARE quantity INT;
    
    WHILE i <= 50000 DO
        SET quantity = 1 + (i % 5);
        SET unit_price = ROUND(10 + (RAND() * 190), 2);
        
        INSERT INTO order_items (order_item_id, order_id, product_id, quantity, unit_price, subtotal)
        VALUES (
            i,
            1 + ((i - 1) / 3) % 20000,
            1 + (i % 5000),
            quantity,
            unit_price,
            ROUND(quantity * unit_price, 2)
        );
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;

CALL generate_order_items();
DROP PROCEDURE generate_order_items;

-- Insert 100 data type test records
INSERT INTO data_types_test 
    (test_id, varchar_col, text_col, int_col, bigint_col, decimal_col, float_col, double_col, date_col, datetime_col, timestamp_col, bool_col, json_col, enum_col)
SELECT 
    n,
    CONCAT('Test string ', n),
    CONCAT('This is a longer text field for testing purposes. Record number: ', n),
    n * 100,
    n * 1000000,
    ROUND(n * 1.5, 2),
    n * 0.123,
    n * 0.123456789,
    DATE_SUB(CURDATE(), INTERVAL n DAY),
    DATE_SUB(NOW(), INTERVAL n HOUR),
    CURRENT_TIMESTAMP,
    n % 2,
    JSON_OBJECT('id', n, 'name', CONCAT('Item ', n), 'value', n * 10),
    CASE (n % 3) WHEN 0 THEN 'active' WHEN 1 THEN 'inactive' ELSE 'pending' END
FROM 
    (SELECT @row := @row + 1 AS n FROM 
        (SELECT 0 UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t1,
        (SELECT 0 UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) t2,
        (SELECT @row := 0) t3
    ) numbers
WHERE n <= 100;

-- ============================================================================
-- Verify data loaded
-- ============================================================================
SELECT 
    'customers' AS table_name, COUNT(*) AS row_count FROM customers
UNION ALL SELECT 
    'products', COUNT(*) FROM products
UNION ALL SELECT 
    'orders', COUNT(*) FROM orders
UNION ALL SELECT 
    'order_items', COUNT(*) FROM order_items
UNION ALL SELECT 
    'data_types_test', COUNT(*) FROM data_types_test;

SELECT 'Test data generation completed successfully!' AS status;
