-- ============================================================================
-- Phase 4: Live DML Operations
-- These operations will be captured by the CDC connector
-- ============================================================================

-- Set session settings
SET autocommit=1;

-- ============================================================================
-- INSERT Operations
-- ============================================================================

-- Insert new customers
INSERT INTO customers (customer_id, first_name, last_name, email, phone, address, city, state, zip_code, country, created_at, updated_at)
VALUES 
    (100001, 'Alice', 'Johnson', 'alice.j@example.com', '555-0001', '123 Main St', 'New York', 'NY', '10001', 'USA', NOW(), NOW()),
    (100002, 'Bob', 'Smith', 'bob.s@example.com', '555-0002', '456 Oak Ave', 'Los Angeles', 'CA', '90001', 'USA', NOW(), NOW()),
    (100003, 'Carol', 'Williams', 'carol.w@example.com', '555-0003', '789 Pine Rd', 'Chicago', 'IL', '60601', 'USA', NOW(), NOW()),
    (100004, 'David', 'Brown', 'david.b@example.com', '555-0004', '321 Elm St', 'Houston', 'TX', '77001', 'USA', NOW(), NOW()),
    (100005, 'Eve', 'Davis', 'eve.d@example.com', '555-0005', '654 Maple Dr', 'Phoenix', 'AZ', '85001', 'USA', NOW(), NOW());

-- Insert new products
INSERT INTO products (product_id, product_name, category, description, price, stock_quantity, supplier, created_at, updated_at)
VALUES
    (100001, 'Widget Pro', 'Electronics', 'Advanced widget with AI', 299.99, 100, 'TechCorp', NOW(), NOW()),
    (100002, 'Gadget Ultra', 'Electronics', 'Premium gadget', 499.99, 50, 'GadgetInc', NOW(), NOW()),
    (100003, 'Tool Master', 'Tools', 'Professional tool set', 149.99, 200, 'ToolCo', NOW(), NOW()),
    (100004, 'Book Classic', 'Books', 'Timeless literature', 19.99, 500, 'BookHouse', NOW(), NOW()),
    (100005, 'Toy Deluxe', 'Toys', 'Premium toy collection', 59.99, 150, 'ToyWorld', NOW(), NOW());

-- Insert new orders
INSERT INTO orders (order_id, customer_id, order_date, total_amount, status, payment_method, shipping_address, created_at, updated_at)
VALUES
    (100001, 100001, NOW(), 299.99, 'pending', 'credit_card', '123 Main St, New York, NY 10001', NOW(), NOW()),
    (100002, 100002, NOW(), 499.99, 'pending', 'paypal', '456 Oak Ave, Los Angeles, CA 90001', NOW(), NOW()),
    (100003, 100003, NOW(), 149.99, 'pending', 'credit_card', '789 Pine Rd, Chicago, IL 60601', NOW(), NOW()),
    (100004, 100004, NOW(), 19.99, 'processing', 'debit_card', '321 Elm St, Houston, TX 77001', NOW(), NOW()),
    (100005, 100005, NOW(), 59.99, 'processing', 'credit_card', '654 Maple Dr, Phoenix, AZ 85001', NOW(), NOW());

-- Insert order items
INSERT INTO order_items (order_item_id, order_id, product_id, quantity, unit_price, subtotal)
VALUES
    (100001, 100001, 100001, 1, 299.99, 299.99),
    (100002, 100002, 100002, 1, 499.99, 499.99),
    (100003, 100003, 100003, 1, 149.99, 149.99),
    (100004, 100004, 100004, 1, 19.99, 19.99),
    (100005, 100005, 100005, 1, 59.99, 59.99);

-- ============================================================================
-- UPDATE Operations
-- ============================================================================

-- Update customer information
UPDATE customers 
SET 
    email = 'alice.johnson.updated@example.com',
    phone = '555-9999',
    updated_at = NOW()
WHERE customer_id = 100001;

-- Update product prices
UPDATE products 
SET 
    price = 279.99,
    updated_at = NOW()
WHERE product_id = 100001;

-- Update order status
UPDATE orders 
SET 
    status = 'shipped',
    updated_at = NOW()
WHERE order_id = 100001;

-- Bulk update
UPDATE products 
SET 
    stock_quantity = stock_quantity - 1,
    updated_at = NOW()
WHERE product_id IN (100001, 100002, 100003);

-- ============================================================================
-- DELETE Operations
-- ============================================================================

-- Delete a specific order item (soft delete pattern)
DELETE FROM order_items WHERE order_item_id = 100005;

-- Delete an order
DELETE FROM orders WHERE order_id = 100005;

-- Delete a customer (this should cascade if foreign keys are set)
DELETE FROM customers WHERE customer_id = 100005;

-- ============================================================================
-- TRANSACTION Tests
-- ============================================================================

-- Transaction 1: COMMIT
START TRANSACTION;
INSERT INTO customers (customer_id, first_name, last_name, email, phone, address, city, state, zip_code, country, created_at, updated_at)
VALUES (100010, 'Frank', 'Miller', 'frank.m@example.com', '555-1000', '100 Test St', 'Seattle', 'WA', '98101', 'USA', NOW(), NOW());

INSERT INTO orders (order_id, customer_id, order_date, total_amount, status, payment_method, shipping_address, created_at, updated_at)
VALUES (100010, 100010, NOW(), 99.99, 'pending', 'credit_card', '100 Test St, Seattle, WA 98101', NOW(), NOW());
COMMIT;

-- Transaction 2: ROLLBACK (should not appear in ClickHouse)
START TRANSACTION;
INSERT INTO customers (customer_id, first_name, last_name, email, phone, address, city, state, zip_code, country, created_at, updated_at)
VALUES (100011, 'Grace', 'Lee', 'grace.l@example.com', '555-1001', '200 Test Ave', 'Boston', 'MA', '02101', 'USA', NOW(), NOW());
ROLLBACK;

-- Transaction 3: Multi-statement transaction COMMIT
START TRANSACTION;
INSERT INTO products (product_id, product_name, category, description, price, stock_quantity, supplier, created_at, updated_at)
VALUES (100010, 'Transaction Test Product', 'Test', 'For transaction testing', 99.99, 10, 'TestSupplier', NOW(), NOW());

UPDATE products SET price = 89.99 WHERE product_id = 100010;

DELETE FROM products WHERE product_id = 100010;
COMMIT;

-- ============================================================================
-- DDL Operations (Schema Evolution)
-- ============================================================================

-- Add a new column to customers table
ALTER TABLE customers ADD COLUMN loyalty_points INT DEFAULT 0;

-- Update with new column
UPDATE customers SET loyalty_points = 100 WHERE customer_id = 100001;

-- Add index (CDC should capture this) - using different name to avoid conflicts
ALTER TABLE customers ADD INDEX idx_email_phase4 (email);

-- Modify column
ALTER TABLE products MODIFY COLUMN description TEXT;

-- ============================================================================
-- Complex Scenarios
-- ============================================================================

-- Scenario 1: Bulk insert with duplicates (should be deduplicated by RMT)
INSERT INTO customers (customer_id, first_name, last_name, email, phone, address, city, state, zip_code, country, created_at, updated_at)
VALUES (100020, 'Helen', 'Taylor', 'helen.t@example.com', '555-2000', '300 Bulk St', 'Miami', 'FL', '33101', 'USA', NOW(), NOW());

UPDATE customers SET phone = '555-2001' WHERE customer_id = 100020;
UPDATE customers SET phone = '555-2002' WHERE customer_id = 100020;
UPDATE customers SET phone = '555-2003' WHERE customer_id = 100020;

-- Scenario 2: Insert-Update-Delete sequence
INSERT INTO products (product_id, product_name, category, description, price, stock_quantity, supplier, created_at, updated_at)
VALUES (100020, 'Temp Product', 'Temp', 'Temporary product', 1.99, 1, 'TempSupplier', NOW(), NOW());

UPDATE products SET price = 2.99 WHERE product_id = 100020;

DELETE FROM products WHERE product_id = 100020;

-- Scenario 3: NULL handling
INSERT INTO customers (customer_id, first_name, last_name, email, phone, address, city, state, zip_code, country, created_at, updated_at)
VALUES (100030, 'Ivan', 'NULL', 'ivan@example.com', NULL, NULL, 'Portland', 'OR', '97201', 'USA', NOW(), NOW());

-- ============================================================================
-- Data Type Edge Cases
-- ============================================================================

-- Insert data with various edge case values
INSERT INTO data_types_test (
    test_id,
    varchar_col,
    text_col,
    int_col,
    bigint_col,
    decimal_col,
    float_col,
    double_col,
    date_col,
    datetime_col,
    timestamp_col,
    bool_col,
    json_col,
    enum_col
) VALUES (
    100001,
    'Special chars: àéîöü 中文 emoji 🎉',
    'Long text with line\nbreaks and\ttabs',
    2147483647,
    9223372036854775807,
    99999.99,
    1.23456,
    1.234567890123,
    '2024-12-31',
    '2024-12-31 23:59:59',
    CURRENT_TIMESTAMP,
    1,
    '{"key": "value", "nested": {"array": [1, 2, 3]}}',
    'active'
);

-- ============================================================================
-- Summary
-- ============================================================================
SELECT 'Live DML operations completed successfully' AS status;
